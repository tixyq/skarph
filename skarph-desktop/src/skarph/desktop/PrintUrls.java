// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

/** Prints the main-mirror URL of every library file, one per line (input of tools/pin_lwjgl.py). */
public final class PrintUrls {
    private PrintUrls() {
    }

    public static void main(String[] args) {
        String mirror = Libraries.defaultMirrors().get(0);
        for (Libraries.Artifact a : Libraries.everything()) {
            System.out.println(a.url(mirror));
        }
    }
}
