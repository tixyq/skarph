// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import skarph.core.Engine;
import skarph.core.TouchDecoder;

/**
 * GLSurfaceView hosting the engine. Forward the Activity's onPause()/onResume()/onDestroy()
 * to this view (onDestroy -> destroy()).
 *
 * <b>Graphics backend:</b> BACKEND_AUTO (default) uses OpenGL ES 2.0 (shaders) when the device runs
 * Android 2.3+ (API 9: the ES 2.0 vertex buffer calls need it) and reports ES 2.0, otherwise OpenGL ES 1.x. You can force a backend; asking for ES 2.0 on a
 * device that cannot do it silently gives ES 1.x. If the ES 2.0 shaders fail on some driver, the optional
 * BackendListener is told (on the UI thread) so the app can start again with BACKEND_GLES1.
 *
 * <b>Input:</b> multi-touch on Android 2.0+ (pointer ids in the events); on Android 1.5 - 1.6 a single finger.
 * Also sets up the engine's Assets, Audio and Storage.
 */
public final class EngineView extends GLSurfaceView {
    public static final int BACKEND_AUTO = 0;
    public static final int BACKEND_GLES1 = 1;
    public static final int BACKEND_GLES2 = 2;

    /** Called on the UI thread when the OpenGL ES 2.0 backend cannot work on this device. */
    public interface BackendListener {
        void onBackendFailed(EngineView view, String reason);
    }

    private final Engine engine;
    private final int backend;
    private BackendListener backendListener;
    private final boolean multiTouch = AndroidVersion.SDK >= 5;
    private final int[] singleId = new int[1];
    private final float[] singleX = new float[1];
    private final float[] singleY = new float[1];

    private final Runnable pauseTask = new Runnable() {
        public void run() {
            engine.pause();
        }
    };
    private final Runnable resumeTask = new Runnable() {
        public void run() {
            engine.resume();
        }
    };

    public EngineView(Context context, Engine engine) {
        this(context, engine, BACKEND_AUTO);
    }

    public EngineView(Context context, Engine engine, int requestedBackend) {
        super(context);
        this.engine = engine;
        engine.setAssets(new AndroidAssets(context.getAssets()));
        engine.setAudio(new AndroidAudio(context.getAssets()));
        engine.setStorage(new AndroidStorage(context));

        boolean gles2 = isGles2Supported(context);
        int use = requestedBackend;
        if (use == BACKEND_AUTO) {
            use = gles2 ? BACKEND_GLES2 : BACKEND_GLES1;
        } else if (use == BACKEND_GLES2 && !gles2) {
            use = BACKEND_GLES1;
        }
        backend = use;
        if (use == BACKEND_GLES2) {
            setRenderer(Gles2Setup.install(this, engine, context.getAssets(),
                    new GLES2Renderer.FailureListener() {
                        public void onFailure(final String reason) {
                            post(new Runnable() {
                                public void run() {
                                    if (backendListener != null) {
                                        backendListener.onBackendFailed(EngineView.this, reason);
                                    }
                                }
                            });
                        }
                    }));
        } else {
            setRenderer(new GLES1Renderer(engine, context.getAssets()));
        }
    }

    /** True if this device can run the OpenGL ES 2.0 backend (Android 2.3+ and ES 2.0 reported). */
    public static boolean isGles2Supported(Context context) {
        return AndroidVersion.SDK >= 9 && Gles2Setup.deviceSupportsGles2(context);
    }

    /** @return BACKEND_GLES1 or BACKEND_GLES2: the backend this view really uses. */
    public int getBackend() {
        return backend;
    }

    public void setBackendListener(BackendListener listener) {
        backendListener = listener;
    }

    public void onPause() {
        queueEvent(pauseTask); // delivered on the GL thread before it stops
        super.onPause();
    }

    public void onResume() {
        super.onResume();
        queueEvent(resumeTask);
    }

    /** Call from Activity.onDestroy(): releases the sound resources. */
    public void destroy() {
        engine.getAudio().release();
    }

    public boolean onTouchEvent(MotionEvent e) {
        if (multiTouch) {
            MultiTouchHandler.handle(e, engine.input); // loaded only on API 5+
        } else {
            // Android 1.5 / 1.6: one finger, API 1 methods only
            singleId[0] = 0;
            singleX[0] = e.getX();
            singleY[0] = e.getY();
            TouchDecoder.decode(e.getAction() & 0xFF, 0, 1, singleId, singleX, singleY, engine.input);
        }
        return true;
    }
}
