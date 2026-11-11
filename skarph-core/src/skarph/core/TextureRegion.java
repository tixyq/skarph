// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A rectangle inside a texture (a sprite, a frame, a font glyph).
 * Mutable, so regions can be created once in init() and reused.
 * With nearest filtering cells may touch; with smooth filtering leave
 * 1 transparent pixel between cells to avoid bleeding.
 */
public final class TextureRegion {
    public int texture;
    public float u0;
    public float v0;
    public float u1;
    public float v1;
    /** Size in pixels. */
    public float width;
    public float height;

    /** Pixel rectangle (x, y from the top-left corner of the image). */
    public TextureRegion set(int texture, int texW, int texH, int x, int y, int w, int h) {
        this.texture = texture;
        u0 = (float) x / texW;
        v0 = (float) y / texH;
        u1 = (float) (x + w) / texW;
        v1 = (float) (y + h) / texH;
        width = w;
        height = h;
        return this;
    }

    /**
     * Splits a texture into a grid of equal cells (sprite sheet).
     * Result is row-major: index = row * cols + col. Allocates: use in init().
     */
    public static TextureRegion[] grid(int texture, int texW, int texH,
                                       int cellW, int cellH, int cols, int rows) {
        TextureRegion[] out = new TextureRegion[cols * rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out[r * cols + c] = new TextureRegion().set(
                        texture, texW, texH, c * cellW, r * cellH, cellW, cellH);
            }
        }
        return out;
    }
}
