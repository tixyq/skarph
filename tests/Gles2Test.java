// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.android.*;
import skarph.core.*;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/** Tests the logic of the OpenGL ES 2.0 renderer on a fake GL: no GPU needed (the shaders are checked by tests/glsl). */
public class Gles2Test {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static boolean near(float a, float b, float eps) { return Math.abs(a - b) < eps; }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    // ------------------------------------------------------------------ fake GL
    static final class Ptr {
        int size, type, stride, offset, clientPos;
        boolean normalized, client;
        float firstFloat;
    }

    static final class Draw {
        int program, mode, count, type, offset, clientIndexPos, arrayBuf, elemBuf, blendSrc, blendDst;
        boolean clientIndices, depth, cull, blend, depthMask;
        final boolean[] attrib = new boolean[8];
        final Ptr[] ptr = new Ptr[8];
        final int[] unitTex = new int[2];
    }

    static final class FakeGl implements Gl2 {
        int next = 1;
        final HashSet<Integer> shaders = new HashSet<Integer>(), programs = new HashSet<Integer>();
        final HashSet<Integer> buffers = new HashSet<Integer>(), textures = new HashSet<Integer>();
        final HashMap<Integer, String> shaderSrc = new HashMap<Integer, String>();
        final HashMap<Integer, ArrayList<Integer>> attached = new HashMap<Integer, ArrayList<Integer>>();
        final HashMap<Integer, HashMap<String, Integer>> attribBind = new HashMap<Integer, HashMap<String, Integer>>();
        final HashMap<Integer, HashMap<String, Integer>> uniformLoc = new HashMap<Integer, HashMap<String, Integer>>();
        final HashMap<Long, float[]> uniformVals = new HashMap<Long, float[]>();
        final HashMap<Long, Integer> uniformInts = new HashMap<Long, Integer>();
        final HashMap<Integer, byte[]> bufferBytes = new HashMap<Integer, byte[]>();
        final HashMap<Integer, short[]> bufferShorts = new HashMap<Integer, short[]>();
        final HashMap<Integer, HashMap<Integer, Integer>> texParams = new HashMap<Integer, HashMap<Integer, Integer>>();
        final HashSet<Integer> caps = new HashSet<Integer>();
        final ArrayList<Draw> draws = new ArrayList<Draw>();
        final ArrayList<String> strings = new ArrayList<String>();
        int shadersCreated, programsCreated, buffersCreated, texturesCreated, useProgramCalls, bufferDataCalls, mipmaps, clears;
        final int[] enableCalls = new int[8], disableCalls = new int[8];
        String failCompileContaining;
        boolean failLink, failCreateShader;
        int currentProgram, arrayBuf, elemBuf, activeUnit, blendSrc, blendDst, frontFace, cullFaceMode;
        final int[] unitTex = new int[8];
        final boolean[] attribEnabled = new boolean[8];
        final Ptr[] ptr = new Ptr[8];

        void loseContext() {
            shaders.clear(); programs.clear(); buffers.clear(); textures.clear();
            currentProgram = 0; arrayBuf = 0; elemBuf = 0; activeUnit = 0;
            for (int i = 0; i < 8; i++) { unitTex[i] = 0; attribEnabled[i] = false; ptr[i] = null; }
            caps.clear();
        }

        String programSource(int p) {
            StringBuilder sb = new StringBuilder();
            for (int s : attached.get(p)) sb.append(shaderSrc.get(s));
            return sb.toString();
        }

        int featuresOf(int p) {
            String s = programSource(p);
            return (s.contains("#define LIT") ? 1 : 0) | (s.contains("#define TEX0") ? 2 : 0) | (s.contains("#define LM") ? 4 : 0) | (s.contains("#define FOG") ? 8 : 0);
        }

        boolean isSprite(int p) { return programSource(p).contains("attribute vec2 aPos"); }

        float[] uniform(int program, String name) {
            Integer loc = uniformLoc.get(program).get(name);
            return loc == null ? null : uniformVals.get(((long) program << 32) | loc);
        }

        public int createShader(int type) { if (failCreateShader) return 0; int id = next++; shaders.add(id); shadersCreated++; return id; }
        public void shaderSource(int sh, String src) { shaderSrc.put(sh, src); }
        public void compileShader(int sh) { }
        public int getShaderi(int sh, int pname) {
            return (failCompileContaining != null && shaderSrc.get(sh).contains(failCompileContaining)) ? 0 : 1;
        }
        public String getShaderInfoLog(int sh) { return "FAKE_COMPILE_LOG"; }
        public void deleteShader(int sh) { shaders.remove(sh); }
        public int createProgram() { int id = next++; programs.add(id); programsCreated++; attached.put(id, new ArrayList<Integer>());
            attribBind.put(id, new HashMap<String, Integer>()); uniformLoc.put(id, new HashMap<String, Integer>()); return id; }
        public void attachShader(int p, int sh) { attached.get(p).add(sh); }
        public void bindAttribLocation(int p, int index, String name) { attribBind.get(p).put(name, index); }
        public void linkProgram(int p) { }
        public int getProgrami(int p, int pname) { return failLink ? 0 : 1; }
        public String getProgramInfoLog(int p) { return "FAKE_LINK_LOG"; }
        public void useProgram(int p) { currentProgram = p; useProgramCalls++; }
        public void deleteProgram(int p) { programs.remove(p); }
        public int getUniformLocation(int p, String name) {
            String s = programSource(p);
            boolean sprite = isSprite(p);
            boolean active;
            if (name.equals("uMvp")) active = true;
            else if (name.equals("uTex0")) active = sprite || s.contains("#define TEX0");
            else if (name.equals("uTex1")) active = s.contains("#define LM");
            else if (name.equals("uFog") || name.equals("uFogColor")) active = s.contains("#define FOG");
            else active = s.contains("#define LIT"); // uNormalMat, uLightDir, uAmbient, uLightColor
            if (!active) return -1;
            HashMap<String, Integer> m = uniformLoc.get(p);
            if (!m.containsKey(name)) m.put(name, m.size() + 1);
            return m.get(name);
        }
        private long key(int loc) { return ((long) currentProgram << 32) | loc; }
        public void uniform1i(int loc, int v) { if (loc >= 0) uniformInts.put(key(loc), v); }
        int uniform3fCalls;
        public void uniform3f(int loc, float x, float y, float z) { if (loc >= 0) { uniform3fCalls++; uniformVals.put(key(loc), new float[] {x, y, z}); } }
        public void uniformMatrix3fv(int loc, float[] v) { if (loc >= 0) uniformVals.put(key(loc), v.clone()); }
        public void uniformMatrix4fv(int loc, float[] v) { if (loc >= 0) uniformVals.put(key(loc), v.clone()); }

