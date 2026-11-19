// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Small rigid body world: spheres, vertical capsules and axis-aligned boxes (no rotation),
 * gravity, bouncing, simple friction, a bounded arena (walls, floor, optional ceiling), an optional
 * height field terrain, layers, trigger zones, contact events, sleeping, distance joints and
 * ray casting. Uses a SpatialGrid as broad phase.
 *
 * Tick based like the rest of the engine: call step() once per tick; velocity
 * is in units per tick and gravity in units per tick squared.
 * Fixed capacity, zero allocation after construction.
 *
 * Limits: no body rotation, no triangle mesh collision, no stacking stability guarantees (stacks jitter
 * a little). Fast bodies are swept against static boxes (ccdEnabled), so thin walls hold.
 * Everything added in 1.2 is off or free until used; a world that uses none of it behaves as in 1.1.
 */
public final class PhysicsWorld {
    public static final int SPHERE = 0;
    public static final int BOX = 1;
    /** A capsule with a vertical axis: radius plus halfHeight, see createCapsule(). */
    public static final int CAPSULE = 2;

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
    /** Height of the ceiling (bodies cannot go above it); Float.MAX_VALUE = none (default). */
    public float ceilingY = Float.MAX_VALUE;
    /**
     * true (default): a sphere, capsule or box that moves farther in one tick than half its smallest size is swept against the
     * static boxes first, so it cannot jump through a thin wall. Slow bodies pay one comparison.
     */
    public boolean ccdEnabled = true;
    /** true = resting bodies fall asleep (no integration, no pair work against static or sleeping bodies). Default false. */
    public boolean sleepingEnabled;
    /** A body sleeps after this many ticks of resting (default 30 = half a second). */
    public int sleepTicks = 30;
    /**
     * Speed (units per tick) below which a touching body counts as resting. It must be a little above one tick of gravity
     * (gravityY), default 0.008.
     */
    public float sleepSpeed = 0.008f;
    /** Closing speed above which ContactListener.onHit() is called (units per tick). */
    public float hitSpeedThreshold = 0.02f;
    /** Number of sleeping bodies in the last step(). */
    public int statSleeping;
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

    private Heightfield terrain;
    private final Vec3 tmpN = new Vec3();
    private ContactListener listener;
    private PairSet pairsNow;
    private PairSet pairsBefore;
    private static final int MAX_HITS = 64;
    private final int[] hitA = new int[MAX_HITS];
    private final int[] hitB = new int[MAX_HITS];
    private final float[] hitData = new float[MAX_HITS * 4];
    private int hitCount;

    // distance joints
    private final int[] jointA;
    private final int[] jointB;
    private final float[] jointData;      // per joint: anchor x, y, z, length, stiffness
    private final boolean[] jointOn;
    private int jointCount;

    /**
     * @param maxBodies capacity (all bodies are preallocated)
     * the box (minX..maxX, minY.., maxZ) is the arena: bodies stay inside on x, z and above minY (no ceiling)
     * @param cellSize grid cell size, about the size of a typical body or a bit more
     */
    public PhysicsWorld(int maxBodies, float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, float cellSize) {
        this(maxBodies, minX, minY, minZ, maxX, maxY, maxZ, cellSize, 0);
    }

