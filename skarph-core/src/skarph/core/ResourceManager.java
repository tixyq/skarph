// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Loads textures and models by file name and caches them with reference
 * counting: asking for the same file twice returns the same GPU resource,
 * and the resource is freed when every user has released it.
 *
 * Loading allocates (call it while loading a level, never in update/render).
 * Lookups are plain loops over small fixed tables (no hash maps, no boxing).
 */
public final class ResourceManager {
    /** A loaded model: a renderer mesh handle plus its bounding radius. */
    public static final class Model {
        public final String name;
        /** Mesh handle for Node.mesh. */
        public final int mesh;
        /** Local bounding radius for Node.boundRadius. */
        public final float boundRadius;
        public final int diffuseTexture;
        public final int lightmapTexture;
        int refs;

        Model(String name, int mesh, float boundRadius, int diffuse, int lightmap) {
            this.name = name;
            this.mesh = mesh;
            this.boundRadius = boundRadius;
            this.diffuseTexture = diffuse;
            this.lightmapTexture = lightmap;
        }

        public boolean hasLightmap() {
            return lightmapTexture >= 0;
        }
    }

    private final Assets assets;
    private final Renderer renderer;

    private final String[] texNames;
    private final boolean[] texSmooth;
    private final boolean[] texRepeat;
    private final int[] texHandles;
    private final int[] texRefs;
    private int texCount;

    private final Model[] models;
    private int modelCount;

    private String lastError;

    public ResourceManager(Assets assets, Renderer renderer, int maxTextures, int maxModels) {
        this.assets = assets;
        this.renderer = renderer;
        texNames = new String[maxTextures];
        texSmooth = new boolean[maxTextures];
        texRepeat = new boolean[maxTextures];
        texHandles = new int[maxTextures];
        texRefs = new int[maxTextures];
        models = new Model[maxModels];
    }

    /** Description of the last failure, or null. */
    public String getLastError() {
        return lastError;
    }

    public int getTextureCount() {
        return texCount;
    }

    public int getModelCount() {
        return modelCount;
    }

    /** Sum of all reference counts (textures + models). */
    public int getTotalRefs() {
        int n = 0;
        for (int i = 0; i < texCount; i++) {
            n += texRefs[i];
        }
        for (int i = 0; i < modelCount; i++) {
            n += models[i].refs;
        }
        return n;
    }

    /** @return renderer texture handle (reference counted), or -1 on failure (see getLastError()). */
    public int getTexture(String name, boolean smooth, boolean repeat) {
        for (int i = 0; i < texCount; i++) {
            if (texNames[i].equals(name) && texSmooth[i] == smooth && texRepeat[i] == repeat) {
                texRefs[i]++;
                return texHandles[i];
            }
        }
        if (texCount == texNames.length) {
            lastError = "texture table is full (" + name + ")";
            return -1;
        }
        int handle = renderer.loadTexture(name, smooth, repeat);
        if (handle < 0) {
            lastError = "cannot load texture " + name;
            return -1;
        }
        int i = texCount++;
        texNames[i] = name;
        texSmooth[i] = smooth;
        texRepeat[i] = repeat;
        texHandles[i] = handle;
        texRefs[i] = 1;
        return handle;
    }

    public void releaseTexture(int handle) {
        for (int i = 0; i < texCount; i++) {
            if (texHandles[i] == handle) {
                if (--texRefs[i] <= 0) {
                    renderer.deleteTexture(handle);
                    // keep the table dense: move the last entry into this slot
                    int last = --texCount;
                    texNames[i] = texNames[last];
                    texSmooth[i] = texSmooth[last];
                    texRepeat[i] = texRepeat[last];
                    texHandles[i] = texHandles[last];
                    texRefs[i] = texRefs[last];
                    texNames[last] = null;
                }
                return;
            }
        }
    }

    /**
     * Loads a model file (and its textures) or returns the cached one.
     * @return the model (reference counted), or null on failure (see getLastError())
     */
    public Model getModel(String name) {
        for (int i = 0; i < modelCount; i++) {
            if (models[i].name.equals(name)) {
                models[i].refs++;
                return models[i];
            }
        }
        if (modelCount == models.length) {
            lastError = "model table is full (" + name + ")";
            return null;
        }
        byte[] bytes = assets.readBytes(name);
        if (bytes == null) {
            lastError = "cannot read " + name;
            return null;
        }
        ModelData data;
        try {
            data = ModelFormat.parse(bytes);
        } catch (IllegalArgumentException e) {
            lastError = name + ": " + e.getMessage();
            return null;
        }
        int diffuse = -1;
        int lightmap = -1;
        if (data.diffuseTexture != null) {
            diffuse = getTexture(data.diffuseTexture, true, true);
            if (diffuse < 0) {
                return null;
            }
        }
        if (data.lightmapTexture != null) {
            lightmap = getTexture(data.lightmapTexture, true, false);
            if (lightmap < 0) {
                if (diffuse >= 0) {
                    releaseTexture(diffuse);
                }
                return null;
            }
        }
        int mesh = renderer.createMesh(data.mesh);
        if (mesh < 0) {
            lastError = "mesh table is full (" + name + ")";
            if (diffuse >= 0) {
                releaseTexture(diffuse);
            }
            if (lightmap >= 0) {
                releaseTexture(lightmap);
            }
            return null;
        }
        renderer.setMeshTextures(mesh, diffuse, lightmap);
        Model m = new Model(name, mesh, data.boundRadius, diffuse, lightmap);
        m.refs = 1;
        models[modelCount++] = m;
        return m;
    }

    public void releaseModel(Model m) {
        for (int i = 0; i < modelCount; i++) {
            if (models[i] == m) {
                if (--m.refs <= 0) {
                    renderer.deleteMesh(m.mesh);
                    if (m.diffuseTexture >= 0) {
                        releaseTexture(m.diffuseTexture);
                    }
                    if (m.lightmapTexture >= 0) {
                        releaseTexture(m.lightmapTexture);
                    }
                    modelCount--;
                    models[i] = models[modelCount];
                    models[modelCount] = null;
                }
                return;
            }
        }
    }
}
