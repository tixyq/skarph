// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL11;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import skarph.core.Billboards;
import skarph.core.Engine;
import skarph.core.Mat4;
import skarph.core.MeshData;
import skarph.core.Renderer;
import skarph.core.TextBuffer;

/**
 * OpenGL ES 1.x backend (fixed pipeline). Works on every Android version.
 * <ul>
 * <li>Meshes use VBOs when the device supports OpenGL ES 1.1 and fall back to
 *     client-side vertex arrays (direct buffers) otherwise, or if the VBO path fails.</li>
 * <li>Textured meshes: diffuse texture (uv0) and a baked lightmap (uv1) via multitexturing.
 *     Without two texture units the lightmap is skipped and the mesh is lit dynamically.</li>
 * <li>Textures, meshes and VBOs are rebuilt automatically when the GL context is recreated.</li>
 * </ul>
 * Everything runs on the GL thread.
 */
public final class GLES1Renderer implements Renderer, GLSurfaceView.Renderer {
    private static final String TAG = "skarph";
    /** Default table sizes (the constructor with two more arguments sets others). */
    public static final int DEFAULT_MAX_MESHES = 256;
    public static final int DEFAULT_MAX_TEXTURES = 128;
    private static final int QUAD_STRIDE = Renderer.QUAD_FLOATS_PER_VERTEX * 4; // bytes

    private final int maxMeshes;
    private final int maxTextures;
    private final Engine engine;
    private final AssetManager assets;
    private GL10 gl;
    private int width;
    private int height;

    // meshes
    private final boolean[] meshUsed;
    private final FloatBuffer[] positions;
    private final FloatBuffer[] normals;
    private final FloatBuffer[] colors;
    private final FloatBuffer[] uv0;
    private final FloatBuffer[] uv1;
    private final ShortBuffer[] indices;
    private final boolean[] meshHasNormals;
    private final int[] meshDiffuse;
    private final int[] meshLightmap;
    private final int[] indexCounts;
    private int meshCount;
    private int drawCalls;
    private int triangles;

    // VBOs (OpenGL ES 1.1)
    private final int[] vboPos;
    private final int[] vboNorm;
    private final int[] vboCol;
    private final int[] vboUv0;
    private final int[] vboUv1;
    private final int[] vboIdx;
    private final int[] vboTmp = new int[1];
    private boolean vboWanted = true;
    private boolean vboActive;
    private boolean vboChecked;
    private boolean vboTexChecked;
    private boolean vboBound;

    // 1.2 mesh state (see Renderer): blend mode, alpha test, culling, tint
    private int blendMode;
    private float alphaCutoff;
    private boolean culling = true;
    private float tintR = 1f, tintG = 1f, tintB = 1f, tintA = 1f;
    private boolean tintOn;
    private final boolean[] meshDynamic;
    private boolean scissorOn;
    private boolean viewportSet;
    private static final int LINE_STRIDE = Renderer.LINE_FLOATS * 4; // bytes
    private final FloatBuffer lineBuffer;

    // GL state tracking (avoids redundant GL calls)
    private boolean lightingRequested;
    private boolean glLit;
    private int boundMesh = -1;
    private boolean multiTexture;
    private boolean lightmapsEnabled = true;
    private final float[] zero4 = new float[4];
    private final float[] modelAmbient = {0.3f, 0.3f, 0.3f, 1f};
    private final float[] lightDiffuse = {1f, 1f, 1f, 1f};
    private final float[] lightPos = {0f, 1f, 0f, 0f};

    // fog (fixed pipeline: GL_FOG, linear). fogRequested = what the game wants, glFog = what GL has now.
    private final float[] camView = new float[16];     // last setCamera() view matrix, for drawSky()
    private final float[] skyView = new float[16];
    private boolean skyPass;                            // true while drawSky() draws: no light
    private boolean fogRequested;
    private boolean glFog;
    private float fogStart;
    private float fogEnd = 1f;
    private final float[] fogColor = {0f, 0f, 0f, 1f};

    // textures
    private final String[] texturePaths; // null = free slot
    private final boolean[] textureSmooth;
    private final boolean[] textureRepeat;
    private final int[] textureGlIds;
    private final int[] textureWidths;
    private final int[] textureHeights;
    private final int[] idTmp = new int[1];
    private int textureCount;

    // 2D quads
    private final FloatBuffer quadVertices;
    private final ShortBuffer quadIndices;

    // 3D billboards: expanded on the CPU into camera facing quads (see Billboards)
    private static final int BB_STRIDE = Billboards.VERTEX_FLOATS * 4; // bytes
    private final float[] billboardVertices = new float[MAX_BILLBOARDS * 4 * Billboards.VERTEX_FLOATS];
    private final FloatBuffer billboardBuffer;
    private final float[] fogBlack = {0f, 0f, 0f, 1f};

    private final TextBuffer statsText = new TextBuffer(64);

