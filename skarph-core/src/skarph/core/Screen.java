// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * One state of the application (menu, level, tool view...).
 * The engine calls init() once, then update() 60 times per second
 * and render() once per frame.
 * All memory must be allocated in init(); update()/render() must not use "new".
 */
public interface Screen {
    void init(Engine engine, Renderer renderer);

    /** One logic tick (1/60 s). No delta time: count ticks instead. */
    void update();

    void render(Renderer renderer);

    void pause();

    void resume();

    void dispose();
}
