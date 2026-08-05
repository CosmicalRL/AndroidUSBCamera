// app/src/main/java/com/jiangdg/uvc/StreamCanvasRenderer.java
package com.jiangdg.uvc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class StreamCanvasRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "StreamCanvasRenderer";

    public interface RendererCallback {
        void onCameraSurfaceReady(Surface cameraInputSurface);
    }

    // ---- Camera (Layer 0) shaders ----
    private static final String CAMERA_VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String CAMERA_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n";

    // ---- WebView PiP (Layer 1) shaders ----
    private static final String WEBVIEW_VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * vec4(aPosition, 0.0, 1.0);\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String WEBVIEW_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "uniform float uAlpha;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(sTexture, vTexCoord);\n" +
            "    gl_FragColor = vec4(c.rgb, c.a * uAlpha);\n" +
            "}\n";

    private static final float[] FULLSCREEN_POS = {
            -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f
    };
    private static final float[] FULLSCREEN_TEX = {
            0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f
    };
    private static final float[] UNIT_QUAD_POS = {
            -0.5f, -0.5f,  0.5f, -0.5f,  -0.5f, 0.5f,  0.5f, 0.5f
    };
    private static final float[] UNIT_QUAD_TEX = {
            0f, 1f,  1f, 1f,  0f, 0f,  1f, 0f
    };

    private final WeakReference<Context> mContextRef;
    private final WeakReference<GLSurfaceView> mGlSurfaceViewRef;
    private final WeakReference<RendererCallback> mCallbackRef;

    private final FloatBuffer mFullscreenPosBuf;
    private final FloatBuffer mFullscreenTexBuf;
    private final FloatBuffer mUnitQuadPosBuf;
    private final FloatBuffer mUnitQuadTexBuf;

    // Layer 0: camera (external OES texture)
    private int mCameraProgram;
    private int mCameraTextureId = -1;
    private SurfaceTexture mCameraSurfaceTexture;
    private Surface mCameraInputSurface;
    private final float[] mSurfaceTextureMatrix = new float[16];
    private final float[] mCropMatrix = new float[16];
    private final float[] mCameraTexTransform = new float[16];
    private final AtomicBoolean mCameraFrameAvailable = new AtomicBoolean(false);

    private int mViewWidth = 0;
    private int mViewHeight = 0;
    private int mCameraFrameWidth = 1280;
    private int mCameraFrameHeight = 720;
    private int mCameraRotationDegrees = 90;

    // Layer 1: WebView overlay (2D texture, alpha blended, scalable PiP)
    private int mWebViewProgram;
    private int mWebViewTextureId = -1;
    private boolean mWebViewTextureAllocated = false;
    private final Object mWebViewBitmapLock = new Object();
    private Bitmap mPendingWebViewBitmap;
    private volatile boolean mWebViewTextureDirty = false;

    private final AtomicBoolean mShowWebViewLayer = new AtomicBoolean(true);
    private volatile float mPipScale = 0.42f;
    private volatile float mPipOffsetX = 0.52f;
    private volatile float mPipOffsetY = 0.62f;
    private volatile float mPipAlpha = 0.95f;
    private final float[] mWebViewModelMatrix = new float[16];

    public StreamCanvasRenderer(Context context, GLSurfaceView glSurfaceView, RendererCallback callback) {
        mContextRef = new WeakReference<>(context.getApplicationContext());
        mGlSurfaceViewRef = new WeakReference<>(glSurfaceView);
        mCallbackRef = new WeakReference<>(callback);

        mFullscreenPosBuf = toFloatBuffer(FULLSCREEN_POS);
        mFullscreenTexBuf = toFloatBuffer(FULLSCREEN_TEX);
        mUnitQuadPosBuf = toFloatBuffer(UNIT_QUAD_POS);
        mUnitQuadTexBuf = toFloatBuffer(UNIT_QUAD_TEX);

        Matrix.setIdentityM(mSurfaceTextureMatrix, 0);
        Matrix.setIdentityM(mCropMatrix, 0);
        Matrix.setIdentityM(mWebViewModelMatrix, 0);
    }

    // =========================================================
    // GLSurfaceView.Renderer
    // =========================================================

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);

        mCameraProgram = buildProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER);
        mWebViewProgram = buildProgram(WEBVIEW_VERTEX_SHADER, WEBVIEW_FRAGMENT_SHADER);

        mCameraTextureId = createOesTexture();
        mCameraSurfaceTexture = new SurfaceTexture(mCameraTextureId);
        mCameraSurfaceTexture.setOnFrameAvailableListener(this);
        mCameraInputSurface = new Surface(mCameraSurfaceTexture);

        mWebViewTextureId = create2dTexturePlaceholder();
        mWebViewTextureAllocated = true;

        RendererCallback cb = mCallbackRef.get();
        if (cb != null) {
            cb.onCameraSurfaceReady(mCameraInputSurface);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mViewWidth = width;
        mViewHeight = height;
        GLES20.glViewport(0, 0, width, height);
        recomputeCropMatrix();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (mCameraFrameAvailable.compareAndSet(true, false)) {
            try {
                mCameraSurfaceTexture.updateTexImage();
                mCameraSurfaceTexture.getTransformMatrix(mSurfaceTextureMatrix);
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage failed", e);
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_BLEND);

        drawCameraLayer();

        if (mShowWebViewLayer.get()) {
            uploadPendingWebViewBitmapIfDirty();
            drawWebViewLayer();
        }
    }

    // =========================================================
    // Layer 0: Camera
    // =========================================================

    private void drawCameraLayer() {
        GLES20.glUseProgram(mCameraProgram);

        int aPosition = GLES20.glGetAttribLocation(mCameraProgram, "aPosition");
        int aTexCoord = GLES20.glGetAttribLocation(mCameraProgram, "aTexCoord");
        int uTexMatrix = GLES20.glGetUniformLocation(mCameraProgram, "uTexMatrix");
        int sTexture = GLES20.glGetUniformLocation(mCameraProgram, "sTexture");

        Matrix.multiplyMM(mCameraTexTransform, 0, mSurfaceTextureMatrix, 0, mCropMatrix, 0);

        mFullscreenPosBuf.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, mFullscreenPosBuf);
        GLES20.glEnableVertexAttribArray(aPosition);

        mFullscreenTexBuf.position(0);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, mFullscreenTexBuf);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, mCameraTexTransform, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mCameraTextureId);
        GLES20.glUniform1i(sTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        // Called on an arbitrary thread. No GL calls here — just flag + request render.
        mCameraFrameAvailable.set(true);
        GLSurfaceView view = mGlSurfaceViewRef.get();
        if (view != null) {
            view.requestRender();
        }
    }

    public void setCameraPreviewSize(int width, int height, int rotationDegrees) {
        mCameraFrameWidth = width;
        mCameraFrameHeight = height;
        mCameraRotationDegrees = rotationDegrees;
        recomputeCropMatrix();
    }

    private void recomputeCropMatrix() {
        Matrix.setIdentityM(mCropMatrix, 0);
        if (mViewWidth == 0 || mViewHeight == 0 || mCameraFrameWidth == 0 || mCameraFrameHeight == 0) {
            return;
        }

        boolean swapped = (mCameraRotationDegrees % 180) != 0;
        int effCamW = swapped ? mCameraFrameHeight : mCameraFrameWidth;
        int effCamH = swapped ? mCameraFrameWidth : mCameraFrameHeight;

        float viewAspect = (float) mViewWidth / (float) mViewHeight;
        float camAspect = (float) effCamW / (float) effCamH;

        float scaleX = 1f, scaleY = 1f;
        if (camAspect > viewAspect) {
            scaleX = viewAspect / camAspect;
        } else {
            scaleY = camAspect / viewAspect;
        }

        Matrix.translateM(mCropMatrix, 0, 0.5f, 0.5f, 0f);
        if (mCameraRotationDegrees != 0) {
            Matrix.rotateM(mCropMatrix, 0, mCameraRotationDegrees, 0f, 0f, 1f);
        }
        Matrix.scaleM(mCropMatrix, 0, scaleX, scaleY, 1f);
        Matrix.translateM(mCropMatrix, 0, -0.5f, -0.5f, 0f);
    }

    public Surface getCameraInputSurface() {
        return mCameraInputSurface;
    }

    // =========================================================
    // Layer 1: WebView PiP overlay
    // =========================================================

    private void drawWebViewLayer() {
        if (!mWebViewTextureAllocated) return;

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glUseProgram(mWebViewProgram);

        int aPosition = GLES20.glGetAttribLocation(mWebViewProgram, "aPosition");
        int aTexCoord = GLES20.glGetAttribLocation(mWebViewProgram, "aTexCoord");
        int uMVPMatrix = GLES20.glGetUniformLocation(mWebViewProgram, "uMVPMatrix");
        int sTexture = GLES20.glGetUniformLocation(mWebViewProgram, "sTexture");
        int uAlpha = GLES20.glGetUniformLocation(mWebViewProgram, "uAlpha");

        float viewAspect = mViewHeight != 0 ? (float) mViewWidth / (float) mViewHeight : 1f;
        Matrix.setIdentityM(mWebViewModelMatrix, 0);
        Matrix.translateM(mWebViewModelMatrix, 0, mPipOffsetX, mPipOffsetY, 0f);
        Matrix.scaleM(mWebViewModelMatrix, 0, mPipScale * 2f * viewAspect, mPipScale * 2f, 1f);

        mUnitQuadPosBuf.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, mUnitQuadPosBuf);
        GLES20.glEnableVertexAttribArray(aPosition);

        mUnitQuadTexBuf.position(0);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, mUnitQuadTexBuf);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        GLES20.glUniformMatrix4fv(uMVPMatrix, 1, false, mWebViewModelMatrix, 0);
        GLES20.glUniform1f(uAlpha, mPipAlpha);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mWebViewTextureId);
        GLES20.glUniform1i(sTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /**
     * Called from the UI thread. Ownership of `bitmap` transfers to the renderer:
     * it is consumed and recycled on the GL thread during the next draw, so the
     * caller must not reuse or recycle it after this call.
     */
    public void submitWebViewFrame(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        Bitmap staleUnconsumed = null;
        synchronized (mWebViewBitmapLock) {
            if (mPendingWebViewBitmap != null && !mPendingWebViewBitmap.isRecycled()) {
                // GL thread hasn't consumed the previous frame yet — it was never
                // touched by GL, so it is safe to recycle here to avoid buildup.
                staleUnconsumed = mPendingWebViewBitmap;
            }
            mPendingWebViewBitmap = bitmap;
            mWebViewTextureDirty = true;
        }
        if (staleUnconsumed != null) {
            staleUnconsumed.recycle();
        }
        GLSurfaceView view = mGlSurfaceViewRef.get();
        if (view != null) {
            view.requestRender();
        }
    }

    private void uploadPendingWebViewBitmapIfDirty() {
        Bitmap toUpload = null;
        synchronized (mWebViewBitmapLock) {
            if (mWebViewTextureDirty && mPendingWebViewBitmap != null && !mPendingWebViewBitmap.isRecycled()) {
                toUpload = mPendingWebViewBitmap;
                mPendingWebViewBitmap = null;
                mWebViewTextureDirty = false;
            }
        }
        if (toUpload == null) return;

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mWebViewTextureId);
        // GLUtils.texImage2D copies pixel data into GPU memory synchronously,
        // so it is safe to recycle the bitmap immediately after this call.
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, toUpload, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        toUpload.recycle();
    }

    // =========================================================
    // Scene controller
    // =========================================================

    /** Instantly toggles the WebView PiP overlay layer on/off. Safe to call from any thread. */
    public void toggleWebViewLayerVisibility() {
        mShowWebViewLayer.set(!mShowWebViewLayer.get());
        GLSurfaceView view = mGlSurfaceViewRef.get();
        if (view != null) {
            view.requestRender();
        }
    }

    public void setWebViewLayerVisible(boolean visible) {
        mShowWebViewLayer.set(visible);
        GLSurfaceView view = mGlSurfaceViewRef.get();
        if (view != null) {
            view.requestRender();
        }
    }

    public boolean isWebViewLayerVisible() {
        return mShowWebViewLayer.get();
    }

    public void setPipScale(float scale) {
        mPipScale = Math.max(0.05f, Math.min(1f, scale));
    }

    public void setPipPosition(float ndcX, float ndcY) {
        mPipOffsetX = ndcX;
        mPipOffsetY = ndcY;
    }

    public void setPipAlpha(float alpha) {
        mPipAlpha = Math.max(0f, Math.min(1f, alpha));
    }

    // =========================================================
    // GL resource helpers
    // =========================================================

    private int createOesTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    private int create2dTexturePlaceholder() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        Bitmap placeholder = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        placeholder.eraseColor(0x00000000);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, placeholder, 0);
        placeholder.recycle();

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return tex[0];
    }

    private int buildProgram(String vertexSrc, String fragmentSrc) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Program link failed: " + log);
        }

        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    private int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);

        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }

    private static FloatBuffer toFloatBuffer(float[] data) {
        FloatBuffer buf = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buf.put(data).position(0);
        return buf;
    }

    /** Must be invoked via glSurfaceView.queueEvent(...) so it runs on the GL thread. */
    public void releaseGlResources() {
        synchronized (mWebViewBitmapLock) {
            if (mPendingWebViewBitmap != null && !mPendingWebViewBitmap.isRecycled()) {
                mPendingWebViewBitmap.recycle();
            }
            mPendingWebViewBitmap = null;
            mWebViewTextureDirty = false;
        }

        if (mCameraTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mCameraTextureId}, 0);
            mCameraTextureId = -1;
        }
        if (mWebViewTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mWebViewTextureId}, 0);
            mWebViewTextureId = -1;
            mWebViewTextureAllocated = false;
        }
        if (mCameraProgram != 0) {
            GLES20.glDeleteProgram(mCameraProgram);
            mCameraProgram = 0;
        }
        if (mWebViewProgram != 0) {
            GLES20.glDeleteProgram(mWebViewProgram);
            mWebViewProgram = 0;
        }
        if (mCameraInputSurface != null) {
            mCameraInputSurface.release();
            mCameraInputSurface = null;
        }
        if (mCameraSurfaceTexture != null) {
            mCameraSurfaceTexture.setOnFrameAvailableListener(null);
            mCameraSurfaceTexture.release();
            mCameraSurfaceTexture = null;
        }
    }
          }