        public int genBuffer() { int id = next++; buffers.add(id); buffersCreated++; return id; }
        public void bindBuffer(int target, int b) { if (target == GL_ARRAY_BUFFER) arrayBuf = b; else elemBuf = b; }
        public void bufferData(int target, int size, Buffer data, int usage) {
            bufferDataCalls++;
            int id = target == GL_ARRAY_BUFFER ? arrayBuf : elemBuf;
            if (data instanceof ByteBuffer) { ByteBuffer b = ((ByteBuffer) data).duplicate(); b.position(0); byte[] a = new byte[size]; b.get(a); bufferBytes.put(id, a); }
            else if (data instanceof ShortBuffer) { ShortBuffer b = ((ShortBuffer) data).duplicate(); b.position(0); short[] a = new short[size / 2]; b.get(a); bufferShorts.put(id, a); }
        }
        public void deleteBuffer(int b) { buffers.remove(b); bufferBytes.remove(b); bufferShorts.remove(b); }

        public int genTexture() { int id = next++; textures.add(id); texturesCreated++; texParams.put(id, new HashMap<Integer, Integer>()); return id; }
        public void bindTexture(int target, int t) { unitTex[activeUnit] = t; }
        public void deleteTexture(int t) { textures.remove(t); }
        public void activeTexture(int unit) { activeUnit = unit - GL_TEXTURE0; }
        public void texParameteri(int target, int pname, int v) { texParams.get(unitTex[activeUnit]).put(pname, v); }
        public void generateMipmap(int target) { mipmaps++; }

        public void enableVertexAttribArray(int i) { attribEnabled[i] = true; enableCalls[i]++; }
        public void disableVertexAttribArray(int i) { attribEnabled[i] = false; disableCalls[i]++; }
        public void vertexAttribPointer(int i, int size, int type, boolean norm, int stride, int offset) {
            Ptr p = new Ptr(); p.size = size; p.type = type; p.normalized = norm; p.stride = stride; p.offset = offset; ptr[i] = p;
        }
        public void vertexAttribPointer(int i, int size, int type, boolean norm, int stride, Buffer data) {
            Ptr p = new Ptr(); p.size = size; p.type = type; p.normalized = norm; p.stride = stride; p.client = true; p.clientPos = data.position();
            if (data instanceof FloatBuffer) p.firstFloat = ((FloatBuffer) data).get(data.position());
            ptr[i] = p;
        }
        private Draw snapshot(int mode, int count, int type) {
            Draw d = new Draw();
            d.program = currentProgram; d.mode = mode; d.count = count; d.type = type; d.arrayBuf = arrayBuf; d.elemBuf = elemBuf;
            for (int i = 0; i < 8; i++) { d.attrib[i] = attribEnabled[i]; d.ptr[i] = ptr[i]; }
            d.unitTex[0] = unitTex[0]; d.unitTex[1] = unitTex[1];
            d.depth = caps.contains(GL_DEPTH_TEST); d.cull = caps.contains(GL_CULL_FACE); d.blend = caps.contains(GL_BLEND);
            d.blendSrc = blendSrc; d.blendDst = blendDst; d.depthMask = depthMaskOn;
            return d;
        }
        public void drawElements(int mode, int count, int type, int offset) { Draw d = snapshot(mode, count, type); d.offset = offset; draws.add(d); }
        public void drawElements(int mode, int count, int type, Buffer idx) { Draw d = snapshot(mode, count, type); d.clientIndices = true; d.clientIndexPos = idx.position(); draws.add(d); }

        public void enable(int cap) { caps.add(cap); }
        public void disable(int cap) { caps.remove(cap); }
        public void blendFunc(int s, int d) { blendSrc = s; blendDst = d; }
        boolean depthMaskOn = true;
        public void depthMask(boolean w) { depthMaskOn = w; }
        public void frontFace(int m) { frontFace = m; }
        public void cullFace(int m) { cullFaceMode = m; }
        public void viewport(int x, int y, int w, int h) { }
        public void clearColor(float r, float g, float b, float a) { }
        public void clear(int mask) { clears++; }
        public int getError() { return 0; }
        public String getString(int name) { strings.add("S" + name); return name == GL_RENDERER ? "FakeRenderer" : "FakeVersion"; }
    }

    /** A Gl2 that does nothing and allocates nothing (for the allocation test). */
    static final class QuietGl implements Gl2 {
        int next = 1;
        public int createShader(int t) { return next++; }
        public void shaderSource(int s, String src) { }
        public void compileShader(int s) { }
        public int getShaderi(int s, int p) { return 1; }
        public String getShaderInfoLog(int s) { return ""; }
        public void deleteShader(int s) { }
        public int createProgram() { return next++; }
        public void attachShader(int p, int s) { }
        public void bindAttribLocation(int p, int i, String n) { }
        public void linkProgram(int p) { }
        public int getProgrami(int p, int n) { return 1; }
        public String getProgramInfoLog(int p) { return ""; }
        public void useProgram(int p) { }
        public void deleteProgram(int p) { }
        public int getUniformLocation(int p, String n) { return 1; }
        public void uniform1i(int l, int v) { }
        public void uniform3f(int l, float x, float y, float z) { }
        public void uniformMatrix3fv(int l, float[] v) { }
        public void uniformMatrix4fv(int l, float[] v) { }
        public int genBuffer() { return next++; }
        public void bindBuffer(int t, int b) { }
        public void bufferData(int t, int s, Buffer d, int u) { }
        public void deleteBuffer(int b) { }
        public int genTexture() { return next++; }
        public void bindTexture(int t, int x) { }
        public void deleteTexture(int t) { }
        public void activeTexture(int u) { }
        public void texParameteri(int t, int p, int v) { }
        public void generateMipmap(int t) { }
        public void enableVertexAttribArray(int i) { }
        public void disableVertexAttribArray(int i) { }
        public void vertexAttribPointer(int i, int s, int t, boolean n, int st, int o) { }
        public void vertexAttribPointer(int i, int s, int t, boolean n, int st, Buffer p) { }
        public void drawElements(int m, int c, int t, int o) { }
        public void drawElements(int m, int c, int t, Buffer i) { }
        public void enable(int c) { }
        public void disable(int c) { }
        public void blendFunc(int s, int d) { }
        public void depthMask(boolean w) { }
        public void frontFace(int m) { }
        public void cullFace(int m) { }
        public void viewport(int x, int y, int w, int h) { }
        public void clearColor(float r, float g, float b, float a) { }
        public void clear(int m) { }
        public int getError() { return 0; }
        public String getString(int n) { return ""; }
    }

    static final class FakeImage implements GlImage {
        final int w, h;
        boolean uploaded, recycled;
        FakeImage(int w, int h) { this.w = w; this.h = h; }
        public int getWidth() { return w; }
        public int getHeight() { return h; }
        public void upload() { uploaded = true; }
        public void recycle() { recycled = true; }
    }

    static final class FakeImages implements GlImageLoader {
        final HashMap<String, int[]> files = new HashMap<String, int[]>();
        final ArrayList<FakeImage> loaded = new ArrayList<FakeImage>();
        public GlImage load(String path) {
            int[] s = files.get(path);
            if (s == null) return null;
            FakeImage i = new FakeImage(s[0], s[1]);
            loaded.add(i);
            return i;
        }
    }

