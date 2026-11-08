// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

/** Tells the person what the first start is doing. */
public interface Progress {
    /** A new step, for example "Downloading lwjgl-glfw-3.3.6.jar (3 of 10)". */
    void status(String text);

    /** Bytes of the current file; total is -1 if unknown. */
    void progress(long done, long total);

    /** Everything is ready (or it failed): close the window. */
    void done();
}
