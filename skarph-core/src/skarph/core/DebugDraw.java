// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Immediate-mode 3D debug shapes made of lines: boxes, spheres, capsules, rays, axes, grids, frusta of bodies.
 * Fixed capacity, zero allocation: a shape that does not fit is dropped. Typical use:
 * <pre>
 *   debug.box(body.bounds);               // anywhere in render(), after the scene
 *   debug.flush(renderer);                // draws everything collected and empties the buffer
 * </pre>
 * Call setCamera() (Scene.render() does) before flush(). Colors are set with setColor() and stay until changed.
 */
public final class DebugDraw {
    private final float[] data;
    private final int maxVertices;
    private int count;
    private float cr = 1f;
    private float cg = 1f;
    private float cb = 1f;
    private float ca = 1f;
    /** Number of vertices that did not fit since the last flush. */
    public int dropped;

    /** @param maxLines capacity in line segments (rounded down to what one drawLines() call takes: 1024) */
    public DebugDraw(int maxLines) {
        int v = maxLines * 2;
        if (v > Renderer.MAX_LINE_VERTICES * 8) {
            v = Renderer.MAX_LINE_VERTICES * 8;
        }
        maxVertices = v;
        data = new float[v * Renderer.LINE_FLOATS];
    }

    public DebugDraw setColor(float r, float g, float b, float a) {
        cr = r;
        cg = g;
        cb = b;
        ca = a;
        return this;
    }

    public DebugDraw setColor(float r, float g, float b) {
        return setColor(r, g, b, 1f);
    }

    public int getLineCount() {
        return count >> 1;
    }

    public DebugDraw line(float x0, float y0, float z0, float x1, float y1, float z1) {
        if (count + 2 > maxVertices) {
            dropped += 2;
            return this;
        }
        int o = count * Renderer.LINE_FLOATS;
        float[] d = data;
        d[o] = x0;
        d[o + 1] = y0;
        d[o + 2] = z0;
        d[o + 3] = cr;
        d[o + 4] = cg;
        d[o + 5] = cb;
        d[o + 6] = ca;
        d[o + 7] = x1;
        d[o + 8] = y1;
        d[o + 9] = z1;
        d[o + 10] = cr;
        d[o + 11] = cg;
        d[o + 12] = cb;
        d[o + 13] = ca;
        count += 2;
        return this;
    }

    /** A ray: from its origin, length units long. */
    public DebugDraw ray(Ray ray, float length) {
        return line(ray.ox, ray.oy, ray.oz, ray.ox + ray.dx * length, ray.oy + ray.dy * length, ray.oz + ray.dz * length);
    }

    public DebugDraw cross(float x, float y, float z, float size) {
        line(x - size, y, z, x + size, y, z);
        line(x, y - size, z, x, y + size, z);
        return line(x, y, z - size, x, y, z + size);
    }

    /** Three axes of length {@code size} at a point: red x, green y, blue z. The color is restored afterwards. */
    public DebugDraw axes(float x, float y, float z, float size) {
        float r = cr, g = cg, b = cb, a = ca;
        setColor(1f, 0.2f, 0.2f, a).line(x, y, z, x + size, y, z);
        setColor(0.2f, 1f, 0.2f, a).line(x, y, z, x, y + size, z);
        setColor(0.3f, 0.4f, 1f, a).line(x, y, z, x, y, z + size);
        return setColor(r, g, b, a);
    }

    public DebugDraw box(Aabb b) {
        return box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    public DebugDraw box(float x0, float y0, float z0, float x1, float y1, float z1) {
        line(x0, y0, z0, x1, y0, z0);
        line(x1, y0, z0, x1, y0, z1);
        line(x1, y0, z1, x0, y0, z1);
        line(x0, y0, z1, x0, y0, z0);
        line(x0, y1, z0, x1, y1, z0);
        line(x1, y1, z0, x1, y1, z1);
        line(x1, y1, z1, x0, y1, z1);
        line(x0, y1, z1, x0, y1, z0);
        line(x0, y0, z0, x0, y1, z0);
        line(x1, y0, z0, x1, y1, z0);
        line(x1, y0, z1, x1, y1, z1);
        return line(x0, y0, z1, x0, y1, z1);
    }

    /** A horizontal circle (axis 1 = y), a vertical one in the xy plane (axis 0 = z) or in the zy plane (axis 2 = x). */
    public DebugDraw circle(float cx, float cy, float cz, float radius, int axis, int segments) {
        float px = 0f, py = 0f, pz = 0f;
        for (int i = 0; i <= segments; i++) {
            float a = 360f * i / segments;
            float s = FastMath.sin(a) * radius;
            float c = FastMath.cos(a) * radius;
            float x, y, z;
            if (axis == 1) {
                x = cx + c;
                y = cy;
                z = cz + s;
            } else if (axis == 0) {
                x = cx + c;
                y = cy + s;
                z = cz;
            } else {
                x = cx;
                y = cy + s;
                z = cz + c;
            }
            if (i > 0) {
                line(px, py, pz, x, y, z);
            }
            px = x;
            py = y;
            pz = z;
        }
        return this;
    }

    /** Three great circles: cheap and readable. */
    public DebugDraw sphere(float cx, float cy, float cz, float radius) {
        circle(cx, cy, cz, radius, 0, 16);
        circle(cx, cy, cz, radius, 1, 16);
        return circle(cx, cy, cz, radius, 2, 16);
    }

    /** A vertical capsule: the center is the middle of the axis, halfHeight is the half length of the straight part. */
    public DebugDraw capsule(float cx, float cy, float cz, float radius, float halfHeight) {
        circle(cx, cy + halfHeight, cz, radius, 1, 12);
        circle(cx, cy - halfHeight, cz, radius, 1, 12);
        line(cx + radius, cy - halfHeight, cz, cx + radius, cy + halfHeight, cz);
        line(cx - radius, cy - halfHeight, cz, cx - radius, cy + halfHeight, cz);
        line(cx, cy - halfHeight, cz + radius, cx, cy + halfHeight, cz + radius);
        line(cx, cy - halfHeight, cz - radius, cx, cy + halfHeight, cz - radius);
        circle(cx, cy + halfHeight, cz, radius, 0, 12);
        circle(cx, cy - halfHeight, cz, radius, 0, 12);
        return this;
    }

    /** A flat grid on the plane y = y0, centered on (cx, cz): cells x cells squares of the given size. */
    public DebugDraw grid(float cx, float y0, float cz, float cell, int cells) {
        float h = cell * cells * 0.5f;
        for (int i = 0; i <= cells; i++) {
            float o = -h + i * cell;
            line(cx + o, y0, cz - h, cx + o, y0, cz + h);
            line(cx - h, y0, cz + o, cx + h, y0, cz + o);
        }
        return this;
    }

    /** Draws everything collected (in batches of MAX_LINE_VERTICES) and empties the buffer. */
    public void flush(Renderer r) {
        int done = 0;
        float[] tmp = data;
        while (done < count) {
            int n = count - done;
            if (n > Renderer.MAX_LINE_VERTICES) {
                n = Renderer.MAX_LINE_VERTICES;
            }
            if (done == 0) {
                r.drawLines(tmp, n);
            } else {
                System.arraycopy(tmp, done * Renderer.LINE_FLOATS, tmp, 0, n * Renderer.LINE_FLOATS);
                r.drawLines(tmp, n);
            }
            done += n;
        }
        count = 0;
        dropped = 0;
    }

    /** Forgets everything collected without drawing. */
    public void clear() {
        count = 0;
        dropped = 0;
    }
}
