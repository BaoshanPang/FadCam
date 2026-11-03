package com.fadcam.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Foreground service that keeps the ported remote-control HTTP server alive.
 */
public class RemoteServerService extends Service {

    private static final String TAG = "FadCamRemoteService";
    private static final String CHANNEL_ID = "FadCamRemoteChannel";
    private static final int NOTIF_ID = 4242;

    public static final String ACTION_START = "com.fadcam.remote.START";
    public static final String ACTION_STOP = "com.fadcam.remote.STOP";

    private static volatile String lastKnownIp = "0.0.0.0";

    private RemoteServer server;

    public static String getLastKnownIp() {
        return lastKnownIp;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand action=" + (intent == null ? "null" : intent.getAction()));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Must call startForeground promptly when started with startForegroundService().
        startForeground(NOTIF_ID, buildNotification());
        Log.i(TAG, "startForeground done");
        ensureServer();
        return START_STICKY;
    }

    private void ensureServer() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        if (!prefs.isRemoteEnabled()) {
            Log.i(TAG, "remote disabled - not starting server");
            stopSelf();
            return;
        }
        lastKnownIp = resolveIp();
        int port = prefs.getRemotePort();
        if (server != null) {
            if (server.getListeningPort() == port) {
                return;
            }
            server.stopServer();
            server = null;
        }
        try {
            server = new RemoteServer(this, port);
            server.start(NanoHttpdSocketFactory.TIMEOUT, false);
            Log.i(TAG, "remote server listening on " + lastKnownIp + ":" + port);
        } catch (Exception e) {
            Log.e(TAG, "failed to start remote server on port " + port, e);
            server = null;
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stopServer();
            server = null;
        }
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "FadCam Remote",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Remote control server");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_remote)
                .setContentTitle("FadCam Remote active")
                .setContentText("http://" + lastKnownIp + ":" + SharedPreferencesManager.getInstance(this).getRemotePort() + "/")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private String resolveIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                            ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }

    /** Small holder so the service does not need to import NanoHTTPD constants directly. */
    private static final class NanoHttpdSocketFactory {
        static final int TIMEOUT = 5000;
    }
}
