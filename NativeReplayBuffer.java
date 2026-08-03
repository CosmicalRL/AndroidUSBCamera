package com.example.replaybuffer;

/**
 * JNI wrapper around a native circular ring buffer that holds up to 1200
 * raw video frames (20s @ 720p60) and can asynchronously encode the buffer
 * contents into an MP4 file via MediaCodec + MediaMuxer on the native side.
 *
 * Thread-safety: addFrame() is safe to call from a single high-rate producer
 * thread (e.g. the camera capture callback). exportAsync() may be called
 * from any thread; encoding runs on a background native thread and reports
 * back via the supplied callback.
 */
public final class NativeReplayBuffer {

    static {
        System.loadLibrary("native-lib");
    }

    /** Fixed ring capacity: 20 seconds of 720p60 raw frames. */
    public static final int RING_CAPACITY = 1200;

    /** Callback invoked on encode completion. Invoked from a native thread. */
    public interface EncodeCallback {
        void onEncodeComplete(boolean success, String messageOrPath);
    }

    private volatile boolean initialized = false;

    public NativeReplayBuffer() {
        nativeInit();
        initialized = true;
    }

    /**
     * Push a raw video frame into the ring buffer. The native layer allocates
     * exactly frameData.length bytes for this slot (reusing the previous
     * allocation when it already fits) and frees any overwritten buffer that
     * no longer fits, so resolution/quality can change frame-to-frame safely.
     *
     * @param frameData raw frame bytes (e.g. NV21/YUV420 or encoder input format)
     * @param presentationTimeUs monotonically increasing timestamp in microseconds
     */
    public void addFrame(byte[] frameData, long presentationTimeUs) {
        if (!initialized || frameData == null || frameData.length == 0) return;
        nativeAddFrame(frameData, presentationTimeUs);
    }

    /** Convenience overload using System.nanoTime()-derived microsecond timestamp. */
    public void addFrame(byte[] frameData) {
        addFrame(frameData, System.nanoTime() / 1000L);
    }

    /** Number of frames currently held in the ring buffer (0..1200). */
    public int getFrameCount() {
        return initialized ? nativeGetFrameCount() : 0;
    }

    /** Discards all buffered frames and frees their native memory immediately. */
    public void clear() {
        if (initialized) nativeClear();
    }

    /**
     * Kicks off asynchronous MP4 export of everything currently in the ring
     * buffer. Returns false immediately if an export is already running or
     * the buffer has not been initialized; otherwise returns true and the
     * callback fires later on a native worker thread once encoding finishes
     * (or fails).
     */
    public boolean exportAsync(String outputPath, int width, int height,
                                int fps, int bitrate, EncodeCallback callback) {
        if (!initialized) return false;
        return nativeExportAsync(outputPath, width, height, fps, bitrate, callback);
    }

    /** Export using the standard 720p60 defaults matching the ring buffer's sizing. */
    public boolean exportAsync(String outputPath, EncodeCallback callback) {
        return exportAsync(outputPath, 1280, 720, 60, 8_000_000, callback);
    }

    /** Requests the in-progress export to stop after the current frame. */
    public void stopExport() {
        if (initialized) nativeStopExport();
    }

    /**
     * Releases all native resources (ring buffer slots and any running
     * encoder). Must be called when the buffer is no longer needed
     * (e.g. Activity/Service onDestroy) to avoid leaking native heap memory.
     */
    public void release() {
        if (initialized) {
            nativeRelease();
            initialized = false;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (initialized) {
                release();
            }
        } finally {
            super.finalize();
        }
    }

    // ---- Native methods ----------------------------------------------------

    private native void nativeInit();

    private native void nativeAddFrame(byte[] frameData, long presentationTimeUs);

    private native int nativeGetFrameCount();

    private native void nativeClear();

    private native boolean nativeExportAsync(String outputPath, int width, int height,
                                              int fps, int bitrate, EncodeCallback callback);

    private native void nativeStopExport();

    private native void nativeRelease();
}
