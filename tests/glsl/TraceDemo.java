// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.android.*;
import skarph.core.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import javax.imageio.ImageIO;

/**
 * Records every OpenGL ES 2.0 call that GLES2Renderer makes while the real backends demo runs (real assets),
 * into a binary trace. tests/glsl/replay.c plays the trace back on a real GL implementation (Mesa) and
 * reports GL errors and saves frames.   usage: TraceDemo <assets dir> <trace file> <frames>
 */
public class TraceDemo {
    /** Gl2 that writes a trace instead of drawing. All numbers are little endian. */
    static final class TraceGl2 implements Gl2 {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int nextShader = 1, nextProgram = 1, nextBuffer = 1, nextTexture = 1;
        int currentProgram;
        final HashMap<Integer, HashMap<String, Integer>> locs = new HashMap<Integer, HashMap<String, Integer>>();
        final Buffer[] clientBuf = new Buffer[8];
        final int[] clientPos = new int[8], clientStride = new int[8], clientSize = new int[8], clientType = new int[8];
        final boolean[] clientNorm = new boolean[8], enabled = new boolean[8];
        int arrayBuf, elemBuf, ops;

        void i(int v) { out.write(v); out.write(v >> 8); out.write(v >> 16); out.write(v >> 24); }
        void f(float v) { i(Float.floatToIntBits(v)); }
        void op(int code) { ops++; i(code); }
        void str(String s) { byte[] b = s.getBytes(); i(b.length); out.write(b, 0, b.length); }
        void bytes(byte[] b) { i(b.length); out.write(b, 0, b.length); }