    static final class TestLog implements GlLog {
        final ArrayList<String> infos = new ArrayList<String>(), warns = new ArrayList<String>(), errors = new ArrayList<String>();
        public void info(String m) { infos.add(m); }
        public void warn(String m) { warns.add(m); }
        public void error(String m) { errors.add(m); }
    }

    static final class Rig {
        final Engine engine = new Engine();
        final FakeGl gl = new FakeGl();
        final FakeImages images = new FakeImages();
        final TestLog log = new TestLog();
        final ArrayList<String> failures = new ArrayList<String>();
        final GLES2Renderer r;
        Rig() {
            images.files.put("diffuse.png", new int[] {64, 64});
            images.files.put("lightmap.png", new int[] {128, 128});
            images.files.put("odd.png", new int[] {100, 64});
            r = new GLES2Renderer(engine, gl, images, log, new GLES2Renderer.FailureListener() {
                public void onFailure(String reason) { failures.add(reason); }
            });
        }
        Rig start() { r.onSurfaceCreated(); r.onSurfaceChanged(800, 480); return this; }
        Draw last() { return gl.draws.get(gl.draws.size() - 1); }
    }

    static MeshData texturedQuad() {
        float[] pos = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        float[] nor = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        float[] col = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        float[] uv0 = {0, 0, 1, 0, 1, 1, 0, 1};
        float[] uv1 = {0.1f, 0.2f, 0.3f, 0.2f, 0.3f, 0.4f, 0.1f, 0.4f};
        return new MeshData(pos, nor, col, uv0, uv1, new short[] {0, 1, 2, 0, 2, 3});
    }

