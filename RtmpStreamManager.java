package com.jiangdongguo.androidusbcamera.streaming;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.pedro.common.ConnectChecker;
import com.pedro.encoder.input.sources.audio.MicrophoneSource;
import com.pedro.encoder.input.sources.video.CameraUvcSource;
import com.pedro.library.generic.GenericStream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background Service wrapping a RootEncoder GenericStream instance.
 * Captures frames from a connected UVC/USB camera via CameraUvcSource,
 * encodes them, and pushes the stream to an RTMP endpoint (e.g. YouTube Live ingestion,
 * used for YouTube Shorts when the source is vertical and the stream is short-form).
 * All prepare/start/stop calls against the encoder pipeline are serialized on a
 * single background thread so GenericStream is never touched from two threads at once.
 */
public class RtmpStreamManager extends Service implements ConnectChecker {

    private static final String TAG = "RtmpStreamManager";

    private static final String NOTIFICATION_CHANNEL_ID = "rtmp_stream_channel";
    private static final int NOTIFICATION_ID = 4501;

    // Full URL is YOUTUBE_RTMP_BASE_URL + <stream key from YouTube Studio>.
    // Use a vertical, Shorts-eligible resolution/aspect ratio (e.g. 720x1280) for Shorts.
    private static final String YOUTUBE_RTMP_BASE_URL = "rtmp://a.rtmp.youtube.com/live2/";

    private static final int VIDEO_WIDTH = 720;
    private static final int VIDEO_HEIGHT = 1280;
    private static final int VIDEO_BITRATE = 2_500_000;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final boolean AUDIO_STEREO = true;
    private static final int AUDIO_BITRATE = 128_000;

    private final IBinder binder = new LocalBinder();
    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isPrepared = new AtomicBoolean(false);
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final Object streamLock = new Object();

    private GenericStream genericStream;
    private RtmpStreamListener listener;

    public interface RtmpStreamListener {
        void onStreamStarted();
        void onStreamStopped();
        void onStreamFailed(String reason);
        void onBitrateChanged(long bitrate);
    }

    public class LocalBinder extends Binder {
        public RtmpStreamManager getService() {
            return RtmpStreamManager.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        genericStream = new GenericStream(
                getApplicationContext(),
                this,
                new CameraUvcSource(getApplicationContext()),
                new MicrophoneSource()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing stream..."));
        return START_STICKY;
    }

    public void setListener(RtmpStreamListener listener) {
        this.listener = listener;
    }

    /** Prepares the encoder pipeline (once) and starts pushing frames to the given stream key. */
    public void startStreaming(String streamKey) {
        streamExecutor.execute(() -> {
            synchronized (streamLock) {
                if (isStreaming.get()) {
                    Log.w(TAG, "startStreaming: already streaming, ignoring call");
                    return;
                }
                try {
                    if (!isPrepared.get()) {
                        boolean videoReady = genericStream.prepareVideo(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_BITRATE);
                        boolean audioReady = genericStream.prepareAudio(AUDIO_SAMPLE_RATE, AUDIO_STEREO, AUDIO_BITRATE);
                        if (!videoReady || !audioReady) {
                            notifyFailure("Failed to prepare encoder (unsupported resolution/bitrate)");
                            return;
                        }
                        isPrepared.set(true);
                    }
                    genericStream.startStream(YOUTUBE_RTMP_BASE_URL + streamKey);
                    isStreaming.set(true);
                    updateNotification("Streaming to YouTube...");
                } catch (Exception e) {
                    Log.e(TAG, "startStreaming failed", e);
                    notifyFailure(e.getMessage());
                }
            }
        });
    }

    /** Stops the active stream. Safe to call from any thread. */
    public void stopStreaming() {
        streamExecutor.execute(() -> {
            synchronized (streamLock) {
                if (!isStreaming.get()) {
                    return;
                }
                try {
                    genericStream.stopStream();
                } finally {
                    isStreaming.set(false);
                    updateNotification("Stream stopped");
                    if (listener != null) {
                        listener.onStreamStopped();
                    }
                }
            }
        });
    }

    public boolean isCurrentlyStreaming() {
        return isStreaming.get();
    }

    private void notifyFailure(String reason) {
        isStreaming.set(false);
        updateNotification("Stream error");
        if (listener != null) {
            listener.onStreamFailed(reason);
        }
    }

    @Override
    public void onDestroy() {
        streamExecutor.execute(() -> {
            synchronized (streamLock) {
                if (genericStream != null) {
                    if (genericStream.isStreaming()) {
                        genericStream.stopStream();
                    }
                    genericStream.release();
                }
            }
        });
        streamExecutor.shutdown();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "RTMP Streaming",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String contentText) {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("USB Camera Live Stream")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String contentText) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(contentText));
        }
    }

    // ConnectChecker callbacks — invoked by RootEncoder's internal RTMP client thread.
    @Override
    public void onConnectionStarted(String url) {
        Log.d(TAG, "onConnectionStarted: " + url);
    }

    @Override
    public void onConnectionSuccess() {
        Log.d(TAG, "onConnectionSuccess");
        if (listener != null) {
            listener.onStreamStarted();
        }
    }

    @Override
    public void onConnectionFailed(String reason) {
        Log.e(TAG, "onConnectionFailed: " + reason);
        notifyFailure(reason);
    }

    @Override
    public void onNewBitrate(long bitrate) {
        if (listener != null) {
            listener.onBitrateChanged(bitrate);
        }
    }

    @Override
    public void onDisconnect() {
        Log.d(TAG, "onDisconnect");
        isStreaming.set(false);
        if (listener != null) {
            listener.onStreamStopped();
        }
    }

    @Override
    public void onAuthError() {
        Log.e(TAG, "onAuthError");
        notifyFailure("RTMP authentication error");
    }

    @Override
    public void onAuthSuccess() {
        Log.d(TAG, "onAuthSuccess");
    }
}
