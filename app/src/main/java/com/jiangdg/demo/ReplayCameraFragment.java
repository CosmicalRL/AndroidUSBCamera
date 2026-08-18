package com.jiangdg.demo;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.IEncodeDataCallBack;
import com.jiangdg.ausbc.camera.bean.PreviewSize;

import java.nio.ByteBuffer;

/**
 * DemoFragment variant that keeps the libausbc UVC pipeline alive and exposes
 * its encoded H.264 stream to ReplayBufferManager.
 */
public class ReplayCameraFragment extends DemoFragment {
    private ReplayBufferManager replayBufferManager;
    private boolean replayStreamRunning;

    public void attachReplayBuffer(ReplayBufferManager manager) {
        replayBufferManager = manager;
    }

    public ReplayBufferManager getReplayBufferManager() {
        return replayBufferManager;
    }

    public int getReplayWidth() {
        PreviewSize size = getCurrentPreviewSize();
        return size == null ? 1280 : size.getWidth();
    }

    public int getReplayHeight() {
        PreviewSize size = getCurrentPreviewSize();
        return size == null ? 720 : size.getHeight();
    }

    @Override
    public void onCameraState(MultiCameraClient.ICamera self,
                               ICameraStateCallBack.State code,
                               String msg) {
        super.onCameraState(self, code, msg);
        if (code == ICameraStateCallBack.State.OPENED) {
            startReplayStream();
        } else if (code == ICameraStateCallBack.State.CLOSED
                || code == ICameraStateCallBack.State.ERROR) {
            stopReplayStream();
        }
    }

    private void startReplayStream() {
        if (replayStreamRunning || replayBufferManager == null) {
            return;
        }
        setEncodeDataCallBack(new IEncodeDataCallBack() {
            @Override
            public void onEncodeData(DataType type, ByteBuffer buffer, int offset,
                                     int size, long timestamp) {
                ReplayBufferManager manager = replayBufferManager;
                if (manager != null) {
                    manager.addEncodedData(type, buffer, offset, size, timestamp);
                }
            }
        });
        captureStreamStart();
        replayStreamRunning = true;
    }

    private void stopReplayStream() {
        if (!replayStreamRunning) {
            return;
        }
        try {
            captureStreamStop();
        } catch (Exception ignored) {
        }
        replayStreamRunning = false;
    }

    @Override
    public void onDestroyView() {
        stopReplayStream();
        super.onDestroyView();
    }
}
