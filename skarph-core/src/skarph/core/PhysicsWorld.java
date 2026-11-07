// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Small rigid body world: spheres and axis-aligned boxes (no rotation),
 * gravity, bouncing, simple friction, a bounded arena (walls and floor) and
 * ray casting. Uses a SpatialGrid as broad phase.
 *
 * Tick based like the rest of the engine: call step() once per tick; velocity
 * is in units per tick and gravity in units per tick squared.
 * Fixed capacity, zero allocation after construction.
 *
 * Limits: no body rotation, no stacking stability guarantees (stacks jitter
 * a little), fast bodies can tunnel through thin static boxes
 * (keep speed below the smallest body size; maxSpeed clamps it).
 */
public final class PhysicsWorld {
    public static final int SPHERE = 0;
    public static final int BOX = 1;

    private static final float SLOP = 0.005f;
    private static final float CORRECTION_PERCENT = 0.8f;

    /** Acceleration along y in units per tick^2 (negative = down). */
    public float gravityY = -0.0055f;
    /** Velocity multiplier per tick (air resistance). */
    public float damping = 0.999f;
    /** Fraction of horizontal speed lost per tick while on the floor. */
    public float floorFriction = 0.03f;
    /** Fraction of relative sliding speed removed per contact between bodies. */
    public float contactFriction = 0.05f;
    /** Bounciness of the arena walls and floor. */
    public float wallRestitution = 0.5f;
    /** Per-axis speed limit in units per tick. */
    public float maxSpeed = 0.6f;
    public int solverIterations = 2;
    /** false = test all pairs (slow, for comparison); true = use the grid. */
    public boolean useGrid = true;

    /** Statistics of the last step(). */
    public int statPairTests;
    public int statPairs;
    public int statContacts;
    public int statDropped;

    private final float minX;
    private final float minY;
    private final float minZ;
    private final float maxX;
    private final float maxZ;

    private final Body[] bodies;
    private final Body[] freeList;
    private int freeCount;
    private int highWater;
    private int activeCount;

    private final SpatialGrid grid;
    private final int[] pairs;
    private final int maxPairs;
    private final Contact contact = new Contact();

