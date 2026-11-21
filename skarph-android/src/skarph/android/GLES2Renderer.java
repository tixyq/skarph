// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import skarph.core.Billboards;
import skarph.core.Engine;
import skarph.core.Mat4;
import skarph.core.MeshData;
import skarph.core.Renderer;

/**
 * OpenGL ES 2.0 backend (programmable pipeline), the counterpart of GLES1Renderer.
 * Pure Java on top of the Gl2 interface, so its logic is unit tested with a fake GL.
 *
 * <ul>
 * <li>Every mesh lives in one interleaved VBO (position, [normal], color as 4 bytes, [uv0], [uv1]) and one
 *     index buffer; the data is kept so that everything can be rebuilt when the GL context is lost.</li>
 * <li>Shader programs are built on demand for each feature combination of a mesh (LIT, TEX0, LM, FOG, see
 *     GLES2Shaders) and cached. Lighting is computed per pixel.</li>
 * <li>Linear fog is one more shader feature (FOG): programs without fog never pay for it, and the fog
 *     uniforms are uploaded to a program only when setFog() changed them since its last draw.</li>
 * <li>Same behaviour as the ES 1.x backend: one directional light + ambient, baked lightmaps replace dynamic
 *     light, sprites are batched quads with alpha blending, textures must be power of two.</li>
 * <li>If a shader does not compile or link, the failure is reported once through the FailureListener and the
 *     renderer stops drawing (it never throws), so the app can switch to the ES 1.x backend.</li>
 * </ul>
 * Everything must be called on the GL thread.
 */
public final class GLES2Renderer implements Renderer {
    /** Told when the backend cannot work on this device. */
    public interface FailureListener {
        void onFailure(String reason);
    }

    private static final Mat4 IDENTITY = new Mat4();
    /** Default table sizes (the constructor with two more arguments sets others). */
    public static final int DEFAULT_MAX_MESHES = 256;
    public static final int DEFAULT_MAX_TEXTURES = 128;
    private static final int SPRITE = GLES2Shaders.VARIANTS;
    private static final int PROGRAMS = GLES2Shaders.VARIANTS + 1;
    private static final int QUAD_STRIDE = Renderer.QUAD_FLOATS_PER_VERTEX * 4; // bytes

    private static final class GlFailure extends RuntimeException {
        GlFailure(String message) {
            super(message);
        }
    }

    private final int maxMeshes;
    private final int maxTextures;
    private final Engine engine;
    private final Gl2 gl;
    private final GlImageLoader images;
    private final GlLog log;
    private final FailureListener listener;
    private boolean failed;
    private int width;
    private int height;

    // programs (index 0..7 = mesh feature bits, 8 = sprites)
    private final int[] program = new int[PROGRAMS];
    private final int[] locMvp = new int[PROGRAMS];
    private final int[] locNormalMat = new int[PROGRAMS];
    private final int[] locLightDir = new int[PROGRAMS];
    private final int[] locAmbient = new int[PROGRAMS];
    private final int[] locLightColor = new int[PROGRAMS];
    private final int[] locFog = new int[PROGRAMS];
    private final int[] locFogColor = new int[PROGRAMS];
    private final int[] locTint = new int[PROGRAMS];
    private final int[] locCutoff = new int[PROGRAMS];
    private final int[] fogStampSent = new int[PROGRAMS];   // fogStamp last uploaded to each program
    private int currentProgram;
    private final boolean[] attribOn = new boolean[GLES2Shaders.ATTRIBUTES];

    // meshes
    private final boolean[] meshUsed;
    private final ByteBuffer[] vertexData;
    private final ShortBuffer[] indexData;
    private final int[] vbo;
    private final int[] ibo;
    private final int[] indexCounts;
    private final int[] meshStride;
    private final int[] offNormal;   // -1 = absent
    private final int[] offColor;
    private final int[] offUv0;
    private final int[] offUv1;
    private final int[] meshDiffuse;
    private final int[] meshLightmap;
    private int meshCount;
    private int drawCalls;
    private int triangles;

    // textures
    private final String[] texturePaths; // null = free slot
    private final boolean[] textureSmooth;
    private final boolean[] textureRepeat;
    private final int[] textureGlIds;
    private final int[] textureWidths;
    private final int[] textureHeights;
    private int textureCount;

    // camera and light
    private final Mat4 viewProj = new Mat4();
    private final Mat4 projection = new Mat4();             // copies of the last setCamera(), for drawSky()
    private final Mat4 viewMatrix = new Mat4();
    private final Mat4 skyViewProj = new Mat4();
    private boolean skyPass;                                // true while drawSky() draws: no light, no fog
    private final Mat4 mvp = new Mat4();
    private final float[] normalMat = new float[9];
    private boolean lightingRequested;
    private boolean lightmapsEnabled = true;
    private final float[] lightDir = {0f, 1f, 0f};      // toward the light, world space, unit length
    private final float[] lightColor = {1f, 1f, 1f};
    private final float[] ambient = {0.3f, 0.3f, 0.3f};
    private boolean fogOn;
    private float fogScale;                                 // fog factor = w * fogScale + fogOffset
    private float fogOffset;
    private final float[] fogColor = {0f, 0f, 0f};
    private int fogStamp;                                   // bumped by setFog, uniforms are re-sent lazily

    // mesh state: blend mode, alpha test, culling, tint (see Renderer)
    private int blendMode;
    private float alphaCutoff;
    private boolean culling = true;
    private float tintR = 1f, tintG = 1f, tintB = 1f, tintA = 1f;
    private boolean tintOn;
    private boolean scissorOn;
    private boolean viewportSet;
    private final boolean[] meshDynamic;
    private final int[] meshMaxVertices;

    // 3D lines
    private static final int LINE_STRIDE = Renderer.LINE_FLOATS * 4; // bytes
    private final FloatBuffer lineBuffer;

    // 2D quads
    private final FloatBuffer quadVertices;
    private final ShortBuffer quadIndices;

