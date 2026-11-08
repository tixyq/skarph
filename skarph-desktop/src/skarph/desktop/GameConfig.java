// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

/** Window and storage settings of a program. Every field has a usable default. */
public final class GameConfig {
    public String title = "Skarph";
    /** Size of the window (the client area, in screen units). */
    public int width = 1280;
    public int height = 720;
    public boolean fullscreen;
    /** Wait for the screen refresh (no tearing, no wasted power). -Dskarph.vsync=false turns it off. */
    public boolean vsync = true;
    /** Folder name for saves and settings: Storage files go to the application data folder of the system, in this folder. */
    public String appName = "skarph-game";
    /** Closing the window with the Escape key. */
    public boolean escapeCloses = true;
    /** Show frames and ticks per second in the window title. */
    public boolean fpsInTitle;
}
