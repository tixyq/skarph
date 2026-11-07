// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Scene graph with a fixed node capacity (all nodes are created up front, so
 * createNode()/destroyNode() never allocate).
 *
 * Per tick:  change node transforms, then scene.update().
 * Per frame: scene.render(renderer, camera)  (frustum culling + drawing).
 * Only nodes whose transform (or an ancestor's) changed are recomputed.
 */
public final class Scene {
    public final Node root;

    /** Skip nodes whose bounding sphere is outside the camera frustum. */
    public boolean cullingEnabled = true;

    /** Simple lighting: one directional light plus ambient. Needs meshes with normals. */
    public boolean lightingEnabled;
    /** Direction the light travels (from the light into the scene). */
    public float lightDx = -0.4f;
    public float lightDy = -1f;
    public float lightDz = -0.3f;
    public float lightR = 1f;
    public float lightG = 1f;
    public float lightB = 1f;
    public float ambientR = 0.3f;
    public float ambientG = 0.3f;
    public float ambientB = 0.3f;

    /** Statistics of the last render(): drawable nodes, drawn, culled. */
    public int statTotal;
    public int statDrawn;
    public int statCulled;

    private final Node[] nodes;
    private final Node[] freeList;
    private int freeCount;
    private int highWater;

    public Scene(int maxNodes) {
        root = new Node(-1);
        root.active = true;
        root.worldVisible = true;
        nodes = new Node[maxNodes];
        freeList = new Node[maxNodes];
        for (int i = 0; i < maxNodes; i++) {
            nodes[i] = new Node(i);
        }
        for (int i = maxNodes - 1; i >= 0; i--) {
            freeList[freeCount++] = nodes[i];
        }
    }

    /**
     * Creates a node under the given parent (null = scene root).
     * @return the node, or null if the capacity is exhausted.
     */
    public Node createNode(Node parent) {
        if (freeCount == 0) {
            return null;
        }
        Node n = freeList[--freeCount];
        n.resetState();
        n.active = true;
        if (n.index >= highWater) {
            highWater = n.index + 1;
        }
        Node p = parent != null ? parent : root;
        n.parent = p;
        n.nextSibling = p.firstChild;
        p.firstChild = n;
        return n;
    }

    /** Destroys a node together with all its children; the slots can be reused. */
    public void destroyNode(Node n) {
        if (n == null || n == root || !n.active) {
            return;
        }
        Node p = n.parent;
        if (p.firstChild == n) {
            p.firstChild = n.nextSibling;
        } else {
            Node s = p.firstChild;
            while (s != null && s.nextSibling != n) {
                s = s.nextSibling;
            }
            if (s != null) {
                s.nextSibling = n.nextSibling;
            }
        }
        release(n);
    }

    private void release(Node n) {
        Node c = n.firstChild;
        while (c != null) {
            Node next = c.nextSibling;
            release(c);
            c = next;
        }
        n.active = false;
        n.firstChild = null;
        n.nextSibling = null;
        n.parent = null;
        freeList[freeCount++] = n;
    }

    public int getFreeNodes() {
        return freeCount;
    }

    /** Recomputes world transforms of changed nodes. Call once per tick after moving nodes. */
    public void update() {
        for (Node c = root.firstChild; c != null; c = c.nextSibling) {
            updateNode(c, false);
        }
    }

    private void updateNode(Node n, boolean parentChanged) {
        Node p = n.parent;
        boolean changed = n.dirty || parentChanged;
        n.worldVisible = p.worldVisible && n.visible;
        if (changed) {
            Mat4 w = n.world;
            w.set(p.world);
            w.translate(n.x, n.y, n.z);
            if (n.ry != 0f) {
                w.rotateY(n.ry);
            }
            if (n.rx != 0f) {
                w.rotateX(n.rx);
            }
            if (n.rz != 0f) {
                w.rotateZ(n.rz);
            }
            if (n.sx != 1f || n.sy != 1f || n.sz != 1f) {
                w.scale(n.sx, n.sy, n.sz);
            }
            float[] m = w.m;
            n.worldX = m[12];
            n.worldY = m[13];
            n.worldZ = m[14];
            float s0 = m[0] * m[0] + m[1] * m[1] + m[2] * m[2];
            float s1 = m[4] * m[4] + m[5] * m[5] + m[6] * m[6];
            float s2 = m[8] * m[8] + m[9] * m[9] + m[10] * m[10];
            if (s1 > s0) {
                s0 = s1;
            }
            if (s2 > s0) {
                s0 = s2;
            }
            n.worldRadius = n.boundRadius * (float) Math.sqrt(s0);
            n.dirty = false;
        }
        for (Node c = n.firstChild; c != null; c = c.nextSibling) {
            updateNode(c, changed);
        }
    }

    /** Draws all visible nodes that have a mesh. Fills the stat* fields. */
    public void render(Renderer r, Camera camera) {
        r.setCamera(camera.projection, camera.view);
        r.setLighting(lightingEnabled);
        if (lightingEnabled) {
            r.setAmbientLight(ambientR, ambientG, ambientB);
            r.setDirectionalLight(lightDx, lightDy, lightDz, lightR, lightG, lightB);
        }
        int total = 0;
        int drawn = 0;
        int culled = 0;
        boolean cull = cullingEnabled;
        for (int i = 0; i < highWater; i++) {
            Node n = nodes[i];
            if (!n.active || n.mesh < 0 || !n.worldVisible) {
                continue;
            }
            total++;
            if (cull && !camera.isSphereVisible(n.worldX, n.worldY, n.worldZ, n.worldRadius)) {
                culled++;
                continue;
            }
            r.drawMesh(n.mesh, n.world);
            drawn++;
        }
        statTotal = total;
        statDrawn = drawn;
        statCulled = culled;
    }
}
