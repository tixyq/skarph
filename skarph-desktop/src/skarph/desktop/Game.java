// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import skarph.core.Engine;
import skarph.core.Screen;

/**
 * What a program gives to the desktop layer. The class needs a public constructor without arguments; its name goes
 * into the jar manifest as Skarph-Game (or -Dskarph.game=NAME on the command line).
 */
public interface Game {
    /** Window title, size, folder name for saves and so on. Called once, before the window exists. */
    GameConfig config();

    /** Called once on the render thread, after the window and the OpenGL context exist. Returns the first screen. */
    Screen createScreen(Engine engine);
}