    /** Same, with room for {@code maxJoints} distance joints. */
    public PhysicsWorld(int maxBodies, float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, float cellSize, int maxJoints) {
        jointA = new int[maxJoints];
        jointB = new int[maxJoints];
        jointData = new float[maxJoints * 5];
        jointOn = new boolean[maxJoints];
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
        pairsNow = new PairSet(maxPairs);
        pairsBefore = new PairSet(maxPairs);
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
        b.layer = 1;
        b.mask = -1;
        b.trigger = false;
        b.halfHeight = 0f;
        b.sleeping = false;
        b.sleepTimer = 0;
        b.touched = false;
        b.uid++;
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

    /**
     * A capsule whose axis is vertical (it never tips over): a cylinder of height 2 * halfHeight capped with two
     * hemispheres of the given radius, centered at (x, y, z). Good for characters; pair it with CharacterController.
     * @param mass 0 = static (CharacterController moves such a body itself). @return the body, or null if the world is full.
     */
    public Body createCapsule(float x, float y, float z, float radius, float halfHeight, float mass) {
        Body b = obtain(x, y, z, mass, CAPSULE);
        if (b != null) {
            b.radius = radius;
            b.halfHeight = halfHeight;
            b.hx = radius;
            b.hy = halfHeight + radius;
            b.hz = radius;
            b.updateBounds();
        }
        return b;
    }

    public void destroyBody(Body b) {
        if (b == null || !b.active) {
            return;
        }
        for (int j = 0; j < jointCount; j++) {
            if (jointOn[j] && (jointA[j] == b.index || jointB[j] == b.index)) {
                jointOn[j] = false;
            }
        }
        if (sleepingEnabled) {
            b.updateBounds();
            wakeNear(b);                                // whatever rested on it must fall
        }
        b.active = false;
        freeList[freeCount++] = b;
        activeCount--;
    }

    /** Wakes the sleeping bodies that touch (or nearly touch) the bounds of b. */
    private void wakeNear(Body b) {
        Aabb o = b.bounds;
        for (int i = 0; i < highWater; i++) {
            Body s = bodies[i];
            if (s.sleeping && s != b) {
                Aabb sb = s.bounds;
                if (sb.minX <= o.maxX + 0.1f && sb.maxX >= o.minX - 0.1f && sb.minY <= o.maxY + 0.1f
                        && sb.maxY >= o.minY - 0.1f && sb.minZ <= o.maxZ + 0.1f && sb.maxZ >= o.minZ - 0.1f) {
                    s.wake();
                }
            }
        }
    }

    public int getBodyCount() {
        return activeCount;
    }

    public int getFreeBodies() {
        return freeCount;
    }

    /** Sets (or clears with null) the receiver of contact events. Costs nothing while null. */
    public void setListener(ContactListener l) {
        listener = l;
        pairsNow.clear();
        pairsBefore.clear();
        hitCount = 0;
    }

    /** Sets (or clears with null) a height field terrain that spheres, capsules and boxes collide with. */
    public void setTerrain(Heightfield t) {
        terrain = t;
    }

    public Heightfield getTerrain() {
        return terrain;
    }

    /** The arena: bodies stay between these limits (the floor is minY). */
    public float getMinX() {
        return minX;
    }

    public float getMinY() {
        return minY;
    }

    public float getMinZ() {
        return minZ;
    }

    public float getMaxX() {
        return maxX;
    }

    public float getMaxZ() {
        return maxZ;
    }

    /** Upper bound of body indices in use, for loops with getBodyAt(). */
    public int getBodyLimit() {
        return highWater;
    }

    /** The body with this slot index (check isActive()); the same objects createXxx() returned. */
    public Body getBodyAt(int index) {
        return bodies[index];
    }

    /** Wakes every sleeping body. */
    public void wakeAll() {
        for (int i = 0; i < highWater; i++) {
            bodies[i].wake();
        }
    }

    /**
     * Joins two bodies at a distance: the pair is pulled together or pushed apart toward {@code length}. stiffness 1 is a rigid
     * rod (a taut rope: it only pulls when {@code rope} is on), small values (0.01 .. 0.3) are springs. Pass null as b to
     * hang a on a fixed anchor point (ax, ay, az). Bodies of a joint never sleep.
     * @return the joint id, or -1 if the world has no room (see the constructor with maxJoints)
     */
    public int createDistanceJoint(Body a, Body b, float ax, float ay, float az, float length, float stiffness) {
        if (jointCount >= jointA.length || a == null) {
            return -1;
        }
        int id = -1;
        for (int j = 0; j < jointCount; j++) {
            if (!jointOn[j]) {
                id = j;
                break;
            }
        }
        if (id < 0) {
            id = jointCount++;
        }
        jointA[id] = a.index;
        jointB[id] = b != null ? b.index : -1;
        jointData[id * 5] = ax;
        jointData[id * 5 + 1] = ay;
        jointData[id * 5 + 2] = az;
        jointData[id * 5 + 3] = length;
        jointData[id * 5 + 4] = stiffness;
        jointOn[id] = true;
        return id;
    }

    /** Joint between two bodies, see the longer overload. */
    public int createDistanceJoint(Body a, Body b, float length, float stiffness) {
        return createDistanceJoint(a, b, 0f, 0f, 0f, length, stiffness);
    }

    public void destroyJoint(int id) {
        if (id >= 0 && id < jointCount) {
            jointOn[id] = false;
        }
    }

    /** Changes the rest length of a joint (winch, rope length). */
    public void setJointLength(int id, float length) {
        if (id >= 0 && id < jointCount) {
            jointData[id * 5 + 3] = length;
        }
    }

    private void solveJoints() {
        for (int j = 0; j < jointCount; j++) {
            if (!jointOn[j]) {
                continue;
            }
            Body a = bodies[jointA[j]];
            int bi = jointB[j];
            Body b = bi >= 0 ? bodies[bi] : null;
            if (!a.active || (b != null && !b.active)) {
                jointOn[j] = false;
                continue;
            }
            a.wake();
            float tx;
            float ty;
            float tz;
            float ib;
            if (b != null) {
                b.wake();
                tx = b.x;
                ty = b.y;
                tz = b.z;
                ib = b.invMass;
            } else {
                tx = jointData[j * 5];
                ty = jointData[j * 5 + 1];
                tz = jointData[j * 5 + 2];
                ib = 0f;
            }
            float dx = tx - a.x;
            float dy = ty - a.y;
            float dz = tz - a.z;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < 1e-12f) {
                continue;
            }
            float d = (float) Math.sqrt(d2);
            float inv = a.invMass + ib;
            if (inv == 0f) {
                continue;
            }
            float k = jointData[j * 5 + 4];
            float corr = (d - jointData[j * 5 + 3]) / d * k / inv;
            a.x += dx * corr * a.invMass;
            a.y += dy * corr * a.invMass;
            a.z += dz * corr * a.invMass;
            if (b != null) {
                b.x -= dx * corr * ib;
                b.y -= dy * corr * ib;
                b.z -= dz * corr * ib;
            }
            // remove the relative speed along the joint so it does not stretch again at once
            float nx = dx / d;
            float ny = dy / d;
            float nz = dz / d;
            float rvn = ((b != null ? b.vx : 0f) - a.vx) * nx + ((b != null ? b.vy : 0f) - a.vy) * ny
                    + ((b != null ? b.vz : 0f) - a.vz) * nz;
            float jn = rvn * k / inv;
            a.vx += nx * jn * a.invMass;
            a.vy += ny * jn * a.invMass;
            a.vz += nz * jn * a.invMass;
            if (b != null) {
                b.vx -= nx * jn * ib;
                b.vy -= ny * jn * ib;
                b.vz -= nz * jn * ib;
            }
        }
    }

