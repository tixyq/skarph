// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Moves a player or an enemy through a PhysicsWorld: walks, slides along walls, climbs low steps, stays on slopes up to a
 * limit, jumps, falls and follows a {@link Heightfield}. It drives a capsule body that was created STATIC
 * (mass 0, see {@link PhysicsWorld#createCapsule}): the controller sets the position itself, other bodies bounce off it
 * and get pushed by it. Static boxes, static spheres and capsules, the arena (walls, floor, ceiling) and the terrain
 * are obstacles; dynamic bodies are not (they are pushed away by the world). Layers are respected.
 * <pre>
 *   Body hero = world.createCapsule(0, 2, 0, 0.4f, 0.5f, 0f);
 *   CharacterController cc = new CharacterController(world, hero);
 *   // every tick, before world.step():
 *   cc.setMove(inputX * 0.08f, inputZ * 0.08f);        // units per tick
 *   if (jumpPressed) cc.jump(0.16f);
 *   cc.update();
 *   node.setPosition(hero.x, hero.y, hero.z);
 * </pre>
 * Allocation free. Once per update() the static bodies near the character are collected into a small list (one cheap
 * pass over the world); the many overlap tests of a step then run on that list only.
 */
public final class CharacterController {
    private static final int GROUND = 1;
    private static final int CEILING = 2;

    /** Highest ledge that is climbed without jumping (default 0.3). */
    public float stepHeight = 0.3f;
    /** Steepest ground, in degrees from the horizontal, that counts as standing (terrain slopes only; default 50). */
    public float slopeLimitDeg = 50f;

    private final PhysicsWorld world;
    private final Body body;
    private final Contact c = new Contact();
    private final Vec3 n = new Vec3();
    private float moveX;
    private float moveZ;
    private float vy;
    private boolean grounded;
    private float groundNy = 1f;
    private float cosLimit;
    private float limitDeg = -1f;
    private boolean vertOnly;
    private static final int CAND_CAP = 48;
    private final Body[] cand = new Body[CAND_CAP];
    private int candN;
    private boolean overflow;

    /** @param capsule a CAPSULE body of the world, created with mass 0 */
    public CharacterController(PhysicsWorld world, Body capsule) {
        if (capsule.getType() != PhysicsWorld.CAPSULE) {
            throw new IllegalArgumentException("the controller needs a capsule body");
        }
        this.world = world;
        this.body = capsule;
    }

    public Body getBody() {
        return body;
    }

    /** The horizontal displacement wanted in the next update(), in units per tick. It is consumed by update(). */
    public void setMove(float dx, float dz) {
        moveX = dx;
        moveZ = dz;
    }

    /** Starts a jump with this upward speed (units per tick) if the character stands on something. @return true if it jumped */
    public boolean jump(float speed) {
        if (grounded) {
            vy = speed;
            grounded = false;
            return true;
        }
        return false;
    }

    public boolean isGrounded() {
        return grounded;
    }

    /** y component of the normal of the ground under the feet (1 = flat). Valid while grounded. */
    public float getGroundNormalY() {
        return groundNy;
    }

    public float getVelocityY() {
        return vy;
    }

    public void setVelocityY(float v) {
        vy = v;
    }

    /** Puts the character somewhere else and stops its fall. */
    public void teleport(float x, float y, float z) {
        body.x = x;
        body.y = y;
        body.z = z;
        vy = 0f;
        body.updateBounds();
    }

    /** One tick of movement: gravity, jump, walking, steps, slopes. Call it before PhysicsWorld.step(). */
    public void update() {
        if (limitDeg != slopeLimitDeg) {
            limitDeg = slopeLimitDeg;
            cosLimit = FastMath.cos(limitDeg);
        }
        Body b = body;
        boolean was = grounded;
        // vertical: gravity, then push out of floors and ceilings
        vy += world.gravityY;
        if (was && vy < 0f) {
            vy = world.gravityY;                    // standing: only enough fall to keep contact each tick
        }
        b.y += vy;
        gather(mxAbs(moveX) + mxAbs(moveZ));
        vertOnly = true;                            // vertical motion is resolved vertically (no sliding off ledge corners)
        int f = depenetrate();
        vertOnly = false;
        grounded = (f & GROUND) != 0;
        if (grounded) {
            if (vy < 0f) {
                vy = 0f;
            }
        } else if ((f & CEILING) != 0 && vy > 0f) {
            vy = 0f;
        }
        // horizontal: walk, slide, climb
        float mx = moveX;
        float mz = moveZ;
        moveX = 0f;
        moveZ = 0f;
        if (mx != 0f || mz != 0f) {
            float x0 = b.x;
            float y0 = b.y;
            float z0 = b.z;
            b.x = x0 + mx;
            b.z = z0 + mz;
            int hf = depenetrate();
            if ((hf & GROUND) != 0) {
                grounded = true;
            }
            float ax = b.x - x0;
            float az = b.z - z0;
            float want2 = mx * mx + mz * mz;
            if (grounded && ax * ax + az * az < want2 * 0.6f && stepHeight > 0f) {
                float sx = b.x;
                float sy = b.y;
                float sz = b.z;
                b.x = x0 + mx;
                b.y = y0 + stepHeight;
                b.z = z0 + mz;
                depenetrate();
                float bx = b.x - x0;
                float bz = b.z - z0;
                boolean ok = bx * bx + bz * bz > ax * ax + az * az + want2 * 0.04f;
                if (ok) {
                    b.y -= stepHeight + 0.02f;      // come down on the ledge
                    vertOnly = true;            // settle onto the ledge straight up, not sideways off its corner
                    int df = depenetrate();
                    vertOnly = false;
                    ok = ((df & GROUND) != 0 || supported()) && b.y - y0 <= stepHeight + 0.01f;
                }
                if (ok) {
                    grounded = true;
                    if (vy < 0f) {
                        vy = 0f;
                    }
                } else {
                    b.x = sx;
                    b.y = sy;
                    b.z = sz;
                }
            }
        }
        // walking down a step or a slope: stay on the ground instead of falling off the edge every tick
        if (was && !grounded && vy <= 0f && stepHeight > 0f) {
            float y1 = b.y;
            b.y -= stepHeight;
            int f2 = depenetrate();
            if ((f2 & GROUND) != 0) {
                grounded = true;
                vy = 0f;
            } else {
                b.y = y1;
            }
        }
        b.updateBounds();
    }

    private static float mxAbs(float v) {
        return v < 0f ? -v : v;
    }

    /** Collects the static bodies that can touch the character this tick (its box grown by the move, jump and step). */
    private void gather(float move) {
        Body b = body;
        float ex = b.radius + move + 0.5f;
        float ey = b.halfHeight + b.radius + mxAbs(vy) + stepHeight + 0.5f;
        float x0 = b.x - ex;
        float x1 = b.x + ex;
        float y0 = b.y - ey;
        float y1 = b.y + ey;
        float z0 = b.z - ex;
        float z1 = b.z + ex;
        int limit = world.getBodyLimit();
        int n = 0;
        overflow = false;
        for (int i = 0; i < limit; i++) {
            Body s = world.getBodyAt(i);
            if (s.invMass != 0f || s == b || s.trigger || !s.isActive()) {
                continue;
            }
            Aabb sb = s.bounds;
            if (sb.minX > x1 || sb.maxX < x0 || sb.minZ > z1 || sb.maxZ < z0 || sb.minY > y1 || sb.maxY < y0) {
                continue;
            }
            if (n == CAND_CAP) {
                overflow = true;                    // a very crowded spot: fall back to testing every body
                break;
            }
            cand[n++] = s;
        }
        candN = n;
    }

    /** True if something below pushes the capsule up when it is lowered a little (resting on a corner counts). */
    private boolean supported() {
        float y1 = body.y - 0.03f;
        body.y = y1;
        vertOnly = true;
        int f = depenetrate();
        vertOnly = false;
        return (f & GROUND) != 0 || body.y > y1 + 0.005f;
    }

    /** Pushes the capsule out of everything it overlaps. @return GROUND and / or CEILING bits */
    private int depenetrate() {
        Body b = body;
        int flags = 0;
        float r = b.radius;
        float hh = b.halfHeight;
        for (int iter = 0; iter < 4; iter++) {
            boolean moved = false;
            // arena
            float lo = world.getMinX() + r;
            if (b.x < lo) {
                b.x = lo;
                moved = true;
            } else if (b.x > world.getMaxX() - r) {
                b.x = world.getMaxX() - r;
                moved = true;
            }
            lo = world.getMinZ() + r;
            if (b.z < lo) {
                b.z = lo;
                moved = true;
            } else if (b.z > world.getMaxZ() - r) {
                b.z = world.getMaxZ() - r;
                moved = true;
            }
            if (b.y - hh - r < world.getMinY()) {
                b.y = world.getMinY() + hh + r;
                flags |= GROUND;
                groundNy = 1f;
                moved = true;
            }
            if (world.ceilingY != Float.MAX_VALUE && b.y + hh + r > world.ceilingY) {
                b.y = world.ceilingY - hh - r;
                flags |= CEILING;
                moved = true;
            }
            // terrain
            Heightfield t = world.getTerrain();
            if (t != null) {
                float h = t.getHeight(b.x, b.z);
                float bottom = b.y - hh - r;
                if (bottom < h) {
                    t.getNormal(b.x, b.z, n);
                    if (n.y >= cosLimit) {
                        b.y += h - bottom;
                        flags |= GROUND;
                        groundNy = n.y;
                    } else {
                        float d = (h - bottom) * n.y;          // steep: slide along the surface, not standing
                        b.x += n.x * d;
                        b.y += n.y * d;
                        b.z += n.z * d;
                    }
                    moved = true;
                }
            }
            // static bodies
            int limit = overflow ? world.getBodyLimit() : candN;
            for (int i = 0; i < limit; i++) {
                Body s = overflow ? world.getBodyAt(i) : cand[i];
                if (!s.isActive() || s == b || s.invMass != 0f || s.trigger
                        || (s.layer & b.mask) == 0 || (b.layer & s.mask) == 0) {
                    continue;
                }
                Aabb sb = s.bounds;
                if (sb.minX > b.x + r || sb.maxX < b.x - r || sb.minZ > b.z + r || sb.maxZ < b.z - r
                        || sb.minY > b.y + hh + r || sb.maxY < b.y - hh - r) {
                    continue;
                }
                boolean hit;
                int st = s.getType();
                if (st == PhysicsWorld.BOX) {
                    hit = Collision.capsuleAabb(b.x, b.y, b.z, r, hh, sb, c);
                } else if (st == PhysicsWorld.SPHERE) {
                    hit = Collision.capsuleSphere(b.x, b.y, b.z, r, hh, s.x, s.y, s.z, s.radius, c);
                } else {
                    hit = Collision.capsuleCapsule(b.x, b.y, b.z, r, hh, s.x, s.y, s.z, s.radius, s.halfHeight, c);
                }
                if (!hit || c.depth < 1e-4f) {
                    continue;
                }
                if (vertOnly && c.ny < -0.05f) {
                    float up = c.depth / -c.ny;               // lift out of a ledge corner instead of backing away
                    b.y += up > 0.5f ? 0.5f : up;
                    moved = true;
                    flags |= GROUND;
                    groundNy = 1f;
                    continue;
                }
                // c.n points from the capsule to the other body: we move the opposite way
                b.x -= c.nx * c.depth;
                b.y -= c.ny * c.depth;
                b.z -= c.nz * c.depth;
                moved = true;
                if (-c.ny > 0.7f) {
                    flags |= GROUND;
                    groundNy = -c.ny;
                } else if (c.ny > 0.7f) {
                    flags |= CEILING;
                }
            }
            if (!moved) {
                break;
            }
        }
        return flags;
    }
}
