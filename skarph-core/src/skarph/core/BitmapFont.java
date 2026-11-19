// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Bitmap font in two flavours, one API.
 * <ul>
 * <li><b>Fixed width</b>: {@code new BitmapFont(TextureRegion.grid(...), 32)}, glyphs in character-code order (the classic
 * sprite sheet, ASCII only).</li>
 * <li><b>Proportional, Unicode</b>: {@link #fromData} with a font made by {@code tools/make_font.py} (any TTF, Latin,
 * Cyrillic, ... up to 65536 characters, each with its own width and offsets). The engine ships
 * {@code fonts/dejavu*.png/.fnt}: Latin, Latin-1, Cyrillic and common punctuation.</li>
 * </ul>
 * Text can be a String or a TextBuffer; '\n' starts a new line. Word wrap, alignment and ellipsis are built in. Nothing
 * allocates while drawing or measuring (a small char buffer grows once if a text is longer than any before).
 * Characters that the font does not have are drawn as '?', or skipped if there is no '?'.
 */
public final class BitmapFont {
    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    private static final int MAX_SCRATCH_START = 256;

    // fixed width mode
    private final TextureRegion[] glyphs;
    private final int firstChar;
    private final float cellW;
    private final float cellH;
    // proportional mode
    private final boolean proportional;
    private final short[][] pages;          // [code >> 8][code & 255] = glyph index + 1
    private final TextureRegion[] regs;
    private final float[] xoff;
    private final float[] yoff;
    private final float[] adv;
    private final float lineH;
    private final int fallback;             // glyph index of '?', or -1
    private final int spaceIndex;

    private float scale = 1f;
    private char[] scratch = new char[MAX_SCRATCH_START];
    // results of wrapLine()
    private int wrapNext;
    private float wrapWidth;

    /** Fixed width font from a sprite sheet. */
    public BitmapFont(TextureRegion[] glyphs, int firstChar) {
        this.glyphs = glyphs;
        this.firstChar = firstChar;
        cellW = glyphs[0].width;
        cellH = glyphs[0].height;
        proportional = false;
        pages = null;
        regs = null;
        xoff = null;
        yoff = null;
        adv = null;
        lineH = cellH;
        fallback = -1;
        spaceIndex = -1;
    }

    private BitmapFont(TextureRegion[] regs, float[] xoff, float[] yoff, float[] adv, short[][] pages, float lineH) {
        this.glyphs = null;
        this.firstChar = 0;
        this.regs = regs;
        this.xoff = xoff;
        this.yoff = yoff;
        this.adv = adv;
        this.pages = pages;
        this.lineH = lineH;
        proportional = true;
        cellH = lineH;
        int q = pages[0] == null ? 0 : pages[0]['?'];
        fallback = q - 1;
        int sp = pages[0] == null ? 0 : pages[0][' '];
        spaceIndex = sp - 1;
        cellW = sp > 0 ? adv[sp - 1] : lineH * 0.5f;
    }

    /**
     * A proportional font from the .fnt file made by tools/make_font.py ("SKF1" + glyph table) and its texture.
     * Allocates (init time). @return the font, or null if the data is not a valid SKF1 file
     */
    public static BitmapFont fromData(int texture, int texW, int texH, byte[] d) {
        if (d == null || d.length < 10 || d[0] != 'S' || d[1] != 'K' || d[2] != 'F' || d[3] != '1') {
            return null;
        }
        int lineHeight = u16(d, 4);
        int count = u16(d, 8);
        if (d.length < 10 + count * 11) {
            return null;
        }
        TextureRegion[] regs = new TextureRegion[count];
        float[] xo = new float[count];
        float[] yo = new float[count];
        float[] ad = new float[count];
        short[][] pages = new short[256][];
        int p = 10;
        for (int i = 0; i < count; i++) {
            int code = u16(d, p);
            int gx = u16(d, p + 2);
            int gy = u16(d, p + 4);
            int gw = d[p + 6] & 255;
            int gh = d[p + 7] & 255;
            xo[i] = d[p + 8];
            yo[i] = d[p + 9];
            ad[i] = d[p + 10] & 255;
            p += 11;
            regs[i] = new TextureRegion().set(texture, texW, texH, gx, gy, gw, gh);
            if (pages[code >> 8] == null) {
                pages[code >> 8] = new short[256];
            }
            pages[code >> 8][code & 255] = (short) (i + 1);
        }
        return new BitmapFont(regs, xo, yo, ad, pages, lineHeight);
    }

    private static int u16(byte[] d, int p) {
        return ((d[p] & 255) << 8) | (d[p + 1] & 255);
    }

    public void setScale(float scale) {
        this.scale = scale;
    }

    public float getScale() {
        return scale;
    }

    public float getLineHeight() {
        return (proportional ? lineH : cellH) * scale;
    }

    public boolean isProportional() {
        return proportional;
    }

    /** Width of this many average characters (the width of a space for proportional fonts). Prefer the String overloads. */
    public float getTextWidth(int chars) {
        return chars * cellW * scale;
    }

