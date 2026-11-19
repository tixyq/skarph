// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A grid of tiles in several layers with per-tile flags, parallax and an object list, plus fast culled drawing, box
 * movement and ray casts. Build it in code or load a map made by tools/tmx2map.py ("SMP1") from the Tiled editor.
 * <pre>
 *   TileSet set = new TileSet(tex, 256, 256, 16, 16);
 *   TileMap map = TileMap.load(assets.readBytes("maps/level1.map"), set);
 *   // per frame:  cam.getProjection(proj, alpha); batch.begin(proj); map.draw(batch, cam); batch.end();
 * </pre>
 * Tile ids are 1-based (0 = empty). One layer is the collision layer: its tiles' flags decide what is solid.
 * Drawing touches only the visible tiles; nothing allocates after construction.
 */
public final class TileMap {
    public static final int SOLID = 1;
    /** Solid only from above: a platform you can jump through and stand on. */
    public static final int ONEWAY = 2;
    public static final int LADDER = 4;
    public static final int HAZARD = 8;
    public static final int WATER = 16;
    public static final int USER1 = 32;
    public static final int USER2 = 64;
    public static final int USER3 = 128;

    private static final int MAX_ID = 65536;

    private final int width;
    private final int height;
    private final int tileW;
    private final int tileH;
    private final TileSet set;
    private final char[][] layers;
    private final boolean[] visible;
    private final float[] parX;
    private final float[] parY;
    private int layerCount;
    private final byte[] flags = new byte[MAX_ID];
    private int collisionLayer;
    /** Tiles outside the map count as solid (a closed room) or as empty (open sides). Default: empty. */
    public boolean outsideSolid;

    // objects
    private int objCount;
    private int[] objId;
    private float[] objX;
    private float[] objY;
    private float[] objW;
    private float[] objH;
    private String[] objName;
    private String[] objType;

    public TileMap(int width, int height, int maxLayers, TileSet set) {
        this.width = width;
        this.height = height;
        this.set = set;
        tileW = set.getTileWidth();
        tileH = set.getTileHeight();
        layers = new char[maxLayers][width * height];
        visible = new boolean[maxLayers];
        parX = new float[maxLayers];
        parY = new float[maxLayers];
    }

    /** Parses an SMP1 file. @return the map, or null if the data is not valid */
    public static TileMap load(byte[] d, TileSet set) {
        if (d == null || d.length < 16 || d[0] != 'S' || d[1] != 'M' || d[2] != 'P' || d[3] != '1') {
            return null;
        }
        int w = u16(d, 4);
        int h = u16(d, 6);
        int nLayers = d[12] & 255;
        int nFlags = u16(d, 13);
        int nObjs = u16(d, 15);
        int p = 17;
        long need = 17L + nFlags * 3L + nLayers * (5L + (long) w * h * 2L);
        if (w < 1 || h < 1 || nLayers < 1 || need > d.length) {
            return null;                            // truncated or absurd: do not allocate for it
        }
        TileMap m = new TileMap(w, h, nLayers < 1 ? 1 : nLayers, set);
        try {
            for (int i = 0; i < nFlags; i++) {
                m.flags[u16(d, p)] = d[p + 2];
                p += 3;
            }
            for (int l = 0; l < nLayers; l++) {
                int fl = d[p] & 255;
                m.visible[l] = (fl & 1) != 0;
                if ((fl & 2) != 0) {
                    m.collisionLayer = l;
                }
                m.parX[l] = s16(d, p + 1) / 1000f;
                m.parY[l] = s16(d, p + 3) / 1000f;
                p += 5;
                char[] data = m.layers[l];
                for (int i = 0; i < w * h; i++) {
                    data[i] = (char) u16(d, p);
                    p += 2;
                }
            }
            m.layerCount = nLayers;
            m.objId = new int[nObjs];
            m.objX = new float[nObjs];
            m.objY = new float[nObjs];
            m.objW = new float[nObjs];
            m.objH = new float[nObjs];
            m.objName = new String[nObjs];
            m.objType = new String[nObjs];
            for (int i = 0; i < nObjs; i++) {
                m.objId[i] = u16(d, p);
                m.objX[i] = f32(d, p + 2);
                m.objY[i] = f32(d, p + 6);
                m.objW[i] = f32(d, p + 10);
                m.objH[i] = f32(d, p + 14);
                p += 18;
                int n1 = d[p] & 255;
                m.objName[i] = new String(d, p + 1, n1, "UTF-8");
                p += 1 + n1;
                int n2 = d[p] & 255;
                m.objType[i] = new String(d, p + 1, n2, "UTF-8");
                p += 1 + n2;
            }
            m.objCount = nObjs;
        } catch (Exception e) {
            return null;
        }
        return m;
    }

