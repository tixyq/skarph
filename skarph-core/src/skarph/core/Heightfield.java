// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A terrain made of a regular grid of heights (y up), for PhysicsWorld.setTerrain() and CharacterController.
 * Heights are bilinearly interpolated, so the surface is continuous. Construct it (and fill it) at load time; the queries
 * allocate nothing.
 */
public final class Heightfield {
    private final int nx;
    private final int nz;
    private final float cell;
    private final float originX;
    private final float originZ;
    private final float[] h;

    /**
     * @param nx number of height samples along x (at least 2)
     * @param nz number of height samples along z (at least 2)
     * @param cellSize distance between samples
     * @param originX x of sample (0, *)
     * @param originZ z of sample (*, 0)
     */
    public Heightfield(int nx, int nz, float cellSize, float originX, float originZ) {
        if (nx < 2 || nz < 2 || cellSize <= 0f) {
            throw new IllegalArgumentException("a heightfield needs at least 2 x 2 samples");
        }
        this.nx = nx;
        this.nz = nz;
        this.cell = cellSize;
        this.originX = originX;
        this.originZ = originZ;
        h = new float[nx * nz];
    }

    public int getSamplesX() {
        return nx;
    }

    public int getSamplesZ() {
        return nz;
    }

    public float getCellSize() {
        return cell;
    }

    /** Height of one sample (row = z index, column = x index). */
    public void setHeight(int ix, int iz, float height) {
        h[iz * nx + ix] = height;
    }

    public float getSample(int ix, int iz) {
        return h[iz * nx + ix];
    }

    /** Fills the field from unsigned bytes (row after row, x fastest): height = value * scale + offset. */
    public void setFromBytes(byte[] data, int offset, float scale, float base) {
        for (int i = 0; i < h.length; i++) {
            h[i] = (data[offset + i] & 0xFF) * scale + base;
        }
    }

    /** Fills the field from 16 bit unsigned little endian values (2 bytes per sample). */
    public void setFromShorts(byte[] data, int offset, float scale, float base) {
        for (int i = 0; i < h.length; i++) {
            int o = offset + i * 2;
            h[i] = ((data[o] & 0xFF) | ((data[o + 1] & 0xFF) << 8)) * scale + base;
        }
    }

    public float getMinX() {
        return originX;
    }

    public float getMaxX() {
        return originX + (nx - 1) * cell;
    }

    public float getMinZ() {
        return originZ;
    }

    public float getMaxZ() {
        return originZ + (nz - 1) * cell;
    }

    /** True if (x, z) lies over the field. */
    public boolean contains(float x, float z) {
        return x >= originX && z >= originZ && x <= getMaxX() && z <= getMaxZ();
    }

    /** Height at (x, z); outside the field the nearest edge height. */
    public float getHeight(float x, float z) {
        float fx = (x - originX) / cell;
        float fz = (z - originZ) / cell;
        if (fx < 0f) {
            fx = 0f;
        } else if (fx > nx - 1) {
            fx = nx - 1;
        }
        if (fz < 0f) {
            fz = 0f;
        } else if (fz > nz - 1) {
            fz = nz - 1;
        }
        int ix = (int) fx;
        int iz = (int) fz;
        if (ix >= nx - 1) {
            ix = nx - 2;
        }
        if (iz >= nz - 1) {
            iz = nz - 2;
        }
        float tx = fx - ix;
        float tz = fz - iz;
        int i = iz * nx + ix;
        float a = h[i] + (h[i + 1] - h[i]) * tx;
        float b = h[i + nx] + (h[i + nx + 1] - h[i + nx]) * tx;
        return a + (b - a) * tz;
    }

    /** Unit surface normal at (x, z) written into out. */
    public void getNormal(float x, float z, Vec3 out) {
        float e = cell * 0.5f;
        float dx = getHeight(x + e, z) - getHeight(x - e, z);
        float dz = getHeight(x, z + e) - getHeight(x, z - e);
        // the surface y = f(x, z) has the normal (-df/dx, 1, -df/dz)
        float nxv = -dx / (2f * e);
        float nzv = -dz / (2f * e);
        float inv = 1f / (float) Math.sqrt(nxv * nxv + 1f + nzv * nzv);
        out.x = nxv * inv;
        out.y = inv;
        out.z = nzv * inv;
    }

    /** Distance along the ray to the surface (from above), or -1. Marches in half-cell steps, then bisects. */
    public float raycast(Ray ray, float maxDist) {
        float step = cell * 0.5f;
        float prevT = 0f;
        float prevD = ray.oy - getHeight(ray.ox, ray.oz);
        if (prevD < 0f) {
            return 0f;          // starts below the surface
        }
        for (float t = step; t <= maxDist + step; t += step) {
            float tt = t > maxDist ? maxDist : t;
            float d = ray.oy + ray.dy * tt - getHeight(ray.ox + ray.dx * tt, ray.oz + ray.dz * tt);
            if (d <= 0f) {
                float lo = prevT;
                float hi = tt;
                for (int i = 0; i < 12; i++) {
                    float mid = (lo + hi) * 0.5f;
                    float dm = ray.oy + ray.dy * mid - getHeight(ray.ox + ray.dx * mid, ray.oz + ray.dz * mid);
                    if (dm > 0f) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                return hi;
            }
            prevT = tt;
            prevD = d;
            if (tt >= maxDist) {
                break;
            }
        }
        return -1f;
    }
}