    /** Advances the simulation by one tick. */
    public void step() {
        float g = gravityY;
        float rest = 3f * (g < 0f ? -g : g);
        boolean sleepOn = sleepingEnabled;
        ContactListener ls = listener;

        for (int i = 0; i < highWater; i++) {
            Body b = bodies[i];
            if (!b.active) {
                continue;
            }
            if (b.sleeping) {
                if (b.vx != 0f || b.vy != 0f || b.vz != 0f || b.x != b.sleepX || b.y != b.sleepY || b.z != b.sleepZ) {
                    b.wake();                           // the game moved it or pushed it
                } else {
                    b.updateBounds();
                    continue;
                }
            }
            b.touched = false;
            if (b.invMass > 0f) {
                float ox = b.x;
                float oy = b.y;
                float oz = b.z;
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
                if (ccdEnabled) {
                    sweepStatic(b, ox, oy, oz, rest);
                }
                collideArena(b, rest);
            } else if (sleepOn && (b.x != b.sleepX || b.y != b.sleepY || b.z != b.sleepZ)) {
                // a static body that the game moved (platform, door): bodies resting on it must wake up
                b.sleepX = b.x;
                b.sleepY = b.y;
                b.sleepZ = b.z;
                wakeNear(b);                            // what rested on the old place ...
                b.updateBounds();
                wakeNear(b);                            // ... and what is at the new one
            }
            b.updateBounds();
        }

        int count = broadPhase();
        statPairs = count;
        statContacts = 0;
        PairSet now = pairsNow;
        if (ls != null) {
            now.clear();
            hitCount = 0;
        }
        for (int it = 0; it < solverIterations; it++) {
            if (jointCount > 0) {
                solveJoints();
            }
            for (int p = 0; p < count; p++) {
                Body a = bodies[pairs[p * 2]];
                Body b = bodies[pairs[p * 2 + 1]];
                if (a.invMass + b.invMass == 0f) {
                    continue;
                }
                if (((a.layer & b.mask) == 0) || ((b.layer & a.mask) == 0)) {
                    continue;
                }
                boolean sa = false;
                boolean sb = false;
                if (sleepOn) {
                    sa = a.sleeping;
                    sb = b.sleeping;
                    if ((sa || a.invMass == 0f) && (sb || b.invMass == 0f)) {
                        continue;                       // nothing here can move
                    }
                }
                if (!narrowPhase(a, b, contact)) {
                    continue;
                }
                if (it == 0) {
                    statContacts++;
                    if (ls != null) {
                        if (a.index < b.index) {
                            now.add(a.index, a.uid, b.index, b.uid);
                        } else {
                            now.add(b.index, b.uid, a.index, a.uid);
                        }
                    }
                }
                if (a.trigger || b.trigger) {
                    continue;                           // reported, never resolved
                }
                if (sa || sb) {
                    // a resting body is woken by something that really moves, otherwise it acts as a wall
                    Body mover = sa ? b : a;
                    float sp = mover.vx * mover.vx + mover.vy * mover.vy + mover.vz * mover.vz;
                    float wake = sleepSpeed * 3f;
                    if (sp > wake * wake) {
                        a.wake();
                        b.wake();
                    } else {
                        float ia = a.invMass;
                        float ib = b.invMass;
                        if (sa) {
                            a.invMass = 0f;
                        }
                        if (sb) {
                            b.invMass = 0f;
                        }
                        resolve(a, b, contact, rest);
                        a.invMass = ia;
                        b.invMass = ib;
                        a.updateBounds();
                        b.updateBounds();
                        continue;
                    }
                }
                a.touched = true;
                b.touched = true;
                float closing = resolve(a, b, contact, rest);
                if (it == 0 && ls != null && closing > hitSpeedThreshold && hitCount < MAX_HITS) {
                    hitA[hitCount] = a.index;
                    hitB[hitCount] = b.index;
                    int o = hitCount * 4;
                    hitData[o] = contact.nx;
                    hitData[o + 1] = contact.ny;
                    hitData[o + 2] = contact.nz;
                    hitData[o + 3] = closing;
                    hitCount++;
                }
                a.updateBounds();
                b.updateBounds();
            }
        }

        int asleep = 0;
        float sleepV2 = sleepSpeed * sleepSpeed;
        for (int i = 0; i < highWater; i++) {
            Body b = bodies[i];
            if (!b.active) {
                continue;
            }
            if (b.invMass > 0f && !b.sleeping) {
                collideArena(b, rest);
                if (b.onFloor && b.vx * b.vx + b.vz * b.vz < 1e-7f) {
                    b.vx = 0f;
                    b.vz = 0f;
                }
                b.updateBounds();
                if (sleepOn) {
                    if ((b.touched || b.onFloor) && b.vx * b.vx + b.vy * b.vy + b.vz * b.vz < sleepV2) {
                        if (++b.sleepTimer >= sleepTicks) {
                            b.sleeping = true;
                            b.vx = 0f;
                            b.vy = 0f;
                            b.vz = 0f;
                            b.sleepX = b.x;
                            b.sleepY = b.y;
                            b.sleepZ = b.z;
                        }
                    } else {
                        b.sleepTimer = 0;
                    }
                }
            }
            if (b.sleeping) {
                asleep++;
            }
        }
        statSleeping = asleep;
        if (ls != null) {
            dispatch(ls);
        }
    }