    /** True if the font has a glyph for this character. */
    public boolean hasGlyph(char c) {
        if (proportional) {
            short[] pg = pages[c >> 8];
            return pg != null && pg[c & 255] != 0;
        }
        int i = c - firstChar;
        return i >= 0 && i < glyphs.length;
    }

    /** Width in pixels of one character at the current scale. */
    public float getCharWidth(char c) {
        return advance(c) * scale;
    }

    /** Width of the widest line of the text. */
    public float getTextWidth(String text) {
        int n = load(text);
        return widest(scratch, n) * scale;
    }

    public float getTextWidth(TextBuffer text) {
        int n = load(text);
        return widest(scratch, n) * scale;
    }

    // ------------------------------------------------------------------ drawing

    public void draw(SpriteBatch batch, String text, float x, float y) {
        drawLines(batch, scratch, load(text), x, y);
    }

    public void draw(SpriteBatch batch, TextBuffer text, float x, float y) {
        drawLines(batch, scratch, load(text), x, y);
    }

    /** One line inside a box of width boxW, aligned. '\n' is not treated specially (use drawWrapped for that). */
    public void drawAligned(SpriteBatch batch, String text, float x, float y, float boxW, int align) {
        int n = load(text);
        float w = width(scratch, 0, n) * scale;
        drawRange(batch, scratch, 0, n, alignX(x, boxW, w, align), y);
    }

    public void drawAligned(SpriteBatch batch, TextBuffer text, float x, float y, float boxW, int align) {
        int n = load(text);
        float w = width(scratch, 0, n) * scale;
        drawRange(batch, scratch, 0, n, alignX(x, boxW, w, align), y);
    }

    /** One character aligned in a box (keyboard keys). */
    public void drawChar(SpriteBatch batch, char c, float x, float y, float boxW, int align) {
        scratch[0] = c;
        float w = width(scratch, 0, 1) * scale;
        drawRange(batch, scratch, 0, 1, alignX(x, boxW, w, align), y);
    }

    /** One line, cut with "..." if it is wider than maxWidth. */
    public void drawEllipsized(SpriteBatch batch, String text, float x, float y, float maxWidth) {
        ellipsize(batch, load(text), x, y, maxWidth);
    }

    public void drawEllipsized(SpriteBatch batch, TextBuffer text, float x, float y, float maxWidth) {
        ellipsize(batch, load(text), x, y, maxWidth);
    }

    /**
     * Word wrapped text in a column of width maxWidth (breaks at spaces and '\n'; a word longer than the column is
     * broken anywhere). @param maxLines 0 = all lines @return the number of lines drawn
     */
    public int drawWrapped(SpriteBatch batch, String text, float x, float y, float maxWidth, int align, int maxLines) {
        return wrapped(batch, load(text), x, y, maxWidth, align, maxLines);
    }

    public int drawWrapped(SpriteBatch batch, TextBuffer text, float x, float y, float maxWidth, int align, int maxLines) {
        return wrapped(batch, load(text), x, y, maxWidth, align, maxLines);
    }

    /** How many lines drawWrapped() would use. */
    public int countLines(String text, float maxWidth) {
        return count(load(text), maxWidth);
    }

    public int countLines(TextBuffer text, float maxWidth) {
        return count(load(text), maxWidth);
    }

    /** Width of the widest line when wrapped to maxWidth (<= maxWidth). */
    public float getWrappedWidth(TextBuffer text, float maxWidth) {
        int n = load(text);
        float best = 0f;
        int start = 0;
        float maxW = maxWidth / scale;
        while (start < n) {
            wrapLine(scratch, n, start, maxW);
            if (wrapWidth > best) {
                best = wrapWidth;
            }
            start = wrapNext;
        }
        return best * scale;
    }

    // ------------------------------------------------------------------ internals

    private int load(String s) {
        int n = s.length();
        if (n > scratch.length) {
            scratch = new char[Math.max(n, scratch.length * 2)];
        }
        s.getChars(0, n, scratch, 0);
        return n;
    }

    private int load(TextBuffer t) {
        int n = t.length();
        if (n > scratch.length) {
            scratch = new char[Math.max(n, scratch.length * 2)];
        }
        t.getChars(scratch);
        return n;
    }

    private static float alignX(float x, float boxW, float w, int align) {
        return align == ALIGN_CENTER ? x + (boxW - w) * 0.5f : (align == ALIGN_RIGHT ? x + boxW - w : x);
    }

    /** Unscaled advance of a character. */
    private float advance(char c) {
        if (proportional) {
            short[] pg = pages[c >> 8];
            int i = pg == null ? 0 : pg[c & 255];
            if (i == 0) {
                i = fallback + 1;
                if (i == 0) {
                    return cellW;
                }
            }
            return adv[i - 1];
        }
        return cellW;
    }

    /** Unscaled width of t[from, to). */
    private float width(char[] t, int from, int to) {
        if (!proportional) {
            return (to - from) * cellW;
        }
        float w = 0f;
        for (int i = from; i < to; i++) {
            w += advance(t[i]);
        }
        return w;
    }

