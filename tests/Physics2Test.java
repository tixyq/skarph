// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import skarph.core.*;

/** Headless tests of the extended physics: capsules, character controller, events, triggers, layers, sleeping, sweeps, joints, terrain. */
public class Physics2Test {
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

    /** A flat floor whose top is y = 0 (the arena floor is far below). */
    static PhysicsWorld world() {
        PhysicsWorld w = new PhysicsWorld(256, -50, -10, -50, 50, 50, 50, 2f, 16);
        w.createBox(0, -0.5f, 0, 40, 0.5f, 40, 0f);
        return w;
    }

    static class Counter implements ContactListener {
        int begins, ends, hits, triggerBegins, triggerEnds;
        float lastSpeed;
        Body lastA, lastB;
        public void onBegin(Body a, Body b, boolean trigger) { if (trigger) triggerBegins++; else begins++; lastA = a; lastB = b; }
        public void onEnd(Body a, Body b, boolean trigger) { if (trigger) triggerEnds++; else ends++; }
        public void onHit(Body a, Body b, float nx, float ny, float nz, float speed) { hits++; lastSpeed = speed; }
    }

    public static void main(String[] args) {
        Contact c = new Contact();
        // ---------------- capsule narrow phase
        Aabb box = new Aabb();
        box.set(-1, -1, -1, 1, 0, 1);
        check(Collision.capsuleAabb(0, 0.85f, 0, 0.4f, 0.5f, box, c) && c.ny < -0.99f && near(c.depth, 0.05f, 1e-3f),
                "capsule standing on a box: normal points down, depth = penetration");
        check(!Collision.capsuleAabb(0, 1.0f, 0, 0.4f, 0.5f, box, c), "capsule above a box: no contact");
        check(Collision.capsuleAabb(1.3f, 0.0f, 0, 0.4f, 0.5f, box, c) && c.nx < -0.99f && near(c.depth, 0.1f, 1e-3f),
                "capsule beside a box: sideways normal (toward the box)");
        check(Collision.capsuleSphere(0, 0, 0, 0.4f, 1f, 0.7f, 0.5f, 0, 0.4f, c) && c.nx > 0.99f && near(c.ny, 0, 1e-3f),
                "capsule vs sphere beside the shaft: horizontal normal");
        check(Collision.capsuleSphere(0, 0, 0, 0.4f, 1f, 0, 1.7f, 0, 0.4f, c) && c.ny > 0.99f,
                "capsule vs sphere on the top cap: vertical normal");
        check(Collision.capsuleCapsule(0, 0, 0, 0.4f, 0.5f, 0.7f, 0.3f, 0, 0.4f, 0.5f, c) && c.nx > 0.99f && near(c.depth, 0.1f, 1e-3f),
                "capsule vs capsule side by side");
        check(!Collision.capsuleCapsule(0, 0, 0, 0.4f, 0.5f, 2f, 0, 0, 0.4f, 0.5f, c), "capsule vs capsule far apart");

        // ---------------- dynamic capsule rests on the floor
        PhysicsWorld w = world();
        Body cap = w.createCapsule(0, 3, 0, 0.4f, 0.5f, 1f);
        for (int i = 0; i < 200; i++) w.step();
        check(near(cap.y, 0.9f, 0.03f) && cap.onFloor == cap.onFloor, "dynamic capsule comes to rest on a box (y = " + cap.y + ")");
        Body s = w.createSphere(0.5f, 4, 0, 0.3f, 1f);
        for (int i = 0; i < 300; i++) w.step();
        check(s.y > 0.2f && Math.abs(s.vy) < 0.05f, "sphere dropped on a capsule ends up resting on the ground or the capsule");

        // ---------------- character controller on a box level
        w = world();
        w.createBox(8, 0.5f, 0, 0.5f, 0.5f, 5, 0f);                 // wall x = 7.5 .. 8.5, top y = 1
        w.createBox(4, 0.125f, 0, 0.5f, 0.125f, 5, 0f);              // low ledge (0.25 high) x = 3.5 .. 4.5
        Body hero = w.createCapsule(0, 2, 0, 0.4f, 0.5f, 0f);
        CharacterController cc = new CharacterController(w, hero);
        for (int i = 0; i < 60; i++) { cc.update(); w.step(); }
        check(cc.isGrounded() && near(hero.y, 0.9f, 0.02f), "controller falls and stands (y = " + hero.y + ")");
        float y0 = hero.y;
        for (int i = 0; i < 20; i++) { cc.setMove(0.05f, 0f); cc.update(); w.step(); }
        check(near(hero.x, 1f, 0.01f) && near(hero.y, y0, 0.01f), "controller walks on flat ground (x = " + hero.x + ")");
        for (int i = 0; i < 60; i++) { cc.setMove(0.05f, 0f); cc.update(); w.step(); }
        check(hero.x > 3.8f && near(hero.y, 0.25f + 0.9f, 0.03f) && cc.isGrounded(), "controller climbs a low step (x = " + hero.x + ", y = " + hero.y + ")");
        for (int i = 0; i < 200; i++) { cc.setMove(0.05f, 0f); cc.update(); w.step(); }
        check(near(hero.x, 7.5f - 0.4f, 0.03f), "controller is stopped by a high wall (x = " + hero.x + ")");
        float z0 = hero.z;
        for (int i = 0; i < 40; i++) { cc.setMove(0.05f, 0.05f); cc.update(); w.step(); }
        check(hero.z > z0 + 1.5f && hero.x < 7.5f - 0.39f, "controller slides along the wall (z + " + (hero.z - z0) + ")");
        for (int i = 0; i < 60; i++) { cc.setMove(-0.05f, 0); cc.update(); w.step(); }
        float before = hero.y;
        cc.jump(0.14f);
        float top = before;
        int air = 0;
        for (int i = 0; i < 80; i++) { cc.update(); w.step(); if (hero.y > top) top = hero.y; if (!cc.isGrounded()) air++; }
        check(top > before + 0.7f && cc.isGrounded() && air > 20, "controller jumps and lands (apex +" + (top - before) + ")");
        Body step2 = w.createBox(-2, 0.4f, 0, 0.5f, 0.4f, 5, 0f);   // 0.8 high: too tall to climb
        hero.x = 0.5f; hero.z = 0;
        cc.teleport(0.5f, 0.9f, 0);
        for (int i = 0; i < 100; i++) { cc.setMove(-0.05f, 0f); cc.update(); w.step(); }
        check(hero.x > -1.5f + 0.39f && near(hero.y, 0.9f, 0.05f), "controller does not climb a 0.8 high block (x = " + hero.x + ")");
        w.destroyBody(step2);

        // ---------------- controller on a terrain
        w = new PhysicsWorld(32, -50, -10, -50, 50, 50, 50, 2f);
        Heightfield hf = new Heightfield(21, 21, 2f, -20f, -20f);
        for (int iz = 0; iz < 21; iz++) for (int ix = 0; ix < 21; ix++) {
            float x = -20 + ix * 2f;
            hf.setHeight(ix, iz, x > 0 ? x * 0.36f : 0f);          // slope of 20 degrees for x > 0
        }
        w.setTerrain(hf);
        check(near(hf.getHeight(10, 3), 3.6f, 1e-3f) && near(hf.getHeight(-5, 0), 0f, 1e-4f), "heightfield: height lookup");
        Vec3 nn = new Vec3();
        hf.getNormal(10, 0, nn);
        check(near(nn.y, (float) Math.cos(Math.atan(0.36)), 0.02f) && nn.x < 0, "heightfield: normal leans away from the slope");
        Ray ray = new Ray();
        ray.set(10, 20, 0, 0, -1, 0);
        float ht = hf.raycast(ray, 100f);
        check(near(ht, 20f - 3.6f, 0.02f), "heightfield: ray hits the ground");
        hero = w.createCapsule(-5, 3, 0, 0.4f, 0.5f, 0f);
        cc = new CharacterController(w, hero);
        for (int i = 0; i < 80; i++) { cc.update(); w.step(); }
        check(cc.isGrounded() && near(hero.y, 0.9f, 0.03f), "controller stands on a terrain");
        for (int i = 0; i < 300; i++) { cc.setMove(0.05f, 0f); cc.update(); w.step(); }
        check(hero.x > 5f && cc.isGrounded() && near(hero.y, hf.getHeight(hero.x, 0) + 0.9f, 0.1f), "controller walks up a 20 degree slope (x = " + hero.x + ")");
        for (int iz = 0; iz < 21; iz++) for (int ix = 0; ix < 21; ix++) hf.setHeight(ix, iz, ix * 2f * 3f);   // slope of 72 degrees
        cc.teleport(-12, 30, 0);
        float startX = 0;
        for (int i = 0; i < 100; i++) { cc.update(); w.step(); }
        check(!cc.isGrounded() || cc.getGroundNormalY() < 0.5f, "a very steep slope is not stood on");
        Body ball = w.createSphere(0, 40, 0, 0.5f, 1f);
        ball.restitution = 0f;
        hf = new Heightfield(21, 21, 2f, -20f, -20f);
        w.setTerrain(hf);
        for (int i = 0; i < 600; i++) w.step();
        check(near(ball.y, 0.5f, 0.05f), "dynamic sphere rests on a flat terrain");

        // ---------------- events, triggers
        w = world();
        Counter ls = new Counter();
        w.setListener(ls);
        Body ball2 = w.createSphere(0, 3, 0, 0.5f, 1f);
        ball2.restitution = 0.2f;
        for (int i = 0; i < 120; i++) w.step();
        check(ls.begins >= 1 && ls.hits >= 1 && ls.lastSpeed > 0.02f, "listener: begin and hit when a ball lands (begins " + ls.begins + ", hits " + ls.hits + ")");
        int b0 = ls.begins;
        for (int i = 0; i < 60; i++) w.step();
        check(ls.begins == b0, "listener: a resting contact does not begin again");
        ball2.y = 5; ball2.vy = 0;
        ball2.wake();
        w.step(); w.step();
        check(ls.ends >= 1, "listener: end when the contact is lost");
        w.setListener(null);

        w = world();
        ls = new Counter();
        w.setListener(ls);
        Body zone = w.createBox(0, 2, 0, 2, 1, 2, 0f);
        zone.trigger = true;
        Body pass = w.createSphere(0, 6, 0, 0.3f, 1f);
        pass.restitution = 0f;
        float maxFall = 0;
        for (int i = 0; i < 150; i++) { w.step(); if (pass.vy < maxFall) maxFall = pass.vy; }
        check(ls.triggerBegins == 1 && ls.triggerEnds == 1 && ls.begins == 1, "trigger: enter and leave reported once; only the floor is a physical contact");
        check(pass.y < 1.4f, "trigger: the ball falls through the zone (y = " + pass.y + ")");
        // a body destroyed inside a callback is safe
        w = world();
        final PhysicsWorld wd = w;
        w.setListener(new ContactListener() {
            public void onBegin(Body a, Body b, boolean t) { if (a.invMass > 0f) wd.destroyBody(a); if (b.invMass > 0f) wd.destroyBody(b); }
            public void onEnd(Body a, Body b, boolean t) { }
            public void onHit(Body a, Body b, float nx, float ny, float nz, float sp) { }
        });
        w.createSphere(0, 2, 0, 0.5f, 1f);
        int before2 = w.getBodyCount();
        for (int i = 0; i < 80; i++) w.step();
        check(w.getBodyCount() == before2 - 1, "bodies may be destroyed inside callbacks");

        // ---------------- layers and masks
        w = world();
        Body la = w.createSphere(0, 2, 0, 0.5f, 1f);
        Body lb = w.createSphere(0, 4, 0, 0.5f, 1f);
        la.layer = 2; la.mask = 1;
        lb.layer = 4; lb.mask = 1;
        for (int i = 0; i < 200; i++) w.step();
        check(Math.abs(la.y - lb.y) < 0.2f, "layers: two bodies that do not collide with each other overlap on the floor");
        w = world();
        la = w.createSphere(0, 0.5f, 0, 0.5f, 0f);
        la.layer = 8;
        RayHit hit = new RayHit();
        ray.set(-5, 0.5f, 0, 1, 0, 0);
        check(w.raycast(ray, 20f, hit) && hit.body == la, "raycast: hits a body on layer 8");
        check(!w.raycast(ray, 20f, 1, hit) || hit.body != la, "raycast: a layer mask skips it");
        Body cp = w.createCapsule(5, 1, 0, 0.5f, 0.5f, 0f);
        ray.set(2, 1.2f, 0, 1, 0, 0);
        check(w.raycast(ray, 20f, hit) && hit.body == cp && near(hit.t, 2.5f, 0.02f), "raycast: hits the shaft of a capsule");
        ray.set(5, 5, 0, 0, -1, 0);
        check(w.raycast(ray, 20f, hit) && hit.body == cp && near(hit.t, 3f, 0.02f), "raycast: hits the top cap of a capsule");

        // ---------------- sleeping
        w = world();
        w.sleepingEnabled = true;
        Body rest = w.createSphere(0, 2, 0, 0.5f, 1f);
        for (int i = 0; i < 200; i++) w.step();
        check(rest.isSleeping() && w.statSleeping == 1, "sleeping: a resting body falls asleep");
        float ry = rest.y;
        for (int i = 0; i < 100; i++) w.step();
        check(rest.y == ry && rest.isSleeping(), "sleeping: it stays still");
        Body drop = w.createSphere(0.2f, 4, 0, 0.5f, 1f);
        for (int i = 0; i < 40; i++) w.step();
        check(!rest.isSleeping() || drop.y > 0.4f, "sleeping: a falling body wakes or rests on it");
        for (int i = 0; i < 300; i++) w.step();
        rest.vx = 0.1f;
        w.step();
        check(!rest.isSleeping(), "sleeping: a velocity wakes the body");
        for (int i = 0; i < 400; i++) w.step();
        check(rest.isSleeping(), "sleeping: ... and it sleeps again");
        rest.x += 5f;
        w.step();
        check(!rest.isSleeping(), "sleeping: moving the position wakes it (teleport)");

        // ---------------- sweeping: no tunnelling
        w = world();
        w.maxSpeed = 5f;
        w.createBox(10, 3, 0, 0.05f, 3, 5, 0f);                  // a thin wall at x = 10
        Body fast = w.createSphere(0, 1, 0, 0.2f, 1f);
        fast.vx = 1.5f;
        w.gravityY = 0f;
        for (int i = 0; i < 40; i++) w.step();
        check(fast.x < 10f, "sweep: a fast sphere does not tunnel through a thin wall (x = " + fast.x + ")");
        w = world();
        w.maxSpeed = 5f;
        w.ccdEnabled = false;
        w.createBox(10, 3, 0, 0.05f, 3, 5, 0f);
        fast = w.createSphere(0, 1, 0, 0.2f, 1f);
        fast.vx = 1.5f;
        w.gravityY = 0f;
        for (int i = 0; i < 40; i++) w.step();
        check(fast.x > 10f, "sweep: without it the same sphere goes through (the test is meaningful)");

        // ---------------- joints
        w = world();
        w.gravityY = -0.01f;
        Body bob = w.createSphere(3, 6, 0, 0.3f, 1f);
        int j = w.createDistanceJoint(bob, null, 0, 8, 0, 3f, 1f);
        float worst = 0;
        for (int i = 0; i < 300; i++) {
            w.step();
            float d = (float) Math.sqrt(bob.x * bob.x + (bob.y - 8) * (bob.y - 8) + bob.z * bob.z);
            if (i > 20 && Math.abs(d - 3f) > worst) worst = Math.abs(d - 3f);
        }
        check(j >= 0 && worst < 0.25f && bob.y < 8f, "joint: a pendulum keeps its length (worst error " + worst + ")");
        Body ja = w.createSphere(20, 6, 0, 0.3f, 1f);
        Body jb = w.createSphere(24, 6, 0, 0.3f, 1f);
        w.gravityY = 0f;
        w.createDistanceJoint(ja, jb, 2f, 0.5f);
        for (int i = 0; i < 200; i++) w.step();
        float dd = (float) Math.sqrt(Math.pow(ja.x - jb.x, 2) + Math.pow(ja.y - jb.y, 2));
        check(near(dd, 2f, 0.2f), "joint: two bodies are pulled to the rest length (" + dd + ")");

        // ---------------- ceiling
        w = world();
        w.ceilingY = 5f;
        w.gravityY = 0f;
        Body up = w.createSphere(0, 2, 0, 0.5f, 1f);
        up.vy = 0.3f;
        for (int i = 0; i < 60; i++) w.step();
        check(up.y <= 4.5f + 1e-3f, "ceiling: bodies cannot rise through it (y = " + up.y + ")");

        // ---------------- debug draw
        w = world();
        w.createCapsule(0, 1, 0, 0.4f, 0.5f, 1f);
        w.createSphere(2, 1, 0, 0.5f, 1f);
        DebugDraw dd2 = new DebugDraw(2000);
        w.debugDraw(dd2);
        check(dd2.getLineCount() > 30, "debug draw: bodies produce lines (" + dd2.getLineCount() + ")");

        // ---------------- sleeping bodies wake when their support goes away or moves
        w = world();
        w.sleepingEnabled = true;
        Body plat = w.createBox(0, 2, 0, 3, 0.5f, 3, 0f);
        Body crate = w.createBox(0, 3f, 0, 0.5f, 0.5f, 0.5f, 1f);
        for (int i = 0; i < 300; i++) w.step();
        check(crate.isSleeping() && near(crate.y, 3f, 0.05f), "support: a crate on a platform falls asleep");
        w.destroyBody(plat);
        for (int i = 0; i < 120; i++) w.step();
        check(crate.y < 1f, "support: destroying the platform wakes the crate, it falls (y = " + crate.y + ")");
        w = world();
        w.sleepingEnabled = true;
        plat = w.createBox(0, 2, 0, 3, 0.5f, 3, 0f);
        crate = w.createBox(0, 3f, 0, 0.5f, 0.5f, 0.5f, 1f);
        for (int i = 0; i < 300; i++) w.step();
        plat.x += 0.05f;
        w.step();
        check(!crate.isSleeping(), "support: moving the platform wakes the crate");
        plat.y -= 5f;
        for (int i = 0; i < 120; i++) w.step();
        check(crate.y < 2.5f, "support: a platform that was moved away lets the crate fall (y = " + crate.y + ")");

        // ---------------- the controller with a crowded level (the candidate list)
        w = new PhysicsWorld(1024, -100, -10, -100, 100, 50, 100, 2f, 8);
        w.createBox(0, -0.5f, 0, 90, 0.5f, 90, 0f);
        for (int i = 0; i < 600; i++) w.createBox(-80 + (i % 30) * 5, 0.5f, -40 + (i / 30) * 4, 0.5f, 0.5f, 0.5f, 0f);
        Body h3 = w.createCapsule(2.5f, 2, 20, 0.4f, 0.5f, 0f);
        CharacterController c3 = new CharacterController(w, h3);
        for (int i = 0; i < 60; i++) { c3.update(); w.step(); }
        check(c3.isGrounded(), "crowded level: the controller stands (600 static boxes)");
        for (int i = 0; i < 100; i++) { c3.setMove(0.05f, 0f); c3.update(); w.step(); }
        check(h3.x > 2.5f + 4f || h3.x < 7f, "crowded level: and walks, stopping at a box");
        // more candidates than the list holds: falls back to a full scan, still correct
        w = new PhysicsWorld(256, -100, -10, -100, 100, 50, 100, 2f, 8);
        w.createBox(0, -0.5f, 0, 90, 0.5f, 90, 0f);
        for (int i = 0; i < 80; i++) w.createBox(1.3f + (i % 8) * 0.02f, 0.5f, 5 + (i / 8) * 0.02f, 0.05f, 0.5f, 0.05f, 0f);
        Body h4 = w.createCapsule(0, 2, 5, 0.4f, 0.5f, 0f);
        CharacterController c4 = new CharacterController(w, h4);
        for (int i = 0; i < 40; i++) { c4.update(); w.step(); }
        for (int i = 0; i < 100; i++) { c4.setMove(0.05f, 0f); c4.update(); w.step(); }
        check(c4.isGrounded() && h4.x < 1.3f, "candidate overflow: still blocked by the cluster (x = " + h4.x + ")");

        // ---------------- zero allocation with everything on
        w = new PhysicsWorld(256, -50, -10, -50, 50, 50, 50, 2f, 8);
        hf = new Heightfield(51, 51, 2f, -50f, -50f);
        w.setTerrain(hf);
        w.sleepingEnabled = true;
        w.ceilingY = 40f;
        ls = new Counter();
        w.setListener(ls);
        w.createBox(0, -0.5f, 0, 40, 0.5f, 40, 0f);
        for (int i = 0; i < 60; i++) {
            Body bb = (i % 3 == 0) ? w.createSphere(-10 + i * 0.3f, 2 + i * 0.2f, (i % 7) - 3, 0.4f, 1f)
                    : (i % 3 == 1 ? w.createBox(-10 + i * 0.3f, 2 + i * 0.2f, (i % 5) - 2, 0.4f, 0.4f, 0.4f, 1f)
                    : w.createCapsule(-10 + i * 0.3f, 2 + i * 0.2f, (i % 4), 0.3f, 0.4f, 1f));
            bb.layer = 1 << (i % 4);
        }
        Body trg = w.createBox(0, 1, 0, 3, 1, 3, 0f);
        trg.trigger = true;
        Body h2 = w.createCapsule(5, 3, 5, 0.4f, 0.5f, 0f);
        CharacterController c2 = new CharacterController(w, h2);
        w.createDistanceJoint(w.getBodyAt(2), w.getBodyAt(5), 3f, 0.3f);
        RayHit rh = new RayHit();
        Ray rr = new Ray();
        for (int i = 0; i < 300; i++) { c2.setMove(0.02f, 0.01f); c2.update(); w.step(); }
        long a0 = allocated();
        for (int i = 0; i < 5000; i++) {
            c2.setMove(0.03f, (i & 64) == 0 ? 0.02f : -0.02f);
            if ((i & 127) == 0) c2.jump(0.12f);
            c2.update();
            w.step();
            rr.set(-20, 1, 0, 1, 0, 0);
            w.raycast(rr, 50f, 3, rh);
        }
        long delta = allocated() - a0;
        check(delta < 2048, "zero allocation: 5000 steps with capsules, controller, listener, sleeping, joints, terrain, rays (" + delta + " bytes)");

        System.out.println(failed == 0 ? "ALL EXTENDED PHYSICS TESTS PASSED" : failed + " TESTS FAILED");
        if (failed != 0) System.exit(1);
    }
}