    /** Tells the listener what changed since the last step. */
    private void dispatch(ContactListener ls) {
        PairSet now = pairsNow;
        PairSet before = pairsBefore;
        for (int i = 0; i < now.size; i++) {
            if (before.find(now.a[i], now.ua[i], now.b[i], now.ub[i]) < 0) {
                Body a = bodies[now.a[i]];
                Body b = bodies[now.b[i]];
                ls.onBegin(a, b, a.trigger || b.trigger);
            }
        }
        for (int i = 0; i < before.size; i++) {
            if (now.find(before.a[i], before.ua[i], before.b[i], before.ub[i]) < 0) {
                Body a = bodies[before.a[i]];
                Body b = bodies[before.b[i]];
                ls.onEnd(a, b, a.trigger || b.trigger);
            }
        }
        for (int i = 0; i < hitCount; i++) {
            int o = i * 4;
            ls.onHit(bodies[hitA[i]], bodies[hitB[i]], hitData[o], hitData[o + 1], hitData[o + 2], hitData[o + 3]);
        }
        hitCount = 0;
        // this step's pairs become the old ones (swap, nothing is allocated)
        pairsBefore = now;
        pairsNow = before;
        pairsNow.clear();
    }

    private static float clamp(float v, float limit) {
        return v > limit ? limit : (v < -limit ? -limit : v);
    }