    private static int u16(byte[] d, int p) {
        return ((d[p] & 255) << 8) | (d[p + 1] & 255);
    }

    private static int s16(byte[] d, int p) {
        return (short) u16(d, p);
    }

    private static float f32(byte[] d, int p) {
        return Float.intBitsToFloat(((d[p] & 255) << 24) | ((d[p + 1] & 255) << 16) | ((d[p + 2] & 255) << 8) | (d[p + 3] & 255));
    }

    // ------------------------------------------------------------------ layers and tiles

    /** Adds a layer (up to maxLayers). @return its index */
    public int addLayer() {
        int l = layerCount++;
        visible[l] = true;
        parX[l] = 1f;
        parY[l] = 1f;
        return l;
    }

    public int getLayerCount() {
        return layerCount;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getTileWidth() {
        return tileW;
    }

    public int getTileHeight() {
        return tileH;
    }

    public float getPixelWidth() {
        return width * tileW;
    }

    public float getPixelHeight() {
        return height * tileH;
    }

    public void setLayerVisible(int layer, boolean v) {
        visible[layer] = v;
    }

    /** 1 = moves with the world, 0.5 = half speed (distant), 0 = fixed to the screen. */
    public void setParallax(int layer, float px, float py) {
        parX[layer] = px;
        parY[layer] = py;
    }

    public void setCollisionLayer(int layer) {
        collisionLayer = layer;
    }

    public int getCollisionLayer() {
        return collisionLayer;
    }

    public int getTile(int layer, int tx, int ty) {
        if (tx < 0 || ty < 0 || tx >= width || ty >= height) {
            return 0;
        }
        return layers[layer][ty * width + tx];
    }

    public void setTile(int layer, int tx, int ty, int id) {
        if (tx >= 0 && ty >= 0 && tx < width && ty < height) {
            layers[layer][ty * width + tx] = (char) id;
        }
    }

    public void fill(int layer, int id) {
        java.util.Arrays.fill(layers[layer], (char) id);
    }

    /** Flags (SOLID, ONEWAY ...) of a tile id. */
    public int getFlags(int id) {
        return flags[id & 0xFFFF] & 255;
    }

    public void setFlags(int id, int f) {
        flags[id & 0xFFFF] = (byte) f;
    }

    /** Flags of the collision layer's tile at a tile position (outside the map: SOLID if outsideSolid). */
    public int flagsAt(int tx, int ty) {
        if (tx < 0 || ty < 0 || tx >= width || ty >= height) {
            return outsideSolid ? SOLID : 0;
        }
        return flags[layers[collisionLayer][ty * width + tx]] & 255;
    }

    /** Flags at a world position (pixels). */
    public int flagsAtPoint(float x, float y) {
        return flagsAt(FastMath.floor(x / tileW), FastMath.floor(y / tileH));
    }

    // ------------------------------------------------------------------ objects

    public int getObjectCount() {
        return objCount;
    }

    public int getObjectId(int i) {
        return objId[i];
    }

    public float getObjectX(int i) {
        return objX[i];
    }

    public float getObjectY(int i) {
        return objY[i];
    }

    public float getObjectWidth(int i) {
        return objW[i];
    }

    public float getObjectHeight(int i) {
        return objH[i];
    }

    public String getObjectName(int i) {
        return objName[i];
    }

    /** The "type" (class) set in Tiled, for example "enemy" or "spawn". */
    public String getObjectType(int i) {
        return objType[i];
    }

    // ------------------------------------------------------------------ drawing

    /** Draws every visible layer. Parallax layers scroll by their factor. Call between batch.begin() and end(). */
    public void draw(SpriteBatch batch, Camera2D cam) {
        for (int l = 0; l < layerCount; l++) {
            drawLayer(batch, cam, l);
        }
    }

    /** Draws one layer (for example the background layers first, then your sprites, then the foreground layers). */
    public void drawLayer(SpriteBatch batch, Camera2D cam, int layer) {
        if (!visible[layer]) {
            return;
        }
        // a layer with parallax p is shifted by cam * (1 - p): the camera sees it moving at p times the speed
        float ox = cam.getLeft() * (1f - parX[layer]);
        float oy = cam.getTop() * (1f - parY[layer]);
        float vl = cam.getLeft() - ox;
        float vt = cam.getTop() - oy;
        int x0 = FastMath.floor(vl / tileW);
        int y0 = FastMath.floor(vt / tileH);
        int x1 = FastMath.floor((vl + cam.getRight() - cam.getLeft()) / tileW);
        int y1 = FastMath.floor((vt + cam.getBottom() - cam.getTop()) / tileH);
        if (x0 < 0) {
            x0 = 0;
        }
        if (y0 < 0) {
            y0 = 0;
        }
        if (x1 >= width) {
            x1 = width - 1;
        }
        if (y1 >= height) {
            y1 = height - 1;
        }
        char[] data = layers[layer];
        for (int ty = y0; ty <= y1; ty++) {
            int row = ty * width;
            float py = ty * tileH + oy;
            for (int tx = x0; tx <= x1; tx++) {
                int id = data[row + tx];
                if (id != 0) {
                    TextureRegion r = set.get(id);
                    if (r != null) {
                        batch.draw(r, tx * tileW + ox, py, tileW, tileH);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ rays

    /**
     * Walks a segment through the collision layer (grid traversal) and reports the first SOLID tile.
     * @return true if it hits; the result is in hit (t = fraction of the segment)
     */
    public boolean raycast(float x0, float y0, float x1, float y1, Hit2D hit) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        int tx = FastMath.floor(x0 / tileW);
        int ty = FastMath.floor(y0 / tileH);
        if ((flagsAt(tx, ty) & SOLID) != 0) {
            hit.t = 0f;
            hit.x = x0;
            hit.y = y0;
            hit.nx = 0f;
            hit.ny = 0f;
            hit.tileX = tx;
            hit.tileY = ty;
            return true;
        }
        int sx = dx > 0f ? 1 : -1;
        int sy = dy > 0f ? 1 : -1;
        float tMaxX = dx == 0f ? Float.MAX_VALUE : ((dx > 0f ? (tx + 1) * tileW : tx * tileW) - x0) / dx;
        float tMaxY = dy == 0f ? Float.MAX_VALUE : ((dy > 0f ? (ty + 1) * tileH : ty * tileH) - y0) / dy;
        float tDx = dx == 0f ? Float.MAX_VALUE : Math.abs(tileW / dx);
        float tDy = dy == 0f ? Float.MAX_VALUE : Math.abs(tileH / dy);
        int guard = width + height + 4;
        while (guard-- > 0) {
            float t;
            float nx = 0f;
            float ny = 0f;
            if (tMaxX < tMaxY) {
                t = tMaxX;
                tx += sx;
                tMaxX += tDx;
                nx = -sx;
            } else {
                t = tMaxY;
                ty += sy;
                tMaxY += tDy;
                ny = -sy;
            }
            if (t > 1f) {
                return false;
            }
            if ((flagsAt(tx, ty) & SOLID) != 0) {
                hit.t = t;
                hit.x = x0 + dx * t;
                hit.y = y0 + dy * t;
                hit.nx = nx;
                hit.ny = ny;
                hit.tileX = tx;
                hit.tileY = ty;
                return true;
            }
        }
        return false;
    }

    /** True if no SOLID tile lies on the segment. */
    public boolean lineOfSight(float x0, float y0, float x1, float y1, Hit2D scratch) {
        return !raycast(x0, y0, x1, y1, scratch);
    }

    /** True if any tile under the box (center, half sizes) has this flag. */
    public boolean overlapsFlag(float cx, float cy, float hw, float hh, int flag) {
        int tx0 = FastMath.floor((cx - hw) / tileW);
        int tx1 = FastMath.floor((cx + hw - 0.001f) / tileW);
        int ty0 = FastMath.floor((cy - hh) / tileH);
        int ty1 = FastMath.floor((cy + hh - 0.001f) / tileH);
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if ((flagsAt(tx, ty) & flag) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
