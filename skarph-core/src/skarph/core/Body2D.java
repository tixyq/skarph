// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A box (centre x, y; half sizes) that moves through a {@link TileMap}: gravity, walking into walls, landing on floors,
 * one-way platforms, ceilings. For platformers and top-down games (gravity 0). Units per tick; y points down.
 * <pre>
 *   Body2D hero = new Body2D(100, 50, 6, 8);        // centre, half width, half height
 *   hero.gravity = 0.25f;
 *   // every tick:  hero.vx = input * 1.5f;  if (jump && hero.onGround) hero.vy = -5f;  hero.update(map);
 *   // every frame: sprite at hero.getRenderX(alpha), hero.getRenderY(alpha)
 * </pre>
 * Moves in steps of at most half a tile, so fast bodies do not tunnel. Allocation free.
 */
public final class Body2D {
    public float x;
    public float y;
    public float hw;
    public float hh;
    public float vx;
    public float vy;
    /** Added to vy every tick (pixels per tick squared); 0 for top-down. */
    public float gravity;
    /** Fastest fall in units per tick. */
    public float maxFall = 12f;
    /** Set by update(): touching the floor / ceiling / a wall during the last tick. */
    public boolean onGround;
    public boolean hitCeiling;
    public boolean hitLeft;
    public boolean hitRight;
    /** Pass through one-way platforms (hold down to drop). */
    public boolean dropThrough;
    /** Free for your game: layer, team, damage ... */
    public int tag;

    private float prevX;
    private float prevY;

    public Body2D(float x, float y, float halfWidth, float halfHeight) {
        this.x = x;
        this.y = y;
        this.hw = halfWidth;
        this.hh = halfHeight;
        prevX = x;
        prevY = y;
    }

    /** Puts the body somewhere without interpolation. */
    public void teleport(float nx, float ny) {
        x = nx;
        y = ny;
        prevX = nx;
        prevY = ny;
    }

    public float getRenderX(float alpha) {
        return prevX + (x - prevX) * alpha;
    }

    public float getRenderY(float alpha) {
        return prevY + (y - prevY) * alpha;
    }

    /** One tick: gravity, then movement with collisions against the map's collision layer. */
    public void update(TileMap map) {
        prevX = x;
        prevY = y;
        vy += gravity;
        if (vy > maxFall) {
            vy = maxFall;
        }
        onGround = false;
        hitCeiling = false;
        hitLeft = false;
        hitRight = false;
        float step = (map.getTileWidth() < map.getTileHeight() ? map.getTileWidth() : map.getTileHeight()) * 0.5f;
        moveAxis(map, vx, step, true);
        moveAxis(map, vy, step, false);
    }

    private void moveAxis(TileMap map, float d, float step, boolean horizontal) {
        if (d == 0f) {
            return;
        }
        float abs = d < 0f ? -d : d;
        int n = (int) (abs / step) + 1;
        float s = d / n;
        for (int i = 0; i < n; i++) {
            if (horizontal) {
                x += s;
                if (collideX(map, s)) {
                    vx = 0f;
                    break;
                }
            } else {
                float oldBottom = y + hh - s;
                y += s;
                if (collideY(map, s, oldBottom)) {
                    vy = 0f;
                    break;
                }
            }
        }
    }

    private boolean collideX(TileMap map, float s) {
        int tw = map.getTileWidth();
        int th = map.getTileHeight();
        int ty0 = FastMath.floor((y - hh) / th);
        int ty1 = FastMath.floor((y + hh - 0.001f) / th);
        if (s > 0f) {
            int tx = FastMath.floor((x + hw - 0.001f) / tw);
            for (int ty = ty0; ty <= ty1; ty++) {
                if ((map.flagsAt(tx, ty) & TileMap.SOLID) != 0) {
                    x = tx * tw - hw;
                    hitRight = true;
                    return true;
                }
            }
        } else {
            int tx = FastMath.floor((x - hw) / tw);
            for (int ty = ty0; ty <= ty1; ty++) {
                if ((map.flagsAt(tx, ty) & TileMap.SOLID) != 0) {
                    x = (tx + 1) * tw + hw;
                    hitLeft = true;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean collideY(TileMap map, float s, float oldBottom) {
        int tw = map.getTileWidth();
        int th = map.getTileHeight();
        int tx0 = FastMath.floor((x - hw) / tw);
        int tx1 = FastMath.floor((x + hw - 0.001f) / tw);
        if (s > 0f) {
            int ty = FastMath.floor((y + hh - 0.001f) / th);
            for (int tx = tx0; tx <= tx1; tx++) {
                int f = map.flagsAt(tx, ty);
                boolean hit = (f & TileMap.SOLID) != 0
                        || ((f & TileMap.ONEWAY) != 0 && !dropThrough && oldBottom <= ty * th + 0.001f);
                if (hit) {
                    y = ty * th - hh;
                    onGround = true;
                    return true;
                }
            }
        } else {
            int ty = FastMath.floor((y - hh) / th);
            for (int tx = tx0; tx <= tx1; tx++) {
                if ((map.flagsAt(tx, ty) & TileMap.SOLID) != 0) {
                    y = (ty + 1) * th + hh;
                    hitCeiling = true;
                    return true;
                }
            }
        }
        return false;
    }

    /** Box overlap with another body. */
    public boolean overlaps(Body2D o) {
        return Collision2D.overlaps(x, y, hw, hh, o.x, o.y, o.hw, o.hh);
    }

    /** True if a tile under the body has this flag (LADDER, WATER, HAZARD ...). */
    public boolean touchesFlag(TileMap map, int flag) {
        return map.overlapsFlag(x, y, hw, hh, flag);
    }
}
