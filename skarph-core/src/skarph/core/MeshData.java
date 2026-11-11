// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Backend independent mesh data: positions (xyz), optional normals (xyz, unit
 * length, needed for lighting), colors (rgba, 0..1) and triangle indices.
 * The renderer copies it into its own buffers.
 */
public final class MeshData {
    public final float[] positions;
    /** May be null: such a mesh is never lit. */
    public final float[] normals;
    public final float[] colors;
    /** Diffuse texture coordinates (u, v per vertex), or null. v = 0 is the TOP of the image. */
    public final float[] uv0;
    /** Lightmap texture coordinates (u, v per vertex), or null. */
    public final float[] uv1;
    public final short[] indices;

    public MeshData(float[] positions, float[] colors, short[] indices) {
        this(positions, null, colors, indices);
    }

    public MeshData(float[] positions, float[] normals, float[] colors, short[] indices) {
        this(positions, normals, colors, null, null, indices);
    }

    public MeshData(float[] positions, float[] normals, float[] colors,
                    float[] uv0, float[] uv1, short[] indices) {
        if (positions.length % 3 != 0 || colors.length % 4 != 0
                || positions.length / 3 != colors.length / 4) {
            throw new IllegalArgumentException("positions/colors size mismatch");
        }
        if (normals != null && normals.length != positions.length) {
            throw new IllegalArgumentException("normals size mismatch");
        }
        if ((uv0 != null && uv0.length / 2 != positions.length / 3)
                || (uv1 != null && uv1.length / 2 != positions.length / 3)) {
            throw new IllegalArgumentException("uv size mismatch");
        }
        this.positions = positions;
        this.normals = normals;
        this.uv0 = uv0;
        this.uv1 = uv1;
        this.colors = colors;
        this.indices = indices;
    }

    public int getVertexCount() {
        return positions.length / 3;
    }

    public int getIndexCount() {
        return indices.length;
    }
}
