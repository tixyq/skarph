// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Fixed-width bitmap font. Glyphs come from a sprite sheet (see
 * TextureRegion.grid) in character-code order starting at firstChar.
 * Supports '\n'. Characters outside the sheet are drawn as blanks.
 * Text can come from a String literal or a TextBuffer: no allocation.
 */
public final class BitmapFont {
    private final TextureRegion[] glyphs;
    private final int firstChar;
    private final float cellW;
    private final float cellH;
    private float scale = 1f;

    public BitmapFont(TextureRegion[] glyphs, int firstChar) {
        this.glyphs = glyphs;
        this.firstChar = firstChar;
        cellW = glyphs[0].width;
        cellH = glyphs[0].height;
    }

    public void setScale(float scale) {
        this.scale = scale;
    }

    public float getLineHeight() {
        return cellH * scale;
    }

    public float getTextWidth(int chars) {
        return chars * cellW * scale;
    }

    public void draw(SpriteBatch batch, String text, float x, float y) {
        float cx = x;
        float cy = y;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                cx = x;
                cy += cellH * scale;
            } else {
                drawGlyph(batch, c, cx, cy);
                cx += cellW * scale;
            }
        }
    }

    public void draw(SpriteBatch batch, TextBuffer text, float x, float y) {
        float cx = x;
        float cy = y;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                cx = x;
                cy += cellH * scale;
            } else {
                drawGlyph(batch, c, cx, cy);
                cx += cellW * scale;
            }
        }
    }

    private void drawGlyph(SpriteBatch batch, char c, float x, float y) {
        int i = c - firstChar;
        if (i >= 0 && i < glyphs.length && c != ' ') {
            batch.draw(glyphs[i], x, y, cellW * scale, cellH * scale);
        }
    }
}
