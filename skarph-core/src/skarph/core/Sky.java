// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * A cheap sky: a low poly dome (sphere) around the camera, colored per vertex with a gradient
 * zenith (up) - horizon (eye level) - nadir (down). Draw it first, every frame, with one call:
 * <pre>
 *   Sky sky = new Sky(renderer, 60f, 12, 6, 0.25f, 0.45f, 0.85f, 0.75f, 0.85f, 0.95f, 0.45f, 0.5f, 0.55f);
 *   // render():
 *   r.clear(...); r.setCamera(proj, view);
 *   sky.draw(r);                 // before everything else
 * </pre>
 * The radius must be larger than the near plane and smaller than the far plane (for example the middle
 * of both), otherwise the dome is clipped. Use the horizon color as the fog color so that fogged
 * terrain melts into the sky. The dome is 120 triangles with 12 segments and 6 rings.
 */
public final class Sky {
    private final int mesh;

    /**
     * Builds the dome mesh in the renderer (allocates: call it during init).
     * @param segments around the vertical axis (3 or more)
     * @param rings from top to bottom (2 or more, rounded up to an even number so that one ring is the horizon)
     */
    public Sky(Renderer r, float radius, int segments, int rings,
               float zr, float zg, float zb, float hr, float hg, float hb, float nr, float ng, float nb) {
        mesh = r.createMesh(dome(radius, segments, rings, zr, zg, zb, hr, hg, hb, nr, ng, nb));
    }

    /** The mesh handle in the renderer, or -1 if the mesh table was full. */
    public int getMesh() {
        return mesh;
    }

    /** Draws the sky (call first in the frame). No allocations. */
    public void draw(Renderer r) {
        if (mesh >= 0) {
            r.drawSky(mesh);
        }
    }

    /** Frees the mesh. The Sky must not be drawn afterwards. */
    public void release(Renderer r) {
        if (mesh >= 0) {
            r.deleteMesh(mesh);
        }
    }

    /**
     * Dome mesh without normals (never lit): one vertex at each pole and a ring of {@code segments} vertices per
     * latitude in between. Colors go linearly from the horizon color to the zenith color (upper half) and to the
     * nadir color (lower half). Allocates; call it during init.
     */
    public static MeshData dome(float radius, int segments, int rings,
                                float zr, float zg, float zb, float hr, float hg, float hb,
                                float nr, float ng, float nb) {
        if (segments < 3) {
            segments = 3;
        }
        if (rings < 2) {
            rings = 2;
        }
        if ((rings & 1) != 0) {
            rings++;
        }
        int ringVerts = (rings - 1) * segments;
        int vc = ringVerts + 2;
        float[] pos = new float[vc * 3];
        float[] col = new float[vc * 4];
        int top = 0;
        int bottom = vc - 1;
        setVertex(pos, col, top, 0f, radius, 0f, zr, zg, zb);
        for (int i = 1; i < rings; i++) {
            float lat = (float) Math.PI * 0.5f - (float) Math.PI * i / rings; // +90 .. -90 degrees
            float y = (float) Math.sin(lat) * radius;
            float rr = (float) Math.cos(lat) * radius;
            float t = (float) (i * 2) / rings - 1f;                           // -1 at the top .. +1 at the bottom
            float cr, cg, cb;
            if (t <= 0f) {                                                     // upper half: zenith -> horizon
                float k = -t;                                                  // 1 at the top, 0 at the horizon
                cr = hr + (zr - hr) * k;
                cg = hg + (zg - hg) * k;
                cb = hb + (zb - hb) * k;
            } else {                                                           // lower half: horizon -> nadir
                cr = hr + (nr - hr) * t;
                cg = hg + (ng - hg) * t;
                cb = hb + (nb - hb) * t;
            }
            for (int s = 0; s < segments; s++) {
                float a = (float) (2.0 * Math.PI * s / segments);
                setVertex(pos, col, 1 + (i - 1) * segments + s,
                        (float) Math.cos(a) * rr, y, (float) Math.sin(a) * rr, cr, cg, cb);
            }
        }
        setVertex(pos, col, bottom, 0f, -radius, 0f, nr, ng, nb);

        short[] idx = new short[segments * (rings - 1) * 6];
        int n = 0;
        for (int s = 0; s < segments; s++) {                                  // top fan
            int s1 = (s + 1) % segments;
            idx[n++] = (short) top;
            idx[n++] = (short) (1 + s1);
            idx[n++] = (short) (1 + s);
        }
        for (int i = 1; i < rings - 1; i++) {                                 // bands between two rings
            int a0 = 1 + (i - 1) * segments;
            int b0 = 1 + i * segments;
            for (int s = 0; s < segments; s++) {
                int s1 = (s + 1) % segments;
                idx[n++] = (short) (a0 + s);
                idx[n++] = (short) (a0 + s1);
                idx[n++] = (short) (b0 + s);
                idx[n++] = (short) (a0 + s1);
                idx[n++] = (short) (b0 + s1);
                idx[n++] = (short) (b0 + s);
            }
        }
        int last = 1 + (rings - 2) * segments;
        for (int s = 0; s < segments; s++) {                                  // bottom fan
            int s1 = (s + 1) % segments;
            idx[n++] = (short) bottom;
            idx[n++] = (short) (last + s);
            idx[n++] = (short) (last + s1);
        }
        return new MeshData(pos, col, idx);
    }

    private static void setVertex(float[] pos, float[] col, int v, float x, float y, float z,
                                  float r, float g, float b) {
        pos[v * 3] = x;
        pos[v * 3 + 1] = y;
        pos[v * 3 + 2] = z;
        col[v * 4] = r;
        col[v * 4 + 1] = g;
        col[v * 4 + 2] = b;
        col[v * 4 + 3] = 1f;
    }
}