    /** Moves a fast body back to where it first touches a static box on its way (and bounces it). */
    private void sweepStatic(Body b, float ox, float oy, float oz, float rest) {
        float dx = b.x - ox;
        float dy = b.y - oy;
        float dz = b.z - oz;
        float ex;
        float ey;
        float ez;
        float small;
        if (b.type == SPHERE) {
            ex = b.radius;
            ey = b.radius;
            ez = b.radius;
            small = b.radius;
        } else if (b.type == CAPSULE) {
            ex = b.radius;
            ey = b.halfHeight + b.radius;
            ez = b.radius;
            small = b.radius;
        } else {
            ex = b.hx;
            ey = b.hy;
            ez = b.hz;
            small = ex < ey ? (ex < ez ? ex : ez) : (ey < ez ? ey : ez);
        }
        float lim = small * 0.5f;
        float adx = dx < 0f ? -dx : dx;
        float ady = dy < 0f ? -dy : dy;
        float adz = dz < 0f ? -dz : dz;
        if (adx <= lim && ady <= lim && adz <= lim) {
            return;                                         // the common case: slow, no tunnelling possible
        }
        float best = 2f;
        int axis = -1;
        float sign = 0f;
        for (int i = 0; i < highWater; i++) {
            Body s = bodies[i];
            if (!s.active || s == b || s.invMass != 0f || s.type != BOX || s.trigger
                    || (s.layer & b.mask) == 0 || (b.layer & s.mask) == 0) {
                continue;
            }
            Aabb sb = s.bounds;
            float tmin = -1e30f;
            float tmax = 1e30f;
            int hitAxis = -1;
            float hitSign = 0f;
            for (int k = 0; k < 3; k++) {
                float o;
                float d;
                float lo;
                float hi;
                if (k == 0) {
                    o = ox; d = dx; lo = sb.minX - ex; hi = sb.maxX + ex;
                } else if (k == 1) {
                    o = oy; d = dy; lo = sb.minY - ey; hi = sb.maxY + ey;
                } else {
                    o = oz; d = dz; lo = sb.minZ - ez; hi = sb.maxZ + ez;
                }
                if (d > -1e-9f && d < 1e-9f) {
                    if (o < lo || o > hi) {
                        tmin = 2f;
                        break;
                    }
                } else {
                    float t1 = (lo - o) / d;
                    float t2 = (hi - o) / d;
                    float sg = -1f;                             // entering through the low face: normal points -axis
                    if (t1 > t2) {
                        float tmp = t1;
                        t1 = t2;
                        t2 = tmp;
                        sg = 1f;
                    }
                    if (t1 > tmin) {
                        tmin = t1;
                        hitAxis = k;
                        hitSign = sg;
                    }
                    if (t2 < tmax) {
                        tmax = t2;
                    }
                    if (tmin > tmax) {
                        tmin = 2f;
                        break;
                    }
                }
            }
            if (hitAxis >= 0 && tmin >= 0f && tmin < best && tmin < 1f && tmin <= tmax) {
                best = tmin;
                axis = hitAxis;
                sign = hitSign;
            }
        }
        if (axis < 0) {
            return;
        }
        float nx = axis == 0 ? sign : 0f;
        float ny = axis == 1 ? sign : 0f;
        float nz = axis == 2 ? sign : 0f;
        b.x = ox + dx * best + nx * 0.001f;
        b.y = oy + dy * best + ny * 0.001f;
        b.z = oz + dz * best + nz * 0.001f;
        float vn = b.vx * nx + b.vy * ny + b.vz * nz;
        if (vn < 0f) {
            float e = -vn < rest ? 0f : b.restitution;
            b.vx -= (1f + e) * vn * nx;
            b.vy -= (1f + e) * vn * ny;
            b.vz -= (1f + e) * vn * nz;
        }
    }

