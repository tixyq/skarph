// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.demo.v08;

/** What the demo screen needs from the Activity. Plain Java: the screen itself never touches android.*. */
public interface BackendSwitcher {
    int GLES1 = 1;
    int GLES2 = 2;

    /** True if the OpenGL ES 2.0 backend can run on this device. */
    boolean isGles2Available();

    /** Restarts with GLES1 or GLES2 (called from the GL thread). */
    void switchBackend(int backend);

    /** Garbage collector runs of the process so far (Debug.getGlobalGcInvocationCount on Android), for the Profiler. */
    int getGcCount();
}
