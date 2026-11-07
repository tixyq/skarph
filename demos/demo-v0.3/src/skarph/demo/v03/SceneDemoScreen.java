// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.demo.v03;

import skarph.core.BitmapFont;
import skarph.core.Camera;
import skarph.core.Engine;
import skarph.core.FastMath;
import skarph.core.InputEvent;
import skarph.core.Mat4;
import skarph.core.Node;
import skarph.core.Renderer;
import skarph.core.Scene;
import skarph.core.ScreenAdapter;
import skarph.core.Shapes;
import skarph.core.SpriteBatch;
import skarph.core.TextBuffer;
import skarph.core.TextureRegion;

/**
 * Tech demo v0.3: scene graph, camera, frustum culling, lighting.
 *
 * - a "solar system" built as a node hierarchy (pivot -> holder -> planet + moon pivot -> moon)
 * - a field of ~250 cubes and towers to show how many objects culling removes
 * - orbit camera: drag to rotate it
 * - HUD buttons: CULL and LIGHT toggle frustum culling and lighting
 * - HUD shows FPS/TPS, node counts, culled objects and whether VBOs are used
 */
public final class SceneDemoScreen extends ScreenAdapter {
    private static final float VIRTUAL_HEIGHT = 320f;
    private static final int GRID = 17;
    private static final float SPACING = 4f;
    private static final float CLEAR_RADIUS = 15f;
    private static final int PLANETS = 4;
    private static final int MAX_SPINNERS = 48;

    // HUD buttons (virtual units)
    private static final float BTN_Y = VIRTUAL_HEIGHT - 40f;
    private static final float BTN_W = 9 * 16f;
    private static final float BTN_H = 32f;
    private static final float BTN_CULL_X = 8f;
    private static final float BTN_LIGHT_X = BTN_CULL_X + BTN_W + 16f;

    private Engine engine;
    private Scene scene;
    private final Camera camera = new Camera();
    private SpriteBatch batch;
    private BitmapFont font;
    private final Mat4 hudProjection = new Mat4();
    private final InputEvent event = new InputEvent();
    private final TextBuffer text = new TextBuffer(128);
    private final TextBuffer button = new TextBuffer(16);

    private final Node[] pivots = new Node[PLANETS];
    private final Node[] moonPivots = new Node[PLANETS];
    private final float[] pivotAngle = new float[PLANETS];
    private final float[] pivotSpeed = {0.55f, 0.38f, 0.26f, 0.17f};
    private final float[] moonAngle = new float[PLANETS];
    private final Node[] spinners = new Node[MAX_SPINNERS];
    private int spinnerCount;
    private float spinAngle;

    private float orbit;
    private boolean dragging;
    private float lastX;
    private int screenW;
    private int screenH;
    private float virtualW = VIRTUAL_HEIGHT * 1.5f;

