// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Optional interface of a {@link Screen}: told when the drawing surface changes size (window resized, device rotated,
 * fullscreen toggled) and once right after init() with the initial size. Implement it to refresh projections and
 * {@code Ui.setViewport()}. Called on the render thread, before update() and render() of that frame; must not allocate.
 */
public interface Resizable {
    /** The surface is now w x h pixels. */
    void resize(int w, int h);
}