    private float widest(char[] t, int n) {
        float best = 0f;
        int start = 0;
        for (int i = 0; i <= n; i++) {
            if (i == n || t[i] == '\n') {
                float w = width(t, start, i);
                if (w > best) {
                    best = w;
                }
                start = i + 1;
            }
        }
        return best;
    }

    private void drawLines(SpriteBatch batch, char[] t, int n, float x, float y) {
        int start = 0;
        float cy = y;
        float lh = getLineHeight();
        for (int i = 0; i <= n; i++) {
            if (i == n || t[i] == '\n') {
                drawRange(batch, t, start, i, x, cy);
                cy += lh;
                start = i + 1;
            }
        }
    }

    private void drawRange(SpriteBatch batch, char[] t, int from, int to, float x, float y) {
        float cx = x;
        float s = scale;
        for (int i = from; i < to; i++) {
            char c = t[i];
            if (proportional) {
                short[] pg = pages[c >> 8];
                int gi = pg == null ? 0 : pg[c & 255];
                if (gi == 0) {
                    gi = fallback + 1;
                    if (gi == 0) {
                        cx += cellW * s;
                        continue;
                    }
                }
                gi--;
                TextureRegion r = regs[gi];
                if (r.width > 0f && r.height > 0f) {
                    batch.draw(r, cx + xoff[gi] * s, y + yoff[gi] * s, r.width * s, r.height * s);
                }
                cx += adv[gi] * s;
            } else {
                int gi = c - firstChar;
                if (gi >= 0 && gi < glyphs.length && c != ' ') {
                    batch.draw(glyphs[gi], cx, y, cellW * s, cellH * s);
                }
                cx += cellW * s;
            }
        }
    }

    private void ellipsize(SpriteBatch batch, int n, float x, float y, float maxWidth) {
        char[] t = scratch;
        float max = maxWidth / scale;
        if (width(t, 0, n) <= max) {
            drawRange(batch, t, 0, n, x, y);
            return;
        }
        float dots = advance('.') * 3f;
        float w = 0f;
        int keep = 0;
        while (keep < n) {
            float a = advance(t[keep]);
            if (w + a + dots > max) {
                break;
            }
            w += a;
            keep++;
        }
        drawRange(batch, t, 0, keep, x, y);
        float cx = x + w * scale;
        float da = advance('.') * scale;
        for (int k = 0; k < 3; k++) {
            drawRange(batch, DOTS, 0, 1, cx, y);
            cx += da;
        }
    }

    private static final char[] DOTS = {'.'};

    /**
     * Finds the line that starts at 'start': sets wrapNext (where the next line starts; n when done) and wrapWidth
     * (unscaled width without trailing spaces) and returns the end (exclusive) of the text to draw.
     */
    private int wrapLine(char[] t, int n, int start, float maxW) {
        float w = 0f;
        int lastSpace = -1;
        float wAtSpace = 0f;
        int i = start;
        while (i < n) {
            char c = t[i];
            if (c == '\n') {
                wrapNext = i + 1;
                wrapWidth = trimmedWidth(t, start, i);
                return i;
            }
            float a = advance(c);
            if (c != ' ' && w + a > maxW && i > start) {
                int end;
                if (lastSpace > start) {
                    end = lastSpace;
                    int k = lastSpace;
                    while (k < n && t[k] == ' ') {
                        k++;
                    }
                    wrapNext = k;
                } else {
                    end = i;
                    wrapNext = i;
                }
                wrapWidth = trimmedWidth(t, start, end);
                return end;
            }
            if (c == ' ') {
                if (lastSpace < 0 || t[i - 1] != ' ') {
                    lastSpace = i;
                    wAtSpace = w;
                }
            }
            w += a;
            i++;
        }
        wrapNext = n;
        wrapWidth = trimmedWidth(t, start, n);
        return n;
    }

    private float trimmedWidth(char[] t, int from, int to) {
        int e = to;
        while (e > from && t[e - 1] == ' ') {
            e--;
        }
        return width(t, from, e);
    }

    private int wrapped(SpriteBatch batch, int n, float x, float y, float maxWidth, int align, int maxLines) {
        char[] t = scratch;
        float maxW = maxWidth / scale;
        float lh = getLineHeight();
        int lines = 0;
        int start = 0;
        float cy = y;
        while (start < n || (n == 0 && lines == 0)) {
            if (maxLines > 0 && lines >= maxLines) {
                break;
            }
            int end = wrapLine(t, n, start, maxW);
            int e = end;
            while (e > start && t[e - 1] == ' ') {
                e--;
            }
            drawRange(batch, t, start, e, alignX(x, maxWidth, wrapWidth * scale, align), cy);
            cy += lh;
            lines++;
            if (n == 0) {
                break;
            }
            start = wrapNext;
        }
        return lines;
    }

    private int count(int n, float maxWidth) {
        char[] t = scratch;
        float maxW = maxWidth / scale;
        int lines = 0;
        int start = 0;
        while (start < n) {
            wrapLine(t, n, start, maxW);
            lines++;
            start = wrapNext;
        }
        return lines == 0 ? 1 : lines;
    }
}
