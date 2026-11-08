// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

import skarph.android.GlLog;

/** GlLog on the standard streams. */
public final class ConsoleLog implements GlLog {
    public void info(String message) {
        System.out.println("skarph: " + message);
    }

    public void warn(String message) {
        System.err.println("skarph: warning: " + message);
    }

    public void error(String message) {
        System.err.println("skarph: error: " + message);
    }
}
