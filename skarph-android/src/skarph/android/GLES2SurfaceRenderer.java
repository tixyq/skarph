// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.opengl.GLSurfaceView;
import android.util.Log;

import skarph.core.Engine;
import skarph.core.TextBuffer;

/** Connects GLSurfaceView's callbacks to the GLES2Renderer (the GL10 argument is not used in ES 2.0). */
final class GLES2SurfaceRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "skarph";

    private final Engine engine;
    private final GLES2Renderer renderer;
    private final TextBuffer statsText = new TextBuffer(64);

    GLES2SurfaceRenderer(Engine engine, GLES2Renderer renderer) {
        this.engine = engine;
        this.renderer = renderer;
    }

    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        renderer.onSurfaceCreated();
    }

    public void onSurfaceChanged(GL10 unused, int w, int h) {
        renderer.onSurfaceChanged(w, h);
    }

    public void onDrawFrame(GL10 unused) {
        renderer.onDrawFrame();
        if (engine.stats.changed) {
            engine.stats.changed = false;
            // once per second, outside the hot path (toString allocates)
            statsText.clear().append("fps=").append(engine.stats.fps)
                    .append(" tps=").append(engine.stats.tps);
            Log.d(TAG, statsText.toString());
        }
    }
}
