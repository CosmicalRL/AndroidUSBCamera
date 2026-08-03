// app/src/main/java/com/jiangdongguo/usbcamera/MainActivity.java
package com.jiangdg.demo;


import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.jiangdongguo.usbcamera.render.StreamCanvasRenderer;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity implements StreamCanvasRenderer.RendererCallback {

    private static final String GITHUB_URL = "https://github.com/jiangdongguo/AndroidUSBCamera";
    private static final long WEBVIEW_CAPTURE_INTERVAL_MS = 66L; // ~15 fps
    private static final int WEBVIEW_CAPTURE_WIDTH = 480;
    private static final int WEBVIEW_CAPTURE_HEIGHT = 854;

    private GLSurfaceView mGlSurfaceView;
    private StreamCanvasRenderer mRenderer;

    private USBMonitor mUsbMonitor;
    private UVCCamera mUvcCamera;
    private volatile boolean mCameraOpened = false;
    private volatile Surface mPendingCameraSurface = null;

    private WebView mWebView;
    private FrameLayout mWebViewContainer;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private WebViewCaptureTask mCaptureTask;
    private volatile boolean mCaptureRunning = false;

    private boolean mIsLive = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mGlSurfaceView = findViewById(R.id.gl_surface_view);
        mGlSurfaceView.setEGLContextClientVersion(2);
        mRenderer = new StreamCanvasRenderer(this, mGlSurfaceView, this);
        mGlSurfaceView.setRenderer(mRenderer);
        mGlSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        setupOffscreenWebView();
        setupUsbMonitor();

        Button btnGoLive = findViewById(R.id.btn_go_live);
        Button btnSaveReplay = findViewById(R.id.btn_save_replay);
        Button btnScenes = findViewById(R.id.btn_scenes);

        btnGoLive.setOnClickListener(v -> toggleLiveState());
        btnSaveReplay.setOnClickListener(v -> saveReplay());
        btnScenes.setOnClickListener(v -> mRenderer.toggleWebViewLayerVisibility());
    }

    // =========================================================
    // WebView (rendered off-screen, captured to bitmap for GL texture)
    // =========================================================

    private void setupOffscreenWebView() {
        mWebViewContainer = new FrameLayout(this);
        mWebViewContainer.setAlpha(0f);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                WEBVIEW_CAPTURE_WIDTH, WEBVIEW_CAPTURE_HEIGHT);
        addContentView(mWebViewContainer, containerParams);

        mWebView = new WebView(this);
        mWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        mWebViewContainer.addView(mWebView, new ViewGroup.LayoutParams(
                WEBVIEW_CAPTURE_WIDTH, WEBVIEW_CAPTURE_HEIGHT));
        mWebView.layout(0, 0, WEBVIEW_CAPTURE_WIDTH, WEBVIEW_CAPTURE_HEIGHT);
        mWebView.loadUrl(GITHUB_URL);

        startWebViewCaptureLoop();
    }

    private void startWebViewCaptureLoop() {
        mCaptureRunning = true;
        mCaptureTask = new WebViewCaptureTask(this);
        mMainHandler.postDelayed(mCaptureTask, WEBVIEW_CAPTURE_INTERVAL_MS);
    }

    private void stopWebViewCaptureLoop() {
        mCaptureRunning = false;
        mMainHandler.removeCallbacksAndMessages(null);
    }

    /** Static + WeakReference to avoid leaking the Activity via the Handler message queue. */
    private static class WebViewCaptureTask implements Runnable {
        private final WeakReference<MainActivity> mActivityRef;

        WebViewCaptureTask(MainActivity activity) {
            mActivityRef = new WeakReference<>(activity);
        }

        @Override
        public void run() {
            MainActivity activity = mActivityRef.get();
            if (activity == null || !activity.mCaptureRunning || activity.isFinishing()) {
                return;
            }
            activity.captureWebViewFrame();
            activity.mMainHandler.postDelayed(this, WEBVIEW_CAPTURE_INTERVAL_MS);
        }
    }

    private void captureWebViewFrame() {
        if (mWebView == null || mRenderer == null || !mRenderer.isWebViewLayerVisible()) {
            return;
        }
        // Fresh bitmap per frame: ownership transfers to the renderer, which
        // recycles it on the GL thread right after the texture upload completes.
        Bitmap frame = Bitmap.createBitmap(WEBVIEW_CAPTURE_WIDTH, WEBVIEW_CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        mWebView.draw(canvas);
        mRenderer.submitWebViewFrame(frame);
    }

    // =========================================================
    // USB / UVC camera wiring
    // =========================================================

    private void setupUsbMonitor() {
        mUsbMonitor = new USBMonitor(this, new USBMonitor.OnDeviceConnectListener() {
            @Override
            public void onAttach(android.hardware.usb.UsbDevice device) {
                mUsbMonitor.requestPermission(device);
            }

            @Override
            public void onConnect(android.hardware.usb.UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                mUvcCamera = new UVCCamera();
                mUvcCamera.open(ctrlBlock);
                try {
                    mUvcCamera.setPreviewSize(1280, 720, UVCCamera.FRAME_FORMAT_MJPEG);
                } catch (IllegalArgumentException e) {
                    mUvcCamera.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_MJPEG);
                }
                mCameraOpened = true;
                mRenderer.setCameraPreviewSize(1280, 720, 90);
                tryBindCameraPreview();
            }

            @Override
            public void onDisconnect(android.hardware.usb.UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                releaseCamera();
            }

            @Override
            public void onDettach(android.hardware.usb.UsbDevice device) {
                releaseCamera();
            }

            @Override
            public void onCancel(android.hardware.usb.UsbDevice device) {
                mCameraOpened = false;
            }
        });
    }

    @Override
    public void onCameraSurfaceReady(Surface cameraInputSurface) {
        mPendingCameraSurface = cameraInputSurface;
        tryBindCameraPreview();
    }

    private synchronized void tryBindCameraPreview() {
        if (mCameraOpened && mUvcCamera != null && mPendingCameraSurface != null) {
            mUvcCamera.setPreviewDisplay(mPendingCameraSurface);
            mUvcCamera.startPreview();
        }
    }

    private void releaseCamera() {
        mCameraOpened = false;
        if (mUvcCamera != null) {
            mUvcCamera.stopPreview();
            mUvcCamera.destroy();
            mUvcCamera = null;
        }
    }

    // =========================================================
    // Button actions
    // =========================================================

    private void toggleLiveState() {
        mIsLive = !mIsLive;
        // Hook up actual RTMP/streaming publisher start/stop here.
    }

    private void saveReplay() {
        // Hook up buffer/segment-based local recording save here.
    }

    // =========================================================
    // Lifecycle
    // =========================================================

    @Override
    protected void onResume() {
        super.onResume();
        mGlSurfaceView.onResume();
        if (mUsbMonitor != null) {
            mUsbMonitor.register();
        }
        if (!mCaptureRunning) {
            startWebViewCaptureLoop();
        }
    }

    @Override
    protected void onPause() {
        stopWebViewCaptureLoop();
        if (mUsbMonitor != null) {
            mUsbMonitor.unregister();
        }
        releaseCamera();
        mGlSurfaceView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopWebViewCaptureLoop();
        mMainHandler.removeCallbacksAndMessages(null);
        mCaptureTask = null;

        if (mGlSurfaceView != null && mRenderer != null) {
            mGlSurfaceView.queueEvent(() -> mRenderer.releaseGlResources());
        }

        if (mUsbMonitor != null) {
            mUsbMonitor.destroy();
            mUsbMonitor = null;
        }

        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.loadUrl("about:blank");
            mWebView.clearHistory();
            if (mWebViewContainer != null) {
                mWebViewContainer.removeView(mWebView);
            }
            mWebView.destroy();
            mWebView = null;
        }

        super.onDestroy();
    }
        }
