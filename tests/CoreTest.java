// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.core.*;

/** Headless tests for skarph-core (run on a normal JVM). */
public class CoreTest {
    static int failed;

    static float fbbW, fbbH;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /** Mock renderer that counts calls. */
    static final class MockRenderer implements Renderer {
        int clears, draws, quadCalls, quadsTotal, lastTexture, cameraCalls, lightCalls;
        boolean lightingOn;
        float[] lastQuads = new float[Renderer.MAX_QUADS * 32];
        public String getBackendName() { return "test"; }
        public int getWidth() { return 800; }
        public int getHeight() { return 480; }
        int fakeCalls, fakeTris;
        public int getDrawCalls() { return fakeCalls; }
        public int getTriangles() { return fakeTris; }
        public void setFog(boolean enabled, float start, float end, float r, float g, float b) { }
        int skyDraws;
        public void drawSky(int mesh) { skyDraws++; }
        int billboardCalls, billboardsTotal, lastBillboardTexture;
        boolean lastAdditive;
        float[] lastBillboardData = new float[Renderer.MAX_BILLBOARDS * Renderer.BILLBOARD_FLOATS];
        public void drawBillboards(int tex, float[] d, int n, boolean add) { billboardCalls++; billboardsTotal += n; lastBillboardTexture = tex; lastAdditive = add; System.arraycopy(d, 0, lastBillboardData, 0, n * Renderer.BILLBOARD_FLOATS); }
        public int createMesh(MeshData d) { return 0; }
        public int loadTexture(String path, boolean smooth) { return 0; }
        public int loadTexture(String path, boolean smooth, boolean repeat) { return 0; }
        public void deleteTexture(int t) { }
        public void deleteMesh(int m) { }
        public void setMeshTextures(int m, int d, int l) { }
        public boolean supportsLightmaps() { return true; }
        public void setLightmapsEnabled(boolean e) { }
        public int getTextureWidth(int t) { return 128; }
        public int getTextureHeight(int t) { return 128; }
        public boolean usesBufferObjects() { return false; }
        public void setLighting(boolean on) { lightingOn = on; }
        public void setAmbientLight(float r, float g, float b) { lightCalls++; }
        public void setDirectionalLight(float dx, float dy, float dz, float r, float g, float b) { lightCalls++; }
        public void clear(float r, float g, float b) { clears++; }
        public void setCamera(Mat4 p, Mat4 v) { cameraCalls++; }
        public void drawMesh(int m, Mat4 model) { draws++; }
        public void drawQuads(int tex, float[] v, int n) {
            quadCalls++; quadsTotal += n; lastTexture = tex;
            System.arraycopy(v, 0, lastQuads, 0, n * 32);
        }
    }

    /** Mock screen that counts ticks. */
    static final class CountScreen extends ScreenAdapter {
        int updates, renders;
        public void update() { updates++; }
        public void render(Renderer r) { renders++; r.clear(0, 0, 0); }
    }

    static int runClock(int fps, int seconds) {
        TickClock c = new TickClock();
        long t = 1000000000L;
        c.poll(t);
        int frames = fps * seconds;
        int total = 0;
        for (int i = 1; i <= frames; i++) {
            long now = t + (long) ((double) i * 1000000000.0 / fps);
            total += c.poll(now);
        }
        return total;
    }

