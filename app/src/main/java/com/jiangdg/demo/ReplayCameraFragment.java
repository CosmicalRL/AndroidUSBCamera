package com.jiangdg.demo;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.ICaptureCallBack;
import com.jiangdg.ausbc.callback.IEncodeDataCallBack;
import com.jiangdg.ausbc.camera.bean.PreviewSize;
import com.jiangdg.ausbc.encode.H264EncodeProcessor;

import java.nio.ByteBuffer;

/** UVC camera fragment with continuous H.264 replay capture. */
public class ReplayCameraFragment extends DemoFragment {
    private ReplayBufferManager replayBufferManager;
    private boolean replayStreamRunning;

    public void attachReplayBuffer(ReplayBufferManager manager) { replayBufferManager = manager; }
    public ReplayBufferManager getReplayBufferManager() { return replayBufferManager; }

    public int getReplayWidth() {
        PreviewSize size = getCurrentPreviewSize();
        return size == null ? 1280 : size.getWidth();
    }

    public int getReplayHeight() {
        PreviewSize size = getCurrentPreviewSize();
        return size == null ? 720 : size.getHeight();
    }

    public void configureQuality(int width, int height, int fps, int bitrateKbps) {
        H264EncodeProcessor.configureDefaults(fps, bitrateKbps * 1000);
        if (isCameraOpened()) updateResolution(width, height);
    }

    public void startRecording(ICaptureCallBack callback, String path) {
        captureVideoStart(callback, path, 0L);
    }

    public void stopRecording() { captureVideoStop(); }

    @Override
    public void onCameraState(MultiCameraClient.ICamera self,
                              ICameraStateCallBack.State code,
                              String msg) {
        super.onCameraState(self, code, msg);
        if (code == ICameraStateCallBack.State.OPENED) startReplayStream();
        else if (code == ICameraStateCallBack.State.CLOSED || code == ICameraStateCallBack.State.ERROR) stopReplayStream();
    }

    private void startReplayStream() {
        if (replayStreamRunning || replayBufferManager == null) return;
        setEncodeDataCallBack(new IEncodeDataCallBack() {
            @Override
            public void onEncodeData(DataType type, ByteBuffer buffer, int offset, int size, long timestamp) {
                ReplayBufferManager manager = replayBufferManager;
                if (manager != null) manager.addEncodedData(type, buffer, offset, size, timestamp);
            }
        });
        captureStreamStart();
        replayStreamRunning = true;
    }

    private void stopReplayStream() {
        if (!replayStreamRunning) return;
        try { captureStreamStop(); } catch (Exception ignored) { }
        replayStreamRunning = false;
    }

    @Override
    public void onDestroyView() {
        stopReplayStream();
        super.onDestroyView();
    }
}
