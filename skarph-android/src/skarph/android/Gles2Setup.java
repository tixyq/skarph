// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.android;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.content.res.AssetManager;
import android.opengl.GLSurfaceView;

import skarph.core.Engine;

/**
 * Creates the OpenGL ES 2.0 renderer for a GLSurfaceView. Uses GLSurfaceView.setEGLContextClientVersion
 * (Android 2.2 / API 8) and AndroidGl2 needs API 9, so it is isolated in its own class that EngineView only touches after checking
 * the Android version.
 */
final class Gles2Setup {
    private Gles2Setup() {
    }

    /** True if the device reports OpenGL ES 2.0 or newer. */
    static boolean deviceSupportsGles2(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        ConfigurationInfo info = am.getDeviceConfigurationInfo();
        return info != null && info.reqGlEsVersion >= 0x20000;
    }

    /** Asks for an ES 2.0 context and returns the renderer to pass to setRenderer(). */
    static GLSurfaceView.Renderer install(GLSurfaceView view, Engine engine, AssetManager assets,
                                          GLES2Renderer.FailureListener listener) {
        view.setEGLContextClientVersion(2);
        GLES2Renderer renderer = new GLES2Renderer(engine, new AndroidGl2(),
                new BitmapImageLoader(assets), new AndroidLog(), listener);
        return new GLES2SurfaceRenderer(engine, renderer);
    }
}
