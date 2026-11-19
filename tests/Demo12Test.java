// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import java.nio.file.*;
import skarph.core.*;
import skarph.demo.v12.Demo12Screen;

/** Runs the 1.2 demo screen headless: scripted touches, many frames, checks that it draws, moves and allocates nothing. */
public class Demo12Test {
    static int failed;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) failed++; }
    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    static final class Rec implements Renderer {
        int meshes, textures, meshDraws, quads, lineCalls, billboards;
        public String getBackendName() { return "t"; }
        public int getWidth() { return 480; }
        public int getHeight() { return 320; }
        public int getDrawCalls() { return meshDraws; }
        public int getTriangles() { return 0; }
        public void setFog(boolean e, float s, float en, float r, float g, float b) { }
        public void drawSky(int m) { }
        public void drawBillboards(int t, float[] d, int n, boolean a) { billboards += n; }
        public int createMesh(MeshData d) { return meshes++; }
        public int loadTexture(String p, boolean s) { return textures++; }
        public int loadTexture(String p, boolean s, boolean r) { return textures++; }
        public void deleteTexture(int t) { }
        public void deleteMesh(int m) { }
        public void setMeshTextures(int m, int d, int l) { }
        public boolean supportsLightmaps() { return false; }
        public void setLightmapsEnabled(boolean e) { }
        public int getTextureWidth(int t) { return t == 2 ? 512 : (t == 1 ? 32 : 16); }
        public int getTextureHeight(int t) { return t == 2 ? 256 : (t == 1 ? 32 : 16); }
        public boolean usesBufferObjects() { return false; }
        public void setLighting(boolean on) { }
        public void setAmbientLight(float r, float g, float b) { }
        public void setDirectionalLight(float dx, float dy, float dz, float r, float g, float b) { }
        public void clear(float r, float g, float b) { }
        public void setCamera(Mat4 p, Mat4 v) { }
        public void drawMesh(int m, Mat4 model) { meshDraws++; }
        public void drawQuads(int tex, float[] v, int n) { quads += n; }
        public void drawLines(float[] d, int n) { lineCalls++; }
        public void setScissor(boolean e, int x, int y, int w, int h) { }
    }

    static void touch(Engine e, int type, float x, float y) { e.input.push(type, 0, x, y); }

    public static void main(String[] a) throws Exception {
        final Path assets = Paths.get("demos/demo-v1.2/assets");
        Engine eng = new Engine();
        eng.setAssets(new Assets() {
            public byte[] readBytes(String p) {
                try { return Files.readAllBytes(assets.resolve(p)); } catch (Exception ex) { return null; }
            }
        });
        Rec r = new Rec();
        Demo12Screen s = new Demo12Screen();
        eng.setScreen(s);
        s.init(eng, r);
        check(r.meshes >= 3 && r.textures == 3, "demo: meshes and textures created (" + r.meshes + ", " + r.textures + ")");
        long t = 1_000_000_000L;
        for (int i = 0; i < 120; i++) { eng.frame(t, r); t += 16_666_667L; }
        check(r.meshDraws > 20, "demo: draws many meshes (" + r.meshDraws + ")");
        check(r.quads > 20, "demo: draws text and Ui (" + r.quads + " quads)");
        check(r.lineCalls > 0, "demo: grid lines drawn");
        // walk: drag left half upward for 2 seconds, then jump button, crate button
        touch(eng, InputEvent.DOWN, 100f, 250f);
        for (int i = 0; i < 120; i++) {
            touch(eng, InputEvent.MOVE, 100f, 250f - Math.min(i * 3, 70));
            eng.frame(t, r); t += 16_666_667L;
        }
        touch(eng, InputEvent.UP, 100f, 180f);
        // camera drag on the right half
        touch(eng, InputEvent.DOWN, 300f, 100f);
        for (int i = 0; i < 30; i++) { touch(eng, InputEvent.MOVE, 300f + i * 3, 100f); eng.frame(t, r); t += 16_666_667L; }
        touch(eng, InputEvent.UP, 390f, 100f);
        // crate button (bottom right) and jump button
        for (int k = 0; k < 3; k++) {
            touch(eng, InputEvent.DOWN, 480f - 160f, 320f - 34f); eng.frame(t, r); t += 16_666_667L;
            touch(eng, InputEvent.UP, 480f - 160f, 320f - 34f); eng.frame(t, r); t += 16_666_667L;
        }
        touch(eng, InputEvent.DOWN, 480f - 58f, 320f - 34f); eng.frame(t, r); t += 16_666_667L;
        touch(eng, InputEvent.UP, 480f - 58f, 320f - 34f);
        for (int i = 0; i < 90; i++) { eng.frame(t, r); t += 16_666_667L; }
        check(r.billboards > 0, "demo: particles drawn (" + r.billboards + ")");
        // zero allocation: 10 s with walking
        for (int i = 0; i < 300; i++) { eng.frame(t, r); t += 16_666_667L; }
        allocated(); allocated();
        long a0 = allocated();
        touch(eng, InputEvent.DOWN, 100f, 250f);
        for (int i = 0; i < 1200; i++) {
            touch(eng, InputEvent.MOVE, 100f + (float) Math.sin(i * 0.02) * 60f, 220f);
            eng.frame(t, r); t += 16_666_667L;
        }
        long d = allocated() - a0;
        check(d < 4096, "demo: 1200 frames of walking allocate (almost) nothing (" + d + " bytes)");
        s.dispose();
        System.out.println(failed == 0 ? "ALL DEMO 1.2 TESTS PASSED" : failed + " TESTS FAILED");
        if (failed != 0) System.exit(1);
    }
}
