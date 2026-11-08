// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

/** Progress on the standard error stream: one line per step. */
public final class ConsoleProgress implements Progress {
    public void status(String text) {
        System.err.println("skarph: " + text);
    }

    public void progress(long done, long total) {
        // one line per step is enough on a console
    }

    public void done() {
    }
}
