// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * 4x4 float matrix, column-major (same layout as OpenGL): element at
 * row r, column c is m[c * 4 + r]. Operations work in place, zero allocation.
 */
public final class Mat4 {
    public final float[] m = new float[16];
    private final float[] t = new float[16]; // scratch

    public Mat4() {
        setIdentity();
    }

    public Mat4 setIdentity() {
        for (int i = 0; i < 16; i++) {
            m[i] = 0f;
        }
        m[0] = 1f;
        m[5] = 1f;
        m[10] = 1f;
        m[15] = 1f;
        return this;
    }

    public Mat4 set(Mat4 o) {
        System.arraycopy(o.m, 0, m, 0, 16);
        return this;
    }

    /** this = a * b (safe if this is a or b). */
    public Mat4 multiply(Mat4 a, Mat4 b) {
        float[] am = a.m;
        float[] bm = b.m;
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                t[c * 4 + r] = am[r] * bm[c * 4]
                        + am[4 + r] * bm[c * 4 + 1]
                        + am[8 + r] * bm[c * 4 + 2]
                        + am[12 + r] * bm[c * 4 + 3];
            }
        }
        System.arraycopy(t, 0, m, 0, 16);
        return this;
    }

    /** this = this * Translation(x, y, z) */
    public Mat4 translate(float x, float y, float z) {
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
        return this;
    }

    /** this = this * Scale(x, y, z) */
    public Mat4 scale(float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            m[i] *= x;
            m[4 + i] *= y;
            m[8 + i] *= z;
        }
        return this;
    }

    /** this = this * Rotation(angleDegrees, axis). Axis need not be normalized. */
    public Mat4 rotate(float angleDeg, float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len == 0f) {
            return this;
        }
        x /= len;
        y /= len;
        z /= len;
        float c = FastMath.cos(angleDeg);
        float s = FastMath.sin(angleDeg);
        float nc = 1f - c;

        float r00 = x * x * nc + c;
        float r10 = y * x * nc + z * s;
        float r20 = x * z * nc - y * s;
        float r01 = x * y * nc - z * s;
        float r11 = y * y * nc + c;
        float r21 = y * z * nc + x * s;
        float r02 = x * z * nc + y * s;
        float r12 = y * z * nc - x * s;
        float r22 = z * z * nc + c;

        for (int r = 0; r < 4; r++) {
            float a = m[r];
            float b = m[4 + r];
            float d = m[8 + r];
            t[r] = a * r00 + b * r10 + d * r20;
            t[4 + r] = a * r01 + b * r11 + d * r21;
            t[8 + r] = a * r02 + b * r12 + d * r22;
        }
        System.arraycopy(t, 0, m, 0, 12);
        return this;
    }

    /** this = this * RotationX(angleDegrees). Faster than rotate(). */
    public Mat4 rotateX(float angleDeg) {
        float c = FastMath.cos(angleDeg);
        float s = FastMath.sin(angleDeg);
        for (int r = 0; r < 4; r++) {
            float a = m[4 + r];
            float b = m[8 + r];
            m[4 + r] = c * a + s * b;
            m[8 + r] = c * b - s * a;
        }
        return this;
    }

    /** this = this * RotationY(angleDegrees). Faster than rotate(). */
    public Mat4 rotateY(float angleDeg) {
        float c = FastMath.cos(angleDeg);
        float s = FastMath.sin(angleDeg);
        for (int r = 0; r < 4; r++) {
            float a = m[r];
            float b = m[8 + r];
            m[r] = c * a - s * b;
            m[8 + r] = s * a + c * b;
        }
        return this;
    }

    /** this = this * RotationZ(angleDegrees). Faster than rotate(). */
    public Mat4 rotateZ(float angleDeg) {
        float c = FastMath.cos(angleDeg);
        float s = FastMath.sin(angleDeg);
        for (int r = 0; r < 4; r++) {
            float a = m[r];
            float b = m[4 + r];
            m[r] = c * a + s * b;
            m[4 + r] = c * b - s * a;
        }
        return this;
    }

    /** Perspective projection (like gluPerspective). */
    public Mat4 setPerspective(float fovyDeg, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovyDeg * (Math.PI / 360.0)));
        for (int i = 0; i < 16; i++) {
            m[i] = 0f;
        }
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = 2f * far * near / (near - far);
        return this;
    }

    /** Orthographic projection (like glOrtho). */
    public Mat4 setOrtho(float l, float r, float b, float top, float near, float far) {
        for (int i = 0; i < 16; i++) {
            m[i] = 0f;
        }
        m[0] = 2f / (r - l);
        m[5] = 2f / (top - b);
        m[10] = -2f / (far - near);
        m[12] = -(r + l) / (r - l);
        m[13] = -(top + b) / (top - b);
        m[14] = -(far + near) / (far - near);
        m[15] = 1f;
        return this;
    }

    /** View matrix (like gluLookAt). */
    public Mat4 setLookAt(float ex, float ey, float ez,
                          float cx, float cy, float cz,
                          float ux, float uy, float uz) {
        float fx = cx - ex;
        float fy = cy - ey;
        float fz = cz - ez;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl;
        fy /= fl;
        fz /= fl;
        float sx = fy * uz - fz * uy;
        float sy = fz * ux - fx * uz;
        float sz = fx * uy - fy * ux;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx /= sl;
        sy /= sl;
        sz /= sl;
        float vx = sy * fz - sz * fy;
        float vy = sz * fx - sx * fz;
        float vz = sx * fy - sy * fx;

        m[0] = sx;  m[4] = sy;  m[8] = sz;   m[12] = -(sx * ex + sy * ey + sz * ez);
        m[1] = vx;  m[5] = vy;  m[9] = vz;   m[13] = -(vx * ex + vy * ey + vz * ez);
        m[2] = -fx; m[6] = -fy; m[10] = -fz; m[14] = fx * ex + fy * ey + fz * ez;
        m[3] = 0f;  m[7] = 0f;  m[11] = 0f;  m[15] = 1f;
        return this;
    }

    /**
     * this = inverse of src (src may be this). General 4x4 inverse.
     * @return false if the matrix is singular (this is left unchanged)
     */
    public boolean setInverse(Mat4 src) {
        float[] a = src.m;
        float[] inv = t;
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15]
                + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15]
                - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15]
                + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14]
                - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15]
                - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15]
                + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15]
                - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14]
                + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15]
                + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15]
                - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15]
                + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14]
                - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11]
                - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11]
                + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11]
                - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10]
                + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        float det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (det == 0f) {
            return false;
        }
        float invDet = 1f / det;
        for (int i = 0; i < 16; i++) {
            m[i] = inv[i] * invDet;
        }
        return true;
    }

    /** Transforms a point in place (affine part only, w is assumed 1). */
    public void transformPoint(Vec3 v) {
        float x = v.x;
        float y = v.y;
        float z = v.z;
        v.x = m[0] * x + m[4] * y + m[8] * z + m[12];
        v.y = m[1] * x + m[5] * y + m[9] * z + m[13];
        v.z = m[2] * x + m[6] * y + m[10] * z + m[14];
    }
}
