// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
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

    /**
     * Keep the previous pose of every node that moved, so render(r, camera, alpha) can draw it between two logic
     * ticks (smooth motion at any display rate). Costs one 16 float copy per moved node per tick; false = switch it off.
     */
    public boolean interpolate = true;

    private final Node[] nodes;
    private final int[] transIdx;       // transparent nodes of this frame, sorted back to front
    private final float[] transKey;     // squared distance to the camera
    private final Mat4 blend = new Mat4();
    private final Node[] freeList;
    private int freeCount;
    private int highWater;

    public Scene(int maxNodes) {
        root = new Node(-1);
        root.active = true;
        root.worldVisible = true;
        nodes = new Node[maxNodes];
        freeList = new Node[maxNodes];
        transIdx = new int[maxNodes];
        transKey = new float[maxNodes];
        for (int i = 0; i < maxNodes; i++) {
            nodes[i] = new Node(i);
        }
        for (int i = maxNodes - 1; i >= 0; i--) {
            freeList[freeCount++] = nodes[i];
        }
    }

    /** Sorts the transparent nodes of this frame farthest first: heap sort, O(n log n), in place, no allocation. */
    private void sortTransparent(int n) {
        final float[] key = transKey;
        final int[] idx = transIdx;
        // min-heap on key; repeatedly moving the smallest to the end leaves the array in descending order
        for (int i = n / 2 - 1; i >= 0; i--) {
            siftDown(key, idx, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            float k = key[0];
            int v = idx[0];
            key[0] = key[end];
            idx[0] = idx[end];
            key[end] = k;
            idx[end] = v;
            siftDown(key, idx, 0, end);
        }
    }

    private static void siftDown(float[] key, int[] idx, int i, int n) {
        float k = key[i];
        int v = idx[i];
        while (true) {
            int c = 2 * i + 1;
            if (c >= n) {
                break;
            }
            if (c + 1 < n && (key[c + 1] < key[c] || (key[c + 1] == key[c] && idx[c + 1] > idx[c]))) {
                c++;
            }
            if (key[c] > k || (key[c] == k && idx[c] < v)) {
                break;
            }
            key[i] = key[c];
            idx[i] = idx[c];
            i = c;
        }
        key[i] = k;
        idx[i] = v;
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
            boolean snapNow = n.snap;
            if (interpolate && !snapNow) {
                n.prevWorld.set(w);
            }
            w.set(p.world);
            w.translate(n.x, n.y, n.z);
            if (n.useQuat) {
                w.rotateQuat(n.qx, n.qy, n.qz, n.qw);
            } else {
                if (n.ry != 0f) {
                    w.rotateY(n.ry);
                }
                if (n.rx != 0f) {
                    w.rotateX(n.rx);
                }
                if (n.rz != 0f) {
                    w.rotateZ(n.rz);
                }
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
            if (snapNow) {
                n.prevWorld.set(w);
                n.snap = false;
            }
        }
        n.moved = changed && interpolate;
        for (Node c = n.firstChild; c != null; c = c.nextSibling) {
            updateNode(c, changed);
        }
    }

    /** Draws all visible nodes that have a mesh, at their current pose. Fills the stat* fields. */
    public void render(Renderer r, Camera camera) {
        render(r, camera, 1f);
    }

    /**
     * Draws all visible nodes. alpha (0..1, from Engine.getAlpha()) places nodes that moved in the last tick between
     * their previous and current pose, so a 60 Hz simulation looks smooth on a 120 Hz screen and with 2 ticks per frame;
     * 1 = current poses. Opaque nodes come first; nodes with a blend mode or an opacity below 1 follow, sorted back to
     * front by the distance of their center to the camera (no allocation: preallocated arrays, insertion sort).
     * Fills the stat* fields. Culling uses the current pose.
     */
    public void render(Renderer r, Camera camera, float alpha) {
        r.setCamera(camera.projection, camera.view);
        r.setLighting(lightingEnabled);
        if (lightingEnabled) {
            r.setAmbientLight(ambientR, ambientG, ambientB);
            r.setDirectionalLight(lightDx, lightDy, lightDz, lightR, lightG, lightB);
        }
        int total = 0;
        int drawn = 0;
        int culled = 0;
        int tc = 0;
        boolean cull = cullingEnabled;
        boolean blendPose = alpha < 1f;
        float ex = camera.eyeX;
        float ey = camera.eyeY;
        float ez = camera.eyeZ;
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
            if (n.transparent) {
                float dx = n.worldX - ex;
                float dy = n.worldY - ey;
                float dz = n.worldZ - ez;
                float key = dx * dx + dy * dy + dz * dz;
                transKey[tc] = key;
                transIdx[tc] = i;
                tc++;
                continue;
            }
            Mat4 model = n.world;
            if (blendPose && n.moved) {
                blend.lerpRigid(n.prevWorld, n.world, alpha);
                model = blend;
            }
            if (n.styled) {
                applyStyle(r, n);
                r.drawMesh(n.mesh, model);
                resetStyle(r);
            } else {
                r.drawMesh(n.mesh, model);
            }
            drawn++;
        }
        if (tc > 1) {
            sortTransparent(tc);
        }
        for (int k = 0; k < tc; k++) {
            Node n = nodes[transIdx[k]];
            Mat4 model = n.world;
            if (blendPose && n.moved) {
                blend.lerpRigid(n.prevWorld, n.world, alpha);
                model = blend;
            }
            applyStyle(r, n);
            r.drawMesh(n.mesh, model);
            drawn++;
        }
        if (tc > 0) {
            resetStyle(r);
        }
        statTotal = total;
        statDrawn = drawn;
        statCulled = culled;
    }

    private static void applyStyle(Renderer r, Node n) {
        r.setBlendMode(n.blend != 0 ? n.blend : (n.alpha < 1f ? Renderer.BLEND_ALPHA : Renderer.BLEND_OPAQUE));
        r.setAlphaTest(n.alphaCutoff);
        r.setCulling(!n.doubleSided);
        r.setTint(n.tintR, n.tintG, n.tintB, n.alpha);
    }

    private static void resetStyle(Renderer r) {
        r.setBlendMode(Renderer.BLEND_OPAQUE);
        r.setAlphaTest(0f);
        r.setCulling(true);
        r.setTint(1f, 1f, 1f, 1f);
    }
}
