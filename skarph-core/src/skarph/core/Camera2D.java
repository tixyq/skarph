// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A 2D camera: position, zoom, smooth follow, level bounds, screen shake and render interpolation. World units are
 * whatever you draw in (pixels of a tile map are the usual choice). y points down.
 * <pre>
 *   Camera2D cam = new Camera2D(480, 320);              // the visible area at zoom 1
 *   cam.setBounds(0, 0, map.getPixelWidth(), map.getPixelHeight());
 *   // every tick:  cam.follow(hero.x, hero.y, 0.12f);  cam.update();
 *   // every frame: cam.getProjection(proj, engine.getAlpha());  batch.begin(proj);  map.draw(batch, cam, ...);
 * </pre>
 * With setPixelSnap(true) the view is placed on whole pixels (no shimmering seams between tiles when scrolling slowly).
 * Allocation free.
 */
public final class Camera2D {
    private float x;
    private float y;
    private float prevX;
    private float prevY;
    private float zoom = 1f;
    private final float viewW;
    private final float viewH;
    private boolean hasBounds;
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;
    private boolean snap = true;
    private int shakeLeft;
    private int shakeTotal;
    private float shakeAmp;
    private int seed = 12345;
    private float shakeX;
    private float shakeY;
    // visible rectangle of the last getProjection()
    private float left;
    private float top;
    private float right;
    private float bottom;

    /** @param viewW,viewH the size of the visible area at zoom 1 (for example the virtual screen size) */
    public Camera2D(float viewW, float viewH) {
        this.viewW = viewW;
        this.viewH = viewH;
        left = 0f;
        top = 0f;
        right = viewW;
        bottom = viewH;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZoom() {
        return zoom;
    }

    public void setZoom(float zoom) {
        this.zoom = zoom < 0.01f ? 0.01f : zoom;
    }

    public void setPixelSnap(boolean snap) {
        this.snap = snap;
    }

    /** Limits the view to a rectangle of the world (the camera stays inside it; if it is smaller than the view: centred). */
    public void setBounds(float minX, float minY, float maxX, float maxY) {
        hasBounds = true;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        clamp();
    }

    public void clearBounds() {
        hasBounds = false;
    }

    /** Jumps to a position without smoothing or interpolation (new level, teleport). */
    public void setPosition(float cx, float cy) {
        x = cx;
        y = cy;
        clamp();
        prevX = x;
        prevY = y;
    }

    /** Moves a fraction (0..1) of the way to the target; call once per tick. 1 = glued to the target. */
    public void follow(float tx, float ty, float smoothing) {
        x += (tx - x) * smoothing;
        y += (ty - y) * smoothing;
        clamp();
    }

    /** Shakes for this many ticks; the amplitude (world units) fades out linearly. */
    public void shake(float amplitude, int ticks) {
        shakeAmp = amplitude;
        shakeLeft = ticks;
        shakeTotal = ticks;
    }

    /** Once per tick, BEFORE follow(): remembers the position for interpolation and advances the shake. */
    public void update() {
        prevX = x;
        prevY = y;
        if (shakeLeft > 0) {
            float k = shakeAmp * shakeLeft / shakeTotal;
            shakeX = (rnd() - 0.5f) * 2f * k;
            shakeY = (rnd() - 0.5f) * 2f * k;
            shakeLeft--;
        } else {
            shakeX = 0f;
            shakeY = 0f;
        }
    }

    private float rnd() {
        seed = seed * 1103515245 + 12345;
        return ((seed >>> 8) & 0xFFFF) / 65536f;
    }

    private void clamp() {
        if (!hasBounds) {
            return;
        }
        float hw = viewW * 0.5f / zoom;
        float hh = viewH * 0.5f / zoom;
        x = maxX - minX <= 2f * hw ? (minX + maxX) * 0.5f : (x < minX + hw ? minX + hw : (x > maxX - hw ? maxX - hw : x));
        y = maxY - minY <= 2f * hh ? (minY + maxY) * 0.5f : (y < minY + hh ? minY + hh : (y > maxY - hh ? maxY - hh : y));
    }

    /**
     * Fills an orthographic projection (y down) for SpriteBatch.begin() and remembers the visible rectangle.
     * @param alpha Engine.getAlpha(): position between the last two ticks
     */
    public Mat4 getProjection(Mat4 out, float alpha) {
        float cx = prevX + (x - prevX) * alpha + shakeX;
        float cy = prevY + (y - prevY) * alpha + shakeY;
        float hw = viewW * 0.5f / zoom;
        float hh = viewH * 0.5f / zoom;
        float l = cx - hw;
        float t = cy - hh;
        if (snap) {
            // whole screen pixels: one pixel is 1 / zoom world units
            l = (float) FastMath.floor(l * zoom + 0.5f) / zoom;
            t = (float) FastMath.floor(t * zoom + 0.5f) / zoom;
        }
        left = l;
        top = t;
        right = l + 2f * hw;
        bottom = t + 2f * hh;
        return out.setOrtho(left, right, bottom, top, -1f, 1f);
    }

    /** Edges of the visible area as of the last getProjection(). */
    public float getLeft() {
        return left;
    }

    public float getTop() {
        return top;
    }

    public float getRight() {
        return right;
    }

    public float getBottom() {
        return bottom;
    }

    /** True if a rectangle of the world is at least partly visible (as of the last getProjection()). */
    public boolean isVisible(float rx, float ry, float rw, float rh) {
        return rx + rw >= left && rx <= right && ry + rh >= top && ry <= bottom;
    }

    /** World x under a screen x (pixels), as of the last getProjection(). */
    public float screenToWorldX(float sx, int screenW) {
        return left + sx * (right - left) / screenW;
    }

    public float screenToWorldY(float sy, int screenH) {
        return top + sy * (bottom - top) / screenH;
    }

    public float worldToScreenX(float wx, int screenW) {
        return (wx - left) * screenW / (right - left);
    }

    public float worldToScreenY(float wy, int screenH) {
        return (wy - top) * screenH / (bottom - top);
    }
}
