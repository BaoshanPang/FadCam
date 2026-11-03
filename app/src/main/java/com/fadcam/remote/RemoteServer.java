package com.fadcam.remote;

import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.util.Base64;
import android.util.Log;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.services.RecordingService;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;

/**
 * Lightweight remote-control / live-view HTTP server.
 *
 * Adapted from the FadCam v3 "FadCam Remote" feature to work with the
 * MediaRecorder based v1.3.0 recording pipeline. Instead of HLS/fMP4 it serves
 * an MJPEG stream produced by RecordingService (see RemoteStreamHub).
 *
 * Endpoints (compatible in spirit with v3):
 *   GET  /                 -> web control UI
 *   GET  /status           -> JSON status
 *   GET  /stream.mjpg      -> MJPEG live view (alias /stream)
 *   GET  /snapshot.jpg     -> single JPEG
 *   GET  /auth/check       -> {"authEnabled":bool}
 *   POST /recording/toggle -> start/stop recording
 *   POST /torch/toggle     -> toggle torch
 */
public class RemoteServer extends NanoHTTPD {

    private static final String TAG = "FadCamRemote";
    private static final String BOUNDARY = "fadcamframe";

    private final Context appContext;
    private final SharedPreferencesManager prefs;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile long lastStatusRequestMs;

    public RemoteServer(Context context, int port) {
        super(port);
        this.appContext = context.getApplicationContext();
        this.prefs = SharedPreferencesManager.getInstance(this.appContext);
    }

    public void stopServer() {
        running.set(false);
        stop();
    }

    // ------------------------------------------------------------------ auth

    private boolean authRequired() {
        String pw = prefs.getRemotePassword();
        return pw != null && !pw.isEmpty();
    }