    /** Disable VBOs (call before the first frame) if a device misbehaves. */
    public void setVboEnabled(boolean enabled) {
        vboWanted = enabled;
    }

    public GLES1Renderer(Engine engine, AssetManager assets) {
        this(engine, assets, DEFAULT_MAX_MESHES, DEFAULT_MAX_TEXTURES);
    }

    /** Same, with the size of the mesh and texture tables (handles are 0 .. max - 1). */
    public GLES1Renderer(Engine engine, AssetManager assets, int maxMeshes, int maxTextures) {
        this.maxMeshes = maxMeshes;
        this.maxTextures = maxTextures;
        meshUsed = new boolean[maxMeshes];
        positions = new FloatBuffer[maxMeshes];
        normals = new FloatBuffer[maxMeshes];
        colors = new FloatBuffer[maxMeshes];
        uv0 = new FloatBuffer[maxMeshes];
        uv1 = new FloatBuffer[maxMeshes];
        indices = new ShortBuffer[maxMeshes];
        meshHasNormals = new boolean[maxMeshes];
        meshDiffuse = new int[maxMeshes];
        meshLightmap = new int[maxMeshes];
        indexCounts = new int[maxMeshes];
        vboPos = new int[maxMeshes];
        vboNorm = new int[maxMeshes];
        vboCol = new int[maxMeshes];
        vboUv0 = new int[maxMeshes];
        vboUv1 = new int[maxMeshes];
        vboIdx = new int[maxMeshes];
        texturePaths = new String[maxTextures];
        textureSmooth = new boolean[maxTextures];
        textureRepeat = new boolean[maxTextures];
        textureGlIds = new int[maxTextures];
        textureWidths = new int[maxTextures];
        textureHeights = new int[maxTextures];
        meshDynamic = new boolean[maxMeshes];
        lineBuffer = ByteBuffer.allocateDirect(MAX_LINE_VERTICES * LINE_STRIDE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        this.engine = engine;
        this.assets = assets;
        for (int i = 0; i < maxMeshes; i++) {
            meshDiffuse[i] = -1;
            meshLightmap[i] = -1;
        }

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

    // ---- GLSurfaceView.Renderer ------------------------------------------

    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        this.gl = gl;
        boundTexU0 = -1;
        boundTexU1 = -1;
        gl.glDisable(GL10.GL_DITHER);
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glEnable(GL10.GL_CULL_FACE);
        gl.glFrontFace(GL10.GL_CCW);
        gl.glCullFace(GL10.GL_BACK);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
        gl.glDisableClientState(GL10.GL_NORMAL_ARRAY);
        gl.glDisable(GL10.GL_LIGHTING);
        gl.glEnable(GL10.GL_COLOR_MATERIAL);
        gl.glEnable(GL10.GL_NORMALIZE);
        gl.glEnable(GL10.GL_LIGHT0);
        glLit = false;
        boundMesh = -1;
        vboBound = false;
        applyLightParams();
        glFog = false;
        gl.glDisable(GL10.GL_FOG);
        gl.glFogf(GL10.GL_FOG_MODE, GL10.GL_LINEAR);
        applyFogParams();

        gl.glGetIntegerv(GL10.GL_MAX_TEXTURE_UNITS, idTmp, 0);
        multiTexture = idTmp[0] >= 2;
        vboActive = vboWanted && (gl instanceof GL11);
        vboChecked = false;
        vboTexChecked = false;
        Log.i(TAG, "GL_RENDERER=" + gl.glGetString(GL10.GL_RENDERER)
                + " GL_VERSION=" + gl.glGetString(GL10.GL_VERSION)
                + " vbo=" + vboActive + " textureUnits=" + idTmp[0]);

        // the GL context was (re)created: all GL objects are gone, rebuild them
        if (vboActive) {
            for (int i = 0; i < meshCount; i++) {
                if (meshUsed[i] && !meshDynamic[i]) {
                    createVbos(i);
                }
            }
        }
        for (int i = 0; i < textureCount; i++) {
            if (texturePaths[i] != null && !uploadTexture(i)) {
                Log.e(TAG, "could not reload texture " + texturePaths[i]);
            }
        }
    }

    public void onSurfaceChanged(GL10 gl, int w, int h) {
        this.gl = gl;
        width = w;
        height = h;
        viewportSet = false;
        if (scissorOn) {
            scissorOn = false;
            gl.glDisable(GL10.GL_SCISSOR_TEST);
        }
        gl.glViewport(0, 0, w, h);
    }

    public void onDrawFrame(GL10 gl) {
        this.gl = gl;
        engine.frame(System.nanoTime(), this);
        if (engine.stats.changed) {
            engine.stats.changed = false;
            // once per second, outside the hot path (toString allocates)
            statsText.clear().append("fps=").append(engine.stats.fps)
                    .append(" tps=").append(engine.stats.tps);
            Log.d(TAG, statsText.toString());
        }
    }

    // ---- skarph.core.Renderer --------------------------------------------

    public String getBackendName() {
        return "OpenGL ES 1.x (fixed pipeline)";
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

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

        positions[id] = floatBuffer(data.positions);
        normals[id] = data.normals != null ? floatBuffer(data.normals) : null;
        colors[id] = floatBuffer(data.colors);
        uv0[id] = data.uv0 != null ? floatBuffer(data.uv0) : null;
        uv1[id] = data.uv1 != null ? floatBuffer(data.uv1) : null;
        ShortBuffer ib = ByteBuffer.allocateDirect(data.indices.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        ((Buffer) ib.put(data.indices)).position(0);
        indices[id] = ib;

        meshHasNormals[id] = data.normals != null;
        meshDiffuse[id] = -1;
        meshLightmap[id] = -1;
        indexCounts[id] = data.indices.length;
        meshDynamic[id] = false;
        meshUsed[id] = true;
        if (vboActive) {
            createVbos(id);
        }
        return id;
    }

    private static FloatBuffer floatBuffer(float[] src) {
        FloatBuffer fb = ByteBuffer.allocateDirect(src.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        ((Buffer) fb.put(src)).position(0);
        return fb;
    }

    public void deleteMesh(int mesh) {
        if (mesh < 0 || mesh >= meshCount || !meshUsed[mesh]) {
            return;
        }
        if (vboActive && !meshDynamic[mesh]) {
            deleteVbos(mesh);
        }
        meshDynamic[mesh] = false;
        positions[mesh] = null;
        normals[mesh] = null;
        colors[mesh] = null;
        uv0[mesh] = null;
        uv1[mesh] = null;
        indices[mesh] = null;
        meshDiffuse[mesh] = -1;
        meshLightmap[mesh] = -1;
        meshUsed[mesh] = false;
        boundMesh = -1;
    }

    public void setMeshTextures(int mesh, int diffuseTexture, int lightmapTexture) {
        meshDiffuse[mesh] = diffuseTexture;
        meshLightmap[mesh] = lightmapTexture;
    }

    public boolean supportsLightmaps() {
        return multiTexture;
    }

    public void setLightmapsEnabled(boolean enabled) {
        lightmapsEnabled = enabled;
    }

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
                Log.e(TAG, "texture table is full");
                return -1;
            }
            id = textureCount++;
        }
        texturePaths[id] = assetPath;
        textureSmooth[id] = smooth;
        textureRepeat[id] = repeat;
        if (!uploadTexture(id)) {
            texturePaths[id] = null;
            return -1;
        }
        return id;
    }

