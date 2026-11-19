// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Scene graph node: a transform, an optional mesh and a bounding sphere.
 * Create nodes with Scene.createNode(). Change the transform ONLY through
 * setPosition/setRotation/setScale (they mark the node dirty); the public
 * fields may be read directly (cheap on old VMs), never written.
 *
 * World transform = parent.world * Translate * RotateY * RotateX * RotateZ * Scale.
 * (Rotation order: Z first, then X, then Y, angles in degrees.)
 * Children inherit the parent's position, rotation, scale and visibility.
 */
public final class Node {
    /** Mesh handle from Renderer.createMesh(), or -1 for an empty (group) node. */
    public int mesh = -1;
    /** Local visibility; a hidden node hides its whole subtree. */
    public boolean visible = true;
    /** Radius of the mesh bounding sphere in local units (mesh is assumed centered at its origin). */
    public float boundRadius;

    public float x;
    public float y;
    public float z;
    public float rx;
    public float ry;
    public float rz;
    public float sx = 1f;
    public float sy = 1f;
    public float sz = 1f;

    /** True when the rotation comes from a quaternion (setRotationQuat) instead of the Euler angles. */
    public boolean useQuat;
    public float qx;
    public float qy;
    public float qz;
    public float qw = 1f;

    /** Tint multiplied with the mesh color (setTint). */
    public float tintR = 1f;
    public float tintG = 1f;
    public float tintB = 1f;
    /** Opacity 0..1; below 1 the node is drawn in the transparent pass (setTint). */
    public float alpha = 1f;
    /** Renderer.BLEND_OPAQUE, BLEND_ALPHA or BLEND_ADD (setBlend). */
    public int blend;
    /** Pixels with less alpha than this are discarded, 0 = off (setAlphaTest). */
    public float alphaCutoff;
    /** true = both sides of the triangles are drawn (setDoubleSided). */
    public boolean doubleSided;
    /** True when any of the four appearance settings above is not the default; maintained by the setters. */
    boolean styled;
    /** True when the node must be drawn after the opaque ones, sorted back to front. */
    boolean transparent;

    // computed by Scene.update()
    public final Mat4 world = new Mat4();
    /** World matrix before the last tick, for interpolation (see Scene.render(r, camera, alpha)). */
    final Mat4 prevWorld = new Mat4();
    /** True if the transform changed in the last update, so render() blends prevWorld and world. */
    boolean moved;
    /** True until the next update: do not blend from the old pose (new node, teleport). */
    boolean snap = true;
    public float worldX;
    public float worldY;
    public float worldZ;
    public float worldRadius;
    public boolean worldVisible;

    final int index;
    boolean active;
    boolean dirty = true;
    Node parent;
    Node firstChild;
    Node nextSibling;

    Node(int index) {
        this.index = index;
    }

    public Node getParent() {
        return parent;
    }

    public Node setPosition(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        dirty = true;
        return this;
    }

    /** Euler angles in degrees. */
    public Node setRotation(float rx, float ry, float rz) {
        useQuat = false;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        dirty = true;
        return this;
    }

    /**
     * Sets the rotation from a quaternion instead of Euler angles (no gimbal lock, smooth with Quat.slerp). A later
     * setRotation() switches back to Euler angles.
     */
    public Node setRotationQuat(Quat q) {
        qx = q.x;
        qy = q.y;
        qz = q.z;
        qw = q.w;
        useQuat = true;
        dirty = true;
        return this;
    }

    /** Multiplies the mesh color and opacity (a below 1 makes the node transparent: drawn after the opaque ones, sorted). */
    public Node setTint(float r, float g, float b, float a) {
        tintR = r;
        tintG = g;
        tintB = b;
        alpha = a;
        refreshStyle();
        return this;
    }

    /** Renderer.BLEND_OPAQUE (default), BLEND_ALPHA or BLEND_ADD. Blended nodes are drawn after the opaque ones, back to front. */
    public Node setBlend(int mode) {
        blend = mode;
        refreshStyle();
        return this;
    }

    /** Discards pixels with less alpha than the cutoff (fences, leaves); 0 = off. The node stays in the opaque pass. */
    public Node setAlphaTest(float cutoff) {
        alphaCutoff = cutoff;
        refreshStyle();
        return this;
    }

    /** true = draw both sides of the triangles. */
    public Node setDoubleSided(boolean both) {
        doubleSided = both;
        refreshStyle();
        return this;
    }

    private void refreshStyle() {
        transparent = blend != 0 || alpha < 1f;
        styled = transparent || alphaCutoff > 0f || doubleSided || tintR != 1f || tintG != 1f || tintB != 1f;
    }

    /** The next Scene.update() puts the node at its new place without blending from the old one (a teleport). */
    public Node snap() {
        snap = true;
        return this;
    }

    public Node setScale(float sx, float sy, float sz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        dirty = true;
        return this;
    }

    void resetState() {
        mesh = -1;
        visible = true;
        boundRadius = 0f;
        x = 0f;
        y = 0f;
        z = 0f;
        rx = 0f;
        ry = 0f;
        rz = 0f;
        sx = 1f;
        sy = 1f;
        sz = 1f;
        useQuat = false;
        qx = 0f;
        qy = 0f;
        qz = 0f;
        qw = 1f;
        tintR = 1f;
        tintG = 1f;
        tintB = 1f;
        alpha = 1f;
        blend = 0;
        alphaCutoff = 0f;
        doubleSided = false;
        styled = false;
        transparent = false;
        moved = false;
        snap = true;
        worldVisible = false;
        worldRadius = 0f;
        dirty = true;
        parent = null;
        firstChild = null;
        nextSibling = null;
    }
}
