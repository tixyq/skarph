// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.demo.v05;

import skarph.core.BitmapFont;
import skarph.core.Body;
import skarph.core.Camera;
import skarph.core.Engine;
import skarph.core.FastMath;
import skarph.core.InputEvent;
import skarph.core.Level;
import skarph.core.LevelLoader;
import skarph.core.Mat4;
import skarph.core.Node;
import skarph.core.PhysicsWorld;
import skarph.core.Ray;
import skarph.core.Renderer;
import skarph.core.ResourceManager;
import skarph.core.Scene;
import skarph.core.ScreenAdapter;
import skarph.core.Shapes;
import skarph.core.SpriteBatch;
import skarph.core.TextBuffer;
import skarph.core.TextureRegion;

/**
 * Tech demo v0.5: assets and formats.
 *
 * - level.lvl is loaded from a file; it places level.mdl (geometry with a BAKED lightmap:
 *   soft shadows from a sun and five lamps), statue.mdl (converted from OBJ) and
 *   gem.mdl (converted from glTF)
 * - the models are loaded through the ResourceManager (cached, reference counted)
 * - statues, gems and balls are lit dynamically; the level itself is lit by its lightmap
 * - TAP the floor to drop a ball (static collision boxes come from the level file)
 * - LMAP: lightmaps on/off, LIGHT: dynamic light on/off, RELOAD: load the level file again
 *   (the new copy is loaded before the old one is released: nothing is loaded twice)
 */
public final class LevelDemoScreen extends ScreenAdapter {
    private static final float VIRTUAL_HEIGHT = 320f;
    private static final int MAX_BALLS = 24;
    private static final int MAX_GEMS = 32;
    private static final float BALL_RADIUS = 0.6f;

    private static final float BTN_Y = VIRTUAL_HEIGHT - 40f;
    private static final float BTN_W = 9 * 16f;
    private static final float BTN_H = 32f;
    private static final float BTN_LMAP_X = 8f;
    private static final float BTN_LIGHT_X = BTN_LMAP_X + BTN_W + 12f;
    private static final float BTN_RELOAD_X = BTN_LIGHT_X + BTN_W + 12f;

    private Engine engine;
    private Renderer renderer;
    private Scene scene;
    private final Camera camera = new Camera();
    private PhysicsWorld world;
    private ResourceManager res;
    private Level level;
    private SpriteBatch batch;
    private BitmapFont font;
    private final Mat4 hudProjection = new Mat4();
    private final InputEvent event = new InputEvent();
    private final TextBuffer text = new TextBuffer(192);
    private final TextBuffer button = new TextBuffer(16);
    private final Ray ray = new Ray();

    private final Node[] gems = new Node[MAX_GEMS];
    private final float[] gemBaseY = new float[MAX_GEMS];
    private int gemCount;
    private final Body[] balls = new Body[MAX_BALLS];
    private final Node[] ballNodes = new Node[MAX_BALLS];
    private int ballCount;
    private int recycle;
    private int tick;
    private int reloads;
    private int seed = 99;

    private boolean lightmaps = true;
    private float orbit = 30f;
    private boolean touching;
    private boolean dragging;
    private float downX;
    private float downY;
    private float lastX;
    private int screenW;
    private int screenH;
    private float virtualW = VIRTUAL_HEIGHT * 1.5f;

    public void init(Engine engine, Renderer r) {
        this.engine = engine;
        this.renderer = r;
        scene = new Scene(512);
        scene.lightingEnabled = true;
        world = new PhysicsWorld(256, -20f, 0f, -20f, 20f, 12f, 20f, 3f);
        res = new ResourceManager(engine.getAssets(), r, 16, 16);

        level = loadLevel();
        collectGems();

        // balls: plain meshes made in code, no files involved
        float[][] colors = {{0.95f, 0.35f, 0.30f}, {0.30f, 0.55f, 0.95f}, {0.95f, 0.80f, 0.25f}};
        int[] ballMeshes = new int[3];
        for (int i = 0; i < 3; i++) {
            ballMeshes[i] = r.createMesh(Shapes.sphere(1f, 12, 8, colors[i][0], colors[i][1], colors[i][2]));
        }
        for (int i = 0; i < MAX_BALLS; i++) {
            Body b = world.createSphere(0f, 6f, 0f, BALL_RADIUS, 1f);
            Node n = scene.createNode(null);
            if (b == null || n == null) {
                throw new IllegalStateException("world or scene is full");
            }
            b.restitution = 0.6f;
            n.mesh = ballMeshes[i % 3];
            n.boundRadius = 1f;
            n.setScale(BALL_RADIUS, BALL_RADIUS, BALL_RADIUS);
            balls[ballCount] = b;
            ballNodes[ballCount] = n;
            ballCount++;
            dropBall(i);
        }

        batch = new SpriteBatch(r, 512);
        int fontTex = res.getTexture("font.png", false, false);
        if (fontTex < 0) {
            throw new IllegalStateException(res.getLastError());
        }
        font = new BitmapFont(TextureRegion.grid(fontTex,
                r.getTextureWidth(fontTex), r.getTextureHeight(fontTex), 8, 16, 16, 6), 32);

        updateCamera();
        scene.update();
    }

    /** Reads level.lvl and builds it. A broken file stops the demo with a readable message. */
    private Level loadLevel() {
        byte[] data = engine.getAssets().readBytes("level.lvl");
        if (data == null) {
            throw new IllegalStateException("level.lvl not found in assets");
        }
        try {
            return LevelLoader.load(data, res, scene, world);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("level.lvl: " + e.getMessage());
        }
    }

