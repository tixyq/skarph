// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.demo.v04;

import skarph.core.Body;
import skarph.core.BitmapFont;
import skarph.core.Camera;
import skarph.core.Engine;
import skarph.core.FastMath;
import skarph.core.InputEvent;
import skarph.core.Mat4;
import skarph.core.Node;
import skarph.core.PhysicsWorld;
import skarph.core.Ray;
import skarph.core.RayHit;
import skarph.core.Renderer;
import skarph.core.Scene;
import skarph.core.ScreenAdapter;
import skarph.core.Shapes;
import skarph.core.SpriteBatch;
import skarph.core.TextBuffer;
import skarph.core.TextureRegion;

/**
 * Tech demo v0.4: collisions and physics.
 *
 * - 60 balls and boxes fall into an arena with 4 static obstacles
 * - TAP a body to kick it, TAP the ground to drop a new ball there (oldest one is recycled)
 * - drag to rotate the camera
 * - GRID button: switches the broad phase between the spatial grid and testing all pairs
 *   (watch the "tests" counter and the physics time in the HUD)
 * - MORE adds 30 bodies (up to 200), RESET restores the start state
 */
public final class PhysicsDemoScreen extends ScreenAdapter {
    private static final float VIRTUAL_HEIGHT = 320f;
    private static final float ARENA = 12f;
    private static final int MAX_DYN = 200;
    private static final int START_DYN = 60;
    private static final int ADD_STEP = 30;

    private static final float BTN_Y = VIRTUAL_HEIGHT - 40f;
    private static final float BTN_W = 9 * 16f;
    private static final float BTN_H = 32f;
    private static final float BTN_GRID_X = 8f;
    private static final float BTN_MORE_X = BTN_GRID_X + BTN_W + 12f;
    private static final float BTN_RESET_X = BTN_MORE_X + BTN_W + 12f;

    // static obstacles: center x, y, z and half extents
    private static final float[][] OBSTACLES = {
        {-5f, 1.0f, -3f, 2.0f, 1.0f, 2.0f},
        {5f, 0.75f, 2f, 1.5f, 0.75f, 3.0f},
        {0f, 0.5f, 7f, 4.0f, 0.5f, 1.0f},
        {-6f, 1.5f, 5f, 1.0f, 1.5f, 1.0f}
    };

    private Engine engine;
    private Scene scene;
    private final Camera camera = new Camera();
    private PhysicsWorld world;
    private SpriteBatch batch;
    private BitmapFont font;
    private final Mat4 hudProjection = new Mat4();
    private final InputEvent event = new InputEvent();
    private final TextBuffer text = new TextBuffer(160);
    private final TextBuffer button = new TextBuffer(16);
    private final Ray ray = new Ray();
    private final RayHit hit = new RayHit();

    private final int[] sphereMeshes = new int[3];
    private final int[] boxMeshes = new int[3];
    private final Body[] dyn = new Body[MAX_DYN];
    private final Node[] dynNode = new Node[MAX_DYN];
    private int dynCount;
    private int recycle;
    private int seed = 4711;

    private float orbit;
    private boolean touching;
    private boolean dragging;
    private float downX;
    private float downY;
    private float lastX;
    private float physAvgNanos;

    private int screenW;
    private int screenH;
    private float virtualW = VIRTUAL_HEIGHT * 1.5f;

    public void init(Engine engine, Renderer r) {
        this.engine = engine;
        scene = new Scene(512);
        scene.lightingEnabled = true;
        scene.lightDx = -0.5f;
        scene.lightDy = -1f;
        scene.lightDz = -0.35f;
        scene.lightR = 0.9f;
        scene.lightG = 0.9f;
        scene.lightB = 0.85f;
        scene.ambientR = 0.38f;
        scene.ambientG = 0.38f;
        scene.ambientB = 0.42f;

        float[][] sphereColors = {{0.95f, 0.35f, 0.30f}, {0.30f, 0.55f, 0.95f}, {0.95f, 0.80f, 0.25f}};
        float[][] boxColors = {{0.40f, 0.85f, 0.45f}, {0.85f, 0.50f, 0.90f}, {0.95f, 0.60f, 0.25f}};
        for (int i = 0; i < 3; i++) {
            sphereMeshes[i] = r.createMesh(Shapes.sphere(1f, 12, 8,
                    sphereColors[i][0], sphereColors[i][1], sphereColors[i][2]));
            boxMeshes[i] = r.createMesh(Shapes.cube(1f,
                    boxColors[i][0], boxColors[i][1], boxColors[i][2]));
        }
        int stone = r.createMesh(Shapes.cube(1f, 0.62f, 0.64f, 0.70f));
        int ground = r.createMesh(Shapes.cube(1f, 0.25f, 0.55f, 0.30f));

        // visuals: ground and four low walls (the physics arena clamps bodies at +-ARENA)
        float size = ARENA * 2f + 1f;
        Node g = scene.createNode(null);
        g.mesh = ground;
        g.boundRadius = 0.866f;
        g.setPosition(0f, -0.1f, 0f).setScale(size, 0.2f, size);
        for (int i = 0; i < 4; i++) {
            Node w = scene.createNode(null);
            w.mesh = stone;
            w.boundRadius = 0.866f;
            float off = ARENA + 0.25f;
            if (i < 2) {
                w.setPosition(i == 0 ? -off : off, 0.75f, 0f).setScale(0.5f, 1.5f, size);
            } else {
                w.setPosition(0f, 0.75f, i == 2 ? -off : off).setScale(size, 1.5f, 0.5f);
            }
        }

        world = new PhysicsWorld(256, -ARENA, 0f, -ARENA, ARENA, 16f, ARENA, 3f);

        // static obstacles
        for (int i = 0; i < OBSTACLES.length; i++) {
            float[] o = OBSTACLES[i];
            world.createBox(o[0], o[1], o[2], o[3], o[4], o[5], 0f);
            Node n = scene.createNode(null);
            n.mesh = stone;
            n.boundRadius = 0.866f;
            n.setPosition(o[0], o[1], o[2]).setScale(o[3] * 2f, o[4] * 2f, o[5] * 2f);
        }

        for (int i = 0; i < START_DYN; i++) {
            addDynamic();
        }

        batch = new SpriteBatch(r, 512);
        int fontTex = r.loadTexture("font.png", false);
        if (fontTex < 0) {
            throw new IllegalStateException("font.png failed to load (see logcat, tag engine)");
        }
        font = new BitmapFont(TextureRegion.grid(fontTex,
                r.getTextureWidth(fontTex), r.getTextureHeight(fontTex), 8, 16, 16, 6), 32);

        updateCamera();
        scene.update();
    }

