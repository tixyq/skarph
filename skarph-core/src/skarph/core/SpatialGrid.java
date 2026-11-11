// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Uniform 3D grid for the broad phase: finds which objects can possibly
 * touch without testing every pair. Fixed capacity, zero allocation.
 *
 * Per tick: clear(), insert() every object, then findPairs() / query().
 * Objects outside the grid volume are clamped into the border cells, so they
 * are still found. If maxEntries is too small, insert() returns false and the
 * object is skipped.
 */
public final class SpatialGrid {
    private final float originX;
    private final float originY;
    private final float originZ;
    private final float invCell;
    private final int nx;
    private final int ny;
    private final int nz;
    private final int maxObjects;

    private final int[] cellHead;
    private final int[] entryNext;
    private final int[] entryObj;
    private int entryCount;

    private final float[] bounds;   // 6 per object: min xyz, max xyz
    private final int[] range;      // 6 per object: min cell xyz, max cell xyz
    private final boolean[] present;
    private final int[] stamp;
    private int stampId;
    private int pairTests;

    public SpatialGrid(float minX, float minY, float minZ, float cellSize,
                       int cellsX, int cellsY, int cellsZ, int maxObjects, int maxEntries) {
        originX = minX;
        originY = minY;
        originZ = minZ;
        invCell = 1f / cellSize;
        nx = cellsX;
        ny = cellsY;
        nz = cellsZ;
        this.maxObjects = maxObjects;
        cellHead = new int[cellsX * cellsY * cellsZ];
        entryNext = new int[maxEntries];
        entryObj = new int[maxEntries];
        bounds = new float[maxObjects * 6];
        range = new int[maxObjects * 6];
        present = new boolean[maxObjects];
        stamp = new int[maxObjects];
        clear();
    }

    public void clear() {
        for (int i = 0; i < cellHead.length; i++) {
            cellHead[i] = -1;
        }
        for (int i = 0; i < maxObjects; i++) {
            present[i] = false;
        }
        entryCount = 0;
    }

    private static int cell(float v, float origin, float invCell, int n) {
        float f = (v - origin) * invCell;
        if (f <= 0f) {
            return 0;
        }
        int c = (int) f;
        return c >= n ? n - 1 : c;
    }

    /** @return false if the id is out of range or the entry table is full. */
    public boolean insert(int id, Aabb b) {
        if (id < 0 || id >= maxObjects) {
            return false;
        }
        int x0 = cell(b.minX, originX, invCell, nx);
        int y0 = cell(b.minY, originY, invCell, ny);
        int z0 = cell(b.minZ, originZ, invCell, nz);
        int x1 = cell(b.maxX, originX, invCell, nx);
        int y1 = cell(b.maxY, originY, invCell, ny);
        int z1 = cell(b.maxZ, originZ, invCell, nz);
        int need = (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (entryCount + need > entryObj.length) {
            return false;
        }
        int bi = id * 6;
        bounds[bi] = b.minX;
        bounds[bi + 1] = b.minY;
        bounds[bi + 2] = b.minZ;
        bounds[bi + 3] = b.maxX;
        bounds[bi + 4] = b.maxY;
        bounds[bi + 5] = b.maxZ;
        range[bi] = x0;
        range[bi + 1] = y0;
        range[bi + 2] = z0;
        range[bi + 3] = x1;
        range[bi + 4] = y1;
        range[bi + 5] = z1;
        present[id] = true;
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int c = (z * ny + y) * nx + x;
                    int e = entryCount++;
                    entryObj[e] = id;
                    entryNext[e] = cellHead[c];
                    cellHead[c] = e;
                }
            }
        }
        return true;
    }

    private boolean boundsOverlap(int a, int b) {
        int i = a * 6;
        int j = b * 6;
        return bounds[i] <= bounds[j + 3] && bounds[i + 3] >= bounds[j]
                && bounds[i + 1] <= bounds[j + 4] && bounds[i + 4] >= bounds[j + 1]
                && bounds[i + 2] <= bounds[j + 5] && bounds[i + 5] >= bounds[j + 2];
    }

    /**
     * Finds all pairs of inserted objects whose boxes overlap. Each pair is
     * reported once as (smaller id, larger id) in out[2k], out[2k+1].
     * @return number of pairs written (at most maxPairs)
     */
    public int findPairs(int[] out, int maxPairs) {
        int count = 0;
        pairTests = 0;
        for (int z = 0; z < nz; z++) {
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++) {
                    int head = cellHead[(z * ny + y) * nx + x];
                    for (int e1 = head; e1 >= 0; e1 = entryNext[e1]) {
                        int a = entryObj[e1];
                        for (int e2 = entryNext[e1]; e2 >= 0; e2 = entryNext[e2]) {
                            int b = entryObj[e2];
                            // report a pair only in the first cell both objects share
                            int ai = a * 6;
                            int bi = b * 6;
                            int cx = range[ai] > range[bi] ? range[ai] : range[bi];
                            int cy = range[ai + 1] > range[bi + 1] ? range[ai + 1] : range[bi + 1];
                            int cz = range[ai + 2] > range[bi + 2] ? range[ai + 2] : range[bi + 2];
                            if (cx != x || cy != y || cz != z) {
                                continue;
                            }
                            pairTests++;
                            if (boundsOverlap(a, b)) {
                                if (count >= maxPairs) {
                                    return count;
                                }
                                out[count * 2] = a < b ? a : b;
                                out[count * 2 + 1] = a < b ? b : a;
                                count++;
                            }
                        }
                    }
                }
            }
        }
        return count;
    }

    /** Number of box-vs-box tests done by the last findPairs() (for statistics). */
    public int getPairTests() {
        return pairTests;
    }

    /**
     * Writes the ids of all inserted objects whose box overlaps the given box
     * into out. @return how many ids were written (at most out.length).
     */
    public int query(Aabb box, int[] out) {
        int x0 = cell(box.minX, originX, invCell, nx);
        int y0 = cell(box.minY, originY, invCell, ny);
        int z0 = cell(box.minZ, originZ, invCell, nz);
        int x1 = cell(box.maxX, originX, invCell, nx);
        int y1 = cell(box.maxY, originY, invCell, ny);
        int z1 = cell(box.maxZ, originZ, invCell, nz);
        stampId++;
        int count = 0;
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    for (int e = cellHead[(z * ny + y) * nx + x]; e >= 0; e = entryNext[e]) {
                        int id = entryObj[e];
                        if (stamp[id] == stampId) {
                            continue;
                        }
                        stamp[id] = stampId;
                        int i = id * 6;
                        if (bounds[i] <= box.maxX && bounds[i + 3] >= box.minX
                                && bounds[i + 1] <= box.maxY && bounds[i + 4] >= box.minY
                                && bounds[i + 2] <= box.maxZ && bounds[i + 5] >= box.minZ) {
                            if (count >= out.length) {
                                return count;
                            }
                            out[count++] = id;
                        }
                    }
                }
            }
        }
        return count;
    }
}