    private void collectGems() {
        gemCount = 0;
        for (int i = 0; i < level.getObjectCount() && gemCount < MAX_GEMS; i++) {
            if ("gem.mdl".equals(level.getModel(i).name)) {
                gems[gemCount] = level.getNode(i);
                gemBaseY[gemCount] = level.getNode(i).y;
                gemCount++;
            }
        }
    }

    private float rand() {
        seed = seed * 1103515245 + 12345;
        return ((seed >>> 8) & 0xFFFF) / 65535f;
    }

    /** Puts ball i at one of the level's spawn points, a bit higher each time. */
    private void dropBall(int i) {
        Body b = balls[i];
        if (level.spawnCount > 0) {
            int s = (i % level.spawnCount) * 3;
            b.x = level.spawns[s] + (rand() - 0.5f) * 3f;
            b.y = level.spawns[s + 1] + (i / level.spawnCount) * 1.6f;
            b.z = level.spawns[s + 2] + (rand() - 0.5f) * 3f;
        } else {
            b.x = 0f;
            b.y = 6f + i;
            b.z = 0f;
        }
        b.vx = 0f;
        b.vy = 0f;
        b.vz = 0f;
    }

    private void reloadLevel() {
        Level old = level;
        level = loadLevel();     // the new copy first: the models are found in the cache
        old.unload(scene, world, res);
        collectGems();
        reloads++;
    }

    public void update() {
        tick++;
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
            orbit += 0.06f;
        }
        if (orbit >= 360f) {
            orbit -= 360f;
        } else if (orbit < 0f) {
            orbit += 360f;
        }
        updateCamera();

        for (int i = 0; i < gemCount; i++) {
            float phase = tick * 2f + i * 40f;
            gems[i].setRotation(0f, phase, 0f);
            gems[i].setPosition(gems[i].x, gemBaseY[i] + 0.25f * FastMath.sin(tick * 3f + i * 70f), gems[i].z);
        }

        world.step();
        for (int i = 0; i < ballCount; i++) {
            Body b = balls[i];
            Node n = ballNodes[i];
            if (n.x != b.x || n.y != b.y || n.z != b.z) {
                n.setPosition(b.x, b.y, b.z);
            }
        }
        scene.update();
    }

    private void updateCamera() {
        float rad = level.camRadius;
        camera.lookAt(level.camTargetX + rad * FastMath.sin(orbit), level.camHeight,
                level.camTargetZ + rad * FastMath.cos(orbit),
                level.camTargetX, level.camTargetY, level.camTargetZ, 0f, 1f, 0f);
    }

    /** Drops the oldest ball where the floor was tapped. */
    private void tap(float px, float py) {
        if (screenH <= 0 || !camera.screenToRay(px, py, screenW, screenH, ray)) {
            return;
        }
        float t = ray.intersectPlaneY(0f);
        if (t <= 0f) {
            return;
        }
        float limit = 19f;
        float x = ray.ox + ray.dx * t;
        float z = ray.oz + ray.dz * t;
        Body b = balls[recycle];
        recycle = (recycle + 1) % ballCount;
        b.x = x < -limit ? -limit : (x > limit ? limit : x);
        b.y = 7f;
        b.z = z < -limit ? -limit : (z > limit ? limit : z);
        b.vx = 0f;
        b.vy = 0f;
        b.vz = 0f;
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
        if (x >= BTN_LMAP_X && x <= BTN_LMAP_X + BTN_W) {
            lightmaps = !lightmaps;
            renderer.setLightmapsEnabled(lightmaps);
            return true;
        }
        if (x >= BTN_LIGHT_X && x <= BTN_LIGHT_X + BTN_W) {
            scene.lightingEnabled = !scene.lightingEnabled;
            return true;
        }
        if (x >= BTN_RELOAD_X && x <= BTN_RELOAD_X + BTN_W) {
            reloadLevel();
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
            camera.setPerspective(50f, (float) sw / (float) sh, 1f, 160f);
            hudProjection.setOrtho(0f, virtualW, VIRTUAL_HEIGHT, 0f, -1f, 1f);
        }

        r.clear(0.10f, 0.12f, 0.17f);
        scene.render(r, camera);

        batch.begin(hudProjection);
        text.clear().append("FPS ").append(engine.stats.fps)
                .append("  TPS ").append(engine.stats.tps).append('\n')
                .append("Drawn ").append(scene.statDrawn)
                .append("  culled ").append(scene.statCulled).append('\n')
                .append("Res: tex ").append(res.getTextureCount())
                .append("  models ").append(res.getModelCount())
                .append("  refs ").append(res.getTotalRefs()).append('\n')
                .append("Lightmaps ").append(r.supportsLightmaps() ? "yes" : "no")
                .append("  VBO ").append(r.usesBufferObjects() ? "yes" : "no")
                .append("  reloads ").append(reloads).append('\n')
                .append("Tap the floor: drop a ball");
        batch.setColor(0f, 0f, 0f, 0.8f);
        font.draw(batch, text, 7f, 7f);
        batch.setColor(1f, 1f, 0.3f, 1f);
        font.draw(batch, text, 6f, 6f);

        font.setScale(2f);
        button.clear().append("LMAP ").append(lightmaps ? "ON" : "OFF");
        drawButton(lightmaps, BTN_LMAP_X);
        button.clear().append("LIGHT ").append(scene.lightingEnabled ? "ON" : "OFF");
        drawButton(scene.lightingEnabled, BTN_LIGHT_X);
        button.clear().append("RELOAD");
        drawButton(true, BTN_RELOAD_X);
        font.setScale(1f);
        batch.end();
    }

    private void drawButton(boolean on, float x) {
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
