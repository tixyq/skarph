// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.demo.v08;

import skarph.core.Body;
import skarph.core.Node;
import skarph.core.PhysicsWorld;
import skarph.core.Renderer;
import skarph.core.Scene;
import skarph.core.Shapes;

/**
 * Physics show of the town demo: balls and crates fall on the plaza and pile up around the obelisk.
 * A fixed pool of CAPACITY bodies and scene nodes is made once; spawning takes the oldest slot of a ring (the old body
 * is destroyed first), so nothing is allocated while it runs. Meshes are made in code (Shapes), no files.
 * Plain Java, runs in ticks, own xorshift generator: after reset() the same show starts the same way.
 */
final class PhysicsShow {
    static final int CAPACITY = 80;
    /** A body lives this many ticks (15 s), then its slot is free again. */
    private static final int LIFE = 900;
    private static final int KINDS = 6;                 // 3 colored spheres + 3 colored crates
    private static final float[][] COLORS = {{0.95f, 0.35f, 0.30f}, {0.30f, 0.55f, 0.95f}, {0.95f, 0.80f, 0.25f}};

    private final PhysicsWorld world;
    private final Body[] bodies = new Body[CAPACITY];
    private final Node[] nodes = new Node[CAPACITY];
    private final int[] age = new int[CAPACITY];
    private final int[] meshes = new int[KINDS];
    private int limit = CAPACITY;
    private int next;
    private int active;
    private int timer;
    private int seed = 12345;

    PhysicsShow(Renderer r, Scene scene, PhysicsWorld world) {
        this.world = world;
        for (int i = 0; i < 3; i++) {
            meshes[i] = r.createMesh(Shapes.sphere(1f, 10, 6, COLORS[i][0], COLORS[i][1], COLORS[i][2]));
            meshes[3 + i] = r.createMesh(Shapes.cube(2f, COLORS[i][0] * 0.9f, COLORS[i][1] * 0.9f, COLORS[i][2] * 0.9f));
        }
        for (int i = 0; i < CAPACITY; i++) {
            Node n = scene.createNode(null);
            if (n == null) {
                throw new IllegalStateException("scene is full (physics show)");
            }
            n.visible = false;
            nodes[i] = n;
        }
    }

    /** Quality level: at most n bodies at a time (1..CAPACITY). Bodies above the new limit are removed at once. */
    void setLimit(int n) {
        limit = n < 1 ? 1 : (n > CAPACITY ? CAPACITY : n);
        for (int i = limit; i < CAPACITY; i++) {
            kill(i);
        }
        if (next >= limit) {
            next = 0;
        }
    }

    int getActiveCount() {
        return active;
    }

    /** Removes every body and restarts the random sequence. */
    void reset() {
        for (int i = 0; i < CAPACITY; i++) {
            kill(i);
        }
        next = 0;
        timer = 0;
        seed = 12345;
    }

    void release(Renderer r) {
        for (int i = 0; i < KINDS; i++) {
            r.deleteMesh(meshes[i]);
        }
    }

    /** Once per tick, BEFORE scene.update(). @param sc scene number of the cutscene, -1 = free orbit. */
    void update(int sc) {
        timer++;
        int period = sc == 2 || sc == 4 ? 4 : (sc == -1 ? 10 : 0);      // PLAZA and FINALE: a shower
        if (period > 0 && timer % period == 0) {
            spawn();
        }
        for (int i = 0; i < CAPACITY; i++) {
            if (bodies[i] != null && ++age[i] >= LIFE) {
                kill(i);
            }
        }
        if (active > 0) {
            world.step();
        }
        for (int i = 0; i < CAPACITY; i++) {
            Body b = bodies[i];
            if (b != null) {
                Node n = nodes[i];
                if (n.x != b.x || n.y != b.y || n.z != b.z) {
                    n.setPosition(b.x, b.y, b.z);
                }
            }
        }
    }

    private void spawn() {
        int slot = next;
        next = (next + 1) % limit;
        kill(slot);
        int kind = (int) (rnd() * KINDS);
        if (kind >= KINDS) {
            kind = KINDS - 1;
        }
        float size = 0.35f + 0.3f * rnd();
        float x = (rnd() - 0.5f) * 14f;
        float z = (rnd() - 0.5f) * 14f;
        float y = 12f + rnd() * 4f;
        Body b;
        float bound;
        if (kind < 3) {
            b = world.createSphere(x, y, z, size, size * size * size * 4f);
            bound = 1f;
        } else {
            b = world.createBox(x, y, z, size, size, size, size * size * size * 8f);
            bound = 1.75f;
        }
        if (b == null) {
            return;                                   // world full: skip, never allocate
        }
        b.restitution = 0.55f;
        b.vx = (rnd() - 0.5f) * 0.04f;
        b.vz = (rnd() - 0.5f) * 0.04f;
        Node n = nodes[slot];
        n.mesh = meshes[kind];
        n.boundRadius = bound;
        n.setScale(size, size, size);
        n.setPosition(x, y, z);
        n.visible = true;
        bodies[slot] = b;
        age[slot] = 0;
        active++;
    }

    private void kill(int i) {
        Body b = bodies[i];
        if (b != null) {
            world.destroyBody(b);
            bodies[i] = null;
            nodes[i].visible = false;
            active--;
        }
    }

    /** 0..1 */
    private float rnd() {
        int s = seed;
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        seed = s;
        return (s >>> 8) * (1f / 16777216f);
    }
}
