// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Camera: projection + view matrices and the view frustum used for culling.
 * Every setter refreshes the frustum, so there is nothing else to call.
 * Zero allocation.
 */
public final class Camera {
    public final Mat4 projection = new Mat4();
    public final Mat4 view = new Mat4();
    public float eyeX;
    public float eyeY;
    public float eyeZ;

    private final Mat4 viewProj = new Mat4();
    private final Mat4 invViewProj = new Mat4();
    private final float[] planes = new float[24]; // 6 planes: a, b, c, d (normalized, pointing inside)

    public Camera setPerspective(float fovyDeg, float aspect, float near, float far) {
        projection.setPerspective(fovyDeg, aspect, near, far);
        updateFrustum();
        return this;
    }

    public Camera setOrtho(float left, float right, float bottom, float top, float near, float far) {
        projection.setOrtho(left, right, bottom, top, near, far);
        updateFrustum();
        return this;
    }

    public Camera lookAt(float ex, float ey, float ez,
                         float cx, float cy, float cz,
                         float ux, float uy, float uz) {
        eyeX = ex;
        eyeY = ey;
        eyeZ = ez;
        view.setLookAt(ex, ey, ez, cx, cy, cz, ux, uy, uz);
        updateFrustum();
        return this;
    }

    /** Recomputes the frustum planes. Call it if you modify projection or view directly. */
    public void updateFrustum() {
        viewProj.multiply(projection, view);
        float[] m = viewProj.m;
        // Gribb/Hartmann plane extraction. Row i of the matrix: m[i], m[4+i], m[8+i], m[12+i]
        setPlane(0, m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12]);   // left
        setPlane(1, m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12]);   // right
        setPlane(2, m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13]);   // bottom
        setPlane(3, m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13]);   // top
        setPlane(4, m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14]);  // near
        setPlane(5, m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14]);  // far
    }

    private void setPlane(int i, float a, float b, float c, float d) {
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        if (len > 0f) {
            float inv = 1f / len;
            a *= inv;
            b *= inv;
            c *= inv;
            d *= inv;
        }
        int o = i * 4;
        planes[o] = a;
        planes[o + 1] = b;
        planes[o + 2] = c;
        planes[o + 3] = d;
    }

    /**
     * Converts a screen position (pixels, origin top-left, y down) into a world
     * space ray, for picking. Works for perspective and orthographic cameras.
     * @return false if the camera matrices cannot be inverted
     */
    public boolean screenToRay(float sx, float sy, int viewW, int viewH, Ray out) {
        if (!invViewProj.setInverse(viewProj)) {
            return false;
        }
        float ndcX = 2f * sx / viewW - 1f;
        float ndcY = 1f - 2f * sy / viewH;
        float[] m = invViewProj.m;
        float nw = m[3] * ndcX + m[7] * ndcY - m[11] + m[15];
        float fw = m[3] * ndcX + m[7] * ndcY + m[11] + m[15];
        if (nw == 0f || fw == 0f) {
            return false;
        }
        float nxp = (m[0] * ndcX + m[4] * ndcY - m[8] + m[12]) / nw;
        float nyp = (m[1] * ndcX + m[5] * ndcY - m[9] + m[13]) / nw;
        float nzp = (m[2] * ndcX + m[6] * ndcY - m[10] + m[14]) / nw;
        float fxp = (m[0] * ndcX + m[4] * ndcY + m[8] + m[12]) / fw;
        float fyp = (m[1] * ndcX + m[5] * ndcY + m[9] + m[13]) / fw;
        float fzp = (m[2] * ndcX + m[6] * ndcY + m[10] + m[14]) / fw;
        out.set(nxp, nyp, nzp, fxp - nxp, fyp - nyp, fzp - nzp);
        return true;
    }

    /** @return false only if the sphere is completely outside the frustum. */
    public boolean isSphereVisible(float x, float y, float z, float radius) {
        float[] pl = planes;
        for (int i = 0; i < 24; i += 4) {
            if (pl[i] * x + pl[i + 1] * y + pl[i + 2] * z + pl[i + 3] < -radius) {
                return false;
            }
        }
        return true;
    }
}