    // 3D billboards: expanded on the CPU into camera facing quads (see Billboards), drawn like the sprites
    private static final int BB_STRIDE = Billboards.VERTEX_FLOATS * 4; // bytes
    private final float[] billboardVertices = new float[MAX_BILLBOARDS * 4 * Billboards.VERTEX_FLOATS];
    private final FloatBuffer billboardBuffer;

    public GLES2Renderer(Engine engine, Gl2 gl, GlImageLoader images, GlLog log, FailureListener listener) {
        this(engine, gl, images, log, listener, DEFAULT_MAX_MESHES, DEFAULT_MAX_TEXTURES);
    }

    /** Same, with the size of the mesh and texture tables (handles are 0 .. max - 1). */
    public GLES2Renderer(Engine engine, Gl2 gl, GlImageLoader images, GlLog log, FailureListener listener,
                         int maxMeshes, int maxTextures) {
        this.maxMeshes = maxMeshes;
        this.maxTextures = maxTextures;
        meshUsed = new boolean[maxMeshes];
        vertexData = new ByteBuffer[maxMeshes];
        indexData = new ShortBuffer[maxMeshes];
        vbo = new int[maxMeshes];
        ibo = new int[maxMeshes];
        indexCounts = new int[maxMeshes];
        meshStride = new int[maxMeshes];
        offNormal = new int[maxMeshes];
        offColor = new int[maxMeshes];
        offUv0 = new int[maxMeshes];
        offUv1 = new int[maxMeshes];
        meshDiffuse = new int[maxMeshes];
        meshLightmap = new int[maxMeshes];
        texturePaths = new String[maxTextures];
        textureSmooth = new boolean[maxTextures];
        textureRepeat = new boolean[maxTextures];
        textureGlIds = new int[maxTextures];
        textureWidths = new int[maxTextures];
        textureHeights = new int[maxTextures];
        this.engine = engine;
        this.gl = gl;
        this.images = images;
        this.log = log;
        this.listener = listener;
        for (int i = 0; i < maxMeshes; i++) {
            meshDiffuse[i] = -1;
            meshLightmap[i] = -1;
        }
        meshDynamic = new boolean[maxMeshes];
        meshMaxVertices = new int[maxMeshes];
        lineBuffer = ByteBuffer.allocateDirect(MAX_LINE_VERTICES * LINE_STRIDE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadVertices = ByteBuffer.allocateDirect(MAX_QUADS * 4 * QUAD_STRIDE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadIndices = ByteBuffer.allocateDirect(MAX_QUADS * 6 * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int q = 0; q < MAX_QUADS; q++) {
            short b = (short) (q * 4);
            quadIndices.put(b).put((short) (b + 1)).put((short) (b + 2));
            quadIndices.put(b).put((short) (b + 2)).put((short) (b + 3));
        }
        ((Buffer) quadIndices).position(0);
        billboardBuffer = ByteBuffer.allocateDirect(MAX_BILLBOARDS * 4 * BB_STRIDE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    // ---- life cycle (called by the surface adapter) -------------------------------------------

    /** The GL context was created (or recreated): set the state and rebuild every GL object. */
    public void onSurfaceCreated() {
        failed = false;
        for (int i = 0; i < PROGRAMS; i++) {
            program[i] = 0;
        }
        currentProgram = 0;
        invalidateBindings();
        for (int i = 0; i < attribOn.length; i++) {
            attribOn[i] = false;
        }
        gl.disable(Gl2.GL_DITHER);
        gl.enable(Gl2.GL_DEPTH_TEST);
        gl.enable(Gl2.GL_CULL_FACE);
        gl.frontFace(Gl2.GL_CCW);
        gl.cullFace(Gl2.GL_BACK);
        log.info("GL_RENDERER=" + gl.getString(Gl2.GL_RENDERER) + " GL_VERSION=" + gl.getString(Gl2.GL_VERSION)
                + " backend=OpenGL ES 2.0");
        // compile the programs that nearly every frame needs, so that a broken driver is found right away
        if (ensureProgram(SPRITE) == 0 || ensureProgram(GLES2Shaders.LIT) == 0) {
            return;
        }
        for (int i = 0; i < meshCount; i++) {
            if (meshUsed[i]) {
                createBuffers(i);
            }
        }
        for (int i = 0; i < textureCount; i++) {
            if (texturePaths[i] != null && !uploadTexture(i)) {
                log.error("could not reload texture " + texturePaths[i]);
            }
        }
    }

    public void onSurfaceChanged(int w, int h) {
        width = w;
        height = h;
        viewportSet = false;
        if (scissorOn) {
            scissorOn = false;
            gl.disable(Gl2.GL_SCISSOR_TEST);
        }
        gl.viewport(0, 0, w, h);
    }

    public void onDrawFrame() {
        if (failed) {
            gl.clearColor(0f, 0f, 0f, 1f);
            gl.clear(Gl2.GL_COLOR_BUFFER_BIT);
            return;
        }
        engine.frame(System.nanoTime(), this);
    }

    public boolean hasFailed() {
        return failed;
    }

    private void fail(String reason) {
        if (!failed) {
            failed = true;
            log.error("OpenGL ES 2.0 backend failed: " + reason);
            if (listener != null) {
                listener.onFailure(reason);
            }
        }
    }

    // ---- programs --------------------------------------------------------------------------------

    private int compile(int type, String source, String what) {
        int sh = gl.createShader(type);
        if (sh == 0) {
            throw new GlFailure("glCreateShader failed for " + what);
        }
        gl.shaderSource(sh, source);
        gl.compileShader(sh);
        if (gl.getShaderi(sh, Gl2.GL_COMPILE_STATUS) == Gl2.GL_FALSE) {
            String info = gl.getShaderInfoLog(sh);
            gl.deleteShader(sh);
            throw new GlFailure(what + " does not compile: " + info);
        }
        return sh;
    }

    /** Builds the program if needed. @return its id, or 0 if the backend has failed. */
    private int ensureProgram(int idx) {
        if (program[idx] != 0) {
            return program[idx];
        }
        if (failed) {
            return 0;
        }
        try {
            String vsSrc;
            String fsSrc;
            String name;
            if (idx == SPRITE) {
                vsSrc = GLES2Shaders.spriteVertex();
                fsSrc = GLES2Shaders.spriteFragment();
                name = "sprite shader";
            } else {
                vsSrc = GLES2Shaders.meshVertex(idx);
                fsSrc = GLES2Shaders.meshFragment(idx);
                name = "mesh shader " + idx;
            }
            int vs = compile(Gl2.GL_VERTEX_SHADER, vsSrc, name + " (vertex)");
            int fs;
            try {
                fs = compile(Gl2.GL_FRAGMENT_SHADER, fsSrc, name + " (fragment)");
            } catch (GlFailure e) {
                gl.deleteShader(vs);
                throw e;
            }
            int p = gl.createProgram();
            if (p == 0) {
                gl.deleteShader(vs);
                gl.deleteShader(fs);
                throw new GlFailure("glCreateProgram failed for " + name);
            }
            gl.attachShader(p, vs);
            gl.attachShader(p, fs);
            for (int a = 0; a < GLES2Shaders.ATTRIBUTES; a++) {
                gl.bindAttribLocation(p, a, GLES2Shaders.ATTRIBUTE_NAMES[a]);
            }
            gl.linkProgram(p);
            gl.deleteShader(vs); // the program keeps what it needs
            gl.deleteShader(fs);
            if (gl.getProgrami(p, Gl2.GL_LINK_STATUS) == Gl2.GL_FALSE) {
                String info = gl.getProgramInfoLog(p);
                gl.deleteProgram(p);
                throw new GlFailure(name + " does not link: " + info);
            }
            program[idx] = p;
            locMvp[idx] = gl.getUniformLocation(p, "uMvp");
            locNormalMat[idx] = gl.getUniformLocation(p, "uNormalMat");
            locLightDir[idx] = gl.getUniformLocation(p, "uLightDir");
            locAmbient[idx] = gl.getUniformLocation(p, "uAmbient");
            locLightColor[idx] = gl.getUniformLocation(p, "uLightColor");
            locFog[idx] = gl.getUniformLocation(p, "uFog");
            locFogColor[idx] = gl.getUniformLocation(p, "uFogColor");
            locTint[idx] = gl.getUniformLocation(p, "uTint");
            locCutoff[idx] = gl.getUniformLocation(p, "uCutoff");
            fogStampSent[idx] = fogStamp - 1;               // force the first upload
            gl.useProgram(p);
            currentProgram = p;
            gl.uniform1i(gl.getUniformLocation(p, "uTex0"), 0);   // sampler -> texture unit 0
            gl.uniform1i(gl.getUniformLocation(p, "uTex1"), 1);   // sampler -> texture unit 1
            return p;
        } catch (GlFailure e) {
            fail(e.getMessage());
            return 0;
        }
    }

    private boolean useProgram(int idx) {
        int p = ensureProgram(idx);
        if (p == 0) {
            return false;
        }
        if (p != currentProgram) {
            gl.useProgram(p);
            currentProgram = p;
        }
        return true;
    }

    private void attrib(int index, boolean on) {
        if (attribOn[index] != on) {
            attribOn[index] = on;
            if (on) {
                gl.enableVertexAttribArray(index);
            } else {
                gl.disableVertexAttribArray(index);
            }
        }
    }

    // ---- skarph.core.Renderer: basics ------------------------------------------------------------

    public String getBackendName() {
        return "OpenGL ES 2.0 (shaders)";
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean usesBufferObjects() {
        return true;
    }

    public boolean supportsLightmaps() {
        return true;
    }

    public void setLightmapsEnabled(boolean enabled) {
        lightmapsEnabled = enabled;
    }

    public void setLighting(boolean enabled) {
        lightingRequested = enabled;
    }

    public void setAmbientLight(float r, float g, float b) {
        ambient[0] = r;
        ambient[1] = g;
        ambient[2] = b;
    }

    public void setDirectionalLight(float dx, float dy, float dz, float r, float g, float b) {
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0f) {
            float inv = -1f / len; // the shader wants the direction TOWARD the light
            lightDir[0] = dx * inv;
            lightDir[1] = dy * inv;
            lightDir[2] = dz * inv;
        }
        lightColor[0] = r;
        lightColor[1] = g;
        lightColor[2] = b;
    }

    public void setFog(boolean enabled, float start, float end, float r, float g, float b) {
        if (enabled && end > start) {
            float inv = 1f / (end - start);
            fogScale = -inv;
            fogOffset = end * inv;
            fogColor[0] = r;
            fogColor[1] = g;
            fogColor[2] = b;
            fogOn = true;
        } else {
            fogOn = false;
        }
        fogStamp++;
    }

    public int getDrawCalls() {
        return drawCalls;
    }

    public int getTriangles() {
        return triangles;
    }

    public void clear(float r, float g, float b) {
        drawCalls = 0;
        triangles = 0;
        if (failed) {
            return;
        }
        gl.clearColor(r, g, b, 1f);
        gl.clear(Gl2.GL_COLOR_BUFFER_BIT | Gl2.GL_DEPTH_BUFFER_BIT);
    }

    public void setCamera(Mat4 projection, Mat4 view) {
        this.projection.set(projection);
        this.viewMatrix.set(view);
        viewProj.multiply(projection, view);
    }

    public void drawSky(int mesh) {
        if (failed || mesh < 0 || mesh >= meshCount || !meshUsed[mesh]) {
            return;
        }
        float[] v = viewMatrix.m;
        float tx = v[12], ty = v[13], tz = v[14];
        v[12] = 0f;                                         // the sky follows the camera: rotation only
        v[13] = 0f;
        v[14] = 0f;
        skyViewProj.multiply(projection, viewMatrix);
        v[12] = tx;
        v[13] = ty;
        v[14] = tz;
        gl.disable(Gl2.GL_DEPTH_TEST);                      // drawn first, everything else covers it
        gl.disable(Gl2.GL_CULL_FACE);                       // seen from inside, whatever the winding
        skyPass = true;
        drawMesh(mesh, IDENTITY);
        skyPass = false;
        gl.enable(Gl2.GL_CULL_FACE);
        gl.enable(Gl2.GL_DEPTH_TEST);
    }

    // ---- meshes ------------------------------------------------------------------------------------

    public int createMesh(MeshData data) {
        int id = -1;
        for (int i = 0; i < meshCount; i++) {
            if (!meshUsed[i]) {
                id = i;
                break;
            }
        }
        if (id < 0) {
            if (meshCount >= maxMeshes) {
                return -1;
            }
            id = meshCount++;
        }
        int vc = data.getVertexCount();
        boolean n = data.normals != null;
        boolean u0 = data.uv0 != null;
        boolean u1 = data.uv1 != null;
        int stride = 12 + (n ? 12 : 0) + 4 + (u0 ? 8 : 0) + (u1 ? 8 : 0);
        offNormal[id] = n ? 12 : -1;
        offColor[id] = 12 + (n ? 12 : 0);
        offUv0[id] = u0 ? offColor[id] + 4 : -1;
        offUv1[id] = u1 ? offColor[id] + 4 + (u0 ? 8 : 0) : -1;
        meshStride[id] = stride;

        ByteBuffer bb = ByteBuffer.allocateDirect(vc * stride).order(ByteOrder.nativeOrder());
        for (int v = 0; v < vc; v++) {
            bb.putFloat(data.positions[v * 3]).putFloat(data.positions[v * 3 + 1]).putFloat(data.positions[v * 3 + 2]);
            if (n) {
                bb.putFloat(data.normals[v * 3]).putFloat(data.normals[v * 3 + 1]).putFloat(data.normals[v * 3 + 2]);
            }
            for (int c = 0; c < 4; c++) {
                int b = Math.round(data.colors[v * 4 + c] * 255f);
                bb.put((byte) (b < 0 ? 0 : (b > 255 ? 255 : b)));
            }
            if (u0) {
                bb.putFloat(data.uv0[v * 2]).putFloat(data.uv0[v * 2 + 1]);
            }
            if (u1) {
                bb.putFloat(data.uv1[v * 2]).putFloat(data.uv1[v * 2 + 1]);
            }
        }
        ((Buffer) bb).position(0);
        ShortBuffer ib = ByteBuffer.allocateDirect(data.indices.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        ((Buffer) ib.put(data.indices)).position(0);

        vertexData[id] = bb;
        indexData[id] = ib;
        indexCounts[id] = data.indices.length;
        meshDiffuse[id] = -1;
        meshLightmap[id] = -1;
        meshDynamic[id] = false;
        meshUsed[id] = true;
        if (!failed) {
            createBuffers(id);
        }
        return id;
    }

    // Last mesh whose buffers and attribute pointers are set up, with the shader features it was set up for, and the
    // textures bound to units 0 and 1: drawing the same mesh again (trees, bricks, bullets) skips those GL calls, which
    // are costly on old phones (every GL call is a JNI call). Everything that touches these bindings calls
    // invalidateBindings().
    private int lastMesh = -1;
    private int lastFeatures = -1;
    private int boundTex0 = -1;
    private int boundTex1 = -1;

    private void invalidateBindings() {
        lastMesh = -1;
        lastFeatures = -1;
        boundTex0 = -1;
        boundTex1 = -1;
    }

    private void createBuffers(int id) {
        invalidateBindings();
        ByteBuffer vb = vertexData[id];
        ((Buffer) vb).position(0);
        vbo[id] = gl.genBuffer();
        gl.bindBuffer(Gl2.GL_ARRAY_BUFFER, vbo[id]);
        int usage = meshDynamic[id] ? Gl2.GL_DYNAMIC_DRAW : Gl2.GL_STATIC_DRAW;
        gl.bufferData(Gl2.GL_ARRAY_BUFFER, vb.capacity(), vb, usage);
        ShortBuffer ix = indexData[id];
        ((Buffer) ix).position(0);
        ibo[id] = gl.genBuffer();
        gl.bindBuffer(Gl2.GL_ELEMENT_ARRAY_BUFFER, ibo[id]);
        gl.bufferData(Gl2.GL_ELEMENT_ARRAY_BUFFER, ix.capacity() * 2, ix, usage);
        gl.bindBuffer(Gl2.GL_ARRAY_BUFFER, 0);
        gl.bindBuffer(Gl2.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public void deleteMesh(int mesh) {
        invalidateBindings();
        if (mesh < 0 || mesh >= meshCount || !meshUsed[mesh]) {
            return;
        }
        if (!failed) {
            gl.deleteBuffer(vbo[mesh]);
            gl.deleteBuffer(ibo[mesh]);
        }
        vbo[mesh] = 0;
        ibo[mesh] = 0;
        vertexData[mesh] = null;
        indexData[mesh] = null;
        meshDiffuse[mesh] = -1;
        meshLightmap[mesh] = -1;
        meshDynamic[mesh] = false;
        meshUsed[mesh] = false;
    }

    public void setMeshTextures(int mesh, int diffuseTexture, int lightmapTexture) {
        meshDiffuse[mesh] = diffuseTexture;
        meshLightmap[mesh] = lightmapTexture;
    }

    public void drawMesh(int mesh, Mat4 model) {
        if (failed) {
            return;
        }
        boolean hasLightmapAsset = meshLightmap[mesh] >= 0 && offUv1[mesh] >= 0
                && textureGlIds[meshLightmap[mesh]] != 0;
        boolean tex0 = meshDiffuse[mesh] >= 0 && offUv0[mesh] >= 0 && textureGlIds[meshDiffuse[mesh]] != 0;
        boolean lm = hasLightmapAsset && lightmapsEnabled;
        // baked light replaces dynamic light, also while lightmaps are switched off for a comparison
        boolean lit = lightingRequested && offNormal[mesh] >= 0 && !hasLightmapAsset && !skyPass;
        boolean styled = !skyPass && (blendMode != BLEND_OPAQUE || alphaCutoff > 0f || !culling || tintOn);
        boolean tint = styled && tintOn;
        boolean atest = styled && alphaCutoff > 0f;
        int features = (lit ? GLES2Shaders.LIT : 0) | (tex0 ? GLES2Shaders.TEX0 : 0) | (lm ? GLES2Shaders.LM : 0)
                | (fogOn && !skyPass ? GLES2Shaders.FOG : 0) | (tint ? GLES2Shaders.TINT : 0)
                | (atest ? GLES2Shaders.ATEST : 0);
        if (!useProgram(features)) {
            return;
        }
        mvp.multiply(skyPass ? skyViewProj : viewProj, model);
        gl.uniformMatrix4fv(locMvp[features], mvp.m);
        boolean additive = styled && blendMode == BLEND_ADD;
        if ((features & GLES2Shaders.FOG) != 0) {
            if (additive) {
                sendFog(features, true);                    // additive meshes fade to black, not to the fog color
            } else if (fogStampSent[features] != fogStamp) {   // only when the fog changed since this program saw it
                sendFog(features, false);
            }
        }
        if (tint) {
            gl.uniform4f(locTint[features], tintR, tintG, tintB, tintA);
        }
        if (atest) {
            gl.uniform1f(locCutoff[features], alphaCutoff);
        }
        if (styled) {
            applyMeshState();
        }
        if (lit) {
            normalMatrix(model, normalMat);
            gl.uniformMatrix3fv(locNormalMat[features], normalMat);
            gl.uniform3f(locLightDir[features], lightDir[0], lightDir[1], lightDir[2]);
            gl.uniform3f(locAmbient[features], ambient[0], ambient[1], ambient[2]);
            gl.uniform3f(locLightColor[features], lightColor[0], lightColor[1], lightColor[2]);
        }

        if (mesh != lastMesh || features != lastFeatures) {
            int stride = meshStride[mesh];
            gl.bindBuffer(Gl2.GL_ARRAY_BUFFER, vbo[mesh]);
            attrib(GLES2Shaders.ATTR_POS, true);
            gl.vertexAttribPointer(GLES2Shaders.ATTR_POS, 3, Gl2.GL_FLOAT, false, stride, 0);
            attrib(GLES2Shaders.ATTR_COLOR, true);
            gl.vertexAttribPointer(GLES2Shaders.ATTR_COLOR, 4, Gl2.GL_UNSIGNED_BYTE, true, stride, offColor[mesh]);
            attrib(GLES2Shaders.ATTR_NORMAL, lit);
            if (lit) {
                gl.vertexAttribPointer(GLES2Shaders.ATTR_NORMAL, 3, Gl2.GL_FLOAT, false, stride, offNormal[mesh]);
            }
            attrib(GLES2Shaders.ATTR_UV0, tex0);
            if (tex0) {
                gl.vertexAttribPointer(GLES2Shaders.ATTR_UV0, 2, Gl2.GL_FLOAT, false, stride, offUv0[mesh]);
            }
            attrib(GLES2Shaders.ATTR_UV1, lm);
            if (lm) {
                gl.vertexAttribPointer(GLES2Shaders.ATTR_UV1, 2, Gl2.GL_FLOAT, false, stride, offUv1[mesh]);
            }
            gl.bindBuffer(Gl2.GL_ELEMENT_ARRAY_BUFFER, ibo[mesh]);
            lastMesh = mesh;
            lastFeatures = features;
        }
        if (tex0) {
            int t = textureGlIds[meshDiffuse[mesh]];
            if (t != boundTex0) {
                gl.activeTexture(Gl2.GL_TEXTURE0);
                gl.bindTexture(Gl2.GL_TEXTURE_2D, t);
                boundTex0 = t;
            }
        }
        if (lm) {
            int t = textureGlIds[meshLightmap[mesh]];
            if (t != boundTex1) {
                gl.activeTexture(Gl2.GL_TEXTURE1);
                gl.bindTexture(Gl2.GL_TEXTURE_2D, t);
                boundTex1 = t;
            }
        }
        if (indexCounts[mesh] > 0) {
            gl.drawElements(Gl2.GL_TRIANGLES, indexCounts[mesh], Gl2.GL_UNSIGNED_SHORT, 0);
            drawCalls++;
            triangles += indexCounts[mesh] / 3;
        }
        if (styled) {
            restoreMeshState();
        }
    }

    /** Switches GL to the blend / culling state requested by the game, for one mesh. */
    private void applyMeshState() {
        if (blendMode != BLEND_OPAQUE) {
            gl.enable(Gl2.GL_BLEND);
            gl.blendFunc(Gl2.GL_SRC_ALPHA, blendMode == BLEND_ADD ? Gl2.GL_ONE : Gl2.GL_ONE_MINUS_SRC_ALPHA);
            gl.depthMask(false);                            // test against the scene, never hide each other
        }
        if (!culling) {
            gl.disable(Gl2.GL_CULL_FACE);
        }
    }

    /** Back to the default state that every other draw call expects. */
    private void restoreMeshState() {
        if (blendMode != BLEND_OPAQUE) {
            gl.disable(Gl2.GL_BLEND);
            gl.depthMask(true);
        }
        if (!culling) {
            gl.enable(Gl2.GL_CULL_FACE);
        }
    }

    public void setBlendMode(int mode) {
        blendMode = mode;
    }

    public void setAlphaTest(float cutoff) {
        alphaCutoff = cutoff;
    }

    public void setCulling(boolean backFaceCulling) {
        culling = backFaceCulling;
    }

    public void setTint(float r, float g, float b, float a) {
        tintR = r;
        tintG = g;
        tintB = b;
        tintA = a;
        tintOn = r != 1f || g != 1f || b != 1f || a != 1f;
    }

    // ---- viewport, scissor ---------------------------------------------------------------------

    public void setViewport(int x, int y, int w, int h) {
        viewportSet = true;
        gl.viewport(x, height - (y + h), w, h);
    }

    public void resetViewport() {
        if (viewportSet) {
            viewportSet = false;
            gl.viewport(0, 0, width, height);
        }
    }

    public void setScissor(boolean enabled, int x, int y, int w, int h) {
        if (enabled) {
            if (!scissorOn) {
                scissorOn = true;
                gl.enable(Gl2.GL_SCISSOR_TEST);
            }
            gl.scissor(x, height - (y + h), w, h);
        } else if (scissorOn) {
            scissorOn = false;
            gl.disable(Gl2.GL_SCISSOR_TEST);
        }
    }

    public void clearDepth() {
        if (!failed) {
            gl.clear(Gl2.GL_DEPTH_BUFFER_BIT);
        }
    }

    // ---- 3D lines --------------------------------------------------------------------------------

    public void drawLines(float[] data, int vertexCount) {
        if (failed || vertexCount < 2) {
            return;
        }
        if (vertexCount > MAX_LINE_VERTICES) {
            vertexCount = MAX_LINE_VERTICES;
        }
        vertexCount &= ~1;
        if (!useProgram(0)) {                               // the plain program: vertex color only
            return;
        }
        invalidateBindings();
        ((Buffer) lineBuffer).clear();
        lineBuffer.put(data, 0, vertexCount * Renderer.LINE_FLOATS);
        gl.uniformMatrix4fv(locMvp[0], viewProj.m);
        gl.enable(Gl2.GL_BLEND);
        gl.blendFunc(Gl2.GL_SRC_ALPHA, Gl2.GL_ONE_MINUS_SRC_ALPHA);
        gl.bindBuffer(Gl2.GL_ARRAY_BUFFER, 0);
        gl.bindBuffer(Gl2.GL_ELEMENT_ARRAY_BUFFER, 0);
        attrib(GLES2Shaders.ATTR_POS, true);
        ((Buffer) lineBuffer).position(0);
        gl.vertexAttribPointer(GLES2Shaders.ATTR_POS, 3, Gl2.GL_FLOAT, false, LINE_STRIDE, lineBuffer);
        attrib(GLES2Shaders.ATTR_COLOR, true);
        ((Buffer) lineBuffer).position(3);
        gl.vertexAttribPointer(GLES2Shaders.ATTR_COLOR, 4, Gl2.GL_FLOAT, false, LINE_STRIDE, lineBuffer);
        attrib(GLES2Shaders.ATTR_NORMAL, false);
        attrib(GLES2Shaders.ATTR_UV0, false);
        attrib(GLES2Shaders.ATTR_UV1, false);
        gl.drawArrays(Gl2.GL_LINES, 0, vertexCount);
        drawCalls++;
        gl.disable(Gl2.GL_BLEND);
    }

    // ---- dynamic meshes ----------------------------------------------------------------------------

    public int createDynamicMesh(int maxVertices, int maxIndices, boolean withNormals, boolean withUv0) {
        if (maxVertices < 1 || maxVertices > 65535 || maxIndices < 3) {
            return -1;
        }
        int id = -1;
        for (int i = 0; i < meshCount; i++) {
            if (!meshUsed[i]) {
                id = i;
                break;
            }
        }
        if (id < 0) {
            if (meshCount >= maxMeshes) {
                return -1;
            }
            id = meshCount++;
        }
        int stride = 12 + (withNormals ? 12 : 0) + 4 + (withUv0 ? 8 : 0);
        offNormal[id] = withNormals ? 12 : -1;
        offColor[id] = 12 + (withNormals ? 12 : 0);
        offUv0[id] = withUv0 ? offColor[id] + 4 : -1;
        offUv1[id] = -1;
        meshStride[id] = stride;
        ByteBuffer bb = ByteBuffer.allocateDirect(maxVertices * stride).order(ByteOrder.nativeOrder());
        ShortBuffer ib = ByteBuffer.allocateDirect(maxIndices * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        vertexData[id] = bb;
        indexData[id] = ib;
        indexCounts[id] = 0;
        meshDiffuse[id] = -1;
        meshLightmap[id] = -1;
        meshDynamic[id] = true;
        meshMaxVertices[id] = maxVertices;
        meshUsed[id] = true;
        if (!failed) {
            createBuffers(id);
        }
        return id;
    }

    public void updateMesh(int mesh, float[] positions, float[] normals, float[] colors, float[] uv0,
                           short[] indices, int vertexCount, int indexCount) {
        if (mesh < 0 || mesh >= meshCount || !meshUsed[mesh] || !meshDynamic[mesh]) {
            return;
        }
        ByteBuffer bb = vertexData[mesh];
        ShortBuffer ib = indexData[mesh];
        int vc = vertexCount > meshMaxVertices[mesh] ? meshMaxVertices[mesh] : vertexCount;
        int ic = indexCount > ib.capacity() ? ib.capacity() : indexCount;
        ic -= ic % 3;
        int stride = meshStride[mesh];
        int oc = offColor[mesh];
        int on = offNormal[mesh];
        int ou = offUv0[mesh];
        for (int v = 0; v < vc; v++) {
            int o = v * stride;
            bb.putFloat(o, positions[v * 3]);
            bb.putFloat(o + 4, positions[v * 3 + 1]);
            bb.putFloat(o + 8, positions[v * 3 + 2]);
            if (on >= 0 && normals != null) {
                bb.putFloat(o + on, normals[v * 3]);
                bb.putFloat(o + on + 4, normals[v * 3 + 1]);
                bb.putFloat(o + on + 8, normals[v * 3 + 2]);
            }
            for (int c = 0; c < 4; c++) {
                int b = (int) (colors[v * 4 + c] * 255f + 0.5f);
                bb.put(o + oc + c, (byte) (b < 0 ? 0 : (b > 255 ? 255 : b)));
            }
            if (ou >= 0 && uv0 != null) {
                bb.putFloat(o + ou, uv0[v * 2]);
                bb.putFloat(o + ou + 4, uv0[v * 2 + 1]);
            }
        }
        for (int i = 0; i < ic; i++) {
            ib.put(i, indices[i]);
        }
        indexCounts[mesh] = ic;
        if (!failed && vc > 0 && ic > 0) {
            invalidateBindings();
            ((Buffer) bb).position(0);
            gl.bindBuffer(Gl2.GL_ARRAY_BUFFER, vbo[mesh]);
            gl.bufferData(Gl2.GL_ARRAY_BUFFER, vc * stride, bb, Gl2.GL_DYNAMIC_DRAW);
            ((Buffer) ib).position(0);
            gl.bindBuffer(Gl2.GL_ELEMENT_ARRAY_BUFFER, ibo[mesh]);
            gl.bufferData(Gl2.GL_ELEMENT_ARRAY_BUFFER, ic * 2, ib, Gl2.GL_DYNAMIC_DRAW);
            gl.bindBuffer(Gl2.GL_ARRAY_BUFFER, 0);
            gl.bindBuffer(Gl2.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
    }

    /** Uploads the fog uniforms. black = fog color black (additive billboards); the program then re-sends the real fog next time. */
    private void sendFog(int features, boolean black) {
        gl.uniform3f(locFog[features], fogScale, fogOffset, 0f);
        if (black) {
            gl.uniform3f(locFogColor[features], 0f, 0f, 0f);
            fogStampSent[features] = fogStamp - 1;
        } else {
            gl.uniform3f(locFogColor[features], fogColor[0], fogColor[1], fogColor[2]);
            fogStampSent[features] = fogStamp;
        }
    }

    /**
     * Normal matrix = inverse transpose of the upper left 3x3 of the model matrix (column-major, written to
     * out[0..8]). It keeps normals correct under non-uniform scale; falls back to identity if singular.
     */
    public static void normalMatrix(Mat4 model, float[] out) {
        float[] m = model.m;
        float a00 = m[0], a10 = m[1], a20 = m[2];
        float a01 = m[4], a11 = m[5], a21 = m[6];
        float a02 = m[8], a12 = m[9], a22 = m[10];
        float c00 = a11 * a22 - a12 * a21;
        float c01 = a12 * a20 - a10 * a22;
        float c02 = a10 * a21 - a11 * a20;
        float c10 = a02 * a21 - a01 * a22;
        float c11 = a00 * a22 - a02 * a20;
        float c12 = a01 * a20 - a00 * a21;
        float c20 = a01 * a12 - a02 * a11;
        float c21 = a02 * a10 - a00 * a12;
        float c22 = a00 * a11 - a01 * a10;
        float det = a00 * c00 + a01 * c01 + a02 * c02;
        if (det > -1e-12f && det < 1e-12f) {
            for (int i = 0; i < 9; i++) {
                out[i] = (i % 4 == 0) ? 1f : 0f;
            }
            return;
        }
        float inv = 1f / det;
        out[0] = c00 * inv;   // column 0
        out[1] = c10 * inv;
        out[2] = c20 * inv;
        out[3] = c01 * inv;   // column 1
        out[4] = c11 * inv;
        out[5] = c21 * inv;
        out[6] = c02 * inv;   // column 2
        out[7] = c12 * inv;
        out[8] = c22 * inv;
    }

    // ---- 2D quads ------------------------------------------------------------------------------------

    public void drawQuads(int texture, float[] vertices, int quadCount) {
        if (failed || quadCount <= 0) {
            return;
        }
        if (quadCount > MAX_QUADS) {
            quadCount = MAX_QUADS;
        }
        if (!useProgram(SPRITE)) {
            return;
        }
        ((Buffer) quadVertices).clear();
        quadVertices.put(vertices, 0, quadCount * 4 * Renderer.QUAD_FLOATS_PER_VERTEX);

        gl.uniformMatrix4fv(locMvp[SPRITE], viewProj.m);
        gl.disable(Gl2.GL_DEPTH_TEST);
        gl.disable(Gl2.GL_CULL_FACE);
        gl.enable(Gl2.GL_BLEND);
        gl.blendFunc(Gl2.GL_SRC_ALPHA, Gl2.GL_ONE_MINUS_SRC_ALPHA);
        invalidateBindings();
        gl.activeTexture(Gl2.GL_TEXTURE0);
        gl.bindTexture(Gl2.GL_TEXTURE_2D, textureGlIds[texture]);
        gl.bindBuffer(Gl2.GL_ARRAY_BUFFER, 0);          // vertex and index data come from client memory
        gl.bindBuffer(Gl2.GL_ELEMENT_ARRAY_BUFFER, 0);

        attrib(GLES2Shaders.ATTR_POS, true);
        ((Buffer) quadVertices).position(0);
        gl.vertexAttribPointer(GLES2Shaders.ATTR_POS, 2, Gl2.GL_FLOAT, false, QUAD_STRIDE, quadVertices);
        attrib(GLES2Shaders.ATTR_UV0, true);
        ((Buffer) quadVertices).position(2);
        gl.vertexAttribPointer(GLES2Shaders.ATTR_UV0, 2, Gl2.GL_FLOAT, false, QUAD_STRIDE, quadVertices);
        attrib(GLES2Shaders.ATTR_COLOR, true);
        ((Buffer) quadVertices).position(4);
        gl.vertexAttribPointer(GLES2Shaders.ATTR_COLOR, 4, Gl2.GL_FLOAT, false, QUAD_STRIDE, quadVertices);
        attrib(GLES2Shaders.ATTR_NORMAL, false);
        attrib(GLES2Shaders.ATTR_UV1, false);

        ((Buffer) quadIndices).position(0);
        gl.drawElements(Gl2.GL_TRIANGLES, quadCount * 6, Gl2.GL_UNSIGNED_SHORT, quadIndices);
        drawCalls++;
        triangles += quadCount * 2;

        gl.disable(Gl2.GL_BLEND);   // restore the state expected by drawMesh()
        gl.enable(Gl2.GL_CULL_FACE);
        gl.enable(Gl2.GL_DEPTH_TEST);
    }

    // ---- 3D billboards -------------------------------------------------------------------------------

    public void drawBillboards(int texture, float[] data, int count, boolean additive) {
        if (failed || count <= 0 || texture < 0 || texture >= textureCount) {
            return;
        }
        if (count > MAX_BILLBOARDS) {
            count = MAX_BILLBOARDS;
        }
        // the textured mesh program (TEX0, no light) with fog if fog is on: a billboard is a mesh without normals
        int features = GLES2Shaders.TEX0 | (fogOn ? GLES2Shaders.FOG : 0);
        if (!useProgram(features)) {
            return;
        }
        Billboards.expand(data, count, viewMatrix.m, billboardVertices);
        ((Buffer) billboardBuffer).clear();
        billboardBuffer.put(billboardVertices, 0, count * 4 * Billboards.VERTEX_FLOATS);

        gl.uniformMatrix4fv(locMvp[features], viewProj.m);
        if (fogOn) {
            if (additive) {
                sendFog(features, true);                     // far additive billboards fade to black
            } else if (fogStampSent[features] != fogStamp) {
                sendFog(features, false);
            }
        }
        gl.depthMask(false);                                 // test against the scene, never hide each other
        gl.disable(Gl2.GL_CULL_FACE);
        gl.enable(Gl2.GL_BLEND);
        gl.blendFunc(Gl2.GL_SRC_ALPHA, additive ? Gl2.GL_ONE : Gl2.GL_ONE_MINUS_SRC_ALPHA);
        invalidateBindings();
        gl.activeTexture(Gl2.GL_TEXTURE0);
        gl.bindTexture(Gl2.GL_TEXTURE_2D, textureGlIds[texture]);
        gl.bindBuffer(Gl2.GL_ARRAY_BUFFER, 0);               // vertex and index data come from client memory
        gl.bindBuffer(Gl2.GL_ELEMENT_ARRAY_BUFFER, 0);

        attrib(GLES2Shaders.ATTR_POS, true);
        ((Buffer) billboardBuffer).position(0);
        gl.vertexAttribPointer(GLES2Shaders.ATTR_POS, 3, Gl2.GL_FLOAT, false, BB_STRIDE, billboardBuffer);
        attrib(GLES2Shaders.ATTR_UV0, true);
        ((Buffer) billboardBuffer).position(3);
        gl.vertexAttribPointer(GLES2Shaders.ATTR_UV0, 2, Gl2.GL_FLOAT, false, BB_STRIDE, billboardBuffer);
        attrib(GLES2Shaders.ATTR_COLOR, true);
        ((Buffer) billboardBuffer).position(5);
        gl.vertexAttribPointer(GLES2Shaders.ATTR_COLOR, 4, Gl2.GL_FLOAT, false, BB_STRIDE, billboardBuffer);
        attrib(GLES2Shaders.ATTR_NORMAL, false);
        attrib(GLES2Shaders.ATTR_UV1, false);

        ((Buffer) quadIndices).position(0);
        gl.drawElements(Gl2.GL_TRIANGLES, count * 6, Gl2.GL_UNSIGNED_SHORT, quadIndices);
        drawCalls++;
        triangles += count * 2;

        gl.disable(Gl2.GL_BLEND);                            // restore the state expected by drawMesh()
        gl.enable(Gl2.GL_CULL_FACE);
        gl.depthMask(true);
    }

    // ---- textures ------------------------------------------------------------------------------------

    public int loadTexture(String assetPath, boolean smooth) {
        return loadTexture(assetPath, smooth, false);
    }

    public int loadTexture(String assetPath, boolean smooth, boolean repeat) {
        int id = -1;
        for (int i = 0; i < textureCount; i++) {
            if (texturePaths[i] == null) {
                id = i;
                break;
            }
        }
        if (id < 0) {
            if (textureCount >= maxTextures) {
                log.error("texture table is full");
                return -1;
            }
            id = textureCount++;
        }
        texturePaths[id] = assetPath;
        textureSmooth[id] = smooth;
        textureRepeat[id] = repeat;
        if (!failed && !uploadTexture(id)) {
            texturePaths[id] = null;
            return -1;
        }
        return id;
    }

    private boolean uploadTexture(int id) {
        GlImage img = images.load(texturePaths[id]);
        if (img == null) {
            log.error("cannot load texture " + texturePaths[id]);
            return false;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if ((w & (w - 1)) != 0 || (h & (h - 1)) != 0) {
            log.error("texture " + texturePaths[id] + " is " + w + "x" + h + ": width and height must be powers of two");
            img.recycle();
            return false;
        }
        boolean smooth = textureSmooth[id];
        boolean repeat = textureRepeat[id];
        boolean mips = smooth && repeat;
        int glId = gl.genTexture();
        invalidateBindings();
        gl.activeTexture(Gl2.GL_TEXTURE0);
        gl.bindTexture(Gl2.GL_TEXTURE_2D, glId);
        gl.texParameteri(Gl2.GL_TEXTURE_2D, Gl2.GL_TEXTURE_MIN_FILTER,
                smooth ? (mips ? Gl2.GL_LINEAR_MIPMAP_NEAREST : Gl2.GL_LINEAR) : Gl2.GL_NEAREST);
        gl.texParameteri(Gl2.GL_TEXTURE_2D, Gl2.GL_TEXTURE_MAG_FILTER, smooth ? Gl2.GL_LINEAR : Gl2.GL_NEAREST);
        int wrap = repeat ? Gl2.GL_REPEAT : Gl2.GL_CLAMP_TO_EDGE;
        gl.texParameteri(Gl2.GL_TEXTURE_2D, Gl2.GL_TEXTURE_WRAP_S, wrap);
        gl.texParameteri(Gl2.GL_TEXTURE_2D, Gl2.GL_TEXTURE_WRAP_T, wrap);
        img.upload();
        if (mips) {
            gl.generateMipmap(Gl2.GL_TEXTURE_2D);
        }
        img.recycle();
        textureGlIds[id] = glId;
        textureWidths[id] = w;
        textureHeights[id] = h;
        return true;
    }

    public void deleteTexture(int texture) {
        if (texture < 0 || texture >= textureCount || texturePaths[texture] == null) {
            return;
        }
        invalidateBindings();
        if (!failed && textureGlIds[texture] != 0) {
            gl.deleteTexture(textureGlIds[texture]);
        }
        textureGlIds[texture] = 0;
        texturePaths[texture] = null;
    }

    public int getTextureWidth(int texture) {
        return textureWidths[texture];
    }

    public int getTextureHeight(int texture) {
        return textureHeights[texture];
    }
}
