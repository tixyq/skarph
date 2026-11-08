// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

/** Minimal logging, so that the renderer logic has no dependency on android.util.Log. */
public interface GlLog {
    void info(String message);

    void warn(String message);

    void error(String message);
}
