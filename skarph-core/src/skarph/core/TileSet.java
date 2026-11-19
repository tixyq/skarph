// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * The pictures of a tile map: a sprite sheet cut into equal cells. Tile id 1 is the first cell (left to right, top to
 * bottom), id 0 means "empty". A tile can be animated: it is drawn as a sequence of other tiles.
 * Create in init(); with smooth filtering leave a 1 pixel margin or use nearest filtering to avoid seams.
 */
public final class TileSet {
    private final TextureRegion[] regions;
    private final int[][] frames;
    private final int[] frameTicks;
    private int tick;
    private final int tileW;
    private final int tileH;

    public TileSet(int texture, int texW, int texH, int tileW, int tileH) {
        int cols = texW / tileW;
        int rows = texH / tileH;
        regions = TextureRegion.grid(texture, texW, texH, tileW, tileH, cols, rows);
        frames = new int[regions.length + 1][];
        frameTicks = new int[regions.length + 1];
        this.tileW = tileW;
        this.tileH = tileH;
    }

    public int getTileWidth() {
        return tileW;
    }

    public int getTileHeight() {
        return tileH;
    }

    public int getTileCount() {
        return regions.length;
    }

    /** Draws tile 'id' as the sequence of tile ids, each shown for ticksPerFrame ticks (water, torches, coins). */
    public void setAnimation(int id, int[] frameIds, int ticksPerFrame) {
        frames[id] = frameIds;
        frameTicks[id] = ticksPerFrame < 1 ? 1 : ticksPerFrame;
    }

    /** Once per tick: advances the animations. */
    public void update() {
        tick++;
    }

    /** The picture to draw for a tile id (animation resolved), or null for empty / unknown ids. */
    public TextureRegion get(int id) {
        if (id <= 0 || id > regions.length) {
            return null;
        }
        int[] f = frames[id];
        if (f != null) {
            id = f[(tick / frameTicks[id]) % f.length];
            if (id <= 0 || id > regions.length) {
                return null;
            }
        }
        return regions[id - 1];
    }
}