    public static void main(String[] args) {
        Mat4 proj = new Mat4().setPerspective(50f, 1.6f, 0.5f, 100f);
        Mat4 view = new Mat4().setLookAt(3, 4, 9, 0, 1, 0, 0, 1, 0);

        // ---------------- start-up
        Rig rig = new Rig().start();
        FakeGl gl = rig.gl;
        check(gl.programsCreated == 2 && gl.shadersCreated == 4 && gl.shaders.isEmpty(), "init: sprite and lit mesh programs are built, their shader objects are deleted after linking");
        boolean binds = true;
        for (int p : gl.programs) for (int a = 0; a < 5; a++) binds &= gl.attribBind.get(p).get(GLES2Shaders.ATTRIBUTE_NAMES[a]) == a;
        check(binds, "init: every program binds aPos/aNormal/aColor/aUv0/aUv1 to locations 0..4");
        boolean samplers = true;
        for (int p : gl.programs) {
            Integer l0 = gl.uniformLoc.get(p).get("uTex0"), l1 = gl.uniformLoc.get(p).get("uTex1");
            if (l0 != null) samplers &= gl.uniformInts.get(((long) p << 32) | l0) == 0;
            if (l1 != null) samplers &= gl.uniformInts.get(((long) p << 32) | l1) == 1;
        }
        check(samplers, "init: samplers are wired to texture units 0 and 1");
        check(gl.caps.contains(Gl2.GL_DEPTH_TEST) && gl.caps.contains(Gl2.GL_CULL_FACE) && !gl.caps.contains(Gl2.GL_DITHER)
                && gl.frontFace == Gl2.GL_CCW && gl.cullFaceMode == Gl2.GL_BACK, "init: depth test, back face culling, CCW");
        check(rig.log.infos.size() == 1 && rig.log.infos.get(0).contains("FakeRenderer") && rig.log.infos.get(0).contains("FakeVersion"), "init: GL renderer and version are logged");
        check(rig.r.getBackendName().contains("2.0") && rig.r.usesBufferObjects() && rig.r.supportsLightmaps() && !rig.r.hasFailed(), "backend: name, VBOs and lightmaps are reported");
        check(rig.failures.isEmpty() && rig.log.errors.isEmpty(), "init: no failure reported on a healthy GL");

        // ---------------- mesh buffers
        MeshData cube = Shapes.cube(1f);
        int m0 = rig.r.createMesh(cube);
        check(m0 == 0 && gl.buffers.size() == 2 && gl.bufferDataCalls == 2, "mesh: one vertex buffer and one index buffer per mesh");
        byte[] vb = null; short[] ib = null;
        for (int b : gl.buffers) { if (gl.bufferBytes.containsKey(b)) vb = gl.bufferBytes.get(b); if (gl.bufferShorts.containsKey(b)) ib = gl.bufferShorts.get(b); }
        check(vb != null && vb.length == 24 * 28 && ib != null && ib.length == 36, "mesh: cube vertex data is 24 x 28 bytes (position, normal, 4 color bytes), 36 indices");
        ByteBuffer bb = ByteBuffer.wrap(vb).order(ByteOrder.nativeOrder());
        boolean content = near(bb.getFloat(0), cube.positions[0], 1e-6f) && near(bb.getFloat(12), cube.normals[0], 1e-6f)
                && (bb.get(24) & 255) == Math.round(cube.colors[0] * 255) && (bb.get(25) & 255) == Math.round(cube.colors[1] * 255);
        int last = 23 * 28;
        content &= near(bb.getFloat(last + 8), cube.positions[23 * 3 + 2], 1e-6f) && (bb.get(last + 27) & 255) == 255;
        for (int i = 0; i < 36; i++) content &= ib[i] == cube.indices[i];
        check(content, "mesh: interleaved data and indices match the MeshData (first and last vertex)");

        // ---------------- drawing an untextured, unlit mesh
        rig.r.setCamera(proj, view);
        Mat4 model = new Mat4().translate(1, 2, 3).rotateY(30f).scale(2, 1, 1);
        rig.r.drawMesh(m0, model);
        Draw d = rig.last();
        check(gl.programsCreated == 3 && gl.featuresOf(d.program) == 0 && d.count == 36 && d.type == Gl2.GL_UNSIGNED_SHORT && !d.clientIndices && d.offset == 0
                && d.mode == Gl2.GL_TRIANGLES, "draw: a plain mesh builds the no-feature program on first use and draws 36 indices from the index buffer");
        Mat4 vp = new Mat4().multiply(proj, view);
        Mat4 expect = new Mat4().multiply(vp, model);
        float[] got = gl.uniform(d.program, "uMvp");
        boolean mvpOk = got != null;
        for (int i = 0; mvpOk && i < 16; i++) mvpOk = near(got[i], expect.m[i], 1e-4f);
        check(mvpOk, "draw: uMvp = projection * view * model");
        check(d.attrib[0] && d.attrib[2] && !d.attrib[1] && !d.attrib[3] && !d.attrib[4], "draw: only position and color arrays are enabled");
        check(d.ptr[0].size == 3 && d.ptr[0].stride == 28 && d.ptr[0].offset == 0 && !d.ptr[0].client && d.ptr[2].size == 4 && d.ptr[2].type == Gl2.GL_UNSIGNED_BYTE
                && d.ptr[2].normalized && d.ptr[2].offset == 24 && d.ptr[2].stride == 28, "draw: attribute layout (3 floats at 0, 4 normalized bytes at 24, stride 28)");
        check(d.arrayBuf != 0 && d.elemBuf != 0 && d.arrayBuf != d.elemBuf, "draw: vertex and index buffers are bound");

        // same features again: no new program, no redundant state calls
        int uses = gl.useProgramCalls, enables0 = gl.enableCalls[0], progs = gl.programsCreated;
        rig.r.drawMesh(m0, model);
        rig.r.drawMesh(m0, model);
        check(gl.programsCreated == progs && gl.useProgramCalls == uses && gl.enableCalls[0] == enables0, "draw: programs are cached, glUseProgram and attribute enables are not repeated");

        rig.r.clear(0f, 0f, 0f);
        check(rig.r.getDrawCalls() == 0 && rig.r.getTriangles() == 0, "counters: clear() resets draw calls and triangles");
        rig.r.drawMesh(m0, model);
        rig.r.drawMesh(m0, model);
        check(rig.r.getDrawCalls() == 2 && rig.r.getTriangles() == 24, "counters: two cube draws = 2 draw calls, 24 triangles");
        rig.r.clear(0f, 0f, 0f);

        // ---------------- lighting
        rig.r.setLighting(true);
        rig.r.setAmbientLight(0.3f, 0.4f, 0.5f);
        rig.r.setDirectionalLight(-0.5f, -1f, -0.4f, 0.9f, 0.8f, 0.7f);
        rig.r.drawMesh(m0, model);
        d = rig.last();
        check(gl.featuresOf(d.program) == 1 && d.attrib[1] && d.ptr[1].size == 3 && d.ptr[1].offset == 12, "lit: LIT program and the normal array");
        float len = (float) Math.sqrt(0.25 + 1 + 0.16);
        float[] ld = gl.uniform(d.program, "uLightDir"), amb = gl.uniform(d.program, "uAmbient"), lc = gl.uniform(d.program, "uLightColor");
        check(near(ld[0], 0.5f / len, 1e-5f) && near(ld[1], 1f / len, 1e-5f) && near(ld[2], 0.4f / len, 1e-5f), "lit: the light direction is normalized and points TOWARD the light");
        check(near(amb[0], 0.3f, 1e-6f) && near(amb[2], 0.5f, 1e-6f) && near(lc[0], 0.9f, 1e-6f) && near(lc[2], 0.7f, 1e-6f), "lit: ambient and light color uniforms");
        float[] nm = gl.uniform(d.program, "uNormalMat");
        Mat4 inv = new Mat4();
        inv.setInverse(model);
        boolean nmOk = nm != null && nm.length == 9;
        // normal matrix = inverse transpose: element (r, c) of the result = inverse(r.c transposed)
        for (int c = 0; nmOk && c < 3; c++) for (int rr = 0; rr < 3; rr++) nmOk &= near(nm[c * 3 + rr], inv.m[rr * 4 + c], 1e-4f);
        check(nmOk, "lit: uNormalMat is the inverse transpose of the model matrix (correct under non-uniform scale)");
        float[] nId = new float[9];
        GLES2Renderer.normalMatrix(new Mat4().rotateY(40f).rotateX(10f), nId);
        Mat4 rot = new Mat4().rotateY(40f).rotateX(10f);
        boolean rotOk = true;
        for (int c = 0; c < 3; c++) for (int rr = 0; rr < 3; rr++) rotOk &= near(nId[c * 3 + rr], rot.m[c * 4 + rr], 1e-5f);
        float[] nSing = new float[9];
        GLES2Renderer.normalMatrix(new Mat4().scale(0f, 1f, 1f), nSing);
        check(rotOk && nSing[0] == 1f && nSing[4] == 1f && nSing[8] == 1f && nSing[1] == 0f, "lit: for a rotation the normal matrix is the rotation, a singular matrix gives identity");
        rig.r.setLighting(false);
        rig.r.drawMesh(m0, model);
        check(gl.featuresOf(rig.last().program) == 0 && !rig.last().attrib[1], "lit: lighting off gives the plain program again, normal array disabled");
        rig.r.setLighting(true);

        // ---------------- textures
        int tDiffuse = rig.r.loadTexture("diffuse.png", true, true);
        int tLight = rig.r.loadTexture("lightmap.png", true, false);
        int tPixel = rig.r.loadTexture("diffuse.png", false, false);
        check(tDiffuse == 0 && tLight == 1 && tPixel == 2 && gl.textures.size() == 3 && rig.images.loaded.size() == 3
                && rig.images.loaded.get(0).uploaded && rig.images.loaded.get(0).recycled, "texture: loaded, uploaded and the CPU image recycled");
        HashMap<Integer, Integer> pr = null;
        int[] ids = new int[3];
        int k = 0;
        for (int t : new java.util.TreeSet<Integer>(gl.textures)) ids[k++] = t;
        HashMap<Integer, Integer> repeatSmooth = gl.texParams.get(ids[0]), clampSmooth = gl.texParams.get(ids[1]), nearest = gl.texParams.get(ids[2]);
        check(repeatSmooth.get(Gl2.GL_TEXTURE_MIN_FILTER) == Gl2.GL_LINEAR_MIPMAP_NEAREST && repeatSmooth.get(Gl2.GL_TEXTURE_WRAP_S) == Gl2.GL_REPEAT
                && repeatSmooth.get(Gl2.GL_TEXTURE_MAG_FILTER) == Gl2.GL_LINEAR && gl.mipmaps == 1, "texture: smooth + repeat = mipmapped, repeating");
        check(clampSmooth.get(Gl2.GL_TEXTURE_MIN_FILTER) == Gl2.GL_LINEAR && clampSmooth.get(Gl2.GL_TEXTURE_WRAP_T) == Gl2.GL_CLAMP_TO_EDGE
                && nearest.get(Gl2.GL_TEXTURE_MIN_FILTER) == Gl2.GL_NEAREST && nearest.get(Gl2.GL_TEXTURE_MAG_FILTER) == Gl2.GL_NEAREST, "texture: smooth + clamp = linear, plain = nearest");
        check(rig.r.getTextureWidth(tLight) == 128 && rig.r.getTextureHeight(tLight) == 128, "texture: size is reported");
        int bad = rig.r.loadTexture("odd.png", false, false);
        int missing = rig.r.loadTexture("nothing.png", false, false);
        check(bad == -1 && missing == -1 && gl.textures.size() == 3 && rig.images.loaded.get(rig.images.loaded.size() - 1).recycled
                && rig.log.errors.size() == 2, "texture: non power of two and missing files are rejected with a log line and no leak");

        int mq = rig.r.createMesh(texturedQuad());
        rig.r.setMeshTextures(mq, tDiffuse, tLight);
        Mat4 id = new Mat4();
        rig.r.drawMesh(mq, id);
        d = rig.last();
        check(gl.featuresOf(d.program) == 6 && d.attrib[3] && d.attrib[4] && !d.attrib[1] && d.unitTex[0] == ids[0] && d.unitTex[1] == ids[1],
                "lightmap mesh: diffuse on unit 0, lightmap on unit 1, TEX0 + LM program, NOT lit although lighting is on");
        check(d.ptr[3].size == 2 && d.ptr[3].offset == 28 && d.ptr[4].size == 2 && d.ptr[4].offset == 36 && d.ptr[0].stride == 44, "lightmap mesh: uv0 at 28, uv1 at 36, stride 44");
        rig.r.setLightmapsEnabled(false);
        rig.r.drawMesh(mq, id);
        d = rig.last();
        check(gl.featuresOf(d.program) == 2 && !d.attrib[4] && !d.attrib[1], "lightmaps off: diffuse texture only, still not lit dynamically");
        rig.r.setLightmapsEnabled(true);
        int mdiff = rig.r.createMesh(texturedQuad());
        rig.r.setMeshTextures(mdiff, tPixel, -1);
        rig.r.drawMesh(mdiff, id);
        d = rig.last();
        check(gl.featuresOf(d.program) == 3 && d.attrib[1] && d.attrib[3] && !d.attrib[4] && d.unitTex[0] == ids[2],
                "diffuse-only mesh with normals: lit + textured (LIT | TEX0)");
        check(gl.programsCreated <= 9, "programs: never more than the 8 mesh variants plus the sprite program (" + gl.programsCreated + ")");

        // ---------------- sprites through the real SpriteBatch
        Mat4 ortho = new Mat4().setOrtho(0, 480, 320, 0, -1, 1);
        SpriteBatch batch = new SpriteBatch(rig.r, 64);
        TextureRegion region = new TextureRegion().set(tPixel, 64, 64, 0, 0, 32, 32);
        batch.begin(ortho);
        batch.setColor(1f, 0.5f, 0.25f, 0.75f);
        batch.draw(region, 10f, 20f, 30f, 40f);
        batch.draw(region, 50f, 60f);
        batch.end();
        d = rig.last();
        check(gl.isSprite(d.program) && d.clientIndices && d.count == 12 && d.type == Gl2.GL_UNSIGNED_SHORT && d.clientIndexPos == 0, "sprites: sprite program, 2 quads = 12 indices from client memory");
        check(!d.depth && !d.cull && d.blend && d.blendSrc == Gl2.GL_SRC_ALPHA && d.blendDst == Gl2.GL_ONE_MINUS_SRC_ALPHA && d.unitTex[0] == ids[2],
                "sprites: depth test and culling off, alpha blending on, texture bound");
        check(d.arrayBuf == 0 && d.elemBuf == 0, "sprites: no buffer object is bound (client arrays)");
        check(d.attrib[0] && d.attrib[2] && d.attrib[3] && !d.attrib[1] && !d.attrib[4], "sprites: position, uv and color arrays only");
        check(d.ptr[0].client && d.ptr[0].size == 2 && d.ptr[0].stride == 32 && d.ptr[0].clientPos == 0 && d.ptr[0].firstFloat == 10f
                && d.ptr[3].size == 2 && d.ptr[3].clientPos == 2 && d.ptr[2].size == 4 && d.ptr[2].type == Gl2.GL_FLOAT && d.ptr[2].clientPos == 4 && d.ptr[2].firstFloat == 1f,
                "sprites: interleaved x,y / u,v / r,g,b,a layout with stride 32");
        float[] sm = gl.uniform(d.program, "uMvp");
        boolean sOk = sm != null;
        for (int i = 0; sOk && i < 16; i++) sOk = near(sm[i], ortho.m[i], 1e-5f);
        check(sOk, "sprites: uMvp is the projection passed to SpriteBatch.begin");
        check(gl.caps.contains(Gl2.GL_DEPTH_TEST) && gl.caps.contains(Gl2.GL_CULL_FACE) && !gl.caps.contains(Gl2.GL_BLEND), "sprites: the state expected by drawMesh is restored");
        rig.r.setCamera(proj, view);
        rig.r.drawMesh(m0, model);
        d = rig.last();
        check(d.attrib[0] && d.attrib[2] && d.attrib[1] && !d.attrib[3] && d.arrayBuf != 0 && d.elemBuf != 0 && !d.clientIndices && d.depth && d.cull && !d.blend && d.ptr[0].size == 3 && !d.ptr[0].client,
                "mixing: a mesh drawn after sprites sets everything up again (3D attributes, buffers, state)");

        // ---------------- delete / reuse
        int meshesBefore = gl.buffers.size();
        rig.r.deleteMesh(mdiff);
        check(gl.buffers.size() == meshesBefore - 2, "mesh: deleting frees both buffers");
        int again = rig.r.createMesh(Shapes.cube(2f));
        check(again == mdiff, "mesh: a freed slot is reused");
        rig.r.deleteTexture(tPixel);
        check(gl.textures.size() == 2 && rig.r.loadTexture("diffuse.png", false, false) == tPixel, "texture: delete frees the GL texture, the slot is reused");

        // ---------------- context loss
        int buffersAlive = gl.buffers.size(), texAlive = gl.textures.size();
        int loadsBefore = rig.images.loaded.size();
        gl.loseContext();
        rig.r.onSurfaceCreated();
        check(gl.buffers.size() == buffersAlive && gl.textures.size() == texAlive && texAlive == 3, "context loss: buffers and textures are recreated (" + gl.buffers.size() + " buffers, " + gl.textures.size() + " textures)");
        check(rig.images.loaded.size() == loadsBefore + 3, "context loss: every texture is decoded and uploaded again");
        gl.draws.clear();
        rig.r.setCamera(proj, view);
        rig.r.drawMesh(mq, id);
        d = rig.last();
        boolean newIds = gl.textures.contains(d.unitTex[0]) && gl.textures.contains(d.unitTex[1]) && gl.buffers.contains(d.arrayBuf) && gl.programs.contains(d.program);
        check(newIds && gl.featuresOf(d.program) == 6, "context loss: after it a lightmapped mesh draws with the NEW buffer, texture and program ids");
        rig.r.drawMesh(m0, model);
        check(gl.featuresOf(rig.last().program) == 1, "context loss: programs are rebuilt on demand");

        // ---------------- fog
        Rig fg = new Rig().start();
        FakeGl fgl = fg.gl;
        int fCube = fg.r.createMesh(Shapes.cube(1f));
        int fTexD = fg.r.loadTexture("diffuse.png", true, true), fTexL = fg.r.loadTexture("lightmap.png", true, false);
        int fLm = fg.r.createMesh(texturedQuad());
        fg.r.setMeshTextures(fLm, fTexD, fTexL);
        Mat4 fm = new Mat4();
        fg.r.setCamera(proj, view);
        fg.r.drawMesh(fCube, fm);
        check(fgl.featuresOf(fg.last().program) == 0, "fog: off by default, the plain program is used");
        fg.r.setFog(true, 10f, 50f, 0.2f, 0.4f, 0.6f);
        fg.r.drawMesh(fCube, fm);
        int fp = fg.last().program;
        check(fgl.featuresOf(fp) == GLES2Shaders.FOG, "fog: on -> the FOG program variant");
        float[] fu = fgl.uniform(fp, "uFog"), fc = fgl.uniform(fp, "uFogColor");
        check(fu != null && fc != null && near(fc[0], 0.2f, 1e-6f) && near(fc[1], 0.4f, 1e-6f) && near(fc[2], 0.6f, 1e-6f), "fog: the fog color reaches the shader");
        // the shader computes f = w * scale + offset: 1 at start, 0 at end, 0.5 in the middle, linear in between
        check(fu != null && near(10f * fu[0] + fu[1], 1f, 1e-5f) && near(50f * fu[0] + fu[1], 0f, 1e-5f) && near(30f * fu[0] + fu[1], 0.5f, 1e-5f),
                "fog: scale/offset give f = 1 at start, 0 at end, 0.5 halfway");
        String fv = GLES2Shaders.meshVertex(GLES2Shaders.FOG), ff = GLES2Shaders.meshFragment(GLES2Shaders.FOG);
        check(fv.contains("gl_Position.w") && ff.contains("mix(uFogColor") && ff.contains("clamp(vFog"), "fog: depth from clip w in the vertex shader, clamp + mix in the fragment shader");
        check(!GLES2Shaders.meshVertex(0).contains("#define FOG") && !GLES2Shaders.meshFragment(7).contains("#define FOG") && GLES2Shaders.meshFragment(8).contains("#define FOG")
                && !GLES2Shaders.spriteVertex().contains("Fog") && !GLES2Shaders.spriteFragment().contains("Fog"),
                "fog: FOG is a compile time #define: programs without it get no fog code, sprites never");
        int sent = fgl.uniform3fCalls;
        fg.r.drawMesh(fCube, fm);
        fg.r.drawMesh(fCube, fm);
        check(fgl.uniform3fCalls == sent, "fog: unchanged fog is not uploaded again");
        fg.r.setFog(true, 20f, 60f, 1f, 1f, 1f);
        fg.r.drawMesh(fCube, fm);
        fu = fgl.uniform(fp, "uFog");
        check(fgl.uniform3fCalls == sent + 2 && near(20f * fu[0] + fu[1], 1f, 1e-5f) && near(60f * fu[0] + fu[1], 0f, 1e-5f) && near(fgl.uniform(fp, "uFogColor")[0], 1f, 1e-6f),
                "fog: changing the settings uploads the new values once");
        fg.r.setLighting(true);
        fg.r.drawMesh(fCube, fm);
        int litFog = fg.last().program;
        check(fgl.featuresOf(litFog) == (GLES2Shaders.LIT | GLES2Shaders.FOG) && fgl.uniform(litFog, "uFog") != null && near(fgl.uniform(litFog, "uFog")[0], fu[0], 1e-7f),
                "fog: combines with lighting (LIT | FOG), a program built later also gets the current fog");
        fg.r.drawMesh(fLm, fm);
        check(fgl.featuresOf(fg.last().program) == (GLES2Shaders.TEX0 | GLES2Shaders.LM | GLES2Shaders.FOG), "fog: combines with diffuse texture + lightmap (TEX0 | LM | FOG)");
        fg.r.setLighting(false);
        Mat4 fortho = new Mat4().setOrtho(0, 480, 320, 0, -1, 1);
        SpriteBatch fbatch = new SpriteBatch(fg.r, 16);
        fbatch.begin(fortho);
        fbatch.draw(new TextureRegion().set(fTexD, 64, 64, 0, 0, 8, 8), 1f, 2f);
        fbatch.end();
        check(fgl.isSprite(fg.last().program) && fgl.featuresOf(fg.last().program) == 0, "fog: sprites are never fogged (the sprite program has no fog)");
        fg.r.setCamera(proj, view);
        fg.r.drawMesh(fCube, fm);
        check(fgl.featuresOf(fg.last().program) == GLES2Shaders.FOG, "fog: meshes drawn after sprites are fogged again");
        fg.r.setFog(false, 20f, 60f, 1f, 1f, 1f);
        fg.r.drawMesh(fCube, fm);
        check(fgl.featuresOf(fg.last().program) == 0, "fog: setFog(false) goes back to the plain program");
        fg.r.setFog(true, 30f, 30f, 1f, 1f, 1f);
        fg.r.drawMesh(fCube, fm);
        boolean degenerate = fgl.featuresOf(fg.last().program) == 0;
        fg.r.setFog(true, 40f, 10f, 1f, 1f, 1f);
        fg.r.drawMesh(fCube, fm);
        check(degenerate && fgl.featuresOf(fg.last().program) == 0, "fog: end <= start is rejected and means no fog (no division by zero)");
        fg.r.setFog(true, 5f, 25f, 0.1f, 0.2f, 0.3f);
        fgl.loseContext();
        fg.r.onSurfaceCreated();
        fg.r.drawMesh(fCube, fm);
        float[] fu2 = fgl.uniform(fg.last().program, "uFog");
        check(fgl.featuresOf(fg.last().program) == GLES2Shaders.FOG && fu2 != null && near(5f * fu2[0] + fu2[1], 1f, 1e-5f) && near(25f * fu2[0] + fu2[1], 0f, 1e-5f)
                && near(fgl.uniform(fg.last().program, "uFogColor")[2], 0.3f, 1e-6f), "fog: survives a lost GL context (new programs get the settings again)");
        check(fg.failures.isEmpty() && fg.log.errors.isEmpty(), "fog: no failure or error was reported");

        // ---------------- sky
        Rig sg = new Rig().start();
        FakeGl sgl = sg.gl;
        MeshData domeData = Sky.dome(50f, 12, 6, 0.1f, 0.2f, 0.9f, 0.8f, 0.85f, 0.9f, 0.3f, 0.3f, 0.3f);
        int skyMesh = sg.r.createMesh(domeData);
        int sCube = sg.r.createMesh(Shapes.cube(1f));
        Mat4 skModel = new Mat4();
        sg.r.setLighting(true);
        sg.r.setFog(true, 10f, 50f, 0.5f, 0.5f, 0.5f);
        sg.r.setCamera(proj, view);
        sg.r.drawSky(skyMesh);
        Draw sd = sg.last();
        check(sgl.featuresOf(sd.program) == 0 && sd.count == domeData.getIndexCount() && sd.type == Gl2.GL_UNSIGNED_SHORT && !sd.clientIndices,
                "sky: drawn with the plain program (no light, no fog) even while lighting and fog are on");
        check(!sd.depth && !sd.cull && !sd.blend, "sky: depth test and culling are off while it is drawn");
        Mat4 rotOnly = new Mat4().set(view);
        rotOnly.m[12] = 0f; rotOnly.m[13] = 0f; rotOnly.m[14] = 0f;
        Mat4 skyExpected = new Mat4().multiply(proj, rotOnly);
        float[] gotMvp = sgl.uniform(sd.program, "uMvp");
        boolean skyMvpOk = gotMvp != null;
        for (int i = 0; skyMvpOk && i < 16; i++) skyMvpOk &= near(gotMvp[i], skyExpected.m[i], 1e-5f);
        check(skyMvpOk, "sky: matrix = projection * view without the translation (the sky follows the camera)");
        check(sgl.caps.contains(Gl2.GL_DEPTH_TEST) && sgl.caps.contains(Gl2.GL_CULL_FACE), "sky: depth test and culling are on again afterwards");
        sg.r.drawMesh(sCube, skModel);
        Draw cd = sg.last();
        Mat4 normalMvp = new Mat4().multiply(proj, view);
        float[] gotCube = sgl.uniform(cd.program, "uMvp");
        boolean cubeOk = gotCube != null;
        for (int i = 0; cubeOk && i < 16; i++) cubeOk &= near(gotCube[i], normalMvp.m[i], 1e-5f);
        check(sgl.featuresOf(cd.program) == (GLES2Shaders.LIT | GLES2Shaders.FOG) && cd.depth && cd.cull && cubeOk,
                "sky: the next mesh is drawn lit, fogged, depth tested, with the full camera again");
        check(sg.r.getDrawCalls() == 2 && sg.r.getTriangles() == domeData.getIndexCount() / 3 + 12, "sky: counted as one draw call with its triangles");
        sg.r.drawSky(-1);
        sg.r.drawSky(63);
        check(sg.r.getDrawCalls() == 2 && sg.failures.isEmpty() && sg.log.errors.isEmpty(), "sky: invalid mesh handles are ignored");
        sg.r.deleteMesh(skyMesh);
        sg.r.drawSky(skyMesh);
        check(sg.r.getDrawCalls() == 2, "sky: a deleted mesh is not drawn");

        // ---------------- billboards
        {
            Rig bg = new Rig().start();
            FakeGl bgl = bg.gl;
            int bTex = bg.r.loadTexture("diffuse.png", true, false);
            bg.r.setLighting(true);
            bg.r.setCamera(proj, view);
            float[] bd = new float[2 * Renderer.BILLBOARD_FLOATS];
            float[] rec = {1f, 2f, 3f, 2f, 4f, 0.25f, 0.5f, 0.75f, 1f, 0.1f, 0.2f, 0.3f, 0.4f};
            System.arraycopy(rec, 0, bd, 0, 13);
            System.arraycopy(rec, 0, bd, 13, 13);
            bd[13] = 5f;
            bg.r.drawBillboards(bTex, bd, 2, false);
            Draw bdw = bg.last();
            check(bgl.featuresOf(bdw.program) == GLES2Shaders.TEX0 && !bgl.isSprite(bdw.program) && bdw.count == 12 && bdw.clientIndices && bdw.type == Gl2.GL_UNSIGNED_SHORT,
                    "billboards: the textured mesh program (no light even while lighting is on), 2 billboards = 12 client indices");
            check(bdw.depth && !bdw.cull && bdw.blend && !bdw.depthMask && bdw.blendSrc == Gl2.GL_SRC_ALPHA && bdw.blendDst == Gl2.GL_ONE_MINUS_SRC_ALPHA,
                    "billboards: depth test on, depth WRITE off, no culling, alpha blending");
            check(bdw.ptr[0].client && bdw.ptr[0].size == 3 && bdw.ptr[0].stride == 36 && bdw.ptr[3].size == 2 && bdw.ptr[3].stride == 36
                    && bdw.ptr[2].size == 4 && bdw.ptr[2].type == Gl2.GL_FLOAT && !bdw.ptr[2].normalized && !bdw.attrib[1] && !bdw.attrib[4] && bdw.attrib[0] && bdw.attrib[2] && bdw.attrib[3],
                    "billboards: client arrays position(3) uv(2) color(4 floats), stride 36, no normals");
            float expX = 1f - view.m[0] * 1f + view.m[1] * 2f;   // center - right * half width + up * half height
            check(near(bdw.ptr[0].firstFloat, expX, 1e-4f) && near(bdw.ptr[3].firstFloat, 0.25f, 1e-6f) && near(bdw.ptr[2].firstFloat, 0.1f, 1e-6f),
                    "billboards: first vertex is the top-left corner facing the camera, with its uv and tint");
            Mat4 bvp = new Mat4().multiply(proj, view);
            float[] bm = bgl.uniform(bdw.program, "uMvp");
            boolean bmOk = bm != null;
            for (int i = 0; bmOk && i < 16; i++) bmOk &= near(bm[i], bvp.m[i], 1e-5f);
            check(bmOk, "billboards: the corners are in world space, so the matrix is projection * view");
            check(bgl.depthMaskOn && !bgl.caps.contains(Gl2.GL_BLEND) && bgl.caps.contains(Gl2.GL_CULL_FACE) && bgl.caps.contains(Gl2.GL_DEPTH_TEST),
                    "billboards: depth write, culling and blend state are restored afterwards");
            bg.r.drawBillboards(bTex, bd, 2, true);
            check(bg.last().blendSrc == Gl2.GL_SRC_ALPHA && bg.last().blendDst == Gl2.GL_ONE && !bg.last().depthMask, "billboards: additive = GL_SRC_ALPHA, GL_ONE");
            check(bg.r.getDrawCalls() == 2 && bg.r.getTriangles() == 8, "billboards: 1 draw call each, 2 triangles per billboard");
            float[] many = new float[600 * Renderer.BILLBOARD_FLOATS];
            int nd = bgl.draws.size();
            bg.r.drawBillboards(bTex, many, 600, false);
            check(bgl.draws.size() == nd + 1 && bg.last().count == Renderer.MAX_BILLBOARDS * 6, "billboards: more than MAX_BILLBOARDS are cut off");
            bg.r.drawBillboards(-1, bd, 2, false);
            bg.r.drawBillboards(bTex, bd, 0, false);
            bg.r.drawBillboards(31, bd, 2, false);
            check(bgl.draws.size() == nd + 1, "billboards: an invalid texture or a count of 0 draws nothing");
            // fog: alpha billboards fade to the fog color, additive ones to black
            bg.r.setLighting(false);
            bg.r.setFog(true, 10f, 50f, 0.5f, 0.6f, 0.7f);
            bg.r.drawBillboards(bTex, bd, 2, false);
            int bp = bg.last().program;
            float[] bfc = bgl.uniform(bp, "uFogColor");
            check(bgl.featuresOf(bp) == (GLES2Shaders.TEX0 | GLES2Shaders.FOG) && bfc != null && near(bfc[0], 0.5f, 1e-6f) && near(bfc[2], 0.7f, 1e-6f),
                    "billboards: with fog on, alpha billboards use the fog program and fade to the fog color");
            bg.r.drawBillboards(bTex, bd, 2, true);
            bfc = bgl.uniform(bp, "uFogColor");
            check(bfc[0] == 0f && bfc[1] == 0f && bfc[2] == 0f, "billboards: additive billboards fade to BLACK");
            int bmesh = bg.r.createMesh(texturedQuad());
            bg.r.setMeshTextures(bmesh, bTex, -1);
            bg.r.drawMesh(bmesh, new Mat4());
            bfc = bgl.uniform(bg.last().program, "uFogColor");
            check(bg.last().program == bp && bfc[0] == 0.5f && bfc[1] == 0.6f && bfc[2] == 0.7f, "billboards: the next mesh with the same program gets the real fog color back");
            check(bg.failures.isEmpty() && bg.log.errors.isEmpty(), "billboards: no failure or error was reported");
        }

        // ---------------- engine integration
        Rig r2 = new Rig().start();
        final int[] calls = new int[2];
        r2.engine.setScreen(new ScreenAdapter() {
            public void update() { calls[0]++; }
            public void render(Renderer rr) { calls[1]++; rr.clear(0f, 0f, 0f); }
        });
        r2.r.onDrawFrame();
        r2.r.onDrawFrame();
        check(calls[1] == 2 && r2.gl.clears == 2, "frame: onDrawFrame runs the engine frame and the screen draws through the renderer");

        // ---------------- failures
        Rig f1 = new Rig();
        f1.gl.failCompileContaining = "gl_Position";
        f1.r.onSurfaceCreated();
        check(f1.r.hasFailed() && f1.failures.size() == 1 && f1.failures.get(0).contains("FAKE_COMPILE_LOG") && f1.failures.get(0).contains("does not compile"),
                "failure: a vertex shader that does not compile is reported once with the compiler log");
        check(f1.gl.shaders.isEmpty() && f1.gl.programs.isEmpty(), "failure: shader and program objects are cleaned up");
        int mf = f1.r.createMesh(texturedQuad());
        int tf = f1.r.loadTexture("diffuse.png", true, true);
        f1.r.setCamera(proj, view);
        f1.r.drawMesh(mf, id);
        f1.r.clear(0f, 0f, 0f);
        batch = new SpriteBatch(f1.r, 8);
        batch.begin(ortho); batch.draw(new TextureRegion().set(0, 64, 64, 0, 0, 8, 8), 0, 0); batch.end();
        f1.r.onDrawFrame();
        check(mf >= 0 && tf >= 0 && f1.gl.draws.isEmpty() && f1.gl.buffers.isEmpty() && f1.gl.textures.isEmpty() && f1.failures.size() == 1,
                "failure: afterwards nothing throws, nothing is drawn and no GL object is created (screens can keep running until the app switches backend)");
        f1.r.deleteMesh(mf);
        f1.r.deleteTexture(tf);
        f1.gl.failCompileContaining = null;
        f1.r.onSurfaceCreated();
        check(!f1.r.hasFailed() && f1.gl.programs.size() == 2, "failure: a new GL context gives the backend another chance");

        Rig f2 = new Rig();
        f2.gl.failLink = true;
        f2.r.onSurfaceCreated();
        check(f2.r.hasFailed() && f2.failures.size() == 1 && f2.failures.get(0).contains("FAKE_LINK_LOG") && f2.gl.programs.isEmpty() && f2.gl.shaders.isEmpty(),
                "failure: a program that does not link is reported with the linker log and cleaned up");
        Rig f3 = new Rig();
        f3.gl.failCreateShader = true;
        f3.r.onSurfaceCreated();
        check(f3.r.hasFailed() && f3.failures.size() == 1 && f3.failures.get(0).contains("glCreateShader"), "failure: glCreateShader returning 0 is reported");
        Rig f4 = new Rig().start();
        f4.gl.failCompileContaining = "#define LM";
        int q4 = f4.r.createMesh(texturedQuad());
        int a4 = f4.r.loadTexture("diffuse.png", true, true), b4 = f4.r.loadTexture("lightmap.png", true, false);
        f4.r.setMeshTextures(q4, a4, b4);
        f4.r.setCamera(proj, view);
        f4.r.drawMesh(q4, id);
        f4.r.drawMesh(q4, id);
        check(f4.r.hasFailed() && f4.failures.size() == 1 && f4.failures.get(0).contains("does not compile") && f4.gl.draws.isEmpty(),
                "failure: a variant that only fails when first used (lightmap shader) is reported once and stops drawing");

        // ---------------- zero allocation
        Engine qe = new Engine();
        FakeImages qimg = new FakeImages();
        qimg.files.put("diffuse.png", new int[] {64, 64});
        qimg.files.put("lightmap.png", new int[] {64, 64});
        GLES2Renderer qr = new GLES2Renderer(qe, new QuietGl(), qimg, new TestLog(), null);
        qr.onSurfaceCreated();
        int c0 = qr.createMesh(Shapes.cube(1f));
        int c1 = qr.createMesh(texturedQuad());
        int tex0 = qr.loadTexture("diffuse.png", true, true), tex1 = qr.loadTexture("lightmap.png", true, false);
        qr.setMeshTextures(c1, tex0, tex1);
        qr.setLighting(true);
        SpriteBatch qb = new SpriteBatch(qr, 128);
        float[] bbq = new float[64 * Renderer.BILLBOARD_FLOATS];
        TextureRegion qreg = new TextureRegion().set(tex0, 64, 64, 0, 0, 16, 16);
        Mat4 qm = new Mat4().translate(1, 2, 3).rotateY(20f).scale(2, 1, 3);
        for (int i = 0; i < 20000; i++) {
            qr.setCamera(proj, view); qr.setAmbientLight(0.3f, 0.3f, 0.3f); qr.setDirectionalLight(-0.5f, -1f, -0.4f, 1f, 1f, 1f);
            qr.setFog(true, 10f, 50f, 0.5f, 0.6f, 0.7f);
            qr.drawSky(c0); qr.drawMesh(c0, qm); qr.drawMesh(c1, qm); qr.drawBillboards(tex0, bbq, 64, (i & 1) == 0);
            qb.begin(ortho); for (int s = 0; s < 10; s++) qb.draw(qreg, s * 3f, 4f); qb.end();
        }
        long b0 = allocated();
        for (int i = 0; i < 100000; i++) {
            qr.setCamera(proj, view); qr.setAmbientLight(0.3f, 0.3f, 0.3f); qr.setDirectionalLight(-0.5f, -1f, -0.4f, 1f, 1f, 1f);
            qr.setFog(true, 10f, 50f, 0.5f, 0.6f, 0.7f);
            qr.drawSky(c0); qr.drawMesh(c0, qm); qr.drawMesh(c1, qm); qr.drawBillboards(tex0, bbq, 64, (i & 1) == 0);
            qb.begin(ortho); for (int s = 0; s < 10; s++) qb.draw(qreg, s * 3f, 4f); qb.end();
        }
        long delta = allocated() - b0;
        check(delta < 4096, "zero allocation: 100000 frames of mesh, lit, lightmapped, fogged, sky, billboard and sprite drawing allocated " + delta + " bytes");

        System.out.println(failed == 0 ? "ALL GLES2 TESTS PASSED" : (failed + " GLES2 TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