    private void collideArena(Body b, float rest) {
        float ex;
        float ey;
        float ez;
        if (b.type == SPHERE) {
            ex = b.radius;
            ey = b.radius;
            ez = b.radius;
        } else if (b.type == CAPSULE) {
            ex = b.radius;
            ey = b.halfHeight + b.radius;
            ez = b.radius;
        } else {
            ex = b.hx;
            ey = b.hy;
            ez = b.hz;
        }
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
        if (ceilingY != Float.MAX_VALUE && b.y + ey > ceilingY) {
            b.y = ceilingY - ey;
            if (b.vy > 0f) {
                b.vy = -b.vy * wallRestitution;
            }
        }
        if (terrain != null) {
            collideTerrain(b, ex, ey, ez, rest);
        }
    }

    private void collideTerrain(Body b, float ex, float ey, float ez, float rest) {
        Heightfield t = terrain;
        float h = t.getHeight(b.x, b.z);
        if (b.type == BOX) {
            float h1 = t.getHeight(b.x - ex, b.z - ez);
            float h2 = t.getHeight(b.x + ex, b.z - ez);
            float h3 = t.getHeight(b.x - ex, b.z + ez);
            float h4 = t.getHeight(b.x + ex, b.z + ez);
            h = h > h1 ? h : h1;
            h = h > h2 ? h : h2;
            h = h > h3 ? h : h3;
            h = h > h4 ? h : h4;
        }
        float bottom = b.y - ey;
        if (bottom >= h) {
            return;
        }
        t.getNormal(b.x, b.z, tmpN);
        b.y += h - bottom;
        float vn = b.vx * tmpN.x + b.vy * tmpN.y + b.vz * tmpN.z;
        if (vn < 0f) {
            float e = (-vn > rest) ? wallRestitution : 0f;
            b.vx -= (1f + e) * vn * tmpN.x;
            b.vy -= (1f + e) * vn * tmpN.y;
            b.vz -= (1f + e) * vn * tmpN.z;
        }
        if (tmpN.y > 0.5f) {
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
        int ta = a.type;
        int tb = b.type;
        if (ta == SPHERE) {
            if (tb == SPHERE) {
                return Collision.sphereSphere(a.x, a.y, a.z, a.radius, b.x, b.y, b.z, b.radius, c);
            }
            if (tb == CAPSULE) {
                if (!Collision.capsuleSphere(b.x, b.y, b.z, b.radius, b.halfHeight, a.x, a.y, a.z, a.radius, c)) {
                    return false;
                }
                c.nx = -c.nx;
                c.ny = -c.ny;
                c.nz = -c.nz;
                return true;
            }
            return Collision.sphereAabb(a.x, a.y, a.z, a.radius, b.bounds, c);
        }
        if (ta == CAPSULE) {
            if (tb == SPHERE) {
                return Collision.capsuleSphere(a.x, a.y, a.z, a.radius, a.halfHeight, b.x, b.y, b.z, b.radius, c);
            }
            if (tb == CAPSULE) {
                return Collision.capsuleCapsule(a.x, a.y, a.z, a.radius, a.halfHeight,
                        b.x, b.y, b.z, b.radius, b.halfHeight, c);
            }
            return Collision.capsuleAabb(a.x, a.y, a.z, a.radius, a.halfHeight, b.bounds, c);
        }
        if (tb == SPHERE) {
            if (!Collision.sphereAabb(b.x, b.y, b.z, b.radius, a.bounds, c)) {
                return false;
            }
            c.nx = -c.nx; // the helper's normal points sphere -> box; we need a -> b
            c.ny = -c.ny;
            c.nz = -c.nz;
            return true;
        }
        if (tb == CAPSULE) {
            if (!Collision.capsuleAabb(b.x, b.y, b.z, b.radius, b.halfHeight, a.bounds, c)) {
                return false;
            }
            c.nx = -c.nx;
            c.ny = -c.ny;
            c.nz = -c.nz;
            return true;
        }
        return Collision.aabbAabb(a.bounds, b.bounds, c);
    }

    /** @return the closing speed along the normal before the impulse (0 if the bodies were separating) */
    private float resolve(Body a, Body b, Contact c, float rest) {
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
            return 0f; // already separating
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
        return -vn;
    }

    /** Finds the nearest body hit by the ray within maxDist (all layers). */
    public boolean raycast(Ray ray, float maxDist, RayHit hit) {
        return raycast(ray, maxDist, -1, hit);
    }

    /**
     * Finds the nearest hit within maxDist among bodies whose layer is in layerMask (triggers are ignored). If the terrain
     * is nearer, hit.body is null and hit.t / x / y / z describe the terrain point.
     */
    public boolean raycast(Ray ray, float maxDist, int layerMask, RayHit hit) {
        float best = maxDist;
        Body found = null;
        for (int i = 0; i < highWater; i++) {
            Body b = bodies[i];
            if (!b.active || b.trigger || (b.layer & layerMask) == 0) {
                continue;
            }
            float t;
            if (b.type == SPHERE) {
                t = ray.intersectSphere(b.x, b.y, b.z, b.radius);
            } else if (b.type == CAPSULE) {
                t = ray.intersectCapsule(b.x, b.y, b.z, b.radius, b.halfHeight);
            } else {
                t = ray.intersectAabb(b.bounds);
            }
            if (t >= 0f && t < best) {
                best = t;
                found = b;
            }
        }
        boolean ground = false;
        if (terrain != null) {
            float t = terrain.raycast(ray, best);
            if (t >= 0f && t < best) {
                best = t;
                found = null;
                ground = true;
            }
        }
        if (found == null && !ground) {
            return false;
        }
        hit.body = found;
        hit.t = best;
        hit.x = ray.ox + ray.dx * best;
        hit.y = ray.oy + ray.dy * best;
        hit.z = ray.oz + ray.dz * best;
        return true;
    }

    /**
     * Draws every body as lines: static gray, dynamic green, sleeping blue, triggers yellow. Call before DebugDraw.flush().
     * Allocation free.
     */
    public void debugDraw(DebugDraw dd) {
        for (int i = 0; i < highWater; i++) {
            Body b = bodies[i];
            if (!b.active) {
                continue;
            }
            if (b.trigger) {
                dd.setColor(1f, 0.9f, 0.1f, 0.8f);
            } else if (b.sleeping) {
                dd.setColor(0.3f, 0.5f, 1f, 0.8f);
            } else if (b.invMass == 0f) {
                dd.setColor(0.6f, 0.6f, 0.6f, 0.8f);
            } else {
                dd.setColor(0.2f, 1f, 0.3f, 0.9f);
            }
            if (b.type == SPHERE) {
                dd.sphere(b.x, b.y, b.z, b.radius);
            } else if (b.type == CAPSULE) {
                dd.capsule(b.x, b.y, b.z, b.radius, b.halfHeight);
            } else {
                dd.box(b.bounds);
            }
        }
        dd.setColor(1f, 1f, 1f, 1f);
    }
}
