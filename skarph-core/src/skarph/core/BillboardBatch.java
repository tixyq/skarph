// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Collects 3D billboards and sends them to the renderer in as few draw calls as possible (one per texture or
 * blend mode change, or per Renderer.MAX_BILLBOARDS). Zero allocation.
 *
 * Usage per frame, after the meshes: setCamera(...) (the 3D camera); begin(); setColor(...); setAdditive(...);
 * draw(...); end(). Billboards are drawn in call order: for alpha blending add them back to front, additive
 * billboards need no order. Draw sprites and UI afterwards (SpriteBatch.begin() replaces the camera).
 */
public final class BillboardBatch {
    private final Renderer renderer;
    private final int maxBillboards;
    private final float[] data;

    private int count;
    private int pos;
    private int texture = -1;
    private boolean drawing;
    private boolean additive;
    private int drawCalls;

    private float cr = 1f;
    private float cg = 1f;
    private float cb = 1f;
    private float ca = 1f;

    public BillboardBatch(Renderer renderer, int maxBillboards) {
        if (maxBillboards < 1 || maxBillboards > Renderer.MAX_BILLBOARDS) {
            throw new IllegalArgumentException("maxBillboards must be 1.." + Renderer.MAX_BILLBOARDS);
        }
        this.renderer = renderer;
        this.maxBillboards = maxBillboards;
        data = new float[maxBillboards * Renderer.BILLBOARD_FLOATS];
    }

    public void begin() {
        if (drawing) {
            throw new IllegalStateException("begin() called twice");
        }
        drawing = true;
        drawCalls = 0;
        count = 0;
        pos = 0;
        texture = -1;
    }

    public void end() {
        if (!drawing) {
            throw new IllegalStateException("end() without begin()");
        }
        flush();
        drawing = false;
    }

    /** Tint and alpha for the following billboards (1,1,1,1 = unchanged). */
    public void setColor(float r, float g, float b, float a) {
        cr = r;
        cg = g;
        cb = b;
        ca = a;
    }

    /** true = add the colors to the screen (fire, sparks), false = alpha blending (smoke). Default false. */
    public void setAdditive(boolean additive) {
        if (additive != this.additive) {
            flush();
            this.additive = additive;
        }
    }

    /** Number of renderer draw calls issued since the last begin(). */
    public int getDrawCalls() {
        return drawCalls;
    }

    /** A billboard of size w x h (world units) centered at (x, y, z), showing the whole region. */
    public void draw(TextureRegion reg, float x, float y, float z, float w, float h) {
        if (!drawing) {
            throw new IllegalStateException("draw() outside begin()/end()");
        }
        if (reg.texture != texture || count == maxBillboards) {
            flush();
            texture = reg.texture;
        }
        float[] d = data;
        int p = pos;
        d[p] = x;
        d[p + 1] = y;
        d[p + 2] = z;
        d[p + 3] = w;
        d[p + 4] = h;
        d[p + 5] = reg.u0;
        d[p + 6] = reg.v0;
        d[p + 7] = reg.u1;
        d[p + 8] = reg.v1;
        d[p + 9] = cr;
        d[p + 10] = cg;
        d[p + 11] = cb;
        d[p + 12] = ca;
        pos = p + Renderer.BILLBOARD_FLOATS;
        count++;
    }

    /**
     * A flat quad lying on the ground (y = height of the plane): w along x, depth along z, the top of the region towards
     * -z. For fake shadow blobs and decals: alpha blend, color black, region with a soft round alpha. Needs a depth
     * offset from the ground (about 0.05..0.1 above it) because the depth test is on.
     */
    public void drawFlat(TextureRegion reg, float x, float y, float z, float w, float depth) {
        draw(reg, x, y, z, w, -depth);
    }

    private void flush() {
        if (count == 0) {
            return;
        }
        renderer.drawBillboards(texture, data, count, additive);
        drawCalls++;
        count = 0;
        pos = 0;
    }
}