    private float rand() {
        seed = seed * 1103515245 + 12345;
        return ((seed >>> 8) & 0xFFFF) / 65535f;
    }

    private void placeRandom(Body b) {
        b.x = (rand() * 2f - 1f) * (ARENA - 1.5f);
        b.y = 2f + rand() * 12f;
        b.z = (rand() * 2f - 1f) * (ARENA - 1.5f);
        b.vx = 0f;
        b.vy = 0f;
        b.vz = 0f;
    }

    /** Creates one dynamic body with its scene node. @return false if a capacity is exhausted. */
    private boolean addDynamic() {
        int index = dynCount;
        if (index >= MAX_DYN) {
            return false;
        }
        boolean sphere = (index % 3) != 0;
        int variant = (index / 3) % 3;
        float s = 0.45f + 0.15f * (index % 3);
        Body b;
        if (sphere) {
            b = world.createSphere(0f, 5f, 0f, s, 8f * s * s * s);
            if (b != null) {
                b.restitution = 0.65f;
            }
        } else {
            b = world.createBox(0f, 5f, 0f, s, s, s, 8f * s * s * s);
            if (b != null) {
                b.restitution = 0.35f;
            }
        }
        if (b == null) {
            return false;
        }
        Node n = scene.createNode(null);
        if (n == null) {
            world.destroyBody(b);
            return false;
        }
        if (sphere) {
            n.mesh = sphereMeshes[variant];
            n.boundRadius = 1f;
            n.setScale(s, s, s);
        } else {
            n.mesh = boxMeshes[variant];
            n.boundRadius = 0.866f;
            n.setScale(s * 2f, s * 2f, s * 2f);
        }
        placeRandom(b);
        n.setPosition(b.x, b.y, b.z);
        dyn[index] = b;
        dynNode[index] = n;
        dynCount++;
        return true;
    }

    private void resetWorld() {
        for (int i = dynCount - 1; i >= START_DYN; i--) {
            world.destroyBody(dyn[i]);
            scene.destroyNode(dynNode[i]);
            dyn[i] = null;
            dynNode[i] = null;
        }
        if (dynCount > START_DYN) {
            dynCount = START_DYN;
        }
        recycle = 0;
        for (int i = 0; i < dynCount; i++) {
            placeRandom(dyn[i]);
        }
    }

    public void update() {
        while (engine.input.poll(event)) {
            switch (event.type) {
                case InputEvent.DOWN:
                    if (!pressButton(event.x, event.y)) {
                        touching = true;
                        dragging = false;
                        downX = event.x;
                        downY = event.y;
                        lastX = event.x;
                    }
                    break;
                case InputEvent.MOVE:
                    if (touching) {
                        float dx = event.x - downX;
                        float dy = event.y - downY;
                        if (!dragging && dx * dx + dy * dy > 144f) {
                            dragging = true;
                        }
                        if (dragging) {
                            orbit -= (event.x - lastX) * 0.3f;
                        }
                        lastX = event.x;
                    }
                    break;
                case InputEvent.UP:
                    if (touching && !dragging) {
                        tap(downX, downY);
                    }
                    touching = false;
                    dragging = false;
                    break;
                default:
                    break;
            }
        }
        if (!touching) {
            orbit += 0.08f;
        }
        if (orbit >= 360f) {
            orbit -= 360f;
        } else if (orbit < 0f) {
            orbit += 360f;
        }
        updateCamera();

        long t0 = System.nanoTime();
        world.step();
        physAvgNanos += ((System.nanoTime() - t0) - physAvgNanos) * 0.05f;

        for (int i = 0; i < dynCount; i++) {
            Body b = dyn[i];
            Node n = dynNode[i];
            if (n.x != b.x || n.y != b.y || n.z != b.z) {
                n.setPosition(b.x, b.y, b.z);
            }
        }
        scene.update();
    }