        public int createShader(int type) { int id = nextShader++; op(1); i(type); i(id); return id; }
        public void shaderSource(int sh, String src) { op(2); i(sh); str(src); }
        public void compileShader(int sh) { op(3); i(sh); }
        public int getShaderi(int sh, int pname) { return 1; }
        public String getShaderInfoLog(int sh) { return ""; }
        public void deleteShader(int sh) { op(4); i(sh); }
        public int createProgram() { int id = nextProgram++; op(5); i(id); locs.put(id, new HashMap<String, Integer>()); return id; }
        public void attachShader(int p, int sh) { op(6); i(p); i(sh); }
        public void bindAttribLocation(int p, int idx, String name) { op(7); i(p); i(idx); str(name); }
        public void linkProgram(int p) { op(8); i(p); }
        public int getProgrami(int p, int pname) { return 1; }
        public String getProgramInfoLog(int p) { return ""; }
        public void useProgram(int p) { currentProgram = p; op(9); i(p); }
        public void deleteProgram(int p) { op(10); i(p); }
        public int getUniformLocation(int p, String name) {
            HashMap<String, Integer> m = locs.get(p);
            if (!m.containsKey(name)) m.put(name, m.size() + 1);
            int loc = m.get(name);
            op(11); i(p); str(name); i(loc);
            return loc;
        }
        public void uniform1i(int loc, int v) { op(12); i(currentProgram); i(loc); i(v); }
        public void uniform3f(int loc, float x, float y, float z) { op(13); i(currentProgram); i(loc); f(x); f(y); f(z); }
        public void uniformMatrix3fv(int loc, float[] v) { op(14); i(currentProgram); i(loc); for (int k = 0; k < 9; k++) f(v[k]); }
        public void uniformMatrix4fv(int loc, float[] v) { op(15); i(currentProgram); i(loc); for (int k = 0; k < 16; k++) f(v[k]); }
        public int genBuffer() { int id = nextBuffer++; op(16); i(id); return id; }
        public void bindBuffer(int target, int b) { if (target == GL_ARRAY_BUFFER) arrayBuf = b; else elemBuf = b; op(17); i(target); i(b); }
        public void bufferData(int target, int size, Buffer data, int usage) {
            byte[] raw = new byte[size];
            if (data instanceof ByteBuffer) { ByteBuffer b = ((ByteBuffer) data).duplicate(); b.position(0); b.get(raw); }
            else { ShortBuffer sb = ((ShortBuffer) data).duplicate(); sb.position(0); ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN); for (int k = 0; k < size / 2; k++) bb.putShort(sb.get()); }
            op(18); i(target); i(usage); bytes(raw);
        }
        public void deleteBuffer(int b) { op(19); i(b); }
        public int genTexture() { int id = nextTexture++; op(20); i(id); return id; }
        public void bindTexture(int target, int t) { op(21); i(target); i(t); }
        public void deleteTexture(int t) { op(22); i(t); }
        public void activeTexture(int unit) { op(23); i(unit); }
        public void texParameteri(int target, int pname, int v) { op(24); i(target); i(pname); i(v); }
        public void generateMipmap(int target) { op(25); i(target); }
        void texImage(int w, int h, byte[] rgba) { op(26); i(w); i(h); bytes(rgba); }
        public void enableVertexAttribArray(int idx) { enabled[idx] = true; op(27); i(idx); }
        public void disableVertexAttribArray(int idx) { enabled[idx] = false; op(28); i(idx); }
        public void vertexAttribPointer(int idx, int size, int type, boolean norm, int stride, int offset) {
            clientBuf[idx] = null; op(29); i(idx); i(size); i(type); i(norm ? 1 : 0); i(stride); i(offset);
        }
        public void vertexAttribPointer(int idx, int size, int type, boolean norm, int stride, Buffer data) {
            clientBuf[idx] = data; clientPos[idx] = data.position(); clientStride[idx] = stride; clientSize[idx] = size; clientType[idx] = type; clientNorm[idx] = norm;
            op(30); i(idx); i(size); i(type); i(norm ? 1 : 0); i(stride);
        }
        public void drawElements(int mode, int count, int type, int offset) { op(31); i(mode); i(count); i(type); i(offset); }
        public void drawElements(int mode, int count, int type, Buffer indices) {
            ShortBuffer sb = (ShortBuffer) indices;
            int pos = sb.position(), max = 0;
            ByteBuffer ib = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int k = 0; k < count; k++) { short v = sb.get(pos + k); ib.putShort(v); max = Math.max(max, v & 0xFFFF); }
            op(32); i(mode); i(count); i(type); bytes(ib.array());
            int n = 0;
            for (int a = 0; a < 8; a++) if (enabled[a] && clientBuf[a] != null) n++;
            i(n);
            for (int a = 0; a < 8; a++) {
                if (!(enabled[a] && clientBuf[a] != null)) continue;
                FloatBuffer fb = (FloatBuffer) clientBuf[a];
                int floats = (max + 1) * clientStride[a] / 4;
                ByteBuffer data = ByteBuffer.allocate(floats * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int k = 0; k < floats; k++) data.putFloat(fb.get(clientPos[a] + k));
                i(a); bytes(data.array());
            }
        }
        public void enable(int cap) { op(33); i(cap); }
        public void disable(int cap) { op(34); i(cap); }
        public void blendFunc(int s, int d) { op(35); i(s); i(d); }
        public void depthMask(boolean w) { op(42); i(w ? 1 : 0); }
        public void frontFace(int m) { op(36); i(m); }
        public void cullFace(int m) { op(37); i(m); }
        public void viewport(int x, int y, int w, int h) { op(38); i(x); i(y); i(w); i(h); }
        public void clearColor(float r, float g, float b, float a) { op(39); f(r); f(g); f(b); f(a); }
        public void clear(int mask) { op(40); i(mask); }
        public int getError() { return 0; }
        public String getString(int name) { return "trace"; }
        void frameEnd() { op(41); }
    }

    static final class PngImages implements GlImageLoader {
        final File dir; final TraceGl2 trace;
        PngImages(String dir, TraceGl2 trace) { this.dir = new File(dir); this.trace = trace; }
        public GlImage load(String path) {
            try {
                final BufferedImage bi = ImageIO.read(new File(dir, path));
                return new GlImage() {
                    public int getWidth() { return bi.getWidth(); }
                    public int getHeight() { return bi.getHeight(); }
                    public void upload() {
                        byte[] px = new byte[bi.getWidth() * bi.getHeight() * 4];
                        int k = 0;
                        for (int y = 0; y < bi.getHeight(); y++) for (int x = 0; x < bi.getWidth(); x++) {
                            int argb = bi.getRGB(x, y);
                            px[k++] = (byte) (argb >> 16); px[k++] = (byte) (argb >> 8); px[k++] = (byte) argb; px[k++] = (byte) (argb >>> 24);
                        }
                        trace.texImage(bi.getWidth(), bi.getHeight(), px);
                    }
                    public void recycle() { }
                };
            } catch (Exception e) { return null; }
        }
    }

    public static void main(String[] args) throws Exception {
        String assets = args[0];
        int frames = Integer.parseInt(args[2]);
        Engine engine = new Engine();
        engine.setAssets(new ContentTest.FileAssets(assets));
        TraceGl2 gl = new TraceGl2();
        GlLog log = new GlLog() {
            public void info(String m) { }
            public void warn(String m) { System.out.println("warn: " + m); }
            public void error(String m) { System.out.println("ERROR: " + m); }
        };
        final boolean[] failed = {false};
        GLES2Renderer r = new GLES2Renderer(engine, gl, new PngImages(assets, gl), log, new GLES2Renderer.FailureListener() {
            public void onFailure(String reason) { failed[0] = true; System.out.println("FAILURE: " + reason); }
        });
        engine.setScreen(new skarph.demo.v07.BackendDemoScreen(new skarph.demo.v07.BackendSwitcher() {
            public boolean isGles2Available() { return true; }
            public void switchBackend(int b) { }
        }));
        r.onSurfaceCreated();
        r.onSurfaceChanged(800, 480);
        long t = 1000000000L;
        for (int f = 0; f < frames; f++) {
            t += 16666667L;
            engine.frame(t, r);
            gl.frameEnd();
        }
        FileOutputStream fos = new FileOutputStream(args[1]);
        gl.out.writeTo(fos);
        fos.close();
        System.out.println("trace: " + gl.ops + " GL calls, " + gl.out.size() / 1024 + " KB, " + frames + " frames, failed=" + failed[0]);
    }
}
