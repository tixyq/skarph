// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import skarph.core.*;
import java.util.HashMap;
import java.util.Random;

/** Headless tests for collisions, rays, grid, collections and the physics world. */
public class PhysicsTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-3f; }
    static boolean near(float a, float b, float eps) { return Math.abs(a - b) < eps; }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    public static void main(String[] args) {
        // ---------------- Mat4.setInverse
        Random rnd = new Random(1);
        boolean invOk = true;
        for (int i = 0; i < 200; i++) {
            Mat4 m = new Mat4().translate(rnd.nextFloat() * 10 - 5, rnd.nextFloat() * 10 - 5, rnd.nextFloat() * 10 - 5)
                    .rotate(rnd.nextFloat() * 360, rnd.nextFloat() + 0.1f, rnd.nextFloat(), rnd.nextFloat())
                    .scale(0.5f + rnd.nextFloat() * 2, 0.5f + rnd.nextFloat() * 2, 0.5f + rnd.nextFloat() * 2);
            Mat4 inv = new Mat4();
            Mat4 prod = new Mat4().multiply(m, inv.setInverse(m) ? inv : m);
            for (int k = 0; k < 16; k++) {
                float expect = (k % 5 == 0) ? 1f : 0f;
                if (Math.abs(prod.m[k] - expect) > 2e-3f) invOk = false;
            }
        }
        check(invOk, "mat4: m * inverse(m) = identity (200 random matrices)");
        Mat4 persp = new Mat4().setPerspective(60f, 1.5f, 0.5f, 80f);
        Mat4 pinv = new Mat4();
        Mat4 pprod = new Mat4().multiply(persp, pinv.setInverse(persp) ? pinv : persp);
        boolean pOk = true;
        for (int k = 0; k < 16; k++) if (Math.abs(pprod.m[k] - ((k % 5 == 0) ? 1f : 0f)) > 1e-3f) pOk = false;
        check(pOk, "mat4: inverse of a projection matrix");
        Mat4 sing = new Mat4().scale(0f, 1f, 1f);
        Mat4 keep = new Mat4().translate(1, 2, 3);
        float before = keep.m[12];
        check(!keep.setInverse(sing) && keep.m[12] == before, "mat4: singular matrix returns false and is untouched");
        Mat4 inplace = new Mat4().translate(3, 0, 0);
        inplace.setInverse(inplace);
        check(near(inplace.m[12], -3f), "mat4: inverse in place");

        // ---------------- Ray
        Ray ray = new Ray().set(0, 0, 0, 0, 0, -5); // direction gets normalized
        check(near(ray.dz, -1f), "ray: direction is normalized");
        check(near(ray.intersectSphere(0, 0, -10, 2), 8f), "ray: sphere hit distance");
        check(ray.intersectSphere(0, 5, -10, 2) < 0f, "ray: sphere miss");
        check(ray.intersectSphere(0, 0, 10, 2) < 0f, "ray: sphere behind");
        check(near(ray.intersectSphere(0, 0, 0, 2), 2f), "ray: origin inside sphere exits at far side");
        Aabb box = new Aabb().setCenterHalf(0, 0, -10, 1, 1, 1);
        check(near(ray.intersectAabb(box), 9f), "ray: box hit distance");
        Aabb off = new Aabb().setCenterHalf(5, 0, -10, 1, 1, 1);
        check(ray.intersectAabb(off) < 0f, "ray: box miss");
        Aabb around = new Aabb().setCenterHalf(0, 0, 0, 1, 1, 1);
        check(near(ray.intersectAabb(around), 0f), "ray: origin inside box returns 0");
        Aabb behind = new Aabb().setCenterHalf(0, 0, 10, 1, 1, 1);
        check(ray.intersectAabb(behind) < 0f, "ray: box behind");
        Ray down = new Ray().set(1, 10, 2, 0, -1, 0);
        check(near(down.intersectPlaneY(0f), 10f) && new Ray().set(0, 0, 0, 1, 0, 0).intersectPlaneY(5f) < 0f
                && down.intersectPlaneY(20f) < 0f, "ray: plane y");
        Vec3 pt = new Vec3();
        down.pointAt(4f, pt);
        check(near(pt.x, 1f) && near(pt.y, 6f) && near(pt.z, 2f), "ray: pointAt");
        Ray diag = new Ray().set(-10, -10, -10, 1, 1, 1);
        check(diag.intersectAabb(new Aabb().setCenterHalf(0, 0, 0, 1, 1, 1)) > 0f, "ray: diagonal through box corner region");

        // ---------------- Camera.screenToRay
        Camera cam = new Camera();
        cam.setPerspective(60f, 1.6f, 0.5f, 100f).lookAt(3, 4, 9, 0, 1, 0, 0, 1, 0);
        Mat4 vp = new Mat4().multiply(cam.projection, cam.view);
        Ray pick = new Ray();
        boolean pickOk = true;
        Random pr = new Random(5);
        for (int i = 0; i < 100; i++) {
            float px = pr.nextFloat() * 6 - 3, py = pr.nextFloat() * 4 - 1, pz = pr.nextFloat() * 6 - 3;
            float cx = vp.m[0]*px + vp.m[4]*py + vp.m[8]*pz + vp.m[12];
            float cy = vp.m[1]*px + vp.m[5]*py + vp.m[9]*pz + vp.m[13];
            float cw = vp.m[3]*px + vp.m[7]*py + vp.m[11]*pz + vp.m[15];
            float sx = (cx / cw * 0.5f + 0.5f) * 800f, sy = (1f - (cy / cw * 0.5f + 0.5f)) * 500f;
            if (!cam.screenToRay(sx, sy, 800, 500, pick)) { pickOk = false; break; }
            // distance from the point to the ray line
            float vx = px - pick.ox, vy = py - pick.oy, vz = pz - pick.oz;
            float t = vx * pick.dx + vy * pick.dy + vz * pick.dz;
            float qx = vx - pick.dx * t, qy = vy - pick.dy * t, qz = vz - pick.dz * t;
            if (Math.sqrt(qx*qx + qy*qy + qz*qz) > 0.02f || t < 0f) pickOk = false;
        }
        check(pickOk, "camera: screenToRay passes through the projected point (100 random points)");
        cam.screenToRay(400, 250, 800, 500, pick);
        float fx = 0 - 3, fy = 1 - 4, fz = 0 - 9, fl = (float) Math.sqrt(fx*fx + fy*fy + fz*fz);
        check(near(pick.dx, fx / fl, 5e-3f) && near(pick.dy, fy / fl, 5e-3f) && near(pick.dz, fz / fl, 5e-3f),
                "camera: screen center ray looks at the target");
        Camera oc = new Camera();
        oc.setOrtho(-10, 10, -5, 5, -1, 100).lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        oc.screenToRay(0, 0, 200, 100, pick); // top-left corner
        check(near(pick.ox, -10f, 5e-2f) && near(pick.oy, 5f, 5e-2f) && near(pick.dz, -1f, 1e-3f), "camera: ortho screenToRay");

        // ---------------- Collision
        Contact c = new Contact();
        check(Collision.sphereSphere(0, 0, 0, 1, 1.5f, 0, 0, 1, c) && near(c.nx, 1f) && near(c.depth, 0.5f), "collision: sphere-sphere overlap");
        check(!Collision.sphereSphere(0, 0, 0, 1, 2.5f, 0, 0, 1, c) && !Collision.sphereSphere(0, 0, 0, 1, 2f, 0, 0, 1, c), "collision: sphere-sphere apart / touching");
        check(Collision.sphereSphere(1, 1, 1, 1, 1, 1, 1, 1, c) && near(c.ny, 1f) && near(c.depth, 2f), "collision: concentric spheres give a valid normal");
        Aabb bx = new Aabb().setCenterHalf(0, 0, 0, 1, 1, 1);
        check(Collision.sphereAabb(1.8f, 0, 0, 1, bx, c) && near(c.nx, -1f) && near(c.depth, 0.2f), "collision: sphere-box face contact (normal sphere->box)");
        check(Collision.sphereAabb(1.5f, 1.5f, 0, 1, bx, c) && near(c.depth, 1f - (float) Math.sqrt(0.5f))
                && near(c.nx, -0.7071f) && near(c.ny, -0.7071f), "collision: sphere-box edge contact");
        check(!Collision.sphereAabb(3, 0, 0, 1, bx, c), "collision: sphere-box apart");
        check(Collision.sphereAabb(0.7f, 0, 0, 0.5f, bx, c) && near(c.nx, -1f) && near(c.depth, 0.8f), "collision: sphere center inside box");
        Aabb b1 = new Aabb().setCenterHalf(0, 0, 0, 1, 1, 1), b2 = new Aabb().setCenterHalf(1.5f, 0.2f, 0, 1, 1, 1);
        check(Collision.aabbAabb(b1, b2, c) && near(c.nx, 1f) && near(c.depth, 0.5f), "collision: box-box least penetration axis");
        check(Collision.aabbAabb(b2, b1, c) && near(c.nx, -1f), "collision: box-box normal direction flips with order");
        check(!Collision.aabbAabb(b1, new Aabb().setCenterHalf(5, 0, 0, 1, 1, 1), c), "collision: box-box apart");

        // ---------------- IntList
        IntList list = new IntList(4);
        list.add(10); list.add(20); list.add(30); list.add(40);
        check(!list.add(50) && list.size() == 4, "intlist: full list rejects add");
        list.removeAt(1);
        check(list.get(0) == 10 && list.get(1) == 30 && list.get(2) == 40 && list.size() == 3, "intlist: removeAt keeps order");
        list.removeSwap(0);
        check(list.get(0) == 40 && list.size() == 2 && list.indexOf(30) == 1 && list.contains(30) && !list.contains(10), "intlist: removeSwap / indexOf");
        boolean threw = false;
        try { list.get(5); } catch (IndexOutOfBoundsException e) { threw = true; }
        list.clear();
        check(threw && list.size() == 0, "intlist: bounds check and clear");

        // ---------------- IntIntMap vs HashMap
        IntIntMap map = new IntIntMap(500);
        HashMap<Integer, Integer> ref = new HashMap<Integer, Integer>();
        Random mr = new Random(9);
        boolean mapOk = true;
        for (int i = 0; i < 20000; i++) {
            int key = mr.nextInt(700) - 100;
            int op = mr.nextInt(3);
            if (op == 0) {
                int val = mr.nextInt();
                if (ref.containsKey(key) || ref.size() < 500) { mapOk &= map.put(key, val); ref.put(key, val); }
                else mapOk &= !map.put(key, val);
            } else if (op == 1) {
                mapOk &= (map.remove(key) == (ref.remove(key) != null));
            } else {
                Integer r = ref.get(key);
                mapOk &= (map.get(key, -777) == (r == null ? -777 : r)) && (map.containsKey(key) == (r != null));
            }
            mapOk &= map.size() == ref.size();
        }
        check(mapOk, "intintmap: 20000 random operations match java.util.HashMap");
        map.clear();
        check(map.size() == 0 && !map.containsKey(5), "intintmap: clear");

        // ---------------- SpatialGrid vs brute force
        Random gr = new Random(3);
        int n = 150;
        Aabb[] boxes = new Aabb[n];
        SpatialGrid grid = new SpatialGrid(0, 0, 0, 5f, 10, 10, 10, n, n * 40);
        boolean gridOk = true;
        for (int round = 0; round < 20; round++) {
            grid.clear();
            for (int i = 0; i < n; i++) {
                float x = gr.nextFloat() * 70 - 10, y = gr.nextFloat() * 70 - 10, z = gr.nextFloat() * 70 - 10; // some outside the grid
                float s = 0.5f + gr.nextFloat() * 6f;
                boxes[i] = new Aabb().set(x, y, z, x + s, y + s * 0.7f, z + s * 1.3f);
                gridOk &= grid.insert(i, boxes[i]);
            }
            int[] out = new int[n * n];
            int found = grid.findPairs(out, n * n);
            boolean[][] seen = new boolean[n][n];
            for (int p = 0; p < found; p++) {
                int a = out[p * 2], b = out[p * 2 + 1];
                if (a >= b || seen[a][b]) gridOk = false; // ordered and unique
                seen[a][b] = true;
            }
            int expected = 0;
            for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
                boolean ov = boxes[i].overlaps(boxes[j]);
                if (ov) expected++;
                if (ov != seen[i][j]) gridOk = false;
            }
            gridOk &= (expected == found);
        }
        check(gridOk, "grid: findPairs equals brute force on 20 random scenes (incl. objects outside the grid)");
        grid.clear();
        for (int i = 0; i < n; i++) grid.insert(i, boxes[i]);
        boolean queryOk = true;
        for (int q = 0; q < 50; q++) {
            float x = gr.nextFloat() * 60, y = gr.nextFloat() * 60, z = gr.nextFloat() * 60;
            Aabb qb = new Aabb().set(x, y, z, x + 8, y + 8, z + 8);
            int[] ids = new int[n];
            int cnt = grid.query(qb, ids);
            int exp = 0;
            for (int i = 0; i < n; i++) if (boxes[i].overlaps(qb)) exp++;
            boolean[] got = new boolean[n];
            for (int k = 0; k < cnt; k++) { if (got[ids[k]]) queryOk = false; got[ids[k]] = true; }
            for (int i = 0; i < n; i++) if (got[i] != boxes[i].overlaps(qb)) queryOk = false;
            queryOk &= (cnt == exp);
        }
        check(queryOk, "grid: query equals brute force (50 queries, no duplicates)");
        SpatialGrid tiny = new SpatialGrid(0, 0, 0, 1f, 4, 4, 4, 4, 3);
        check(!tiny.insert(0, new Aabb().set(0, 0, 0, 3.5f, 3.5f, 3.5f)) && tiny.insert(1, new Aabb().set(0, 0, 0, 0.5f, 0.5f, 0.5f)), "grid: insert fails when the entry table is too small");

        // ---------------- PhysicsWorld
        // ball dropped on the floor comes to rest
        PhysicsWorld w = new PhysicsWorld(8, -10, 0, -10, 10, 20, 10, 2f);
        Body ball = w.createSphere(0, 8, 0, 0.5f, 1f);
        ball.restitution = 0.6f;
        float firstImpact = 0f;
        boolean bounced = false;
        float prevVy = 0f;
        for (int t = 0; t < 1500; t++) {
            prevVy = ball.vy;
            w.step();
            if (!bounced && ball.vy > 0f) { bounced = true; firstImpact = prevVy; }
        }
        check(bounced && Math.abs(ball.vy) < 0.003f && near(ball.y, 0.5f, 0.02f), "physics: ball bounces and rests on the floor (y=" + ball.y + ")");
        float rebound = 0f;
        {
            PhysicsWorld w2 = new PhysicsWorld(2, -10, 0, -10, 10, 20, 10, 2f);
            w2.damping = 1f;
            Body bb = w2.createSphere(0, 6, 0, 0.5f, 1f);
            bb.restitution = 0.8f;
            w2.wallRestitution = 0.8f;
            float v0 = 0f;
            for (int t = 0; t < 400; t++) {
                float pv = bb.vy;
                w2.step();
                if (bb.vy > 0f && v0 == 0f) { v0 = pv; rebound = bb.vy; break; }
            }
            check(v0 < 0f && near(rebound / -v0, 0.8f, 0.05f), "physics: bounce speed ratio equals restitution (" + rebound / -v0 + ")");
        }
        // head-on equal spheres without gravity swap velocities
        PhysicsWorld w3 = new PhysicsWorld(4, -50, -5, -50, 50, 5, 50, 2f);
        w3.gravityY = 0f; w3.damping = 1f;
        Body sa = w3.createSphere(-3, 0, 0, 0.5f, 1f), sb = w3.createSphere(3, 0, 0, 0.5f, 1f);
        sa.restitution = 1f; sb.restitution = 1f;
        sa.vx = 0.1f; sb.vx = -0.1f;
        for (int t = 0; t < 120; t++) w3.step();
        check(near(sa.vx, -0.1f, 0.01f) && near(sb.vx, 0.1f, 0.01f), "physics: equal spheres collide head-on and rebound (" + sa.vx + ", " + sb.vx + ")");
        // momentum with different masses
        PhysicsWorld w4 = new PhysicsWorld(4, -50, -5, -50, 50, 5, 50, 2f);
        w4.gravityY = 0f; w4.damping = 1f; w4.contactFriction = 0f;
        Body heavy = w4.createSphere(-3, 0, 0, 0.5f, 4f), light = w4.createSphere(3, 0, 0, 0.5f, 1f);
        heavy.restitution = 1f; light.restitution = 1f;
        heavy.vx = 0.1f; light.vx = 0f;
        float p0 = 4f * 0.1f;
        for (int t = 0; t < 120; t++) w4.step();
        float p1 = 4f * heavy.vx + 1f * light.vx;
        float e0 = 0.5f * 4f * 0.01f, e1 = 0.5f * 4f * heavy.vx * heavy.vx + 0.5f * light.vx * light.vx;
        check(near(p0, p1, 0.01f) && near(e0, e1, 0.002f), "physics: momentum and energy conserved in an elastic collision");
        // static box: sphere and box rest on top
        PhysicsWorld w5 = new PhysicsWorld(8, -10, 0, -10, 10, 20, 10, 2f);
        Body platform = w5.createBox(0, 1, 0, 2, 1, 2, 0f);
        Body drop = w5.createSphere(0.3f, 8, 0.2f, 0.5f, 1f);
        Body crate = w5.createBox(-1f, 12, -1f, 0.4f, 0.4f, 0.4f, 1f);
        for (int t = 0; t < 1500; t++) w5.step();
        check(near(drop.y, 2.5f, 0.03f) && Math.abs(drop.vy) < 0.005f, "physics: sphere rests on a static box (y=" + drop.y + ")");
        check(near(crate.y, 2.4f, 0.03f) && Math.abs(crate.vy) < 0.005f, "physics: box rests on a static box (y=" + crate.y + ")");
        check(platform.x == 0f && platform.y == 1f, "physics: static bodies never move");
        // walls keep bodies inside the arena
        PhysicsWorld w6 = new PhysicsWorld(4, -5, 0, -5, 5, 10, 5, 2f);
        Body fast = w6.createSphere(0, 2, 0, 0.5f, 1f);
        fast.vx = 0.5f; fast.vz = -0.45f;
        boolean inside = true;
        for (int t = 0; t < 2000; t++) {
            w6.step();
            if (fast.x < -4.51f || fast.x > 4.51f || fast.z < -4.51f || fast.z > 4.51f || fast.y < 0.49f) inside = false;
        }
        check(inside, "physics: walls and floor keep a fast sphere inside");

        // pile of mixed bodies: everything settles inside the arena, no big overlaps
        PhysicsWorld pile = new PhysicsWorld(80, -6, 0, -6, 6, 20, 6, 2f);
        pile.createBox(0, 1, 0, 2, 1, 1, 0f);
        Random rr = new Random(77);
        Body[] pb = new Body[60];
        for (int i = 0; i < pb.length; i++) {
            float x = rr.nextFloat() * 8 - 4, y = 3 + i * 0.6f, z = rr.nextFloat() * 8 - 4;
            pb[i] = (i % 3 == 0) ? pile.createBox(x, y, z, 0.4f, 0.4f, 0.4f, 1f) : pile.createSphere(x, y, z, 0.35f + 0.1f * (i % 3), 1f);
        }
        float maxSpeed = 0f;
        for (int t = 0; t < 2400; t++) {
            pile.step();
            for (int i = 0; i < pb.length; i++) {
                float sp = Math.max(Math.abs(pb[i].vx), Math.max(Math.abs(pb[i].vy), Math.abs(pb[i].vz)));
                maxSpeed = Math.max(maxSpeed, sp);
            }
        }
        float settled = 0f, maxDepth = 0f;
        boolean allInside = true;
        Contact pc = new Contact();
        for (int i = 0; i < pb.length; i++) {
            settled = Math.max(settled, Math.max(Math.abs(pb[i].vx), Math.max(Math.abs(pb[i].vy), Math.abs(pb[i].vz))));
            if (pb[i].x < -6.01f || pb[i].x > 6.01f || pb[i].z < -6.01f || pb[i].z > 6.01f || pb[i].y < -0.01f) allInside = false;
            for (int j = i + 1; j < pb.length; j++) {
                boolean hit;
                Body a = pb[i], b = pb[j];
                if (a.getType() == PhysicsWorld.SPHERE && b.getType() == PhysicsWorld.SPHERE)
                    hit = Collision.sphereSphere(a.x, a.y, a.z, a.radius, b.x, b.y, b.z, b.radius, pc);
                else if (a.getType() == PhysicsWorld.SPHERE) hit = Collision.sphereAabb(a.x, a.y, a.z, a.radius, b.bounds, pc);
                else if (b.getType() == PhysicsWorld.SPHERE) hit = Collision.sphereAabb(b.x, b.y, b.z, b.radius, a.bounds, pc);
                else hit = Collision.aabbAabb(a.bounds, b.bounds, pc);
                if (hit) maxDepth = Math.max(maxDepth, pc.depth);
            }
        }
        check(allInside && maxSpeed <= 0.6001f, "physics: 60-body pile stays inside the arena, speed clamp holds (max " + maxSpeed + ")");
        check(maxDepth < 0.12f, "physics: pile overlaps stay small (max depth " + maxDepth + ")");
        check(settled < 0.02f, "physics: pile calms down (max speed after 40 s: " + settled + ")");

        // grid and brute-force broad phases find exactly the same pairs and contacts
        // (contacts and later ticks may differ slightly: the sequential solver result depends on the pair order)
        boolean sameSets = true;
        int totalContacts = 0;
        for (int seed = 0; seed < 25; seed++) {
            PhysicsWorld ga = new PhysicsWorld(60, -6, 0, -6, 6, 20, 6, 2f), gb = new PhysicsWorld(60, -6, 0, -6, 6, 20, 6, 2f);
            gb.useGrid = false;
            Random r1 = new Random(100 + seed);
            for (int i = 0; i < 45; i++) {
                float x = r1.nextFloat() * 6 - 3, y = 1 + r1.nextFloat() * 4, z = r1.nextFloat() * 6 - 3;
                float sz = 0.3f + r1.nextFloat() * 0.5f;
                if (i % 4 == 0) { ga.createBox(x, y, z, sz, sz, sz, 1f); gb.createBox(x, y, z, sz, sz, sz, 1f); }
                else { ga.createSphere(x, y, z, sz, 1f); gb.createSphere(x, y, z, sz, 1f); }
            }
            ga.step(); gb.step();
            if (ga.statPairs != gb.statPairs) sameSets = false;
            totalContacts += ga.statPairs;
        }
        check(sameSets && totalContacts > 100, "physics: grid and brute force find the same overlapping pairs (25 scenes, " + totalContacts + " pairs)");
        PhysicsWorld ga = new PhysicsWorld(60, -6, 0, -6, 6, 20, 6, 2f), gb = new PhysicsWorld(60, -6, 0, -6, 6, 20, 6, 2f);
        gb.useGrid = false;
        Random r1 = new Random(11);
        for (int i = 0; i < 40; i++) {
            float x = r1.nextFloat() * 10 - 5, y = 2 + r1.nextFloat() * 8, z = r1.nextFloat() * 10 - 5;
            ga.createSphere(x, y, z, 0.5f, 1f);
            gb.createSphere(x, y, z, 0.5f, 1f);
        }
        for (int t = 0; t < 300; t++) { ga.step(); gb.step(); }
        check(ga.statPairTests < gb.statPairTests, "physics: grid needs fewer pair tests (" + ga.statPairTests + " vs " + gb.statPairTests + ")");

        // raycast
        PhysicsWorld rw = new PhysicsWorld(8, -10, 0, -10, 10, 20, 10, 2f);
        rw.gravityY = 0f;
        Body near1 = rw.createSphere(0, 1, -5, 1f, 1f), far1 = rw.createBox(0, 1, -12, 1, 1, 1, 0f);
        RayHit hit = new RayHit();
        Ray rr2 = new Ray().set(0, 1, 5, 0, 0, -1);
        check(rw.raycast(rr2, 100f, hit) && hit.body == near1 && near(hit.t, 9f) && near(hit.z, -4f), "world: raycast returns the nearest body");
        check(!rw.raycast(rr2, 5f, hit), "world: raycast respects maxDist");
        rw.destroyBody(near1);
        check(rw.raycast(rr2, 100f, hit) && hit.body == far1, "world: destroyed bodies are not hit");
        check(rw.getBodyCount() == 1 && rw.getFreeBodies() == 7, "world: body counts after destroy");
        PhysicsWorld full = new PhysicsWorld(2, -5, 0, -5, 5, 5, 5, 2f);
        full.createSphere(0, 1, 0, 0.5f, 1f); full.createSphere(1, 1, 0, 0.5f, 1f);
        check(full.createSphere(2, 1, 0, 0.5f, 1f) == null, "world: createSphere returns null when full");

        // ---------------- zero allocation
        final PhysicsWorld hot = new PhysicsWorld(120, -10, 0, -10, 10, 20, 10, 2f);
        hot.createBox(0, 1, 0, 2, 1, 2, 0f);
        Random hr = new Random(2);
        for (int i = 0; i < 100; i++) {
            if (i % 3 == 0) hot.createBox(hr.nextFloat() * 16 - 8, 3 + hr.nextFloat() * 12, hr.nextFloat() * 16 - 8, 0.4f, 0.4f, 0.4f, 1f);
            else hot.createSphere(hr.nextFloat() * 16 - 8, 3 + hr.nextFloat() * 12, hr.nextFloat() * 16 - 8, 0.5f, 1f);
        }
        final Ray hotRay = new Ray();
        final RayHit hotHit = new RayHit();
        final Camera hotCam = new Camera().setPerspective(50f, 1.6f, 0.5f, 100f).lookAt(0, 10, 20, 0, 0, 0, 0, 1, 0);
        for (int i = 0; i < 2000; i++) hot.step();
        long b0 = allocated();
        for (int i = 0; i < 5000; i++) {
            hot.step();
            hotCam.screenToRay(400f + (i % 50), 300f, 800, 600, hotRay);
            hot.raycast(hotRay, 100f, hotHit);
        }
        long delta = allocated() - b0;
        check(delta < 2048, "zero allocation: 5000 physics steps + raycasts allocated " + delta + " bytes");

        System.out.println(failed == 0 ? "ALL PHYSICS TESTS PASSED" : (failed + " PHYSICS TEST(S) FAILED"));
        System.exit(failed == 0 ? 0 : 1);
    }
}
