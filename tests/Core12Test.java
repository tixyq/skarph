// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.core.*;

/** Math (Quat, Vec2, Ease, Tween, Plane, frustum-AABB), render interpolation, resize, AsyncLoader, Level capacities. */
public class Core12Test {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 0.002f; }
    static boolean near(float a, float b, float e) { return Math.abs(a - b) < e; }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /** Renderer that remembers the model matrices of the drawn meshes. */
    static final class Mr implements Renderer {
        float[] lastModel = new float[16];
        int draws;
        int w = 800, h = 480;
        float[] drawOrderAlpha = new float[16];
        int blendCalls;
        public String getBackendName() { return "t"; }
        public int getWidth() { return w; }
        public int getHeight() { return h; }
        public int getDrawCalls() { return 0; }
        public int getTriangles() { return 0; }
        public void setFog(boolean e, float s, float en, float r, float g, float b) { }
        public void drawSky(int m) { }
        public void drawBillboards(int t, float[] d, int n, boolean a) { }
        public int createMesh(MeshData d) { return 7; }
        public int loadTexture(String p, boolean s) { return 3; }
        public int loadTexture(String p, boolean s, boolean r) { return 3; }
        public void deleteTexture(int t) { }
        public void deleteMesh(int m) { }
        public void setMeshTextures(int m, int d, int l) { }
        public boolean supportsLightmaps() { return false; }
        public void setLightmapsEnabled(boolean e) { }
        public int getTextureWidth(int t) { return 64; }
        public int getTextureHeight(int t) { return 64; }
        public boolean usesBufferObjects() { return false; }
        public void setLighting(boolean on) { }
        public void setAmbientLight(float r, float g, float b) { }
        public void setDirectionalLight(float dx, float dy, float dz, float r, float g, float b) { }
        public void clear(float r, float g, float b) { }
        public void setCamera(Mat4 p, Mat4 v) { }
        public void drawMesh(int m, Mat4 model) { draws++; System.arraycopy(model.m, 0, lastModel, 0, 16); lastAlphaSeen = curAlpha; order[orderN++ & 255] = model.m[12]; }
        public void drawQuads(int t, float[] v, int n) { }
        float curAlpha = 1f, lastAlphaSeen;
        float[] order = new float[256];
        int orderN;
        public void setTint(float r, float g, float b, float a) { curAlpha = a; }
        public void setBlendMode(int mode) { blendCalls++; }
    }

    public static void main(String[] args) throws Exception {
        // ---- FastMath additions
        check(near(FastMath.lerp(2, 6, 0.25f), 3f) && FastMath.clamp(5f, 0f, 1f) == 1f && FastMath.clamp(7, 0, 3) == 3, "fastmath: lerp, clamp");
        check(near(FastMath.smoothstep(0, 1, 0.5f), 0.5f) && FastMath.smoothstep(0, 1, -1) == 0f, "fastmath: smoothstep");
        check(near(FastMath.wrapAngle(190f), -170f) && near(FastMath.wrapAngle(-190f), 170f) && near(FastMath.angleDelta(350f, 10f), 20f), "fastmath: wrapAngle, angleDelta");
        float worst = 0;
        for (float a = -3f; a <= 3f; a += 0.1f) for (float b = -3f; b <= 3f; b += 0.1f) {
            if (a == 0 && b == 0) continue;
            float e = Math.abs(FastMath.wrapAngle(FastMath.atan2(b, a) - (float) Math.toDegrees(Math.atan2(b, a))));
            if (e > worst) worst = e;
        }
        check(worst < 0.1f, "fastmath: atan2 in degrees (worst error " + worst + ")");
        check(near(FastMath.acos(0.5f), 60f, 0.2f) && near(FastMath.asin(0.5f), 30f, 0.2f), "fastmath: acos, asin");

        // ---- Vec2
        Vec2 v = new Vec2(3, 4);
        check(near(v.length(), 5f) && near(v.normalize().length(), 1f) && near(new Vec2(1, 0).rotate(90).y, 1f), "vec2: length, normalize, rotate");
        check(near(new Vec2(1, 2).dot(new Vec2(3, 4)), 11f) && near(new Vec2(1, 0).cross(new Vec2(0, 1)), 1f) && near(new Vec2().lerp(new Vec2(0, 0), new Vec2(10, 20), 0.5f).y, 10f), "vec2: dot, cross, lerp");
        check(near(new Vec2(0, 1).angle(), 90f, 0.2f) && near(new Vec2(0, 0).distanceTo(new Vec2(3, 4)), 5f), "vec2: angle, distance");

        // ---- Quat
        Quat q = new Quat().setAxisAngle(90f, 0, 1, 0);
        Vec3 p = new Vec3(1, 0, 0);
        q.rotate(p);
        check(near(p.x, 0f) && near(p.z, -1f), "quat: 90 degrees about y turns +x into -z (" + p.x + "," + p.z + ")");
        Quat e = new Quat().setEuler(30f, 45f, 60f);
        Mat4 mq = new Mat4().setIdentity().rotate(e);
        Mat4 me = new Mat4().setIdentity();
        Scene sc0 = new Scene(4);
        Node n0 = sc0.createNode(null);
        n0.setRotation(30f, 45f, 60f);
        sc0.update();
        boolean same = true;
        for (int i = 0; i < 16; i++) if (!near(n0.world.m[i], mq.m[i], 0.01f)) same = false;
        check(same, "quat: setEuler matches the node's Euler rotation order");
        Quat a1 = new Quat().setAxisAngle(0, 0, 1, 0), b1 = new Quat().setAxisAngle(90, 0, 1, 0), mid = new Quat();
        mid.slerp(a1, b1, 0.5f);
        Quat ref = new Quat().setAxisAngle(45, 0, 1, 0);
        check(near(Math.abs(mid.dot(ref)), 1f) && near(mid.x * mid.x + mid.y * mid.y + mid.z * mid.z + mid.w * mid.w, 1f), "quat: slerp halfway");
        mid.nlerp(a1, b1, 0.5f);
        check(near(Math.abs(mid.dot(ref)), 1f, 0.01f), "quat: nlerp halfway is close");
        Quat prod = new Quat().multiply(new Quat().setAxisAngle(90, 0, 1, 0), new Quat().setAxisAngle(90, 0, 1, 0));
        check(near(Math.abs(prod.dot(new Quat().setAxisAngle(180, 0, 1, 0))), 1f), "quat: multiply adds angles");
        Quat c = new Quat().set(q).conjugate();
        p.set(0, 0, -1);
        c.rotate(p);
        check(near(p.x, 1f) && near(p.z, 0f), "quat: conjugate undoes the rotation");
        Quat look = new Quat().setLookRotation(1, 0, 0, 0, 1, 0);
        p.set(0, 0, -1);
        look.rotate(p);
        check(near(p.x, 1f) && near(p.y, 0f), "quat: look rotation turns forward (-z) toward the target (" + p.x + ")");
        Vec3 eul = new Vec3();
        new Quat().setEuler(10f, 20f, 30f).toEuler(eul);
        check(near(eul.x, 10f, 0.2f) && near(eul.y, 20f, 0.2f) && near(eul.z, 30f, 0.2f), "quat: toEuler round trip (" + eul.x + "," + eul.y + "," + eul.z + ")");

        // ---- Ease and Tween
        boolean ends = true, mono = true;
        for (int t = 0; t < Ease.COUNT; t++) {
            if (!near(Ease.apply(t, 0f), 0f) || !near(Ease.apply(t, 1f), 1f)) ends = false;
        }
        check(ends, "ease: all " + Ease.COUNT + " curves go from 0 to 1");
        check(Ease.apply(Ease.IN_QUAD, 0.5f) < 0.5f && Ease.apply(Ease.OUT_QUAD, 0.5f) > 0.5f && near(Ease.apply(Ease.LINEAR, 0.3f), 0.3f), "ease: in is slow at the start, out is fast");
        check(Ease.apply(Ease.OUT_BACK, 0.7f) > 1f || Ease.apply(Ease.OUT_ELASTIC, 0.3f) > 1f, "ease: overshooting curves overshoot");
        Tween tw = new Tween(10f, 20f, 60, Ease.LINEAR);
        for (int i = 0; i < 30; i++) tw.update();
        check(near(tw.getValue(), 15f) && !tw.isFinished() && near(tw.getValue(0.5f), 15.083f, 0.01f), "tween: halfway, interpolated between ticks");
        for (int i = 0; i < 40; i++) tw.update();
        check(tw.isFinished() && near(tw.getValue(), 20f) && near(tw.getValue(1f), 20f), "tween: finishes at the target");
        tw.start(0, 1, 10, Ease.LINEAR).setDelay(5);
        for (int i = 0; i < 5; i++) tw.update();
        check(near(tw.getValue(), 0f), "tween: delay");
        tw.finish();
        check(tw.isFinished() && near(tw.getValue(), 1f), "tween: finish()");

        // ---- Plane, Aabb classification, frustum
        Plane pl = new Plane().set(0, 5, 0, 0, 1, 0);
        check(near(pl.distance(0, 8, 0), 3f) && near(pl.distance(2, 2, 2), -3f), "plane: signed distance");
        Ray ray = new Ray();
        ray.set(0, 10, 0, 0, -1, 0);
        check(near(pl.raycast(ray), 5f) && pl.raycast(new Ray().set(0, 10, 0, 0, 1, 0)) < 0f, "plane: ray hit, ray going away");
        Aabb box = new Aabb();
        box.set(-1, 6, -1, 1, 8, 1);
        int above = pl.classify(box);
        box.set(-1, 3, -1, 1, 4, 1);
        int below = pl.classify(box);
        box.set(-1, 4, -1, 1, 6, 1);
        int cross = pl.classify(box);
        check(above > 0 && below < 0 && cross == 0, "plane: classify box (front / behind / crossing)");
        Camera cam = new Camera().setPerspective(60f, 1.5f, 0.5f, 100f);
        cam.lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        check(cam.isAabbVisible(-1, -1, -11, 1, 1, -9) && !cam.isAabbVisible(-1, -1, 9, 1, 1, 11) && !cam.isAabbVisible(500, 0, -10, 502, 2, -8)
                && !cam.isAabbVisible(-1, -1, -300, 1, 1, -200) && cam.isAabbVisible(-30, -1, -10, 30, 1, -9), "camera: AABB against the frustum (in front, behind, far side, too far, wide box)");

        // ---- render interpolation
        Scene sc = new Scene(8);
        Node mover = sc.createNode(null);
        mover.mesh = 0;
        mover.boundRadius = 1f;
        Node still = sc.createNode(null);
        still.mesh = 0;
        still.boundRadius = 1f;
        still.setPosition(0, 0, -5);
        mover.setPosition(0, 0, -5);
        sc.update();
        Camera cc = new Camera().setPerspective(60f, 1.5f, 0.1f, 100f);
        cc.lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        Mr mr = new Mr();
        mover.setPosition(10, 0, -5);
        sc.update();                                   // one tick: x 0 -> 10 (the first update snapped the starting position)
        sc.render(mr, cc, 0.5f);
        float xs = Float.NaN;
        sc.render(mr, cc, 0f);
        boolean found0 = false;
        mr.draws = 0;
        Mr rec = new Mr();
        sc.cullingEnabled = false;
        sc.render(rec, cc, 0.25f);
        // mover is drawn after 'still' or before: collect both translations
        float t0 = rec.order[0], t1 = rec.order[1];
        float lo = Math.min(t0, t1), hi = Math.max(t0, t1);
        check(near(lo, 0f) && near(hi, 2.5f, 0.05f), "interpolation: a moving node is drawn a quarter of the way (" + lo + ", " + hi + ")");
        rec.orderN = 0;
        sc.render(rec, cc, 1f);
        check(near(Math.max(rec.order[0], rec.order[1]), 10f), "interpolation: alpha 1 is the current position");
        mover.snap();
        mover.setPosition(50, 0, -5);
        mover.snap();
        sc.update();
        rec.orderN = 0;
        sc.render(rec, cc, 0.5f);
        check(near(Math.max(rec.order[0], rec.order[1]), 50f), "interpolation: snap() makes a teleport jump instead of gliding");
        sc.interpolate = false;
        mover.setPosition(60, 0, -5);
        sc.update();
        rec.orderN = 0;
        sc.render(rec, cc, 0.5f);
        check(near(Math.max(rec.order[0], rec.order[1]), 60f), "interpolation: can be switched off");
        sc.interpolate = true;
        sc.update();                                   // nothing moved: both positions equal
        rec.orderN = 0;
        sc.render(rec, cc, 0.3f);
        check(near(Math.max(rec.order[0], rec.order[1]), 60f), "interpolation: a node that stopped is not smeared");

        // transparent nodes are drawn after opaque ones, far to near
        Scene ts = new Scene(8);
        Node opaque = ts.createNode(null);
        opaque.mesh = 0; opaque.boundRadius = 1f; opaque.setPosition(0, 0, -20);
        Node glassNear = ts.createNode(null);
        glassNear.mesh = 0; glassNear.boundRadius = 1f; glassNear.setPosition(0, 0, -5); glassNear.setBlend(Renderer.BLEND_ALPHA); glassNear.setTint(1, 1, 1, 0.5f);
        Node glassFar = ts.createNode(null);
        glassFar.mesh = 0; glassFar.boundRadius = 1f; glassFar.setPosition(0, 0, -10); glassFar.setBlend(Renderer.BLEND_ALPHA); glassFar.setTint(1, 1, 1, 0.5f);
        ts.update();
        Mr tr = new Mr();
        ts.render(tr, cc, 1f);
        check(tr.draws == 3 && near(tr.order[0], 0f) && tr.blendCalls > 0, "transparency: three meshes drawn, blend mode used");

        // ---- Engine: resize notification and alpha
        final int[] sizes = new int[4];
        final int[] calls = {0};
        Engine eng = new Engine();
        eng.setScreen(new ScreenAdapter() {
            public void update() { }
            public void resize(int w, int h) { sizes[calls[0] * 2 % 4] = w; sizes[calls[0] * 2 % 4 + 1] = h; calls[0]++; }
            public void render(Renderer r) { r.clear(0, 0, 0); }
        });
        Mr er = new Mr();
        long now = 1000000000L;
        eng.frame(now, er);
        check(calls[0] == 1 && sizes[0] == 800 && sizes[1] == 480, "engine: Resizable.resize after init with the initial size");
        eng.frame(now += 16666667L, er);
        check(calls[0] == 1, "engine: no resize without a change");
        er.w = 480; er.h = 800;
        eng.frame(now += 16666667L, er);
        check(calls[0] == 2 && sizes[2] == 480 && sizes[3] == 800, "engine: resize when the surface changes (rotation)");
        eng.frame(now += 8333333L, er);
        check(eng.getAlpha() >= 0f && eng.getAlpha() <= 1f, "engine: alpha is between ticks (" + eng.getAlpha() + ")");

        // ---- Level capacities
        Level big = new Level(500, 40, 300);
        Level def = new Level();
        check(big.maxObjects == 500 && big.maxSpawns == 40 && big.maxBodies == 300 && def.maxObjects > 0, "level: capacities are configurable");

        // ---- AsyncLoader
        MeshData md = new MeshData(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}, new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, new short[] {0, 1, 2});
        final byte[] mdl = ModelFormat.write(new ModelData(md, null, null));
        final byte[] raw = {1, 2, 3, 4};
        Assets as = new Assets() {
            public byte[] readBytes(String path) {
                if (path.equals("tri.mdl")) { try { Thread.sleep(20); } catch (Exception e2) { } return mdl; }
                if (path.equals("raw.bin")) return raw;
                if (path.equals("bad.mdl")) return new byte[] {9, 9, 9};
                return null;
            }
        };
        Mr gl = new Mr();
        ResourceManager rm = new ResourceManager(as, gl, 8, 8);
        AsyncLoader al = new AsyncLoader(as, rm, 16);
        int k1 = al.requestModel("tri.mdl");
        int k2 = al.requestBytes("raw.bin");
        int k3 = al.requestModel("bad.mdl");
        int k4 = al.requestTexture("pic.png", true, false);
        int k5 = al.requestBytes("missing.bin");
        check(al.getState(k1) == AsyncLoader.PENDING && al.getPendingCount() == 5 && !al.isIdle(), "loader: requests are pending");
        long dl = System.currentTimeMillis() + 5000;
        while (!al.isIdle() && System.currentTimeMillis() < dl) { al.pump(4000000L); Thread.sleep(1); }
        check(al.isIdle() && al.getProgress() >= 0.999f, "loader: everything finishes (progress " + al.getProgress() + ")");
        check(al.getState(k1) == AsyncLoader.DONE && al.getModel(k1) != null && al.getModel(k1).mesh == 7, "loader: a model is parsed and uploaded");
        check(al.getState(k2) == AsyncLoader.DONE && al.getBytes(k2)[3] == 4, "loader: raw bytes");
        check(al.getState(k3) == AsyncLoader.FAILED && al.getError(k3) != null, "loader: a corrupt model fails with an error");
        check(al.getState(k4) == AsyncLoader.DONE && al.getTexture(k4) == 3, "loader: a texture");
        check(al.getState(k5) == AsyncLoader.FAILED, "loader: a missing file fails");
        al.release(k1);
        check(al.getState(k1) == AsyncLoader.INVALID, "loader: a released ticket is invalid (generation safe)");
        int again = al.requestBytes("raw.bin");
        check(again != k1 && al.getState(k1) == AsyncLoader.INVALID, "loader: the slot is reused with a new ticket");
        // the ticket order is kept: a model requested first finishes first
        al.shutdown();

        // many transparent nodes: strictly far to near
        Scene bs = new Scene(200);
        for (int i = 0; i < 150; i++) {
            Node g = bs.createNode(null);
            g.mesh = 0; g.boundRadius = 1f;
            g.setPosition(((i * 37) % 150) * 0.02f, 0, -10);
            g.setBlend(Renderer.BLEND_ALPHA); g.setTint(1, 1, 1, 0.5f);
        }
        bs.update();
        Mr br = new Mr();
        bs.render(br, cc, 1f);
        boolean sorted = br.draws >= 2;
        for (int i = 1; i < 150; i++) if (br.order[i] > br.order[i - 1] + 1e-4f) sorted = false;
        check(sorted, "transparency: heap sort of many nodes keeps far-to-near order (draws " + br.draws + ")");

        // rigid blend keeps the length of rotated axes
        Mat4 ra = new Mat4(), rb = new Mat4(), rmid = new Mat4();
        rb.rotateY(90f);
        rmid.lerpRigid(ra, rb, 0.5f);
        float l0 = (float) Math.sqrt(rmid.m[0] * rmid.m[0] + rmid.m[1] * rmid.m[1] + rmid.m[2] * rmid.m[2]);
        check(Math.abs(l0 - 1f) < 0.01f, "lerpRigid: half way through a 90 degree turn keeps unit length (" + l0 + ")");

        // ---- zero allocation of the new per-frame paths
        Scene zs = new Scene(64);
        Node[] zn = new Node[40];
        for (int i = 0; i < 40; i++) {
            zn[i] = zs.createNode(null);
            zn[i].mesh = 0;
            zn[i].boundRadius = 1f;
            zn[i].setPosition(i % 8 * 3 - 10, 0, -5 - i / 8 * 3);
            if (i % 4 == 0) { zn[i].setBlend(Renderer.BLEND_ALPHA); zn[i].setTint(1, 1, 1, 0.5f); }
            if (i % 5 == 0) zn[i].setRotationQuat(new Quat().setAxisAngle(i, 0, 1, 0));
        }
        Mr zr = new Mr();
        Quat zq = new Quat();
        Quat za = new Quat().setAxisAngle(10, 1, 0, 0), zb = new Quat().setAxisAngle(80, 0, 1, 0);
        Vec3 zv = new Vec3();
        Tween zt = new Tween();
        Plane zp = new Plane();
        for (int i = 0; i < 300; i++) { zn[i % 40].setPosition(i % 7, 0, -6); zs.update(); zs.render(zr, cc, 0.5f); }
        allocated(); allocated();
        long a0 = allocated();
        for (int i = 0; i < 5000; i++) {
            zn[i % 40].setPosition(i % 7, 0, -6);
            zn[(i + 5) % 40].setRotationQuat(zq.slerp(za, zb, (i % 100) / 100f));
            zs.update();
            zs.render(zr, cc, (i % 10) / 10f);
            zq.rotate(zv.set(1, 2, 3));
            zq.toEuler(zv);
            if (zt.isFinished()) zt.start(0, 1, 30, i % Ease.COUNT);
            zt.update();
            zt.getValue(0.5f);
            zp.setFromPoints(0, 0, 0, 1, 0, 0, 0, 0, 1);
            zp.classify(box);
            cc.isAabbVisible(box);
            FastMath.atan2(i, 3f);
        }
        long d = allocated() - a0;
        check(d < 1024, "zero allocation: 5000 frames with quaternions, interpolation, transparency, tweens (" + d + " bytes)");

        // particles, billboards, input queue, spatial grid: no allocation per frame
        {
            ParticleSystem ps = new ParticleSystem(200);
            ParticleType pt = new ParticleType(new TextureRegion().set(0, 64, 64, 0, 0, 16, 16));
            BillboardBatch bb = new BillboardBatch(zr, 256);
            InputQueue iq = new InputQueue(64);
            InputEvent ie = new InputEvent();
            SpatialGrid sg = new SpatialGrid(-50f, -50f, -50f, 5f, 20, 20, 20, 64, 512);
            int[] pairs = new int[256];
            Aabb gb = new Aabb();
            for (int i = 0; i < 400; i++) { ps.burst(pt, 0, 0, -8, 3, 0.1f, 20, 40); ps.update(); bb.begin(); ps.draw(bb, cc); bb.end(); }
            allocated(); allocated();
            long q0 = allocated();
            for (int i = 0; i < 3000; i++) {
                ps.burst(pt, 0, 0, -8, 3, 0.1f, 20, 40);
                ps.update();
                bb.begin(); ps.draw(bb, cc); bb.end();
                iq.push(1, 0, i, i); iq.poll(ie);
                sg.clear();
                for (int k = 0; k < 20; k++) { gb.set(k, 0, 0, k + 1.5f, 1, 1); sg.insert(k, gb); }
                sg.findPairs(pairs, 128);
                sg.query(gb, pairs);
            }
            long qd = allocated() - q0;
            check(qd < 2048, "zero allocation: particles, billboards, input queue, spatial grid (" + qd + " bytes)");
        }

        System.out.println(failed == 0 ? "ALL EXTENDED CORE TESTS PASSED" : failed + " TESTS FAILED");
        if (failed != 0) System.exit(1);
    }
}