    private boolean isAuthorized(IHTTPSession session) {
        if (!authRequired()) return true;
        String header = session.getHeaders().get("authorization");
        if (header == null) return false;
        if (!header.toLowerCase(Locale.US).startsWith("basic ")) return false;
        try {
            String decoded = new String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT));
            int idx = decoded.indexOf(':');
            if (idx < 0) return false;
            String user = decoded.substring(0, idx);
            String pass = decoded.substring(idx + 1);
            return user.equals(prefs.getRemoteUsername()) && pass.equals(prefs.getRemotePassword());
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ serve

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        try {
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", RemoteWebUi.HTML);
            }
            if ("/auth/check".equals(uri)) {
                return json(Response.Status.OK,
                        "{\"authEnabled\":" + authRequired() + ",\"authenticated\":" + isAuthorized(session) + "}");
            }
            if ("/status".equals(uri)) {
                if (!isAuthorized(session)) return unauthorized();
                return json(Response.Status.OK, buildStatusJson());
            }
            if ("/stream.mjpg".equals(uri) || "/stream".equals(uri)) {
                if (!isAuthorized(session)) return unauthorized();
                return serveMjpeg();
            }
            if ("/snapshot.jpg".equals(uri)) {
                if (!isAuthorized(session)) return unauthorized();
                byte[] jpeg = RemoteStreamHub.get().getLatestJpeg();
                if (jpeg == null) {
                    return json(Response.Status.SERVICE_UNAVAILABLE, "{\"error\":\"no frame yet - start recording\"}");
                }
                return newFixedLengthResponse(Response.Status.OK, "image/jpeg", new java.io.ByteArrayInputStream(jpeg), jpeg.length);
            }
            if ("/recording/toggle".equals(uri) && method == Method.POST) {
                if (!isAuthorized(session)) return unauthorized();
                boolean nowRecording = toggleRecording();
                return json(Response.Status.OK, "{\"recording\":" + nowRecording + "}");
            }
            if ("/torch/toggle".equals(uri) && method == Method.POST) {
                if (!isAuthorized(session)) return unauthorized();
                boolean on = toggleTorch();
                return json(Response.Status.OK, "{\"torch\":" + on + "}");
            }
            if ("/favicon.ico".equals(uri)) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "");
            }
            return json(Response.Status.NOT_FOUND, "{\"error\":\"not found\"}");
        } catch (Exception e) {
            Log.e(TAG, "serve error for " + uri, e);
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"internal error\"}");
        }
    }

    private Response unauthorized() {
        Response r = json(Response.Status.UNAUTHORIZED, "{\"error\":\"unauthorized\"}");
        r.addHeader("WWW-Authenticate", "Basic realm=\"FadCam Remote\"");
        return r;
    }

    private Response json(Response.Status status, String body) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body);
    }

    // ------------------------------------------------------------- MJPEG view

    private Response serveMjpeg() {
        try {
            PipedInputStream in = new PipedInputStream(256 * 1024);
            PipedOutputStream out = new PipedOutputStream(in);
            Thread t = new Thread(() -> pumpMjpeg(out), "fadcam-mjpeg");
            t.setDaemon(true);
            t.start();
            Response r = newChunkedResponse(Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=" + BOUNDARY, in);
            r.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            r.addHeader("Connection", "close");
            r.addHeader("Pragma", "no-cache");
            return r;
        } catch (IOException e) {
            Log.e(TAG, "failed to create mjpeg stream", e);
            return json(Response.Status.INTERNAL_ERROR, "{\"error\":\"stream unavailable\"}");
        }
    }

    private void pumpMjpeg(OutputStream out) {
        long lastSent = -1;
        try {
            // Send the most recent frame immediately if present.
            while (running.get()) {
                RemoteStreamHub hub = RemoteStreamHub.get();
                long ts = hub.getLastFrameMs();
                byte[] jpeg = hub.getLatestJpeg();
                if (jpeg != null && ts != lastSent) {
                    String header = "--" + BOUNDARY + "\r\n"
                            + "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + jpeg.length + "\r\n\r\n";
                    out.write(header.getBytes("US-ASCII"));
                    out.write(jpeg);
                    out.write("\r\n".getBytes("US-ASCII"));
                    out.flush();
                    lastSent = ts;
                    // Cap at ~12 fps regardless of camera push rate.
                    Thread.sleep(80);
                } else {
                    Thread.sleep(50);
                }
            }
        } catch (Exception e) {
            // Client disconnected or server stopping - expected.
            Log.d(TAG, "mjpeg client ended: " + e.getMessage());
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    // --------------------------------------------------------------- control

    private boolean isRecordingNow() {
        return RecordingService.sRemoteState != null
                && ("IN_PROGRESS".equals(RecordingService.sRemoteState)
                || "PAUSED".equals(RecordingService.sRemoteState)
                || "STARTING".equals(RecordingService.sRemoteState));
    }

    private boolean toggleRecording() {
        Intent intent = new Intent(appContext, RecordingService.class);
        if (isRecordingNow()) {
            intent.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        } else {
            intent.setAction(Constants.INTENT_ACTION_START_RECORDING);
        }
        appContext.startService(intent);
        // Optimistically report the intended state; /status reflects reality shortly.
        return !isRecordingNow();
    }

    private boolean toggleTorch() {
        boolean on = !prefs.sharedPreferences.getBoolean(Constants.PREF_TORCH_STATE, false);
        if (isRecordingNow()) {
            Intent intent = new Intent(appContext, RecordingService.class);
            intent.setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);
            appContext.startService(intent);
        } else {
            try {
                CameraManager cm = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
                if (cm != null) {
                    String id = cm.getCameraIdList().length > 0 ? cm.getCameraIdList()[0] : null;
                    if (id != null) cm.setTorchMode(id, on);
                }
            } catch (Exception e) {
                Log.w(TAG, "torch toggle failed", e);
            }
        }
        prefs.sharedPreferences.edit().putBoolean(Constants.PREF_TORCH_STATE, on).apply();
        return on;
    }

    // ---------------------------------------------------------------- status

    private String buildStatusJson() {
        RemoteStreamHub hub = RemoteStreamHub.get();
        boolean recording = isRecordingNow();
        String state = RecordingService.sRemoteState != null ? RecordingService.sRemoteState : "NONE";

        int battery = -1;
        BatteryManager bm = (BatteryManager) appContext.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        long freeMb = -1;
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            freeMb = (stat.getAvailableBlocksLong() * stat.getBlockSizeLong()) / (1024 * 1024);
        } catch (Exception ignored) {
        }

        boolean torch = prefs.sharedPreferences.getBoolean(Constants.PREF_TORCH_STATE, false);
        long frameAge = hub.getLastFrameMs() == 0 ? -1 : (System.currentTimeMillis() - hub.getLastFrameMs());
        String url = "http://" + RemoteServerService.getLastKnownIp() + ":" + getListeningPort() + "/";

        return "{" +
                "\"app\":\"FadCam\"," +
                "\"version\":\"1.3.0-remote\"," +
                "\"state\":\"" + state + "\"," +
                "\"recording\":" + recording + "," +
                "\"cameraActive\":" + hub.isCameraActive() + "," +
                "\"streaming\":" + hub.hasFreshFrame(5000) + "," +
                "\"frameAgeMs\":" + frameAge + "," +
                "\"width\":" + hub.getWidth() + "," +
                "\"height\":" + hub.getHeight() + "," +
                "\"battery\":" + battery + "," +
                "\"storageFreeMb\":" + freeMb + "," +
                "\"torch\":" + torch + "," +
                "\"authEnabled\":" + authRequired() + "," +
                "\"url\":\"" + url + "\"" +
                "}";
    }
}