    public static void main(String[] args) {
        // ---- TickClock: 60 ticks/s regardless of FPS
        check(runClock(60, 10) == 600, "ticks: 60 fps -> 600 ticks in 10 s");
        check(runClock(120, 10) == 600, "ticks: 120 fps -> 600 ticks in 10 s");
        check(runClock(30, 10) == 600, "ticks: 30 fps -> 600 ticks in 10 s");
        check(runClock(17, 10) == 600, "ticks: 17 fps -> 600 ticks in 10 s");
        TickClock c = new TickClock();
        c.poll(0);
        check(c.poll(10000000000L) == TickClock.MAX_CATCHUP_TICKS, "ticks: 10 s stall is capped");
        check(c.poll(10000000000L + 16666667L) == 1, "ticks: recovers after stall (no burst)");
        c.reset();
        check(c.poll(5L) == 0, "ticks: reset gives 0 on first poll");

        // ---- Engine
        Engine e = new Engine();
        CountScreen s = new CountScreen();
        MockRenderer mr = new MockRenderer();
        e.setScreen(s);
        long t = 1000000000L;
        for (int i = 0; i <= 120; i++) {
            e.frame(t + i * 8333333L + (i / 3), mr);
        }
        check(s.updates == 60 || s.updates == 59 || s.updates == 61, "engine: ~60 updates/s at 120 fps (" + s.updates + ")");
        check(s.renders == 121, "engine: render every frame");
        check(e.stats.fps > 0 && e.stats.tps > 0, "engine: stats updated (fps=" + e.stats.fps + " tps=" + e.stats.tps + ")");

        // ---- Mat4 / Vec3
        Mat4 m = new Mat4();
        m.rotate(90f, 0f, 1f, 0f);
        Vec3 v = new Vec3(1f, 0f, 0f);
        m.transformPoint(v);
        check(near(v.x, 0f) && near(v.y, 0f) && near(v.z, -1f), "mat4: rotate 90 deg about Y maps +X to -Z");
        m.setIdentity().translate(1f, 2f, 3f).scale(2f, 2f, 2f);
        v.set(1f, 1f, 1f);
        m.transformPoint(v);
        check(near(v.x, 3f) && near(v.y, 4f) && near(v.z, 5f), "mat4: translate*scale order");
        Mat4 a = new Mat4().translate(5f, 0f, 0f);
        Mat4 b = new Mat4().rotate(90f, 0f, 0f, 1f);
        Mat4 ab = new Mat4().multiply(a, b);
        v.set(1f, 0f, 0f);
        ab.transformPoint(v);
        check(near(v.x, 5f) && near(v.y, 1f), "mat4: multiply a*b");
        Mat4 look = new Mat4().setLookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
        v.set(0f, 0f, 0f);
        look.transformPoint(v);
        check(near(v.x, 0f) && near(v.y, 0f) && near(v.z, -5f), "mat4: lookAt puts origin 5 units in front");
        Mat4 proj = new Mat4().setPerspective(90f, 1f, 1f, 11f);
        check(near(proj.m[0], 1f) && near(proj.m[5], 1f) && near(proj.m[11], -1f), "mat4: perspective 90 deg");
        Mat4 ortho = new Mat4().setOrtho(0, 100, 0, 50, -1, 1);
        v.set(100f, 50f, 0f);
        ortho.transformPoint(v);
        check(near(v.x, 1f) && near(v.y, 1f), "mat4: ortho maps corner to (1,1)");
        Vec3 cr = new Vec3().cross(new Vec3(1, 0, 0), new Vec3(0, 1, 0));
        check(near(cr.z, 1f) && near(cr.x, 0f), "vec3: cross X x Y = Z");

        // ---- Pool
        Pool<StringBuilder> pool = new Pool<StringBuilder>(new Pool.Factory<StringBuilder>() {
            public StringBuilder create() { return new StringBuilder(); }
        }, 3);
        StringBuilder p1 = pool.obtain(), p2 = pool.obtain(), p3 = pool.obtain();
        check(p1 != null && p2 != null && p3 != null && pool.obtain() == null, "pool: exhausts and returns null");
        pool.free(p2);
        check(pool.getFreeCount() == 1 && pool.obtain() == p2, "pool: reuses freed object");

        // ---- TextBuffer
        TextBuffer tb = new TextBuffer(64);
        tb.append("fps=").append(60).append(" t=").append(-42).append(' ').append(3.14159f, 2).append(' ').append(0.05f, 2).append(' ').append(7f, 0);
        check(tb.toString().equals("fps=60 t=-42 3.14 0.05 7"), "textbuffer: formats ints/floats (\"" + tb + "\")");
        TextBuffer small = new TextBuffer(4);
        small.append("abcdefgh");
        check(small.length() == 4 && small.toString().equals("abcd"), "textbuffer: overflow is truncated");
        check(new TextBuffer(32).append(Integer.MIN_VALUE).toString().equals("-2147483648"), "textbuffer: Integer.MIN_VALUE");

        // ---- InputQueue
        InputQueue q = new InputQueue(4);
        InputEvent ev = new InputEvent();
        for (int i = 0; i < 4; i++) q.push(InputEvent.MOVE, 0, i, i);
        q.push(InputEvent.MOVE, 0, 99, 99);
        check(q.size() == 4, "input: full queue drops MOVE");
        q.push(InputEvent.UP, 0, 7, 7);
        int last = -1;
        int n = 0;
        while (q.poll(ev)) { last = ev.type; n++; }
        check(n == 4 && last == InputEvent.UP, "input: UP is never lost when full");
        q.push(InputEvent.DOWN, 0, 1, 2);
        q.poll(ev);
        q.push(InputEvent.MOVE, 3, 5, 6);
        q.poll(ev);
        check(ev.pointer == 3 && near(ev.x, 5f) && near(ev.y, 6f), "input: ring wraps correctly");

        // ---- MeshData / cube
        MeshData cube = Shapes.cube(1f);
        check(cube.getVertexCount() == 24 && cube.getIndexCount() == 36, "shapes: cube 24 vertices / 36 indices");
        boolean outward = true;
        for (int i = 0; i < 36; i += 3) {
            int i0 = cube.indices[i], i1 = cube.indices[i + 1], i2 = cube.indices[i + 2];
            float[] p = cube.positions;
            float ax = p[i1*3]-p[i0*3], ay = p[i1*3+1]-p[i0*3+1], az = p[i1*3+2]-p[i0*3+2];
            float bx = p[i2*3]-p[i0*3], by = p[i2*3+1]-p[i0*3+1], bz = p[i2*3+2]-p[i0*3+2];
            float nx = ay*bz-az*by, ny = az*bx-ax*bz, nz = ax*by-ay*bx;
            float cx = (p[i0*3]+p[i1*3]+p[i2*3])/3, cy = (p[i0*3+1]+p[i1*3+1]+p[i2*3+1])/3, cz = (p[i0*3+2]+p[i1*3+2]+p[i2*3+2])/3;
            if (nx*cx + ny*cy + nz*cz <= 0f) outward = false;
        }
        check(outward, "shapes: all cube triangles are CCW seen from outside");


        // ---- TextureRegion / grid
        TextureRegion[] grid = TextureRegion.grid(3, 128, 128, 32, 32, 4, 4);
        check(grid.length == 16 && grid[5].texture == 3, "region: grid has 16 cells");
        check(near(grid[5].u0, 0.25f) && near(grid[5].v0, 0.25f) && near(grid[5].u1, 0.5f) && near(grid[5].v1, 0.5f)
                && near(grid[5].width, 32f), "region: cell (1,1) uv = 0.25..0.5");

        // ---- FrameAnimation (tick based)
        FrameAnimation loopAnim = new FrameAnimation(new TextureRegion[] {grid[0], grid[1], grid[2]}, 4, true);
        check(loopAnim.getFrame(0) == grid[0] && loopAnim.getFrame(3) == grid[0] && loopAnim.getFrame(4) == grid[1]
                && loopAnim.getFrame(11) == grid[2] && loopAnim.getFrame(12) == grid[0], "anim: looping frames by tick");
        FrameAnimation onceAnim = new FrameAnimation(new TextureRegion[] {grid[0], grid[1]}, 5, false);
        check(onceAnim.getFrame(100) == grid[1] && onceAnim.isFinished(10) && !onceAnim.isFinished(9)
                && !loopAnim.isFinished(1000000), "anim: non-looping clamps and finishes");
        check(loopAnim.getFrame(-5) == grid[0], "anim: negative ticks are safe");

        // ---- SpriteBatch
        MockRenderer sbr = new MockRenderer();
        SpriteBatch sb = new SpriteBatch(sbr, 8);
        Mat4 ortho2d = new Mat4().setOrtho(0, 100, 100, 0, -1, 1);
        TextureRegion ra = new TextureRegion().set(1, 64, 64, 0, 0, 16, 16);
        TextureRegion rb = new TextureRegion().set(2, 64, 64, 16, 16, 16, 16);
        sb.begin(ortho2d);
        sb.setColor(1f, 0.5f, 0.25f, 0.75f);
        sb.draw(ra, 10f, 20f, 30f, 40f);
        sb.draw(ra, 0f, 0f);
        check(sbr.quadCalls == 0, "batch: sprites of one texture are buffered");
        sb.draw(rb, 0f, 0f);
        check(sbr.quadCalls == 1 && sbr.quadsTotal == 2 && sbr.lastTexture == 1, "batch: texture change flushes");
        sb.end();
        check(sbr.quadCalls == 2 && sbr.quadsTotal == 3 && sb.getDrawCalls() == 2 && sbr.cameraCalls == 1, "batch: end flushes, 2 draw calls");
        sb.begin(ortho2d);
        sb.setColor(1f, 0.5f, 0.25f, 0.75f);
        sb.draw(ra, 10f, 20f, 30f, 40f);
        sb.end();
        float[] qd = sbr.lastQuads;
        check(near(qd[0], 10f) && near(qd[1], 20f) && near(qd[8], 40f) && near(qd[9], 20f)
                && near(qd[16], 40f) && near(qd[17], 60f) && near(qd[24], 10f) && near(qd[25], 60f), "batch: quad corners TL,TR,BR,BL");
        check(near(qd[2], 0f) && near(qd[3], 0f) && near(qd[10], 0.25f) && near(qd[19], 0.25f), "batch: uv mapping");
        check(near(qd[4], 1f) && near(qd[5], 0.5f) && near(qd[6], 0.25f) && near(qd[7], 0.75f), "batch: vertex color");
        sb.begin(ortho2d);
        sb.draw(ra, 50f, 0f, -10f, 10f); // flipped horizontally
        sb.end();
        check(near(qd[0], 50f) && near(qd[8], 40f) && near(qd[2], 0f) && near(qd[10], 0.25f), "batch: negative width flips (x goes left, uv unchanged)");
        sb.begin(ortho2d);
        sb.drawRotated(ra, 50f, 50f, 20f, 20f, 90f);
        sb.end();
        check(near(qd[0], 60f) && near(qd[1], 40f) && near(qd[8], 60f) && near(qd[9], 60f), "batch: 90 deg rotation about center");
        sb.begin(ortho2d);
        for (int i = 0; i < 20; i++) sb.draw(ra, i, 0f);
        sb.end();
        check(sb.getDrawCalls() == 3 && sbr.quadsTotal >= 20, "batch: auto-flush when full (20 sprites, capacity 8 -> 3 calls)");
        boolean threw = false;
        try { sb.draw(ra, 0, 0); } catch (IllegalStateException ex) { threw = true; }
        check(threw, "batch: draw outside begin/end throws");

        // ---- BitmapFont
        TextureRegion[] fg = TextureRegion.grid(1, 128, 128, 8, 16, 16, 6);
        BitmapFont bf = new BitmapFont(fg, 32);
        MockRenderer fr = new MockRenderer();
        SpriteBatch fb = new SpriteBatch(fr, 64);
        fb.begin(ortho2d);
        bf.draw(fb, "AB C\nD", 0f, 0f);
        fb.end();
        check(fr.quadsTotal == 4, "font: 4 glyphs drawn (space skipped, newline handled)");
        check(near(fr.lastQuads[96], 0f) && near(fr.lastQuads[97], 16f) && near(fr.lastQuads[32], 8f), "font: 4th glyph after newline is at x=0, y=16; 2nd glyph at x=8");
        fr.quadsTotal = 0;
        fb.begin(ortho2d);
        bf.draw(fb, new TextBuffer(8).append("FPS ").append(60), 0f, 0f);
        fb.end();
        check(fr.quadsTotal == 5, "font: TextBuffer text (5 glyphs, space skipped)");

        {
        // ---- FastMath
        float maxErr = 0f;
        for (float d = -1000f; d < 1000f; d += 0.37f) {
            maxErr = Math.max(maxErr, Math.abs(FastMath.sin(d) - (float) Math.sin(Math.toRadians(d))));
            maxErr = Math.max(maxErr, Math.abs(FastMath.cos(d) - (float) Math.cos(Math.toRadians(d))));
        }
        check(maxErr < 3e-6f, "fastmath: sin/cos error " + maxErr);

        // ---- Mat4 fast axis rotations equal the general rotate()
        float[][] axes = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        boolean axesOk = true;
        for (int ax = 0; ax < 3; ax++) {
            Mat4 g1 = new Mat4().translate(1, 2, 3).scale(2, 3, 4);
            Mat4 g2 = new Mat4().set(g1);
            g1.rotate(37f, axes[ax][0], axes[ax][1], axes[ax][2]);
            if (ax == 0) g2.rotateX(37f); else if (ax == 1) g2.rotateY(37f); else g2.rotateZ(37f);
            for (int i = 0; i < 16; i++) if (Math.abs(g1.m[i] - g2.m[i]) > 1e-4f) axesOk = false;
        }
        check(axesOk, "mat4: rotateX/Y/Z match rotate()");

        // ---- Shapes: normals and sphere
        MeshData cb = Shapes.cube(2f, 0.1f, 0.2f, 0.3f);
        boolean cubeNormalsOk = cb.normals != null && cb.normals.length == cb.positions.length;
        for (int vtx = 0; vtx < 24 && cubeNormalsOk; vtx++) {
            float dot = cb.normals[vtx*3]*cb.positions[vtx*3] + cb.normals[vtx*3+1]*cb.positions[vtx*3+1] + cb.normals[vtx*3+2]*cb.positions[vtx*3+2];
            if (dot <= 0f) cubeNormalsOk = false;
        }
        check(cubeNormalsOk && near(cb.colors[0], 0.1f) && near(cb.colors[2], 0.3f), "shapes: cube normals point outward, uniform color");
        MeshData sph = Shapes.sphere(2f, 12, 8, 1f, 0f, 0f);
        boolean sphOk = sph.normals != null;
        for (int vtx = 0; vtx < sph.getVertexCount(); vtx++) {
            float px = sph.positions[vtx*3], py = sph.positions[vtx*3+1], pz = sph.positions[vtx*3+2];
            float len = (float) Math.sqrt(px*px + py*py + pz*pz);
            float nl = (float) Math.sqrt(sph.normals[vtx*3]*sph.normals[vtx*3] + sph.normals[vtx*3+1]*sph.normals[vtx*3+1] + sph.normals[vtx*3+2]*sph.normals[vtx*3+2]);
            if (Math.abs(len - 2f) > 1e-3f || Math.abs(nl - 1f) > 1e-3f) sphOk = false;
        }
        check(sphOk, "shapes: sphere radius and unit normals");
        boolean sphOut = true;
        int sphTris = 0;
        for (int i = 0; i < sph.getIndexCount(); i += 3) {
            int i0 = sph.indices[i], i1 = sph.indices[i + 1], i2 = sph.indices[i + 2];
            float[] p = sph.positions;
            float ax = p[i1*3]-p[i0*3], ay = p[i1*3+1]-p[i0*3+1], az = p[i1*3+2]-p[i0*3+2];
            float bx = p[i2*3]-p[i0*3], by = p[i2*3+1]-p[i0*3+1], bz = p[i2*3+2]-p[i0*3+2];
            float nx = ay*bz-az*by, ny = az*bx-ax*bz, nz = ax*by-ay*bx;
            float cx = (p[i0*3]+p[i1*3]+p[i2*3])/3, cy = (p[i0*3+1]+p[i1*3+1]+p[i2*3+1])/3, cz = (p[i0*3+2]+p[i1*3+2]+p[i2*3+2])/3;
            if (nx*nx + ny*ny + nz*nz < 1e-12f) { sphOut = false; } // degenerate triangles must be gone
            else if (nx*cx + ny*cy + nz*cz <= 0f) sphOut = false;
            sphTris++;
        }
        check(sphOut && sphTris == 12 * 8 * 2 - 24, "shapes: sphere triangles CCW from outside, no degenerates (" + sphTris + " tris)");

        // ---- Camera frustum
        Camera cam = new Camera();
        cam.setPerspective(90f, 1f, 1f, 100f).lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        check(cam.isSphereVisible(0, 0, -10, 0.1f), "camera: point in front is visible");
        check(!cam.isSphereVisible(0, 0, 10, 0.1f), "camera: point behind is culled");
        check(!cam.isSphereVisible(0, 0, -200, 0.1f), "camera: beyond far plane is culled");
        check(!cam.isSphereVisible(0, 0, -0.2f, 0.1f), "camera: before near plane is culled");
        check(!cam.isSphereVisible(20, 0, -10, 1f), "camera: far to the right is culled");
        check(cam.isSphereVisible(12, 0, -10, 3f) && !cam.isSphereVisible(12, 0, -10, 1f), "camera: sphere touching the edge");
        check(!cam.isSphereVisible(0, 15, -10, 1f) && cam.isSphereVisible(0, 9, -10, 0.5f), "camera: top plane");
        cam.lookAt(0, 0, 0, 1, 0, 0, 0, 1, 0); // now looking along +X
        check(cam.isSphereVisible(10, 0, 0, 0.1f) && !cam.isSphereVisible(0, 0, -10, 0.1f), "camera: follows lookAt");
        Camera oc = new Camera();
        oc.setOrtho(-10, 10, -5, 5, -1, 1).lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        check(oc.isSphereVisible(9, 4, 0, 0.1f) && !oc.isSphereVisible(12, 0, 0, 0.5f), "camera: ortho frustum");

        // ---- Scene graph
        Scene sc = new Scene(8);
        Node na = sc.createNode(null);
        na.setPosition(10f, 0f, 0f);
        Node nb = sc.createNode(na);
        nb.setPosition(0f, 5f, 0f);
        nb.mesh = 0;
        nb.boundRadius = 1f;
        sc.update();
        check(near(nb.worldX, 10f) && near(nb.worldY, 5f) && nb.worldVisible, "scene: child inherits parent position");
        na.setScale(2f, 2f, 2f);
        sc.update();
        check(near(nb.worldX, 10f) && near(nb.worldY, 10f) && near(nb.worldRadius, 2f), "scene: child inherits scale (position and bounding radius)");
        na.setScale(1f, 1f, 1f).setRotation(0f, 90f, 0f);
        nb.setPosition(0f, 0f, 3f);
        sc.update();
        check(near(nb.worldX, 13f) && near(nb.worldY, 0f) && near(nb.worldZ, 0f), "scene: child inherits rotation");
        float[] before = new float[16];
        System.arraycopy(nb.world.m, 0, before, 0, 16);
        sc.update();
        boolean same = true;
        for (int i = 0; i < 16; i++) if (before[i] != nb.world.m[i]) same = false;
        check(same, "scene: unchanged nodes keep their matrix");
        na.visible = false;
        sc.update();
        check(!nb.worldVisible, "scene: hidden parent hides children");
        na.visible = true;
        sc.update();
        check(nb.worldVisible, "scene: visibility restored");

        Scene smallScene = new Scene(3);
        Node s1 = smallScene.createNode(null), s2 = smallScene.createNode(s1), s3 = smallScene.createNode(s2);
        check(smallScene.createNode(null) == null && smallScene.getFreeNodes() == 0, "scene: returns null when full");
        smallScene.destroyNode(s1);
        check(smallScene.getFreeNodes() == 3, "scene: destroying a node frees its subtree");
        Node s4 = smallScene.createNode(null);
        check(s4 != null && s4.mesh == -1 && s4.visible && s4.sx == 1f, "scene: reused node is reset");
        Node s5 = smallScene.createNode(s4), s6 = smallScene.createNode(s4);
        smallScene.destroyNode(s5);
        check(smallScene.getFreeNodes() == 1 && s4.getParent() != null, "scene: destroying one of several siblings");
        smallScene.destroyNode(smallScene.root);
        check(smallScene.getFreeNodes() == 1, "scene: root cannot be destroyed");

        // culling + lighting through the renderer
        Scene cs = new Scene(8);
        float[][] pos = {{0, 0, -10}, {0, 0, 10}, {50, 0, -10}, {0, 0, -200}};
        for (int i = 0; i < 4; i++) {
            Node cn = cs.createNode(null);
            cn.mesh = 0;
            cn.boundRadius = 1f;
            cn.setPosition(pos[i][0], pos[i][1], pos[i][2]);
        }
        Node hidden = cs.createNode(null);
        hidden.mesh = 0;
        hidden.visible = false;
        cs.update();
        MockRenderer cullRend = new MockRenderer();
        Camera cc = new Camera().setPerspective(90f, 1f, 1f, 100f).lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        cs.render(cullRend, cc);
        check(cs.statTotal == 4 && cs.statDrawn == 1 && cs.statCulled == 3 && cullRend.draws == 1, "scene: culling draws 1 of 4 (hidden node not counted)");
        cs.cullingEnabled = false;
        cullRend.draws = 0;
        cs.render(cullRend, cc);
        check(cs.statDrawn == 4 && cs.statCulled == 0 && cullRend.draws == 4, "scene: culling off draws everything");
        check(!cullRend.lightingOn && cullRend.lightCalls == 0, "scene: lighting is off by default");
        cs.lightingEnabled = true;
        cs.render(cullRend, cc);
        check(cullRend.lightingOn && cullRend.lightCalls == 2, "scene: lighting on sends ambient and directional light");
        }

        // ---- Zero allocation in hot paths (guideline #1)
        Engine e2 = new Engine();
        final Mat4 pr = new Mat4(), vw = new Mat4(), md = new Mat4();
        final Vec3 vv = new Vec3();
        final MockRenderer mr2 = new MockRenderer();
        e2.setScreen(new ScreenAdapter() {
            public void update() { md.setIdentity().rotate(1f, 0, 1, 0).translate(1, 2, 3).scale(1, 2, 3); vv.set(1, 2, 3).normalize(); md.transformPoint(vv); }
            public void render(Renderer r) { r.clear(0, 0, 0); r.setCamera(pr, vw); r.drawMesh(0, md); }
        });
        TextBuffer hot = new TextBuffer(64);
        final MockRenderer br = new MockRenderer();
        final SpriteBatch hotBatch = new SpriteBatch(br, 256);
        final Mat4 hotProj = new Mat4().setOrtho(0, 480, 320, 0, -1, 1);
        final TextureRegion[] hotGrid = TextureRegion.grid(0, 128, 128, 32, 32, 4, 4);
        final FrameAnimation hotAnim = new FrameAnimation(new TextureRegion[] {hotGrid[0], hotGrid[1], hotGrid[2], hotGrid[3]}, 8, true);
        final BitmapFont hotFont = new BitmapFont(TextureRegion.grid(1, 128, 128, 8, 16, 16, 6), 32);
        final Scene hotScene = new Scene(200);
        final Camera hotCam = new Camera().setPerspective(50f, 1.6f, 0.5f, 100f);
        Node hotRoot = hotScene.createNode(null);
        final Node[] hotNodes = new Node[100];
        for (int i = 0; i < 100; i++) {
            hotNodes[i] = hotScene.createNode(i % 5 == 0 ? hotRoot : null);
            hotNodes[i].mesh = 0;
            hotNodes[i].boundRadius = 1f;
            hotNodes[i].setPosition(i % 10 * 3f - 15f, 0f, i / 10 * -3f);
        }
        hotScene.lightingEnabled = true;
        long tt = 1000000000L;
        for (int i = 0; i < 20000; i++) { // warm up
            e2.frame(tt += 16666667L, mr2);
            hot.clear().append("fps=").append(i).append(' ').append(1.5f, 2);
        }
        long before = allocated();
        for (int i = 0; i < 200000; i++) {
            e2.frame(tt += 16666667L, mr2);
            hot.clear().append("fps=").append(i).append(' ').append(1.5f, 2);
            pr.setPerspective(45f, 1.6f, 0.5f, 50f);
            vw.setLookAt(0, 3, 9, 0, 0, 0, 0, 1, 0);
            hotBatch.begin(hotProj);
            for (int k = 0; k < 20; k++) hotBatch.draw(hotAnim.getFrame(i + k), k * 10f, 5f);
            hotBatch.drawRotated(hotGrid[4], 100f, 100f, 32f, 32f, i);
            hotFont.draw(hotBatch, hot, 0f, 0f);
            hotBatch.end();
            hotNodes[i % 100].setRotation(i, i * 2f, 0f).setScale(1f, 2f, 1f);
            hotRoot.setPosition(i % 7, 0f, 0f);
            hotCam.lookAt(0, 3, 9, 0, 0, 0, 0, 1, 0);
            hotScene.update();
            hotScene.render(br, hotCam);
        }
        long delta = allocated() - before;
        check(delta < 2048, "zero allocation: 200000 frames allocated " + delta + " bytes");

        Profiler prof = new Profiler();
        MockRenderer pm = new MockRenderer();
        Stats ps = new Stats();
        ps.fps = 58; ps.tps = 60;
        pm.fakeCalls = 12; pm.fakeTris = 3400;
        prof.setGcCount(100);
        prof.frame(ps, pm);
        check(prof.getText().toString().equals("FPS 58  TPS 60\nDRAW 12  TRI 3400\nGC 0"), "profiler: text is \"" + prof.getText().toString().replace("\n", "|") + "\"");
        pm.fakeCalls = 30; pm.fakeTris = 9000;
        prof.setGcCount(103);
        prof.frame(ps, pm);
        pm.fakeCalls = 5; pm.fakeTris = 100;
        prof.frame(ps, pm);
        check(prof.getMaxDrawCalls() == 30 && prof.getMaxTriangles() == 9000 && prof.getGcCount() == 3 && prof.getDrawCalls() == 5,
                "profiler: maximums, current values and GC runs since start");
        prof.reset();
        check(prof.getMaxDrawCalls() == 0 && prof.getGcCount() == 0, "profiler: reset clears maximums and the GC base");
        long pb = allocated();
        for (int i = 0; i < 100000; i++) {
            ps.fps = 50 + (i & 15);
            prof.setGcCount(103 + (i >> 10));
            prof.frame(ps, pm);
        }
        long pd = allocated() - pb;
        check(pd < 1024, "profiler: zero allocation over 100000 frames (" + pd + " bytes)");

        // ---- sky dome
        MeshData dm = Sky.dome(50f, 12, 6, 0.1f, 0.2f, 0.9f, 0.8f, 0.85f, 0.9f, 0.3f, 0.3f, 0.3f);
        check(dm.getVertexCount() == 2 + 5 * 12 && dm.getIndexCount() == 12 * 5 * 6 && dm.normals == null && dm.uv0 == null,
                "sky: 12 segments x 6 rings = 62 vertices, 120 triangles, no normals (never lit)");
        boolean onSphere = true, inRange = true;
        for (int dv = 0; dv < dm.getVertexCount(); dv++) {
            float x = dm.positions[dv * 3], y = dm.positions[dv * 3 + 1], z = dm.positions[dv * 3 + 2];
            onSphere &= Math.abs(Math.sqrt(x * x + y * y + z * z) - 50.0) < 1e-3;
        }
        for (int i = 0; i < dm.getIndexCount(); i++) inRange &= dm.indices[i] >= 0 && dm.indices[i] < dm.getVertexCount();
        check(onSphere && inRange, "sky: every vertex lies on the sphere of the radius, every index is valid");
        int topV = 0, botV = dm.getVertexCount() - 1, eqV = 1 + 2 * 12;  // ring 3 of 6 = the equator
        check(Math.abs(dm.positions[topV * 3 + 1] - 50f) < 1e-4f && dm.colors[topV * 4] == 0.1f && dm.colors[topV * 4 + 2] == 0.9f,
                "sky: top vertex is up and has the zenith color");
        check(Math.abs(dm.positions[eqV * 3 + 1]) < 1e-3f && Math.abs(dm.colors[eqV * 4] - 0.8f) < 1e-6f && Math.abs(dm.colors[eqV * 4 + 2] - 0.9f) < 1e-6f,
                "sky: the ring at eye level has the horizon color");
        check(Math.abs(dm.positions[botV * 3 + 1] + 50f) < 1e-4f && dm.colors[botV * 4] == 0.3f, "sky: bottom vertex is down and has the nadir color");
        float midUp = dm.colors[(1 + 12) * 4]; // ring 2 of 6: one third of the way from the horizon (0.8) to the zenith (0.1)
        check(Math.abs(midUp - (0.8f - 0.7f / 3f)) < 1e-5f, "sky: color goes linearly from zenith to horizon");
        MeshData tiny = Sky.dome(1f, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        check(tiny.getVertexCount() == 2 + 3 && tiny.getIndexCount() == 3 * 2 * 3, "sky: too small segment/ring counts are raised to 3 and 2");
        MockRenderer skr = new MockRenderer();
        Sky sk = new Sky(skr, 50f, 12, 6, 0, 0, 1, 1, 1, 1, 0, 0, 0);
        int d0 = skr.skyDraws;
        sk.draw(skr);
        check(skr.skyDraws == d0 + 1, "sky: draw() asks the renderer to draw the sky mesh");

        // ---- billboards
        Mat4 bview = new Mat4().setLookAt(7f, 3f, -4f, 1f, 2f, 0.5f, 0f, 1f, 0f);
        float[] bsrc = new float[2 * Renderer.BILLBOARD_FLOATS];
        float[] bone = {1f, 2f, 3f, 4f, 2f, 0.25f, 0.5f, 0.75f, 1f, 0.1f, 0.2f, 0.3f, 0.4f};
        System.arraycopy(bone, 0, bsrc, 0, 13);
        System.arraycopy(bone, 0, bsrc, 13, 13);
        bsrc[13] = -5f;
        float[] bout = new float[2 * 4 * Billboards.VERTEX_FLOATS];
        Billboards.expand(bsrc, 2, bview.m, bout);
        float[] fwd = {-bview.m[2], -bview.m[6], -bview.m[10]};   // where the camera looks, in world space
        float[][] bc = new float[4][3];
        for (int bci = 0; bci < 4; bci++) for (int k = 0; k < 3; k++) bc[bci][k] = bout[bci * 9 + k];
        float[] tlTr = {bc[1][0] - bc[0][0], bc[1][1] - bc[0][1], bc[1][2] - bc[0][2]};
        float[] tlBl = {bc[3][0] - bc[0][0], bc[3][1] - bc[0][1], bc[3][2] - bc[0][2]};
        float wLen = (float) Math.sqrt(tlTr[0] * tlTr[0] + tlTr[1] * tlTr[1] + tlTr[2] * tlTr[2]);
        float hLen = (float) Math.sqrt(tlBl[0] * tlBl[0] + tlBl[1] * tlBl[1] + tlBl[2] * tlBl[2]);
        check(Math.abs(wLen - 4f) < 1e-4f && Math.abs(hLen - 2f) < 1e-4f, "billboard: the quad has the requested width and height");
        check(Math.abs(tlTr[0] * fwd[0] + tlTr[1] * fwd[1] + tlTr[2] * fwd[2]) < 1e-4f
                && Math.abs(tlBl[0] * fwd[0] + tlBl[1] * fwd[1] + tlBl[2] * fwd[2]) < 1e-4f
                && Math.abs(tlTr[0] * tlBl[0] + tlTr[1] * tlBl[1] + tlTr[2] * tlBl[2]) < 1e-4f,
                "billboard: the quad faces the camera (perpendicular to the view direction, edges at right angles)");
        float cx = (bc[0][0] + bc[2][0]) * 0.5f, cy = (bc[0][1] + bc[2][1]) * 0.5f, cz = (bc[0][2] + bc[2][2]) * 0.5f;
        check(Math.abs(cx - 1f) < 1e-4f && Math.abs(cy - 2f) < 1e-4f && Math.abs(cz - 3f) < 1e-4f, "billboard: the quad is centered on the given point");
        // top edge is up: the first two corners are higher (camera up has a positive y here) than the last two
        check(bc[0][1] > bc[3][1] && bc[1][1] > bc[2][1], "billboard: the first two corners are the top edge (camera up)");
        check(bout[3] == 0.25f && bout[4] == 0.5f && bout[9 + 3] == 0.75f && bout[9 + 4] == 0.5f && bout[18 + 3] == 0.75f && bout[18 + 4] == 1f
                && bout[27 + 3] == 0.25f && bout[27 + 4] == 1f, "billboard: texture rectangle corners top-left, top-right, bottom-right, bottom-left");
        check(bout[5] == 0.1f && bout[6] == 0.2f && bout[7] == 0.3f && bout[8] == 0.4f && bout[27 + 8] == 0.4f, "billboard: the tint is copied to every corner");
        float cx2 = (bout[36] + bout[36 + 18]) * 0.5f;
        check(Math.abs(cx2 + 5f) < 1e-4f, "billboard: the second billboard is centered on its own point");

        MockRenderer bmr = new MockRenderer();
        BillboardBatch bb = new BillboardBatch(bmr, 4);
        TextureRegion ba = new TextureRegion().set(1, 64, 64, 0, 0, 16, 16), bbr = new TextureRegion().set(2, 64, 64, 0, 0, 16, 16);
        bb.begin();
        bb.draw(ba, 0, 0, 0, 1, 1); bb.draw(ba, 1, 0, 0, 1, 1);
        bb.draw(bbr, 2, 0, 0, 1, 1);
        bb.end();
        check(bmr.billboardCalls == 2 && bmr.billboardsTotal == 3 && bmr.lastBillboardTexture == 2 && bb.getDrawCalls() == 2, "billboard batch: one draw call per texture");
        bmr.billboardCalls = 0; bmr.billboardsTotal = 0;
        bb.begin();
        for (int i = 0; i < 10; i++) bb.draw(ba, i, 0, 0, 1, 1);
        bb.end();
        check(bmr.billboardCalls == 3 && bmr.billboardsTotal == 10, "billboard batch: 10 billboards with capacity 4 -> 3 draw calls");
        bmr.billboardCalls = 0;
        bb.begin();
        bb.draw(ba, 0, 0, 0, 1, 1);
        bb.setAdditive(true);
        bb.draw(ba, 1, 0, 0, 1, 1);
        check(bmr.billboardCalls == 1 && !bmr.lastAdditive, "billboard batch: changing the blend mode flushes the pending billboards in the old mode");
        bb.end();
        check(bmr.billboardCalls == 2 && bmr.lastAdditive, "billboard batch: the next ones are drawn additive");
        boolean bex1 = false, bex2 = false, bex3 = false;
        try { bb.draw(ba, 0, 0, 0, 1, 1); } catch (IllegalStateException bexc) { bex1 = true; }
        bb.begin();
        try { bb.begin(); } catch (IllegalStateException bexc) { bex2 = true; }
        bb.end();
        try { bb.end(); } catch (IllegalStateException bexc) { bex3 = true; }
        check(bex1 && bex2 && bex3, "billboard batch: draw outside begin/end, double begin and end without begin throw");
        float[] bfast = new float[Renderer.BILLBOARD_FLOATS * 64];
        float[] bfastOut = new float[64 * 4 * Billboards.VERTEX_FLOATS];
        for (int i = 0; i < 2000; i++) {            // warm up
            bb.begin();
            for (int k = 0; k < 8; k++) bb.draw(ba, k, 0, 0, 1, 1);
            bb.end();
            Billboards.expand(bfast, 64, bview.m, bfastOut);
        }
        long bm0 = allocated();
        for (int i = 0; i < 100000; i++) {
            bb.begin();
            for (int k = 0; k < 8; k++) bb.draw(ba, k, 0, 0, 1, 1);
            bb.end();
            Billboards.expand(bfast, 64, bview.m, bfastOut);
        }
        long bdelta = allocated() - bm0;
        check(bdelta < 4096, "billboard: 100000 batches and 64-billboard expansions allocated " + bdelta + " bytes");

        // ---- particles
        Camera pcam = new Camera().lookAt(0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f);
        MockRenderer pmr = new MockRenderer();
        BillboardBatch pbb = new BillboardBatch(pmr, 64);
        ParticleType psmoke = new ParticleType(ba);
        psmoke.gravity = 0.01f; psmoke.drag = 0.5f; psmoke.sizeStart = 1f; psmoke.sizeEnd = 3f; psmoke.alphaStart = 1f; psmoke.alphaEnd = 0f;
        ParticleType pspark = new ParticleType(bbr);
        pspark.additive = true;
        ParticleSystem psys = new ParticleSystem(8);
        check(psys.emit(psmoke, 0f, 0f, -5f, 0f, 0f, 0f, 3) && psys.getActiveCount() == 1, "particles: emit adds a particle");
        psys.update();
        check(psys.getActiveCount() == 1, "particles: alive after 1 of 3 ticks");
        psys.update(); psys.update();
        check(psys.getActiveCount() == 0, "particles: removed after lifeTicks ticks");
        check(!psys.emit(psmoke, 0f, 0f, 0f, 0f, 0f, 0f, 0), "particles: life 0 is rejected");
        // physics: v = (v + g) * drag, then x += v
        psys.emit(psmoke, 0f, 0f, -5f, 1f, 0f, 0f, 10);
        psys.update();
        pbb.begin(); psys.draw(pbb, pcam); pbb.end();
        check(near(pmr.lastBillboardData[0], 0.5f) && near(pmr.lastBillboardData[1], 0.005f), "particles: gravity and drag per tick (x=" + pmr.lastBillboardData[0] + " y=" + pmr.lastBillboardData[1] + ")");
        check(near(pmr.lastBillboardData[3], 1.2f) && near(pmr.lastBillboardData[12], 0.9f), "particles: size and alpha follow the age");
        psys.clear();
        check(psys.getActiveCount() == 0, "particles: clear removes all");
        // pool exhaustion
        int pe = 0;
        for (int i = 0; i < 20; i++) if (psys.emit(psmoke, 0f, 0f, -5f, 0f, 0f, 0f, 5)) pe++;
        check(pe == 8 && psys.getActiveCount() == 8, "particles: full pool drops new particles (" + pe + ")");
        psys.clear();
        check(psys.burst(psmoke, 0f, 0f, -5f, 20, 1f, 5, 9) == 8, "particles: burst stops when the pool is empty");
        psys.clear();
        // sort: far alpha particle is drawn first; additive after all alpha
        psmoke.gravity = 0f; psmoke.drag = 1f;
        psys.emit(psmoke, 1f, 0f, -2f, 0f, 0f, 0f, 9);   // near
        psys.emit(pspark, 2f, 0f, -9f, 0f, 0f, 0f, 9);   // additive
        psys.emit(psmoke, 3f, 0f, -8f, 0f, 0f, 0f, 9);   // far
        pmr.billboardCalls = 0;
        pbb.begin(); psys.draw(pbb, pcam); pbb.end();
        check(pmr.billboardCalls == 2 && pmr.lastAdditive && pmr.lastBillboardTexture == 2, "particles: alpha and additive go in separate batches, additive last");
        pbb.begin(); psys.draw(pbb, pcam); pbb.end();
        psys.clear();
        psys.emit(psmoke, 1f, 0f, -2f, 0f, 0f, 0f, 9);
        psys.emit(psmoke, 3f, 0f, -8f, 0f, 0f, 0f, 9);
        pbb.begin(); psys.draw(pbb, pcam); pbb.end();
        check(pmr.lastBillboardData[0] == 3f && pmr.lastBillboardData[13] == 1f, "particles: alpha particles are sorted back to front");
        // determinism
        psys.clear(); psys.setSeed(7); psys.burst(psmoke, 0f, 0f, -5f, 4, 1f, 5, 9);
        pbb.begin(); psys.draw(pbb, pcam); pbb.end();
        float pfirst = pmr.lastBillboardData[0] + pmr.lastBillboardData[1] * 3f + pmr.lastBillboardData[2];
        psys.clear(); psys.setSeed(7); psys.burst(psmoke, 0f, 0f, -5f, 4, 1f, 5, 9);
        pbb.begin(); psys.draw(pbb, pcam); pbb.end();
        check(pfirst == pmr.lastBillboardData[0] + pmr.lastBillboardData[1] * 3f + pmr.lastBillboardData[2], "particles: same seed gives the same burst");
        // zero allocation
        ParticleSystem pbig = new ParticleSystem(256);
        for (int i = 0; i < 3000; i++) {
            pbig.burst(psmoke, 0f, 0f, -5f, 3, 0.1f, 10, 60); pbig.burst(pspark, 0f, 0f, -5f, 3, 0.1f, 10, 60);
            pbig.update();
            pbb.begin(); pbig.draw(pbb, pcam); pbb.end();
        }
        long pm0 = allocated();
        for (int i = 0; i < 20000; i++) {
            pbig.burst(psmoke, 0f, 0f, -5f, 3, 0.1f, 10, 60); pbig.burst(pspark, 0f, 0f, -5f, 3, 0.1f, 10, 60);
            pbig.update();
            pbb.begin(); pbig.draw(pbb, pcam); pbb.end();
        }
        long pdelta = allocated() - pm0;
        check(pdelta < 4096 && pbig.getActiveCount() > 100, "particles: 20000 ticks of burst + update + draw allocated " + pdelta + " bytes");

        // ---- camera track
        float[] cp = new float[3 * CameraTrack.FLOATS];
        for (int i = 0; i < 3; i++) {
            int o = i * 7;
            cp[o] = i * 10f; cp[o + 1] = 2f; cp[o + 2] = 5f; cp[o + 3] = i * 10f; cp[o + 4] = 1f; cp[o + 5] = 0f; cp[o + 6] = 50f + i;
        }
        CameraTrack ctrack = new CameraTrack(cp, 3);
        byte[] cbytes = ctrack.toBytes();
        check(cbytes.length == 8 + 3 * 28 && cbytes[0] == 'C' && cbytes[3] == '1' && cbytes[4] == 3 && cbytes[5] == 0, "camera track: file layout (magic, little endian count, 28 bytes per tick)");
        CameraTrack cback = CameraTrack.parse(cbytes);
        check(cback.getTickCount() == 3 && cback.get(1, CameraTrack.EYE_X) == 10f && cback.getFov(2) == 52f && cback.get(2, CameraTrack.TARGET_Y) == 1f, "camera track: parse gives back the poses");
        check(cback.get(-5, CameraTrack.EYE_X) == 0f && cback.get(99, CameraTrack.EYE_X) == 20f && !cback.isFinished(2) && cback.isFinished(3), "camera track: ticks out of range are clamped, isFinished after the last");
        Camera ccam = new Camera().setPerspective(60f, 1.5f, 0.1f, 100f);
        cback.apply(1, ccam);
        check(near(ccam.eyeX, 10f) && near(ccam.eyeY, 2f) && near(ccam.eyeZ, 5f), "camera track: apply moves the camera eye");
        Vec3 cv = new Vec3(10f, 1f, 0f);
        ccam.view.transformPoint(cv);
        check(near(cv.x, 0f) && near(cv.y, 0f) && cv.z < 0f, "camera track: the target is straight ahead (view space -Z)");
        byte[] cbad = cbytes.clone();
        cbad[0] = 'X';
        byte[] ctrunc = new byte[cbytes.length - 1];
        System.arraycopy(cbytes, 0, ctrunc, 0, ctrunc.length);
        float[] cnan = cp.clone(); cnan[2] = Float.NaN;
        float[] cfov = cp.clone(); cfov[6] = 0f;
        float[] cdown = cp.clone(); cdown[3] = cdown[0]; cdown[5] = cdown[2];
        int cerr = 0;
        try { CameraTrack.parse(cbad); } catch (IllegalArgumentException x) { cerr++; }
        try { CameraTrack.parse(ctrunc); } catch (IllegalArgumentException x) { cerr++; }
        try { new CameraTrack(cnan, 3); } catch (IllegalArgumentException x) { cerr++; }
        try { new CameraTrack(cfov, 3); } catch (IllegalArgumentException x) { cerr++; }
        try { new CameraTrack(cdown, 3); } catch (IllegalArgumentException x) { cerr++; }
        try { new CameraTrack(cp, 0); } catch (IllegalArgumentException x) { cerr++; }
        try { CameraTrack.parse(null); } catch (IllegalArgumentException x) { cerr++; }
        check(cerr == 7, "camera track: bad magic, truncated, NaN, bad fov, looking straight down, empty, null are rejected (" + cerr + "/7)");
        for (int i = 0; i < 3000; i++) cback.apply(i % 5, ccam);
        long cm0 = allocated();
        for (int i = 0; i < 100000; i++) { cback.apply(i % 5, ccam); cback.getFov(i); }
        long cdelta = allocated() - cm0;
        check(cdelta < 4096, "camera track: 100000 apply calls allocated " + cdelta + " bytes");

        // ---- flat billboards: a negative height lies on the ground
        float[] fsrc = {2f, 0.1f, 3f, 4f, -2f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 0.5f};
        float[] fout = new float[4 * Billboards.VERTEX_FLOATS];
        Billboards.expand(fsrc, 1, bview.m, fout);
        boolean flatOk = true;
        for (int i = 0; i < 4; i++) if (Math.abs(fout[i * 9 + 1] - 0.1f) > 1e-6f) flatOk = false;     // all corners at y
        check(flatOk && fout[0] == 0f && fout[9] == 4f && fout[2] == 2f && fout[20] == 4f,
                "flat billboard: a quad on the plane y, width 4 along x, depth 2 along z, whatever the camera looks at");
        check(fout[3] == 0f && fout[4] == 0f && fout[12] == 1f && fout[22] == 1f && fout[8] == 0.5f, "flat billboard: uv and color are kept");
        BillboardBatch fbb = new BillboardBatch(new Renderer() {
            public String getBackendName() { return "x"; } public int getWidth() { return 1; } public int getHeight() { return 1; }
            public int createMesh(MeshData d) { return 0; } public int loadTexture(String p, boolean s) { return 1; }
            public int loadTexture(String p, boolean s, boolean r) { return 1; } public void deleteTexture(int t) { }
            public void deleteMesh(int m) { } public void setMeshTextures(int m, int d, int l) { }
            public boolean supportsLightmaps() { return false; } public void setLightmapsEnabled(boolean e) { }
            public int getTextureWidth(int t) { return 1; } public int getTextureHeight(int t) { return 1; }
            public void clear(float r, float g, float b) { } public int getDrawCalls() { return 0; } public int getTriangles() { return 0; }
            public boolean usesBufferObjects() { return false; } public void setLighting(boolean e) { }
            public void setAmbientLight(float r, float g, float b) { }
            public void setDirectionalLight(float a, float b, float c, float d, float e, float f) { }
            public void setFog(boolean e, float s, float en, float r, float g, float b) { }
            public void setCamera(Mat4 p, Mat4 v) { } public void drawSky(int m) { }
            public void drawBillboards(int t, float[] d, int n, boolean a) { fbbH = d[4]; fbbW = d[3]; }
            public void drawMesh(int m, Mat4 model) { } public void drawQuads(int t, float[] v, int n) { }
        }, 4);
        fbb.begin();
        fbb.drawFlat(new TextureRegion().set(1, 8, 8, 0, 0, 8, 8), 0f, 0.05f, 0f, 3f, 2f);
        fbb.end();
        check(fbbW == 3f && fbbH == -2f, "flat billboard: BillboardBatch.drawFlat sends the depth as a negative height");

        // ---- Timeline
        Timeline tl = Timeline.parse("# demo\n0 INTRO\n  8   GATE   # comment\n13.5 PLAZA\n\nend 20\n");
        check(tl.getSceneCount() == 3 && tl.getEndTick() == 1200 && tl.getStart(0) == 0 && tl.getStart(1) == 480 && tl.getStart(2) == 810, "timeline: parse gives the scenes in ticks (seconds x 60, rounded)");
        check(tl.getName(1).equals("GATE") && tl.getEnd(0) == 480 && tl.getEnd(2) == 1200, "timeline: names, scene ends (next start, last = end of the timeline)");
        check(tl.sceneAt(0) == 0 && tl.sceneAt(479) == 0 && tl.sceneAt(480) == 1 && tl.sceneAt(809) == 1 && tl.sceneAt(810) == 2 && tl.sceneAt(1199) == 2, "timeline: sceneAt around the scene borders");
        check(tl.sceneAt(-1) == -1 && tl.sceneAt(1200) == -1 && !tl.isFinished(1199) && tl.isFinished(1200) && !tl.isFinished(-5), "timeline: before the start and from the end on no scene runs, isFinished");
        check(tl.getSceneTick(0) == 0 && tl.getSceneTick(500) == 20 && tl.getSceneTick(1199) == 389 && tl.getSceneTick(1200) == -1, "timeline: getSceneTick counts ticks since the start of the scene");
        String[] badTl = {
            "0 A\n8 B\n",                       // no end
            "1 A\nend 5\n",                     // first scene not at 0
            "0 A\n0 B\nend 5\n",               // starts not growing
            "0 A\n5 B\nend 5\n",               // end not after the last start
            "0 A\nend 5\n6 B\n",               // line after end
            "0 A B\nend 5\n",                   // too many words
            "zero A\nend 5\n",                  // not a number
            "0 ABCDEFGHIJKLMNOPQ\nend 5\n",     // name longer than 16
            "-1 A\nend 5\n",                    // negative
            "end 5\n"};                          // no scene
        int terr = 0;
        for (int i = 0; i < badTl.length; i++) {
            try { Timeline.parse(badTl[i]); } catch (IllegalArgumentException x) { terr++; }
        }
        try { Timeline.parse(null); } catch (IllegalArgumentException x) { terr++; }
        check(terr == badTl.length + 1, "timeline: " + (badTl.length + 1) + " kinds of bad text are rejected (" + terr + ")");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= Timeline.MAX_SCENES; i++) many.append(i).append(" S").append(i).append('\n');
        many.append("end 100\n");
        boolean manyRejected = false;
        try { Timeline.parse(many.toString()); } catch (IllegalArgumentException x) { manyRejected = true; }
        check(manyRejected, "timeline: more than " + Timeline.MAX_SCENES + " scenes are rejected");
        int sink = 0;
        for (int i = 0; i < 3000; i++) sink += tl.sceneAt(i) + tl.getSceneTick(i);
        long tm0 = allocated();
        for (int i = 0; i < 100000; i++) { sink += tl.sceneAt(i % 1300) + tl.getSceneTick(i % 1300) + tl.getEnd(i % 3); tl.isFinished(i); }
        long tdelta = allocated() - tm0;
        check(tdelta < 4096 && sink != 42, "timeline: 100000 queries allocated " + tdelta + " bytes");

        // ---- Benchmark
        Benchmark bm = new Benchmark();
        bm.frame(5L);                                                       // not running: ignored
        check(!bm.isRunning() && bm.getFrames() == 0, "benchmark: frames are ignored until start()");
        bm.start();
        long bt = 1000000000L;
        for (int i = 0; i < 601; i++) {                                     // 600 intervals of 1/60 s
            bm.frame(bt);
            bt += 16666667L;
        }
        bm.stop();
        check(bm.getFrames() == 601 && Math.abs(bm.getSeconds() - 10f) < 0.01f, "benchmark: counts frames and seconds (" + bm.getFrames() + ", " + bm.getSeconds() + " s)");
        check(Math.abs(bm.getAvgFps() - 60f) < 0.1f && Math.abs(bm.getMinFps() - 60f) < 0.1f && bm.getScore() >= 5995 && bm.getScore() <= 6005,
                "benchmark: steady 60 FPS gives avg 60, min 60, score 6000 (" + bm.getAvgFps() + ", " + bm.getMinFps() + ", " + bm.getScore() + ")");
        bm.start();
        bt = 0L;
        for (int i = 0; i < 200; i++) {                                     // 100 frames of 10 ms, a 100 ms hitch at frame 100, a hitch in warmup
            bm.frame(bt);
            bt += (i == 5) ? 400000000L : (i == 100 ? 100000000L : 10000000L);
        }
        bm.stop();
        check(Math.abs(bm.getMinFps() - 10f) < 0.1f, "benchmark: minimum comes from the longest frame after the warmup, the early hitch is ignored (" + bm.getMinFps() + ")");
        long bg = allocated();
        bm.start();
        for (int i = 0; i < 100000; i++) {
            bm.frame(i * 16666667L);
        }
        bm.stop();
        long bmDelta = allocated() - bg;
        check(bmDelta < 2048, "benchmark: 100000 frames allocated " + bmDelta + " bytes");

        // ---- QualityGovernor
        QualityGovernor qg = new QualityGovernor(3, 2);
        long qt = 1000000000L;
        int firstChange = -1;
        for (int i = 0; i < 20 * 10; i++) {                                 // 10 fps: ten frames per second
            qt += 100000000L;
            if (qg.frame(qt) && firstChange < 0) firstChange = i / 10;
        }
        check(firstChange >= 5 && firstChange <= 6 && qg.getLevel() == 0, "quality: too slow lowers the level after the warm up + 3 s, then again to 0 (first change at second " + firstChange + ", level " + qg.getLevel() + ")");
        check(qg.getCeiling() == 0, "quality: the heavy level is remembered (ceiling " + qg.getCeiling() + ")");
        for (int i = 0; i < 60 * 40; i++) {                                 // 40 s at a steady 60 FPS
            qt += 16666667L;
            qg.frame(qt);
        }
        check(qg.getLevel() == 0, "quality: it does not go back up to a level that was too slow (no flip-flop)");
        qg.reset();
        for (int i = 0; i < 60 * 30; i++) {
            qt += 16666667L;
            qg.frame(qt);
        }
        check(qg.getLevel() == 2 && qg.getFps() >= 59, "quality: after reset() a fast device climbs back to the top (level " + qg.getLevel() + ", " + qg.getFps() + " fps)");
        QualityGovernor mid = new QualityGovernor(3, 2);
        for (int i = 0; i < 60 * 30; i++) {                                 // 40 FPS: neither slow nor fast
            qt += 25000000L;
            mid.frame(qt);
        }
        check(mid.getLevel() == 2, "quality: a medium frame rate changes nothing");
        QualityGovernor lo = new QualityGovernor(3, 0);
        for (int i = 0; i < 60 * 30; i++) {
            qt += 16666667L;
            lo.frame(qt);
        }
        check(lo.getLevel() == 2, "quality: starting low on a fast device raises to the top in 2 steps (10 s each)");
        long qa = allocated();
        for (int i = 0; i < 100000; i++) {
            qt += 16666667L;
            qg.frame(qt);
        }
        long qdelta = allocated() - qa;
        check(qdelta < 2048, "quality: 100000 frames allocated " + qdelta + " bytes");

        System.out.println(failed == 0 ? "ALL TESTS PASSED" : (failed + " TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
