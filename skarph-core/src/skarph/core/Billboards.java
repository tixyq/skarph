// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Turns billboard records (see Renderer.drawBillboards) into camera facing quads. Shared by both
 * renderer backends, so they look the same; pure Java, no allocations.
 */
public final class Billboards {
    /** Floats per vertex of the result: x, y, z, u, v, r, g, b, a. */
    public static final int VERTEX_FLOATS = 9;

    private Billboards() {
    }

    /**
     * @param src   {@code count} billboards, Renderer.BILLBOARD_FLOATS floats each; a NEGATIVE height (field 4) makes
     *              a flat quad lying horizontally at y: width along x, depth |height| along z
     * @param view  the camera view matrix (column-major, 16 floats): its rows are the camera axes
     * @param out   receives 4 vertices per billboard (top-left, top-right, bottom-right, bottom-left),
     *              VERTEX_FLOATS floats each; needs count * 4 * VERTEX_FLOATS floats
     */
    public static void expand(float[] src, int count, float[] view, float[] out) {
        float rx = view[0], ry = view[4], rz = view[8];   // camera right in world space
        float ux = view[1], uy = view[5], uz = view[9];   // camera up in world space
        int s = 0;
        int o = 0;
        for (int i = 0; i < count; i++) {
            float x = src[s], y = src[s + 1], z = src[s + 2];
            float hw = src[s + 3] * 0.5f, hh = src[s + 4] * 0.5f;
            float u0 = src[s + 5], v0 = src[s + 6], u1 = src[s + 7], v1 = src[s + 8];
            float r = src[s + 9], g = src[s + 10], b = src[s + 11], a = src[s + 12];
            float ax, ay, az, bx, by, bz;
            if (hh < 0f) {                                    // negative height = flat on the ground (decal, fake shadow)
                hh = -hh;
                ax = hw;  ay = 0f; az = 0f;                   // half width along world x
                bx = 0f;  by = 0f; bz = -hh;                  // half depth along world -z (the top of the region)
            } else {
                ax = rx * hw; ay = ry * hw; az = rz * hw;     // half width along the camera right
                bx = ux * hh; by = uy * hh; bz = uz * hh;     // half height along the camera up
            }
            o = vertex(out, o, x - ax + bx, y - ay + by, z - az + bz, u0, v0, r, g, b, a);
            o = vertex(out, o, x + ax + bx, y + ay + by, z + az + bz, u1, v0, r, g, b, a);
            o = vertex(out, o, x + ax - bx, y + ay - by, z + az - bz, u1, v1, r, g, b, a);
            o = vertex(out, o, x - ax - bx, y - ay - by, z - az - bz, u0, v1, r, g, b, a);
            s += Renderer.BILLBOARD_FLOATS;
        }
    }

    private static int vertex(float[] out, int o, float x, float y, float z, float u, float v,
                              float r, float g, float b, float a) {
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = z;
        out[o + 3] = u;
        out[o + 4] = v;
        out[o + 5] = r;
        out[o + 6] = g;
        out[o + 7] = b;
        out[o + 8] = a;
        return o + VERTEX_FLOATS;
    }
}
