// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/** A model loaded from a model file: geometry plus the names of its textures. */
public final class ModelData {
    public final MeshData mesh;
    /** Diffuse texture file name, or null. */
    public final String diffuseTexture;
    /** Lightmap texture file name, or null. */
    public final String lightmapTexture;
    /** Radius of the sphere around the model origin that contains all vertices. */
    public final float boundRadius;

    public ModelData(MeshData mesh, String diffuseTexture, String lightmapTexture) {
        this.mesh = mesh;
        this.diffuseTexture = diffuseTexture;
        this.lightmapTexture = lightmapTexture;
        float max2 = 0f;
        float[] p = mesh.positions;
        for (int i = 0; i < p.length; i += 3) {
            float d2 = p[i] * p[i] + p[i + 1] * p[i + 1] + p[i + 2] * p[i + 2];
            if (d2 > max2) {
                max2 = d2;
            }
        }
        boundRadius = (float) Math.sqrt(max2);
    }
}
