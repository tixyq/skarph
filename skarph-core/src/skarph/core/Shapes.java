// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** Builders for simple meshes (with normals). They allocate: call them only during init. */
public final class Shapes {
    private Shapes() {
    }

    private static final float[][] FACE_COLORS = {
        {0.95f, 0.25f, 0.25f, 1f}, // +z red
        {0.25f, 0.85f, 0.35f, 1f}, // -z green
        {0.30f, 0.45f, 0.95f, 1f}, // +x blue
        {0.95f, 0.85f, 0.25f, 1f}, // -x yellow
        {0.25f, 0.85f, 0.90f, 1f}, // +y cyan
        {0.90f, 0.35f, 0.85f, 1f}  // -y magenta
    };

    private static final float[][] FACE_NORMALS = {
        {0f, 0f, 1f}, {0f, 0f, -1f}, {1f, 0f, 0f}, {-1f, 0f, 0f}, {0f, 1f, 0f}, {0f, -1f, 0f}
    };

    /** Cube centered at the origin with a different color per face. Bounding radius: size * 0.866. */
    public static MeshData cube(float size) {
        return buildCube(size, null);
    }

    /** Cube centered at the origin, one color. */
    public static MeshData cube(float size, float r, float g, float b) {
        return buildCube(size, new float[] {r, g, b, 1f});
    }

    private static MeshData buildCube(float size, float[] uniform) {
        float h = size * 0.5f;
        float[] p = {
            // +z
            -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
            // -z
             h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
            // +x
             h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
            // -x
            -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
            // +y
            -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
            // -y
            -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h
        };
        float[] n = new float[24 * 3];
        float[] c = new float[24 * 4];
        short[] idx = new short[36];
        for (int f = 0; f < 6; f++) {
            float[] col = uniform != null ? uniform : FACE_COLORS[f];
            for (int v = 0; v < 4; v++) {
                int o = (f * 4 + v) * 4;
                c[o] = col[0];
                c[o + 1] = col[1];
                c[o + 2] = col[2];
                c[o + 3] = col[3];
                int on = (f * 4 + v) * 3;
                n[on] = FACE_NORMALS[f][0];
                n[on + 1] = FACE_NORMALS[f][1];
                n[on + 2] = FACE_NORMALS[f][2];
            }
            int b = f * 4;
            int i = f * 6;
            idx[i] = (short) b;
            idx[i + 1] = (short) (b + 1);
            idx[i + 2] = (short) (b + 2);
            idx[i + 3] = (short) b;
            idx[i + 4] = (short) (b + 2);
            idx[i + 5] = (short) (b + 3);
        }
        return new MeshData(p, n, c, idx);
    }

    /**
     * UV sphere centered at the origin, one color, smooth normals.
     * Bounding radius = radius. segments >= 3, rings >= 2.
     */
    public static MeshData sphere(float radius, int segments, int rings, float r, float g, float b) {
        int vcount = (segments + 1) * (rings + 1);
        float[] p = new float[vcount * 3];
        float[] n = new float[vcount * 3];
        float[] c = new float[vcount * 4];
        int vi = 0;
        for (int i = 0; i <= rings; i++) {
            float theta = 180f * i / rings;
            float st = FastMath.sin(theta);
            float ct = FastMath.cos(theta);
            for (int j = 0; j <= segments; j++) {
                float phi = 360f * j / segments;
                float x = st * FastMath.cos(phi);
                float y = ct;
                float z = st * FastMath.sin(phi);
                p[vi * 3] = x * radius;
                p[vi * 3 + 1] = y * radius;
                p[vi * 3 + 2] = z * radius;
                n[vi * 3] = x;
                n[vi * 3 + 1] = y;
                n[vi * 3 + 2] = z;
                c[vi * 4] = r;
                c[vi * 4 + 1] = g;
                c[vi * 4 + 2] = b;
                c[vi * 4 + 3] = 1f;
                vi++;
            }
        }
        short[] tmp = new short[segments * rings * 6];
        int ti = 0;
        for (int i = 0; i < rings; i++) {
            for (int j = 0; j < segments; j++) {
                int a = i * (segments + 1) + j;
                int bb = a + segments + 1;
                if (i != 0) { // skip the degenerate triangle at the top pole
                    tmp[ti++] = (short) a;
                    tmp[ti++] = (short) (a + 1);
                    tmp[ti++] = (short) bb;
                }
                if (i != rings - 1) { // and at the bottom pole
                    tmp[ti++] = (short) (a + 1);
                    tmp[ti++] = (short) (bb + 1);
                    tmp[ti++] = (short) bb;
                }
            }
        }
        short[] idx = new short[ti];
        System.arraycopy(tmp, 0, idx, 0, ti);
        return new MeshData(p, n, c, idx);
    }
}
