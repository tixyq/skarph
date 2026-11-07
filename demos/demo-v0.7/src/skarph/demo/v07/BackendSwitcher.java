// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.demo.v07;

/** Lets the demo screen ask the Activity to restart the graphics with another backend. */
public interface BackendSwitcher {
    /** True if the OpenGL ES 2.0 backend can run on this device. */
    boolean isGles2Available();

    /** Restarts with EngineView.BACKEND_GLES1 or BACKEND_GLES2 (called from the GL thread). */
    void switchBackend(int backend);
}
