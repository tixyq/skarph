// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Collects 2D sprites and sends them to the renderer in as few draw calls as
 * possible (one per texture change). Sprites are drawn in call order
 * (painter's algorithm). Zero allocation.
 *
 * Usage per frame: begin(projection); setColor(...); draw(...); end();
 * Coordinates follow the projection you pass (for example an ortho matrix
 * with y pointing down). Negative width/height flips the sprite.
 * begin() replaces the renderer camera: call setCamera() again for 3D after end().
 */
public final class SpriteBatch {
    private static final int FLOATS_PER_SPRITE = 4 * Renderer.QUAD_FLOATS_PER_VERTEX;

    private final Renderer renderer;
    private final int maxSprites;
    private final float[] data;
    private final Mat4 identity = new Mat4();

    private int count;
    private int pos;
    private int texture = -1;
    private boolean drawing;
    private int drawCalls;

    private float cr = 1f;
    private float cg = 1f;
    private float cb = 1f;
    private float ca = 1f;

    public SpriteBatch(Renderer renderer, int maxSprites) {
        if (maxSprites < 1 || maxSprites > Renderer.MAX_QUADS) {
            throw new IllegalArgumentException("maxSprites must be 1.." + Renderer.MAX_QUADS);
        }
        this.renderer = renderer;
        this.maxSprites = maxSprites;
        data = new float[maxSprites * FLOATS_PER_SPRITE];
    }

    public void begin(Mat4 projection) {
        if (drawing) {
            throw new IllegalStateException("begin() called twice");
        }
        drawing = true;
        drawCalls = 0;
        count = 0;
        pos = 0;
        texture = -1;
        renderer.setCamera(projection, identity);
    }

    public void end() {
        if (!drawing) {
            throw new IllegalStateException("end() without begin()");
        }
        flush();
        drawing = false;
    }

    /** Tint and alpha for the following sprites (1,1,1,1 = unchanged). */
    public void setColor(float r, float g, float b, float a) {
        cr = r;
        cg = g;
        cb = b;
        ca = a;
    }

    /** Number of renderer draw calls issued since the last begin(). */
    public int getDrawCalls() {
        return drawCalls;
    }

    public void draw(TextureRegion reg, float x, float y) {
        draw(reg, x, y, reg.width, reg.height);
    }

    public void draw(TextureRegion reg, float x, float y, float w, float h) {
        prepare(reg.texture);
        float x2 = x + w;
        float y2 = y + h;
        vertex(x, y, reg.u0, reg.v0);
        vertex(x2, y, reg.u1, reg.v0);
        vertex(x2, y2, reg.u1, reg.v1);
        vertex(x, y2, reg.u0, reg.v1);
        count++;
    }

    /** Draws a sprite of size w x h centered at (cx, cy), rotated by angleDeg (clockwise on a y-down screen). */
    public void drawRotated(TextureRegion reg, float cx, float cy, float w, float h, float angleDeg) {
        prepare(reg.texture);
        double rad = angleDeg * (Math.PI / 180.0);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float hw = w * 0.5f;
        float hh = h * 0.5f;
        vertex(cx + (-hw * c + hh * s), cy + (-hw * s - hh * c), reg.u0, reg.v0);
        vertex(cx + (hw * c + hh * s), cy + (hw * s - hh * c), reg.u1, reg.v0);
        vertex(cx + (hw * c - hh * s), cy + (hw * s + hh * c), reg.u1, reg.v1);
        vertex(cx + (-hw * c - hh * s), cy + (-hw * s + hh * c), reg.u0, reg.v1);
        count++;
    }

    private void prepare(int tex) {
        if (!drawing) {
            throw new IllegalStateException("draw() outside begin()/end()");
        }
        if (tex != texture || count == maxSprites) {
            flush();
            texture = tex;
        }
    }

    private void vertex(float x, float y, float u, float v) {
        float[] d = data;
        int p = pos;
        d[p] = x;
        d[p + 1] = y;
        d[p + 2] = u;
        d[p + 3] = v;
        d[p + 4] = cr;
        d[p + 5] = cg;
        d[p + 6] = cb;
        d[p + 7] = ca;
        pos = p + Renderer.QUAD_FLOATS_PER_VERTEX;
    }

    private void flush() {
        if (count == 0) {
            return;
        }
        renderer.drawQuads(texture, data, count);
        drawCalls++;
        count = 0;
        pos = 0;
    }
}
