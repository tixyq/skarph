// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Backend independent rendering interface (implemented by the platform layer).
 * Meshes are referred to by int handles, so screens keep working when the GL
 * context is lost and recreated.
 */
public interface Renderer {
    /** Maximum number of quads accepted by one drawQuads() call. */
    int MAX_QUADS = 1024;

    /** Floats per vertex in drawQuads(): x, y, u, v, r, g, b, a. */
    int QUAD_FLOATS_PER_VERTEX = 8;

    /** Maximum number of billboards accepted by one drawBillboards() call. */
    int MAX_BILLBOARDS = 512;

    /** Floats per billboard in drawBillboards(): x, y, z, width, height, u0, v0, u1, v1, r, g, b, a. */
    int BILLBOARD_FLOATS = 13;

    /** Human readable name of the backend, for example "OpenGL ES 2.0 (shaders)". */
    String getBackendName();

    int getWidth();

    int getHeight();

    /** Registers a mesh. @return handle (>= 0) or -1 if the mesh table is full. */
    int createMesh(MeshData data);

    /**
     * Loads a PNG from the app assets into a texture. Width and height must be
     * powers of two (required by OpenGL ES 1.x). The handle stays valid when the
     * GL context is lost: the renderer reloads the texture itself.
     * @param smooth true = linear filtering, false = nearest (crisp pixels)
     * @return handle (>= 0) or -1 on failure
     */
    int loadTexture(String assetPath, boolean smooth);

    /**
     * Same, with a wrap mode: repeat = true tiles the texture (for UVs outside 0..1),
     * false clamps at the edges.
     */
    int loadTexture(String assetPath, boolean smooth, boolean repeat);

    /** Frees the texture. The handle must not be used afterwards (it may be reused). */
    void deleteTexture(int texture);

    /** Frees the mesh. The handle must not be used afterwards (it may be reused). */
    void deleteMesh(int mesh);

    /**
     * Binds textures to a mesh that has texture coordinates: diffuse uses uv0, the
     * lightmap uses uv1 and is multiplied with the diffuse color. Use -1 for none.
     * Meshes that have a lightmap are never lit dynamically (the light is baked).
     */
    void setMeshTextures(int mesh, int diffuseTexture, int lightmapTexture);

    /** True if the device can draw lightmaps (needs two texture units). */
    boolean supportsLightmaps();

    /** Turns lightmaps on/off globally (off = only the diffuse texture, handy for comparison). */
    void setLightmapsEnabled(boolean enabled);

    int getTextureWidth(int texture);

    int getTextureHeight(int texture);

    /** Clears color and depth buffers. Also resets the draw call and triangle counters. */
    void clear(float r, float g, float b);

    /** Number of draw calls (meshes and quad batches) issued since the last clear(). */
    int getDrawCalls();

    /** Number of triangles sent to the GPU since the last clear(). */
    int getTriangles();

    /** True if meshes are stored in GPU buffer objects (VBO, OpenGL ES 1.1+). Informational. */
    boolean usesBufferObjects();

    /** Turns lighting on/off for meshes drawn afterwards (only meshes with normals are lit). */
    void setLighting(boolean enabled);

    /** Ambient light color (0..1). */
    void setAmbientLight(float r, float g, float b);

    /**
     * The single directional light. (dx, dy, dz) is the direction the light
     * travels, in world space; r, g, b is its color. Call after setCamera().
     */
    void setDirectionalLight(float dx, float dy, float dz, float r, float g, float b);

    /**
     * Linear distance fog for the meshes drawn afterwards (sprites and quads are never fogged).
     * A pixel at depth d (distance along the view direction, in world units) gets
     * f = clamp((end - d) / (end - start), 0, 1) and the color f * color + (1 - f) * fogColor:
     * nothing changes before {@code start}, only the fog color remains at {@code end} and beyond.
     * Needs a perspective projection. Call it again whenever the values change; the setting stays
     * until the next call. Requires {@code end > start}, otherwise fog is switched off.
     * Tip: use the clear color as the fog color so far objects melt into the background.
     * Baked lightmaps and dynamic light are applied first, the fog last.
     */
    void setFog(boolean enabled, float start, float end, float r, float g, float b);

    /** Sets projection and view matrices for the following draw calls. */
    void setCamera(Mat4 projection, Mat4 view);

    /**
     * Draws a sky mesh around the camera: the view translation is dropped (the sky never gets closer), and depth
     * test, lighting and fog are off, so draw it FIRST, right after clear() and setCamera(), and everything else
     * covers it. The mesh is drawn once with its vertex colors (and its diffuse texture if it has one).
     * The sky must lie between the near and far plane (see {@link Sky}). Resets nothing the game has set:
     * lighting, fog and the camera apply again to the next drawMesh().
     */
    void drawSky(int mesh);

    /**
     * Draws camera facing 3D sprites (smoke, sparks, flares) of one texture in a single draw call.
     * data: per billboard {x, y, z, width, height, u0, v0, u1, v1, r, g, b, a}: the center in world space,
     * the size in world units, the texture rectangle (v = 0 is the top of the image) and the tint.
     * Every billboard is a quad that faces the camera upright (the camera's right and up vectors), so call
     * setCamera() first. The depth test is on but nothing is written to the depth buffer, so billboards hide behind
     * walls but never hide each other; culling and lighting are off, and fog applies.
     * additive = false: normal alpha blending (smoke); sort back to front for a correct look.
     * additive = true: colors are added to the screen (fire, sparks, flares), no sorting needed; fogged
     * additive billboards fade to black, never to the fog color.
     * More than MAX_BILLBOARDS are cut off. Normally called through BillboardBatch.
     */
    void drawBillboards(int texture, float[] data, int count, boolean additive);

    /** Draws a mesh with the given model matrix. */
    void drawMesh(int mesh, Mat4 model);

    /**
     * Draws textured, tinted, alpha-blended 2D quads (depth test off).
     * vertices: per quad 4 vertices of {x, y, u, v, r, g, b, a}, corners in order
     * top-left, top-right, bottom-right, bottom-left. Normally called by SpriteBatch.
     * Call setCamera() first (SpriteBatch.begin() does it).
     */
    void drawQuads(int texture, float[] vertices, int quadCount);
}