    public void init(Engine engine, Renderer r) {
        this.engine = engine;
        scene = new Scene(512);
        scene.lightingEnabled = true;
        scene.lightDx = -0.5f;
        scene.lightDy = -1f;
        scene.lightDz = -0.4f;
        scene.lightR = 0.9f;
        scene.lightG = 0.9f;
        scene.lightB = 0.85f;
        scene.ambientR = 0.38f;
        scene.ambientG = 0.38f;
        scene.ambientB = 0.42f;

        int cubeFaces = r.createMesh(Shapes.cube(1f));
        int cubeStone = r.createMesh(Shapes.cube(1f, 0.62f, 0.64f, 0.70f));
        int cubeBrick = r.createMesh(Shapes.cube(1f, 0.75f, 0.42f, 0.32f));
        int ground = r.createMesh(Shapes.cube(1f, 0.25f, 0.55f, 0.30f));
        int sunMesh = r.createMesh(Shapes.sphere(1f, 16, 10, 1.0f, 0.82f, 0.25f));
        int[] planetMesh = {
            r.createMesh(Shapes.sphere(1f, 12, 8, 0.35f, 0.55f, 0.95f)),
            r.createMesh(Shapes.sphere(1f, 12, 8, 0.90f, 0.40f, 0.30f)),
            r.createMesh(Shapes.sphere(1f, 12, 8, 0.45f, 0.85f, 0.45f)),
            r.createMesh(Shapes.sphere(1f, 12, 8, 0.80f, 0.55f, 0.90f))
        };
        int moonMesh = r.createMesh(Shapes.sphere(1f, 8, 6, 0.80f, 0.80f, 0.80f));

        // ground: one big flat box
        Node g = scene.createNode(null);
        g.mesh = ground;
        g.boundRadius = 0.866f;
        g.setPosition(0f, -0.1f, 0f).setScale(80f, 0.2f, 80f);

        // solar system hierarchy
        Node system = scene.createNode(null);
        system.setPosition(0f, 3f, 0f);
        Node sun = scene.createNode(system);
        sun.mesh = sunMesh;
        sun.boundRadius = 1f;
        sun.setScale(2.2f, 2.2f, 2.2f);
        for (int i = 0; i < PLANETS; i++) {
            Node pivot = scene.createNode(system);          // rotates around the sun
            Node holder = scene.createNode(pivot);          // sits at the orbit radius
            holder.setPosition(4.5f + i * 2.8f, 0f, 0f);
            Node body = scene.createNode(holder);           // the planet itself (scaled)
            body.mesh = planetMesh[i];
            body.boundRadius = 1f;
            float sz = 0.7f + 0.12f * i;
            body.setScale(sz, sz, sz);
            Node moonPivot = scene.createNode(holder);      // rotates around the planet
            Node moon = scene.createNode(moonPivot);
            moon.mesh = moonMesh;
            moon.boundRadius = 1f;
            moon.setPosition(1.7f, 0f, 0f);
            moon.setScale(0.28f, 0.28f, 0.28f);
            pivots[i] = pivot;
            moonPivots[i] = moonPivot;
            pivotAngle[i] = i * 70f;
        }

        // field of cubes and towers around the center
        int seed = 12345;
        int half = GRID / 2;
        for (int gz = -half; gz <= half; gz++) {
            for (int gx = -half; gx <= half; gx++) {
                float px = gx * SPACING;
                float pz = gz * SPACING;
                if (px * px + pz * pz < CLEAR_RADIUS * CLEAR_RADIUS) {
                    continue;
                }
                seed = seed * 1103515245 + 12345;
                int rnd = (seed >>> 16) & 0x7fff;
                Node n = scene.createNode(null);
                if (n == null) {
                    break;
                }
                n.boundRadius = 0.866f;
                float h = 1f + (rnd % 3);
                n.setPosition(px, h * 0.5f, pz).setScale(1.2f, h, 1.2f);
                if (rnd % 7 == 0 && spinnerCount < MAX_SPINNERS) {
                    n.mesh = cubeFaces;
                    n.setScale(1.3f, 1.3f, 1.3f).setPosition(px, 0.65f, pz);
                    spinners[spinnerCount++] = n;
                } else {
                    n.mesh = (rnd & 1) == 0 ? cubeStone : cubeBrick;
                }
            }
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

    public void update() {
        while (engine.input.poll(event)) {
            switch (event.type) {
                case InputEvent.DOWN:
                    if (!pressButton(event.x, event.y)) {
                        dragging = true;
                        lastX = event.x;
                    }
                    break;
                case InputEvent.MOVE:
                    if (dragging) {
                        orbit -= (event.x - lastX) * 0.3f;
                        lastX = event.x;
                    }
                    break;
                case InputEvent.UP:
                    dragging = false;
                    break;
                default:
                    break;
            }
        }
        if (!dragging) {
            orbit += 0.12f; // 7.2 degrees per second
        }
        if (orbit >= 360f) {
            orbit -= 360f;
        } else if (orbit < 0f) {
            orbit += 360f;
        }
        updateCamera();

        // planets and moons: rotate the pivots, children follow automatically
        for (int i = 0; i < PLANETS; i++) {
            pivotAngle[i] += pivotSpeed[i];
            if (pivotAngle[i] >= 360f) {
                pivotAngle[i] -= 360f;
            }
            pivots[i].setRotation(0f, pivotAngle[i], 0f);
            moonAngle[i] += 1.6f + i * 0.4f;
            if (moonAngle[i] >= 360f) {
                moonAngle[i] -= 360f;
            }
            moonPivots[i].setRotation(0f, moonAngle[i], 0f);
        }
        spinAngle += 1.2f;
        if (spinAngle >= 360f) {
            spinAngle -= 360f;
        }
        for (int i = 0; i < spinnerCount; i++) {
            spinners[i].setRotation(0f, spinAngle * (1 + (i & 1)), 0f);
        }
        scene.update();
    }

    private void updateCamera() {
        float rad = 20f;
        camera.lookAt(rad * FastMath.sin(orbit), 9f, rad * FastMath.cos(orbit),
                0f, 2.5f, 0f, 0f, 1f, 0f);
    }

    /** Converts screen pixels to virtual units and handles the HUD buttons. */
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
        if (x >= BTN_CULL_X && x <= BTN_CULL_X + BTN_W) {
            scene.cullingEnabled = !scene.cullingEnabled;
            return true;
        }
        if (x >= BTN_LIGHT_X && x <= BTN_LIGHT_X + BTN_W) {
            scene.lightingEnabled = !scene.lightingEnabled;
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
            camera.setPerspective(50f, (float) sw / (float) sh, 0.5f, 100f);
            hudProjection.setOrtho(0f, virtualW, VIRTUAL_HEIGHT, 0f, -1f, 1f);
        }

        r.clear(0.50f, 0.70f, 0.92f);
        scene.render(r, camera);

        // HUD
        batch.begin(hudProjection);
        text.clear().append("FPS ").append(engine.stats.fps)
                .append("  TPS ").append(engine.stats.tps).append('\n')
                .append("Objects ").append(scene.statTotal)
                .append("  drawn ").append(scene.statDrawn)
                .append("  culled ").append(scene.statCulled).append('\n')
                .append("VBO ").append(r.usesBufferObjects() ? "yes" : "no");
        batch.setColor(0f, 0f, 0f, 0.8f);
        font.draw(batch, text, 7f, 7f);
        batch.setColor(1f, 1f, 0.3f, 1f);
        font.draw(batch, text, 6f, 6f);

        font.setScale(2f);
        drawButton("CULL ", scene.cullingEnabled, BTN_CULL_X);
        drawButton("LIGHT ", scene.lightingEnabled, BTN_LIGHT_X);
        font.setScale(1f);
        batch.end();
    }

    private void drawButton(String label, boolean on, float x) {
        button.clear().append(label).append(on ? "ON" : "OFF");
        batch.setColor(0f, 0f, 0f, 0.8f);
        font.draw(batch, button, x + 1f, BTN_Y + 1f);
        if (on) {
            batch.setColor(0.4f, 1f, 0.4f, 1f);
        } else {
            batch.setColor(1f, 0.4f, 0.4f, 1f);
        }
        font.draw(batch, button, x, BTN_Y);
    }
}