    private void updateCamera() {
        float rad = 25f;
        camera.lookAt(rad * FastMath.sin(orbit), 17f, rad * FastMath.cos(orbit),
                0f, 1.5f, 0f, 0f, 1f, 0f);
    }

    /** Pick with a ray: kick the body that was hit, or drop a ball where the ground was tapped. */
    private void tap(float px, float py) {
        if (screenH <= 0 || !camera.screenToRay(px, py, screenW, screenH, ray)) {
            return;
        }
        if (world.raycast(ray, 200f, hit)) {
            Body b = hit.body;
            if (!b.isStatic()) {
                b.vy += 0.2f;
                b.vx += (rand() - 0.5f) * 0.12f;
                b.vz += (rand() - 0.5f) * 0.12f;
            }
            return;
        }
        float t = ray.intersectPlaneY(9f);
        if (t > 0f && dynCount > 0) {
            float limit = ARENA - 1f;
            float x = ray.ox + ray.dx * t;
            float z = ray.oz + ray.dz * t;
            Body b = dyn[recycle];
            recycle = (recycle + 1) % dynCount;
            b.x = x < -limit ? -limit : (x > limit ? limit : x);
            b.y = 9f;
            b.z = z < -limit ? -limit : (z > limit ? limit : z);
            b.vx = 0f;
            b.vy = 0f;
            b.vz = 0f;
        }
    }

    private boolean pressButton(float px, float py) {
        if (screenH <= 0) {
            return false;
        }
        float k = VIRTUAL_HEIGHT / screenH;
        float x = px * k;
        float y = py * k;
        if (y < BTN_Y || y > BTN_Y + BTN_H) {
            return false;
        }
        if (x >= BTN_GRID_X && x <= BTN_GRID_X + BTN_W) {
            world.useGrid = !world.useGrid;
            return true;
        }
        if (x >= BTN_MORE_X && x <= BTN_MORE_X + BTN_W) {
            for (int i = 0; i < ADD_STEP; i++) {
                if (!addDynamic()) {
                    break;
                }
            }
            return true;
        }
        if (x >= BTN_RESET_X && x <= BTN_RESET_X + BTN_W) {
            resetWorld();
            return true;
        }
        return false;
    }

    public void render(Renderer r) {
        int sw = r.getWidth();
        int sh = r.getHeight();
        if (sh > 0 && (sw != screenW || sh != screenH)) {
            screenW = sw;
            screenH = sh;
            virtualW = VIRTUAL_HEIGHT * sw / sh;
            camera.setPerspective(50f, (float) sw / (float) sh, 0.5f, 120f);
            hudProjection.setOrtho(0f, virtualW, VIRTUAL_HEIGHT, 0f, -1f, 1f);
        }

        r.clear(0.50f, 0.70f, 0.92f);
        scene.render(r, camera);

        batch.begin(hudProjection);
        text.clear().append("FPS ").append(engine.stats.fps)
                .append("  TPS ").append(engine.stats.tps).append('\n')
                .append("Bodies ").append(world.getBodyCount())
                .append("  contacts ").append(world.statContacts).append('\n')
                .append("Pairs ").append(world.statPairs)
                .append("  tests ").append(world.statPairTests).append('\n')
                .append("Physics ").append(physAvgNanos / 1000000f, 2).append(" ms\n")
                .append("Tap: kick / drop   VBO ").append(r.usesBufferObjects() ? "yes" : "no");
        batch.setColor(0f, 0f, 0f, 0.8f);
        font.draw(batch, text, 7f, 7f);
        batch.setColor(1f, 1f, 0.3f, 1f);
        font.draw(batch, text, 6f, 6f);

        font.setScale(2f);
        button.clear().append("GRID ").append(world.useGrid ? "ON" : "OFF");
        drawButton(button, world.useGrid, BTN_GRID_X);
        button.clear().append("MORE +").append(ADD_STEP);
        drawButton(button, true, BTN_MORE_X);
        button.clear().append("RESET");
        drawButton(button, true, BTN_RESET_X);
        font.setScale(1f);
        batch.end();
    }

    private void drawButton(TextBuffer label, boolean on, float x) {
        batch.setColor(0f, 0f, 0f, 0.8f);
        font.draw(batch, label, x + 1f, BTN_Y + 1f);
        if (on) {
            batch.setColor(0.4f, 1f, 0.4f, 1f);
        } else {
            batch.setColor(1f, 0.4f, 0.4f, 1f);
        }
        font.draw(batch, label, x, BTN_Y);
    }
}
