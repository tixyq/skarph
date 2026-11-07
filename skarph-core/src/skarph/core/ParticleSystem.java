// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * 3D particles on a fixed pool (sparks, smoke, dust). Logic in ticks: call update() once per tick, draw() once per frame.
 * Zero allocation after the constructor. When the pool is empty new particles are dropped (emit returns false).
 *
 * Draw order: alpha particles first, sorted back to front (nearly sorted every frame, so the sort is cheap), then
 * additive ones (no order needed). Draw after the meshes and BEFORE SpriteBatch.begin() (see BillboardBatch).
 */
public final class ParticleSystem {
    private static final class Particle {
        ParticleType type;
        float x, y, z;
        float vx, vy, vz;
        int age;
        int life;
        float depth;
    }

    private final Pool<Particle> pool;
    private final Particle[] active;
    private final int capacity;
    private int count;
    private int seed = 0x2545F491;

    public ParticleSystem(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        active = new Particle[capacity];
        pool = new Pool<Particle>(new Pool.Factory<Particle>() {
            public Particle create() {
                return new Particle();
            }
        }, capacity);
    }

    /** One particle at (x, y, z) with velocity (units per tick) living lifeTicks ticks. false = pool empty or bad args. */
    public boolean emit(ParticleType type, float x, float y, float z, float vx, float vy, float vz, int lifeTicks) {
        if (lifeTicks < 1 || type == null) {
            return false;
        }
        Particle p = pool.obtain();
        if (p == null) {
            return false;
        }
        p.type = type;
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.age = 0;
        p.life = lifeTicks;
        active[count++] = p;
        return true;
    }

    /**
     * A burst of count particles flying in random directions with speed 0.5..1 x speed, life lifeMin..lifeMax ticks.
     * Returns how many were emitted (fewer when the pool runs out).
     */
    public int burst(ParticleType type, float x, float y, float z, int n, float speed, int lifeMin, int lifeMax) {
        int done = 0;
        for (int i = 0; i < n; i++) {
            float dx = 0f, dy = 1f, dz = 0f;
            for (int t = 0; t < 8; t++) {
                float ax = rnd() * 2f - 1f;
                float ay = rnd() * 2f - 1f;
                float az = rnd() * 2f - 1f;
                float l2 = ax * ax + ay * ay + az * az;
                if (l2 > 0.0001f && l2 <= 1f) {
                    float inv = 1f / (float) Math.sqrt(l2);
                    dx = ax * inv;
                    dy = ay * inv;
                    dz = az * inv;
                    break;
                }
            }
            float s = speed * (0.5f + 0.5f * rnd());
            int life = lifeMin + (int) (rnd() * (lifeMax - lifeMin + 1));
            if (life > lifeMax) {
                life = lifeMax;
            }
            if (!emit(type, x, y, z, dx * s, dy * s, dz * s, life)) {
                break;
            }
            done++;
        }
        return done;
    }

    /** One logic tick: move, apply gravity and drag, age, remove the dead. */
    public void update() {
        int i = 0;
        while (i < count) {
            Particle p = active[i];
            ParticleType t = p.type;
            p.vy += t.gravity;
            p.vx *= t.drag;
            p.vy *= t.drag;
            p.vz *= t.drag;
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            p.age++;
            if (p.age >= p.life) {
                active[i] = active[--count];
                active[count] = null;
                p.type = null;
                pool.free(p);
            } else {
                i++;
            }
        }
    }

    /** Sends every particle to the batch (call between bb.begin() and bb.end(); the batch color/blend are changed). */
    public void draw(BillboardBatch bb, Camera cam) {
        float[] v = cam.view.m;
        int n = count;
        for (int i = 0; i < n; i++) {
            Particle p = active[i];
            p.depth = v[2] * p.x + v[6] * p.y + v[10] * p.z + v[14];   // view space z: the farther, the more negative
        }
        for (int i = 1; i < n; i++) {                                     // insertion sort, far (small depth) first
            Particle p = active[i];
            int j = i - 1;
            while (j >= 0 && active[j].depth > p.depth) {
                active[j + 1] = active[j];
                j--;
            }
            active[j + 1] = p;
        }
        drawPass(bb, false);
        drawPass(bb, true);
    }

    private void drawPass(BillboardBatch bb, boolean additive) {
        boolean modeSet = false;
        for (int i = 0; i < count; i++) {
            Particle p = active[i];
            ParticleType t = p.type;
            if (t.additive != additive) {
                continue;
            }
            if (!modeSet) {
                bb.setAdditive(additive);
                modeSet = true;
            }
            float f = (float) p.age / p.life;
            float a = t.alphaStart + (t.alphaEnd - t.alphaStart) * f;
            float size = t.sizeStart + (t.sizeEnd - t.sizeStart) * f;
            if (additive) {
                bb.setColor(t.r * a, t.g * a, t.b * a, 1f);
            } else {
                bb.setColor(t.r, t.g, t.b, a);
            }
            bb.draw(t.animation.getFrame(p.age), p.x, p.y, p.z, size, size);
        }
    }

    /** Removes all particles. */
    public void clear() {
        for (int i = 0; i < count; i++) {
            active[i].type = null;
            pool.free(active[i]);
            active[i] = null;
        }
        count = 0;
    }

    public int getActiveCount() {
        return count;
    }

    public int getCapacity() {
        return capacity;
    }

    /** Seed of the random numbers used by burst() (same seed = same effect). Must not be 0. */
    public void setSeed(int seed) {
        this.seed = seed == 0 ? 1 : seed;
    }

    private float rnd() {
        int s = seed;
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        seed = s;
        return (s >>> 8) * (1f / 16777216f);
    }
}