    /**
     * @param maxBodies capacity (all bodies are preallocated)
     * the box (minX..maxX, minY.., maxZ) is the arena: bodies stay inside on x, z and above minY (no ceiling)
     * @param cellSize grid cell size, about the size of a typical body or a bit more
     */
    public PhysicsWorld(int maxBodies, float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, float cellSize) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        bodies = new Body[maxBodies];
        freeList = new Body[maxBodies];
        for (int i = 0; i < maxBodies; i++) {
            bodies[i] = new Body(i);
        }
        for (int i = maxBodies - 1; i >= 0; i--) {
            freeList[freeCount++] = bodies[i];
        }
        int cx = Math.max(1, (int) Math.ceil((maxX - minX) / cellSize));
        int cy = Math.max(1, (int) Math.ceil((maxY - minY) / cellSize));
        int cz = Math.max(1, (int) Math.ceil((maxZ - minZ) / cellSize));
        grid = new SpatialGrid(minX, minY, minZ, cellSize, cx, cy, cz, maxBodies, maxBodies * 16);
        maxPairs = maxBodies * 8;
        pairs = new int[maxPairs * 2];
    }

    private Body obtain(float x, float y, float z, float mass, int type) {
        if (freeCount == 0) {
            return null;
        }
        Body b = freeList[--freeCount];
        b.x = x;
        b.y = y;
        b.z = z;
        b.vx = 0f;
        b.vy = 0f;
        b.vz = 0f;
        b.invMass = mass > 0f ? 1f / mass : 0f;
        b.restitution = 0.5f;
        b.tag = 0;
        b.onFloor = false;
        b.type = type;
        b.active = true;
        if (b.index >= highWater) {
            highWater = b.index + 1;
        }
        activeCount++;
        return b;
    }

    /** @param mass 0 = static body. @return the body, or null if the world is full. */
    public Body createSphere(float x, float y, float z, float radius, float mass) {
        Body b = obtain(x, y, z, mass, SPHERE);
        if (b != null) {
            b.radius = radius;
            b.hx = radius;
            b.hy = radius;
            b.hz = radius;
            b.updateBounds();
        }
        return b;
    }

    /** @param mass 0 = static body. @return the body, or null if the world is full. */
    public Body createBox(float x, float y, float z, float hx, float hy, float hz, float mass) {
        Body b = obtain(x, y, z, mass, BOX);
        if (b != null) {
            b.hx = hx;
            b.hy = hy;
            b.hz = hz;
            b.radius = 0f;
            b.updateBounds();
        }
        return b;
    }

    public void destroyBody(Body b) {
        if (b == null || !b.active) {
            return;
        }
        b.active = false;
        freeList[freeCount++] = b;
        activeCount--;
    }

    public int getBodyCount() {
        return activeCount;
    }

    public int getFreeBodies() {
        return freeCount;
    }

    /** Advances the simulation by one tick. */
    public void step() {
        float g = gravityY;
        float rest = 3f * (g < 0f ? -g : g);

        for (int i = 0; i < highWater; i++) {
            Body b = bodies[i];
            if (!b.active) {
                continue;
            }
            if (b.invMass > 0f) {
                b.vy += g;
                b.vx *= damping;
                b.vy *= damping;
                b.vz *= damping;
                b.vx = clamp(b.vx, maxSpeed);
                b.vy = clamp(b.vy, maxSpeed);
                b.vz = clamp(b.vz, maxSpeed);
                b.x += b.vx;
                b.y += b.vy;
                b.z += b.vz;
                collideArena(b, rest);
            }
            b.updateBounds();
        }

        int count = broadPhase();
        statPairs = count;
        statContacts = 0;
        for (int it = 0; it < solverIterations; it++) {
            for (int p = 0; p < count; p++) {
                Body a = bodies[pairs[p * 2]];
                Body b = bodies[pairs[p * 2 + 1]];
                if (a.invMass + b.invMass == 0f) {
                    continue;
                }
                if (!narrowPhase(a, b, contact)) {
                    continue;
                }
                if (it == 0) {
                    statContacts++;
                }
                resolve(a, b, contact, rest);
                a.updateBounds();
                b.updateBounds();
            }
        }

        for (int i = 0; i < highWater; i++) {
            Body b = bodies[i];
            if (b.active && b.invMass > 0f) {
                collideArena(b, rest);
                if (b.onFloor && b.vx * b.vx + b.vz * b.vz < 1e-7f) {
                    b.vx = 0f;
                    b.vz = 0f;
                }
                b.updateBounds();
            }
        }
    }

    private static float clamp(float v, float limit) {
        return v > limit ? limit : (v < -limit ? -limit : v);
    }

    private void collideArena(Body b, float rest) {
        float ex = b.type == SPHERE ? b.radius : b.hx;
        float ey = b.type == SPHERE ? b.radius : b.hy;
        float ez = b.type == SPHERE ? b.radius : b.hz;
        if (b.x - ex < minX) {
            b.x = minX + ex;
            if (b.vx < 0f) {
                b.vx = -b.vx * wallRestitution;
            }
        } else if (b.x + ex > maxX) {
            b.x = maxX - ex;
            if (b.vx > 0f) {
                b.vx = -b.vx * wallRestitution;
            }
        }
        if (b.z - ez < minZ) {
            b.z = minZ + ez;
            if (b.vz < 0f) {
                b.vz = -b.vz * wallRestitution;
            }
        } else if (b.z + ez > maxZ) {
            b.z = maxZ - ez;
            if (b.vz > 0f) {
                b.vz = -b.vz * wallRestitution;
            }
        }
        b.onFloor = false;
        if (b.y - ey < minY) {
            b.y = minY + ey;
            if (b.vy < 0f) {
                b.vy = (-b.vy > rest) ? -b.vy * wallRestitution : 0f;
            }
            b.onFloor = true;
            b.vx *= 1f - floorFriction;
            b.vz *= 1f - floorFriction;
        }
    }

    private int broadPhase() {
        if (useGrid) {
            grid.clear();
            statDropped = 0;
            for (int i = 0; i < highWater; i++) {
                Body b = bodies[i];
                if (b.active && !grid.insert(i, b.bounds)) {
                    statDropped++;
                }
            }
            int n = grid.findPairs(pairs, maxPairs);
            statPairTests = grid.getPairTests();
            return n;
        }
        int n = 0;
        int tests = 0;
        for (int i = 0; i < highWater; i++) {
            Body a = bodies[i];
            if (!a.active) {
                continue;
            }
            for (int j = i + 1; j < highWater; j++) {
                Body b = bodies[j];
                if (!b.active) {
                    continue;
                }
                tests++;
                if (a.bounds.overlaps(b.bounds) && n < maxPairs) {
                    pairs[n * 2] = i;
                    pairs[n * 2 + 1] = j;
                    n++;
                }
            }
        }
        statPairTests = tests;
        statDropped = 0;
        return n;
    }

    private static boolean narrowPhase(Body a, Body b, Contact c) {
        if (a.type == SPHERE) {
            if (b.type == SPHERE) {
                return Collision.sphereSphere(a.x, a.y, a.z, a.radius, b.x, b.y, b.z, b.radius, c);
            }
            return Collision.sphereAabb(a.x, a.y, a.z, a.radius, b.bounds, c);
        }
        if (b.type == SPHERE) {
            if (!Collision.sphereAabb(b.x, b.y, b.z, b.radius, a.bounds, c)) {
                return false;
            }
            c.nx = -c.nx; // the helper's normal points sphere -> box; we need a -> b
            c.ny = -c.ny;
            c.nz = -c.nz;
            return true;
        }
        return Collision.aabbAabb(a.bounds, b.bounds, c);
    }

    private void resolve(Body a, Body b, Contact c, float rest) {
        float inv = a.invMass + b.invMass;
        float nx = c.nx;
        float ny = c.ny;
        float nz = c.nz;

        float corr = c.depth - SLOP;
        if (corr > 0f) {
            corr = corr * CORRECTION_PERCENT / inv;
            a.x -= nx * corr * a.invMass;
            a.y -= ny * corr * a.invMass;
            a.z -= nz * corr * a.invMass;
            b.x += nx * corr * b.invMass;
            b.y += ny * corr * b.invMass;
            b.z += nz * corr * b.invMass;
        }

        float rvx = b.vx - a.vx;
        float rvy = b.vy - a.vy;
        float rvz = b.vz - a.vz;
        float vn = rvx * nx + rvy * ny + rvz * nz;
        if (vn >= 0f) {
            return; // already separating
        }
        float e = a.restitution < b.restitution ? a.restitution : b.restitution;
        if (-vn < rest) {
            e = 0f;
        }
        float j = -(1f + e) * vn / inv;
        a.vx -= j * nx * a.invMass;
        a.vy -= j * ny * a.invMass;
        a.vz -= j * nz * a.invMass;
        b.vx += j * nx * b.invMass;
        b.vy += j * ny * b.invMass;
        b.vz += j * nz * b.invMass;

        float tx = rvx - vn * nx;
        float ty = rvy - vn * ny;
        float tz = rvz - vn * nz;
        float jf = contactFriction / inv;
        a.vx += tx * jf * a.invMass;
        a.vy += ty * jf * a.invMass;
        a.vz += tz * jf * a.invMass;
        b.vx -= tx * jf * b.invMass;
        b.vy -= ty * jf * b.invMass;
        b.vz -= tz * jf * b.invMass;
    }

    /** Finds the nearest body hit by the ray within maxDist. */
    public boolean raycast(Ray ray, float maxDist, RayHit hit) {
        float best = maxDist;
        Body found = null;
        for (int i = 0; i < highWater; i++) {
            Body b = bodies[i];
            if (!b.active) {
                continue;
            }
            float t = b.type == SPHERE
                    ? ray.intersectSphere(b.x, b.y, b.z, b.radius)
                    : ray.intersectAabb(b.bounds);
            if (t >= 0f && t < best) {
                best = t;
                found = b;
            }
        }
        if (found == null) {
            return false;
        }
        hit.body = found;
        hit.t = best;
        hit.x = ray.ox + ray.dx * best;
        hit.y = ray.oy + ray.dy * best;
        hit.z = ray.oz + ray.dz * best;
        return true;
    }
}