    public void deleteTexture(int texture) {
        if (texture < 0 || texture >= textureCount || texturePaths[texture] == null) {
            return;
        }
        idTmp[0] = textureGlIds[texture];
        gl.glDeleteTextures(1, idTmp, 0);
        boundTexU0 = -1;
        boundTexU1 = -1;
        texturePaths[texture] = null;
        textureGlIds[texture] = 0;
    }

    public int getTextureWidth(int texture) {
        return textureWidths[texture];
    }

    public int getTextureHeight(int texture) {
        return textureHeights[texture];
    }

    public boolean usesBufferObjects() {
        return vboActive;
    }

    public void setLighting(boolean enabled) {
        lightingRequested = enabled;
    }

    public void setAmbientLight(float r, float g, float b) {
        modelAmbient[0] = r;
        modelAmbient[1] = g;
        modelAmbient[2] = b;
        gl.glLightModelfv(GL10.GL_LIGHT_MODEL_AMBIENT, modelAmbient, 0);
    }

    public void setDirectionalLight(float dx, float dy, float dz, float r, float g, float b) {
        lightPos[0] = -dx; // GL wants the direction TOWARD the light
        lightPos[1] = -dy;
        lightPos[2] = -dz;
        lightPos[3] = 0f;
        lightDiffuse[0] = r;
        lightDiffuse[1] = g;
        lightDiffuse[2] = b;
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_DIFFUSE, lightDiffuse, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_POSITION, lightPos, 0);
    }

    public void setFog(boolean enabled, float start, float end, float r, float g, float b) {
        fogRequested = enabled && end > start;
        if (fogRequested) {
            fogStart = start;
            fogEnd = end;
            fogColor[0] = r;
            fogColor[1] = g;
            fogColor[2] = b;
            if (gl != null) {
                applyFogParams();
            }
        }
        if (gl != null) {
            setFogState(fogRequested);
        }
    }

    private void applyFogParams() {
        gl.glFogf(GL10.GL_FOG_START, fogStart);
        gl.glFogf(GL10.GL_FOG_END, fogEnd);
        gl.glFogfv(GL10.GL_FOG_COLOR, fogColor, 0);
        if (fogRequested) {
            setFogState(true);
        }
    }

    /** Switches GL_FOG on/off only when it really changes. */
    private void setFogState(boolean on) {
        if (on != glFog) {
            if (on) {
                gl.glEnable(GL10.GL_FOG);
            } else {
                gl.glDisable(GL10.GL_FOG);
            }
            glFog = on;
        }
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
        gl.glClearColor(r, g, b, 1f);
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
    }

    public void setCamera(Mat4 projection, Mat4 view) {
        gl.glMatrixMode(GL10.GL_PROJECTION);
        gl.glLoadMatrixf(projection.m, 0);
        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glLoadMatrixf(view.m, 0);
        System.arraycopy(view.m, 0, camView, 0, 16);
        // GL transforms the light position by the current modelview matrix
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_POSITION, lightPos, 0);
    }

    private static final Mat4 IDENTITY = new Mat4();

    public void drawSky(int mesh) {
        if (mesh < 0 || mesh >= meshCount || !meshUsed[mesh]) {
            return;
        }
        System.arraycopy(camView, 0, skyView, 0, 16);
        skyView[12] = 0f;                                   // the sky follows the camera: rotation only
        skyView[13] = 0f;
        skyView[14] = 0f;
        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glLoadMatrixf(skyView, 0);
        gl.glDisable(GL10.GL_DEPTH_TEST);                   // drawn first, everything else covers it
        gl.glDisable(GL10.GL_CULL_FACE);                    // seen from inside, whatever the winding
        setFogState(false);
        skyPass = true;
        drawMesh(mesh, IDENTITY);
        skyPass = false;
        setFogState(fogRequested);
        gl.glEnable(GL10.GL_CULL_FACE);
        gl.glEnable(GL10.GL_DEPTH_TEST);
        gl.glLoadMatrixf(camView, 0);
    }

    public void drawMesh(int mesh, Mat4 model) {
        boolean hasLightmapAsset = meshLightmap[mesh] >= 0 && uv1[mesh] != null;
        boolean tex0 = meshDiffuse[mesh] >= 0 && uv0[mesh] != null;
        boolean lm = hasLightmapAsset && multiTexture && lightmapsEnabled;
        // baked light replaces dynamic light; without multitexture the lightmap is skipped
        // and the mesh falls back to dynamic lighting
        boolean lit = lightingRequested && meshHasNormals[mesh] && !skyPass
                && !(hasLightmapAsset && multiTexture);
        if (lit != glLit) {
            setLitState(lit);
        }
        boolean styled = !skyPass && (blendMode != BLEND_OPAQUE || alphaCutoff > 0f || !culling || tintOn);
        if (styled) {
            beginStyle();
        }
        gl.glPushMatrix();
        gl.glMultMatrixf(model.m, 0);
        if (indexCounts[mesh] > 0) {
            if (!vboActive || meshDynamic[mesh] || !drawVbo(mesh, lit, tex0, lm)) {
                drawClient(mesh, lit, tex0, lm);
            }
        }
        gl.glPopMatrix();
        if (styled) {
            endStyle();
        }
    }

    private int boundTexU0 = -1;                      // GL texture id currently bound on unit 0 / 1 (-1 = unknown)
    private int boundTexU1 = -1;

    private boolean styleBlackFog;

    /** Switches GL to the blend / alpha test / culling / tint state requested by the game, for one mesh. */
    private void beginStyle() {
        if (blendMode != BLEND_OPAQUE) {
            gl.glEnable(GL10.GL_BLEND);
            gl.glBlendFunc(GL10.GL_SRC_ALPHA, blendMode == BLEND_ADD ? GL10.GL_ONE : GL10.GL_ONE_MINUS_SRC_ALPHA);
            gl.glDepthMask(false);                           // test against the scene, never hide each other
            styleBlackFog = blendMode == BLEND_ADD && glFog;  // additive meshes fade to black in fog
            if (styleBlackFog) {
                gl.glFogfv(GL10.GL_FOG_COLOR, fogBlack, 0);
            }
        }
        if (alphaCutoff > 0f) {
            gl.glEnable(GL10.GL_ALPHA_TEST);
            gl.glAlphaFunc(GL10.GL_GEQUAL, alphaCutoff);
        }
        if (!culling) {
            gl.glDisable(GL10.GL_CULL_FACE);
        }
        if (tintOn) {
            // the fixed pipeline has no per-draw color multiplier on every device: draw with the tint instead of
            // the vertex colors (see Renderer.setTint)
            gl.glDisableClientState(GL10.GL_COLOR_ARRAY);
            gl.glColor4f(tintR, tintG, tintB, tintA);
        }
    }

    private void endStyle() {
        if (tintOn) {
            gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
        }
        if (!culling) {
            gl.glEnable(GL10.GL_CULL_FACE);
        }
        if (alphaCutoff > 0f) {
            gl.glDisable(GL10.GL_ALPHA_TEST);
        }
        if (blendMode != BLEND_OPAQUE) {
            if (styleBlackFog) {
                gl.glFogfv(GL10.GL_FOG_COLOR, fogColor, 0);
                styleBlackFog = false;
            }
            gl.glDisable(GL10.GL_BLEND);
            gl.glDepthMask(true);
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

    // ---- viewport, scissor -----------------------------------------------------------------------

    public void setViewport(int x, int y, int w, int h) {
        viewportSet = true;
        gl.glViewport(x, height - (y + h), w, h);
    }

    public void resetViewport() {
        if (viewportSet) {
            viewportSet = false;
            gl.glViewport(0, 0, width, height);
        }
    }

    public void setScissor(boolean enabled, int x, int y, int w, int h) {
        if (enabled) {
            if (!scissorOn) {
                scissorOn = true;
                gl.glEnable(GL10.GL_SCISSOR_TEST);
            }
            gl.glScissor(x, height - (y + h), w, h);
        } else if (scissorOn) {
            scissorOn = false;
            gl.glDisable(GL10.GL_SCISSOR_TEST);
        }
    }

    public void clearDepth() {
        gl.glClear(GL10.GL_DEPTH_BUFFER_BIT);
    }

    // ---- 3D lines ----------------------------------------------------------------------------------

    public void drawLines(float[] data, int vertexCount) {
        if (vertexCount < 2) {
            return;
        }
        if (vertexCount > MAX_LINE_VERTICES) {
            vertexCount = MAX_LINE_VERTICES;
        }
        vertexCount &= ~1;
        ((Buffer) lineBuffer).clear();
        lineBuffer.put(data, 0, vertexCount * Renderer.LINE_FLOATS);
        if (vboBound) {
            unbindBuffers();
        }
        if (glLit) {
            setLitState(false);
        }
        boundMesh = -1;
        setFogState(false);
        gl.glEnable(GL10.GL_BLEND);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
        ((Buffer) lineBuffer).position(0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, LINE_STRIDE, lineBuffer);
        ((Buffer) lineBuffer).position(3);
        gl.glColorPointer(4, GL10.GL_FLOAT, LINE_STRIDE, lineBuffer);
        gl.glDrawArrays(GL10.GL_LINES, 0, vertexCount);
        drawCalls++;
        gl.glDisable(GL10.GL_BLEND);
        setFogState(fogRequested);
    }

    // ---- dynamic meshes (client arrays: the data changes every frame, a VBO would not help) ------------

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
        positions[id] = ByteBuffer.allocateDirect(maxVertices * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        normals[id] = withNormals
                ? ByteBuffer.allocateDirect(maxVertices * 12).order(ByteOrder.nativeOrder()).asFloatBuffer() : null;
        colors[id] = ByteBuffer.allocateDirect(maxVertices * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        uv0[id] = withUv0
                ? ByteBuffer.allocateDirect(maxVertices * 8).order(ByteOrder.nativeOrder()).asFloatBuffer() : null;
        uv1[id] = null;
        indices[id] = ByteBuffer.allocateDirect(maxIndices * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        meshHasNormals[id] = withNormals;
        meshDiffuse[id] = -1;
        meshLightmap[id] = -1;
        indexCounts[id] = 0;
        meshDynamic[id] = true;
        meshUsed[id] = true;
        boundMesh = -1;
        return id;
    }

    public void updateMesh(int mesh, float[] pos, float[] nor, float[] col, float[] uv, short[] idx,
                           int vertexCount, int indexCount) {
        if (mesh < 0 || mesh >= meshCount || !meshUsed[mesh] || !meshDynamic[mesh]) {
            return;
        }
        int vc = vertexCount > positions[mesh].capacity() / 3 ? positions[mesh].capacity() / 3 : vertexCount;
        int ic = indexCount > indices[mesh].capacity() ? indices[mesh].capacity() : indexCount;
        ic -= ic % 3;
        FloatBuffer fp = positions[mesh];
        FloatBuffer fc = colors[mesh];
        for (int i = 0; i < vc * 3; i++) {
            fp.put(i, pos[i]);
        }
        for (int i = 0; i < vc * 4; i++) {
            fc.put(i, col[i]);
        }
        if (normals[mesh] != null && nor != null) {
            FloatBuffer fn = normals[mesh];
            for (int i = 0; i < vc * 3; i++) {
                fn.put(i, nor[i]);
            }
        }
        if (uv0[mesh] != null && uv != null) {
            FloatBuffer fu = uv0[mesh];
            for (int i = 0; i < vc * 2; i++) {
                fu.put(i, uv[i]);
            }
        }
        ShortBuffer ib = indices[mesh];
        for (int i = 0; i < ic; i++) {
            ib.put(i, idx[i]);
        }
        indexCounts[mesh] = ic;
    }

    private void setLitState(boolean lit) {
        if (lit) {
            gl.glEnable(GL10.GL_LIGHTING);
            gl.glEnableClientState(GL10.GL_NORMAL_ARRAY);
        } else {
            gl.glDisable(GL10.GL_LIGHTING);
            gl.glDisableClientState(GL10.GL_NORMAL_ARRAY);
        }
        glLit = lit;
        boundMesh = -1; // vertex pointers must be set again
    }

    private void applyLightParams() {
        gl.glLightModelfv(GL10.GL_LIGHT_MODEL_AMBIENT, modelAmbient, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_AMBIENT, zero4, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_DIFFUSE, lightDiffuse, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_SPECULAR, zero4, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_POSITION, lightPos, 0);
    }

    /** Selects a texture unit for both texturing and the texture coordinate array. */
    private void selectUnit(int unit) {
        gl.glActiveTexture(unit);
        gl.glClientActiveTexture(unit);
    }

    /** Binds the textures of a mesh to unit 0 (diffuse) and unit 1 (lightmap). */
    private void bindTextures(int mesh, boolean tex0, boolean lm) {
        if (tex0) {
            selectUnit(GL10.GL_TEXTURE0);
            gl.glEnable(GL10.GL_TEXTURE_2D);
            int id = textureGlIds[meshDiffuse[mesh]];
            if (id != boundTexU0) {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, id);
                boundTexU0 = id;
            }
        }
        if (lm) {
            selectUnit(GL10.GL_TEXTURE1);
            gl.glEnable(GL10.GL_TEXTURE_2D);
            int id = textureGlIds[meshLightmap[mesh]];
            if (id != boundTexU1) {
                gl.glBindTexture(GL10.GL_TEXTURE_2D, id);
                boundTexU1 = id;
            }
        }
    }

    /** Switches texturing off again so that other draw calls see the default state. */
    private void restoreTextureState(boolean tex0, boolean lm) {
        if (lm) {
            selectUnit(GL10.GL_TEXTURE1);
            gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
            gl.glDisable(GL10.GL_TEXTURE_2D);
        }
        selectUnit(GL10.GL_TEXTURE0);
        if (tex0) {
            gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
            gl.glDisable(GL10.GL_TEXTURE_2D);
        }
        boundMesh = -1;
    }

    /** Client-side vertex arrays path (works everywhere). */
    private void drawClient(int mesh, boolean lit, boolean tex0, boolean lm) {
        if (vboBound) {
            unbindBuffers();
        }
        boolean textured = tex0 || lm;
        if (boundMesh != mesh || textured) {
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, positions[mesh]);
            if (lit) {
                gl.glNormalPointer(GL10.GL_FLOAT, 0, normals[mesh]);
            }
            gl.glColorPointer(4, GL10.GL_FLOAT, 0, colors[mesh]);
            boundMesh = mesh;
        }
        if (tex0) {
            selectUnit(GL10.GL_TEXTURE0);
            gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, uv0[mesh]);
        }
        if (lm) {
            selectUnit(GL10.GL_TEXTURE1);
            gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, uv1[mesh]);
        }
        bindTextures(mesh, tex0, lm);
        gl.glDrawElements(GL10.GL_TRIANGLES, indexCounts[mesh],
                GL10.GL_UNSIGNED_SHORT, indices[mesh]);
        drawCalls++;
        triangles += indexCounts[mesh] / 3;
        if (textured) {
            restoreTextureState(tex0, lm);
        }
    }

    /**
     * VBO path (OpenGL ES 1.1). Returns false if it failed: VBOs are then
     * switched off for good and the caller draws with client arrays.
     */
    private boolean drawVbo(int mesh, boolean lit, boolean tex0, boolean lm) {
        try {
            GL11 g = (GL11) gl;
            boolean textured = tex0 || lm;
            boolean check = textured ? !vboTexChecked : !vboChecked;
            if (check) {
                int guard = 0;
                while (gl.glGetError() != GL10.GL_NO_ERROR && ++guard < 8) {
                    // flush errors that were raised before our first VBO draw
                }
            }
            if (boundMesh != mesh || textured) {
                g.glBindBuffer(GL11.GL_ARRAY_BUFFER, vboPos[mesh]);
                g.glVertexPointer(3, GL10.GL_FLOAT, 0, 0);
                if (lit) {
                    g.glBindBuffer(GL11.GL_ARRAY_BUFFER, vboNorm[mesh]);
                    g.glNormalPointer(GL10.GL_FLOAT, 0, 0);
                }
                g.glBindBuffer(GL11.GL_ARRAY_BUFFER, vboCol[mesh]);
                g.glColorPointer(4, GL10.GL_FLOAT, 0, 0);
                if (tex0) {
                    selectUnit(GL10.GL_TEXTURE0);
                    g.glBindBuffer(GL11.GL_ARRAY_BUFFER, vboUv0[mesh]);
                    g.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
                    g.glTexCoordPointer(2, GL10.GL_FLOAT, 0, 0);
                }
                if (lm) {
                    selectUnit(GL10.GL_TEXTURE1);
                    g.glBindBuffer(GL11.GL_ARRAY_BUFFER, vboUv1[mesh]);
                    g.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
                    g.glTexCoordPointer(2, GL10.GL_FLOAT, 0, 0);
                }
                g.glBindBuffer(GL11.GL_ELEMENT_ARRAY_BUFFER, vboIdx[mesh]);
                boundMesh = mesh;
                vboBound = true;
            }
            bindTextures(mesh, tex0, lm);
            g.glDrawElements(GL10.GL_TRIANGLES, indexCounts[mesh], GL10.GL_UNSIGNED_SHORT, 0);
            drawCalls++;
            triangles += indexCounts[mesh] / 3;
            if (textured) {
                restoreTextureState(tex0, lm);
            }
            if (check) {
                if (textured) {
                    vboTexChecked = true;
                } else {
                    vboChecked = true;
                }
                int err = gl.glGetError();
                if (err != GL10.GL_NO_ERROR) {
                    throw new RuntimeException("GL error 0x" + Integer.toHexString(err));
                }
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "VBO path failed, using client arrays instead: " + e);
            vboActive = false;
            boundMesh = -1;
            try {
                unbindBuffers();
            } catch (RuntimeException ignored) {
                // nothing more we can do
            }
            return false;
        }
    }

    private void unbindBuffers() {
        GL11 g = (GL11) gl;
        g.glBindBuffer(GL11.GL_ARRAY_BUFFER, 0);
        g.glBindBuffer(GL11.GL_ELEMENT_ARRAY_BUFFER, 0);
        vboBound = false;
        boundMesh = -1;
    }

    private int genBuffer(GL11 g) {
        g.glGenBuffers(1, vboTmp, 0);
        return vboTmp[0];
    }

    private void createVbos(int id) {
        GL11 g = (GL11) gl;
        vboPos[id] = upload(g, GL11.GL_ARRAY_BUFFER, positions[id], 4);
        vboCol[id] = upload(g, GL11.GL_ARRAY_BUFFER, colors[id], 4);
        vboNorm[id] = normals[id] != null ? upload(g, GL11.GL_ARRAY_BUFFER, normals[id], 4) : 0;
        vboUv0[id] = uv0[id] != null ? upload(g, GL11.GL_ARRAY_BUFFER, uv0[id], 4) : 0;
        vboUv1[id] = uv1[id] != null ? upload(g, GL11.GL_ARRAY_BUFFER, uv1[id], 4) : 0;
        vboIdx[id] = upload(g, GL11.GL_ELEMENT_ARRAY_BUFFER, indices[id], 2);
        g.glBindBuffer(GL11.GL_ARRAY_BUFFER, 0);
        g.glBindBuffer(GL11.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private int upload(GL11 g, int target, Buffer data, int bytesPerElement) {
        int bufferId = genBuffer(g);
        ((Buffer) data).position(0);
        g.glBindBuffer(target, bufferId);
        g.glBufferData(target, data.capacity() * bytesPerElement, data, GL11.GL_STATIC_DRAW);
        return bufferId;
    }

    private void deleteVbos(int id) {
        GL11 g = (GL11) gl;
        int[] ids = {vboPos[id], vboCol[id], vboNorm[id], vboUv0[id], vboUv1[id], vboIdx[id]};
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != 0) {
                vboTmp[0] = ids[i];
                g.glDeleteBuffers(1, vboTmp, 0);
            }
        }
        vboPos[id] = 0;
        vboCol[id] = 0;
        vboNorm[id] = 0;
        vboUv0[id] = 0;
        vboUv1[id] = 0;
        vboIdx[id] = 0;
        vboBound = false;
    }

    public void drawQuads(int texture, float[] vertices, int quadCount) {
        if (quadCount <= 0) {
            return;
        }
        if (quadCount > MAX_QUADS) {
            quadCount = MAX_QUADS;
        }
        if (vboBound) {
            unbindBuffers();
        }
        if (glLit) {
            setLitState(false);
        }
        boundMesh = -1;
        setFogState(false); // sprites are never fogged

        ((Buffer) quadVertices).clear();
        quadVertices.put(vertices, 0, quadCount * 4 * Renderer.QUAD_FLOATS_PER_VERTEX);

        gl.glDisable(GL10.GL_DEPTH_TEST);
        gl.glDisable(GL10.GL_CULL_FACE);
        gl.glEnable(GL10.GL_BLEND);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
        gl.glEnable(GL10.GL_TEXTURE_2D);
        gl.glBindTexture(GL10.GL_TEXTURE_2D, textureGlIds[texture]);
        boundTexU0 = -1;
        boundTexU1 = -1;
        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);

        ((Buffer) quadVertices).position(0);
        gl.glVertexPointer(2, GL10.GL_FLOAT, QUAD_STRIDE, quadVertices);
        ((Buffer) quadVertices).position(2);
        gl.glTexCoordPointer(2, GL10.GL_FLOAT, QUAD_STRIDE, quadVertices);
        ((Buffer) quadVertices).position(4);
        gl.glColorPointer(4, GL10.GL_FLOAT, QUAD_STRIDE, quadVertices);

        ((Buffer) quadIndices).position(0);
        gl.glDrawElements(GL10.GL_TRIANGLES, quadCount * 6,
                GL10.GL_UNSIGNED_SHORT, quadIndices);
        drawCalls++;
        triangles += quadCount * 2;

        // restore the state expected by drawMesh()
        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
        gl.glDisable(GL10.GL_TEXTURE_2D);
        gl.glDisable(GL10.GL_BLEND);
        gl.glEnable(GL10.GL_CULL_FACE);
        gl.glEnable(GL10.GL_DEPTH_TEST);
        setFogState(fogRequested);
    }

    public void drawBillboards(int texture, float[] data, int count, boolean additive) {
        if (count <= 0 || texture < 0 || texture >= maxTextures) {
            return;
        }
        if (count > MAX_BILLBOARDS) {
            count = MAX_BILLBOARDS;
        }
        Billboards.expand(data, count, camView, billboardVertices);
        ((Buffer) billboardBuffer).clear();
        billboardBuffer.put(billboardVertices, 0, count * 4 * Billboards.VERTEX_FLOATS);

        if (vboBound) {
            unbindBuffers();
        }
        if (glLit) {
            setLitState(false);
        }
        boundMesh = -1;

        gl.glDepthMask(false);                               // test against the scene, never hide each other
        gl.glDisable(GL10.GL_CULL_FACE);
        gl.glEnable(GL10.GL_BLEND);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, additive ? GL10.GL_ONE : GL10.GL_ONE_MINUS_SRC_ALPHA);
        boolean blackFog = additive && glFog;                // far additive billboards fade to black
        if (blackFog) {
            gl.glFogfv(GL10.GL_FOG_COLOR, fogBlack, 0);
        }
        selectUnit(GL10.GL_TEXTURE0);
        gl.glEnable(GL10.GL_TEXTURE_2D);
        gl.glBindTexture(GL10.GL_TEXTURE_2D, textureGlIds[texture]);
        boundTexU0 = -1;
        boundTexU1 = -1;
        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);

        ((Buffer) billboardBuffer).position(0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, BB_STRIDE, billboardBuffer);
        ((Buffer) billboardBuffer).position(3);
        gl.glTexCoordPointer(2, GL10.GL_FLOAT, BB_STRIDE, billboardBuffer);
        ((Buffer) billboardBuffer).position(5);
        gl.glColorPointer(4, GL10.GL_FLOAT, BB_STRIDE, billboardBuffer);

        ((Buffer) quadIndices).position(0);
        gl.glDrawElements(GL10.GL_TRIANGLES, count * 6, GL10.GL_UNSIGNED_SHORT, quadIndices);
        drawCalls++;
        triangles += count * 2;

        // restore the state expected by drawMesh()
        if (blackFog) {
            gl.glFogfv(GL10.GL_FOG_COLOR, fogColor, 0);
        }
        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
        gl.glDisable(GL10.GL_TEXTURE_2D);
        gl.glDisable(GL10.GL_BLEND);
        gl.glEnable(GL10.GL_CULL_FACE);
        gl.glDepthMask(true);
    }

    // ---- textures ----------------------------------------------------------

    /** Decodes the asset and uploads it. The Bitmap is recycled right away. */
    private boolean uploadTexture(int id) {
        Bitmap bmp = null;
        InputStream in = null;
        try {
            in = assets.open(texturePaths[id]);
            BitmapFactory.Options opts = BitmapOptionsCompat.exactPixels(); // never rescale (API 3 safe)
            bmp = BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException e) {
            Log.e(TAG, "cannot open asset " + texturePaths[id]);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
        if (bmp == null) {
            return false;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if ((w & (w - 1)) != 0 || (h & (h - 1)) != 0) {
            Log.e(TAG, "texture " + texturePaths[id] + " is " + w + "x" + h
                    + ": width and height must be powers of two");
            bmp.recycle();
            return false;
        }

        selectUnit(GL10.GL_TEXTURE0);
        gl.glGenTextures(1, idTmp, 0);
        int glId = idTmp[0];
        gl.glBindTexture(GL10.GL_TEXTURE_2D, glId);
        boundTexU0 = -1;
        boundTexU1 = -1;
        boolean smooth = textureSmooth[id];
        boolean repeat = textureRepeat[id];
        boolean mips = smooth && repeat && (gl instanceof GL11);
        float minFilter = smooth ? GL10.GL_LINEAR : GL10.GL_NEAREST;
        if (mips) {
            minFilter = GL10.GL_LINEAR_MIPMAP_NEAREST;
            // ES 1.1: the driver builds the mip chain when the image is uploaded
            gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL11.GL_GENERATE_MIPMAP, GL10.GL_TRUE);
        }
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, minFilter);
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER,
                smooth ? GL10.GL_LINEAR : GL10.GL_NEAREST);
        float wrap = repeat ? GL10.GL_REPEAT : GL10.GL_CLAMP_TO_EDGE;
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, wrap);
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, wrap);
        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();

        textureGlIds[id] = glId;
        textureWidths[id] = w;
        textureHeights[id] = h;
        return true;
    }
}
