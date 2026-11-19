// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A unit quaternion (x, y, z, w) for rotations without gimbal lock and with smooth interpolation. Works in place,
 * returns {@code this}, never allocates. Angles are in DEGREES like everywhere in the engine.
 * Use it with {@link Node#setRotationQuat}, {@link Mat4#rotate(Quat)} and {@link #slerp}.
 */
public final class Quat {
    public float x;
    public float y;
    public float z;
    public float w = 1f;

    public Quat() {
    }

    public Quat(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public Quat setIdentity() {
        x = 0f;
        y = 0f;
        z = 0f;
        w = 1f;
        return this;
    }

    public Quat set(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
        return this;
    }

    public Quat set(Quat q) {
        x = q.x;
        y = q.y;
        z = q.z;
        w = q.w;
        return this;
    }

    /** Rotation by an angle around an axis (the axis need not be normalized; a zero axis gives the identity). */
    public Quat setAxisAngle(float angleDeg, float ax, float ay, float az) {
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-12f) {
            return setIdentity();
        }
        float h = angleDeg * 0.5f;
        float s = FastMath.sin(h) / len;
        x = ax * s;
        y = ay * s;
        z = az * s;
        w = FastMath.cos(h);
        return this;
    }

    /**
     * The same rotation as Node.setRotation(rx, ry, rz): Z first, then X, then Y (matrix = Ry * Rx * Rz), degrees.
     */
    public Quat setEuler(float rx, float ry, float rz) {
        float hx = rx * 0.5f, hy = ry * 0.5f, hz = rz * 0.5f;
        float sx = FastMath.sin(hx), cx = FastMath.cos(hx);
        float sy = FastMath.sin(hy), cy = FastMath.cos(hy);
        float sz = FastMath.sin(hz), cz = FastMath.cos(hz);
        // q = qy * qx * qz
        float ax = cy * sx, ay = sy * cx, az = -sy * sx, aw = cy * cx;   // qy * qx
        x = aw * 0f + ax * cz + ay * sz - az * 0f;                       // times qz = (0, 0, sz, cz)
        y = ay * cz - ax * sz + az * 0f + aw * 0f;
        z = az * cz + aw * sz;
        w = aw * cz - az * sz;
        return this;
    }

    /** this = a * b (apply b first, then a). Safe when this is a or b. */
    public Quat multiply(Quat a, Quat b) {
        float nx = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y;
        float ny = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x;
        float nz = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w;
        float nw = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z;
        x = nx;
        y = ny;
        z = nz;
        w = nw;
        return this;
    }

    public Quat conjugate() {
        x = -x;
        y = -y;
        z = -z;
        return this;
    }

    public float dot(Quat q) {
        return x * q.x + y * q.y + z * q.z + w * q.w;
    }

    /** Scales to length 1 (call it now and then when you multiply many rotations); a zero quaternion becomes the identity. */
    public Quat normalize() {
        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (len < 1e-12f) {
            return setIdentity();
        }
        float inv = 1f / len;
        x *= inv;
        y *= inv;
        z *= inv;
        w *= inv;
        return this;
    }

    /** Spherical interpolation along the shortest arc: a at t = 0, b at t = 1. Constant angular speed. */
    public Quat slerp(Quat a, Quat b, float t) {
        float d = a.dot(b);
        float bx = b.x, by = b.y, bz = b.z, bw = b.w;
        if (d < 0f) {
            d = -d;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        float ka;
        float kb;
        if (d > 0.9995f) {              // nearly the same direction: a plain blend is exact enough
            ka = 1f - t;
            kb = t;
        } else {
            float theta = FastMath.acos(d);          // degrees
            float inv = 1f / FastMath.sin(theta);
            ka = FastMath.sin((1f - t) * theta) * inv;
            kb = FastMath.sin(t * theta) * inv;
        }
        x = a.x * ka + bx * kb;
        y = a.y * ka + by * kb;
        z = a.z * ka + bz * kb;
        w = a.w * ka + bw * kb;
        return normalize();
    }

    /** Cheap interpolation: blend and normalize (speed not constant, fine for small angles). */
    public Quat nlerp(Quat a, Quat b, float t) {
        float s = a.dot(b) < 0f ? -1f : 1f;
        x = a.x + (b.x * s - a.x) * t;
        y = a.y + (b.y * s - a.y) * t;
        z = a.z + (b.z * s - a.z) * t;
        w = a.w + (b.w * s - a.w) * t;
        return normalize();
    }

    /** Rotates the vector in place. */
    public void rotate(Vec3 v) {
        float tx = 2f * (y * v.z - z * v.y);
        float ty = 2f * (z * v.x - x * v.z);
        float tz = 2f * (x * v.y - y * v.x);
        float nx = v.x + w * tx + (y * tz - z * ty);
        float ny = v.y + w * ty + (z * tx - x * tz);
        float nz = v.z + w * tz + (x * ty - y * tx);
        v.x = nx;
        v.y = ny;
        v.z = nz;
    }

    /**
     * The rotation that turns the local -z axis (the OpenGL view direction) to look along (fx, fy, fz) with
     * (ux, uy, uz) as the approximate up direction. Parallel direction and up fall back to a perpendicular up.
     */
    public Quat setLookRotation(float fx, float fy, float fz, float ux, float uy, float uz) {
        float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (len < 1e-12f) {
            return setIdentity();
        }
        float zx = -fx / len, zy = -fy / len, zz = -fz / len;        // local z = backwards
        float xx = uy * zz - uz * zy, xy = uz * zx - ux * zz, xz = ux * zy - uy * zx;
        float xl = (float) Math.sqrt(xx * xx + xy * xy + xz * xz);
        if (xl < 1e-6f) {                                           // up is parallel to the view direction
            xx = 0f;
            xy = zz;
            xz = -zy;
            xl = (float) Math.sqrt(xx * xx + xy * xy + xz * xz);
            if (xl < 1e-6f) {
                xx = 1f;
                xy = 0f;
                xz = 0f;
                xl = 1f;
            }
        }
        xx /= xl;
        xy /= xl;
        xz /= xl;
        float yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
        // rotation matrix columns are (xx,xy,xz), (yx,yy,yz), (zx,zy,zz)
        float tr = xx + yy + zz;
        if (tr > 0f) {
            float s = (float) Math.sqrt(tr + 1f) * 2f;
            w = 0.25f * s;
            x = (yz - zy) / s;
            y = (zx - xz) / s;
            z = (xy - yx) / s;
        } else if (xx > yy && xx > zz) {
            float s = (float) Math.sqrt(1f + xx - yy - zz) * 2f;
            w = (yz - zy) / s;
            x = 0.25f * s;
            y = (yx + xy) / s;
            z = (zx + xz) / s;
        } else if (yy > zz) {
            float s = (float) Math.sqrt(1f + yy - xx - zz) * 2f;
            w = (zx - xz) / s;
            x = (yx + xy) / s;
            y = 0.25f * s;
            z = (zy + yz) / s;
        } else {
            float s = (float) Math.sqrt(1f + zz - xx - yy) * 2f;
            w = (xy - yx) / s;
            x = (zx + xz) / s;
            y = (zy + yz) / s;
            z = 0.25f * s;
        }
        return normalize();
    }

    /** Writes the Euler angles (degrees, order of Node.setRotation) into out: x = rx, y = ry, z = rz. */
    public void toEuler(Vec3 out) {
        float xx = x * x, yy = y * y, zz = z * z;
        float r12 = 2f * (y * z - w * x);          // matrix row 1, column 2 = -sin(rx)
        float sx = -r12;
        sx = sx < -1f ? -1f : (sx > 1f ? 1f : sx);
        out.x = FastMath.asin(sx);
        if (sx > 0.99999f || sx < -0.99999f) {     // looking straight up or down: roll and yaw are one angle
            out.y = FastMath.atan2(-2f * (x * z - w * y), 1f - 2f * (yy + zz));
            out.z = 0f;
        } else {
            out.y = FastMath.atan2(2f * (x * z + w * y), 1f - 2f * (xx + yy));
            out.z = FastMath.atan2(2f * (x * y + w * z), 1f - 2f * (xx + zz));
        }
    }
}
