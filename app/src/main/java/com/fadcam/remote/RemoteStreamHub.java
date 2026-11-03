package com.fadcam.remote;

/**
 * Process-wide bridge between the camera pipeline (RecordingService) and the
 * remote HTTP server. The recording service pushes JPEG frames here; any number
 * of HTTP clients can pull the latest frame.
 *
 * Ported (adapted to the MediaRecorder based v1.3.0 pipeline) from the
 * FadCam v3 "FadCam Remote" feature.
 */
public final class RemoteStreamHub {

    private static final RemoteStreamHub INSTANCE = new RemoteStreamHub();

    public static RemoteStreamHub get() {
        return INSTANCE;
    }

    private RemoteStreamHub() {
    }

    private volatile byte[] latestJpeg;
    private volatile long lastFrameMs;
    private volatile int width;
    private volatile int height;
    private volatile boolean cameraActive;

    public synchronized void pushJpeg(byte[] jpeg, int w, int h) {
        if (jpeg == null || jpeg.length == 0) return;
        this.latestJpeg = jpeg;
        this.width = w;
        this.height = h;
        this.lastFrameMs = System.currentTimeMillis();
        notifyAll();
    }

    public byte[] getLatestJpeg() {
        return latestJpeg;
    }

    public long getLastFrameMs() {
        return lastFrameMs;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setCameraActive(boolean active) {
        this.cameraActive = active;
    }

    public boolean isCameraActive() {
        return cameraActive;
    }

    public boolean hasFreshFrame(long maxAgeMs) {
        return latestJpeg != null && (System.currentTimeMillis() - lastFrameMs) <= maxAgeMs;
    }
}
