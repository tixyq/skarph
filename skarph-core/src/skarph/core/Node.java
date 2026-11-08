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

    // computed by Scene.update()
    public final Mat4 world = new Mat4();
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
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        dirty = true;
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
        worldVisible = false;
        worldRadius = 0f;
        dirty = true;
        parent = null;
        firstChild = null;
        nextSibling = null;
    }
}
