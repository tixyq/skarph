// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.demo.v07;

import skarph.android.EngineView;
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
import skarph.core.TextureRegion;
import skarph.core.Ui;

/**
 * Tech demo v0.7: two graphics backends, one scene.
 *
 * The level of demo v0.5 (baked lightmaps, lit statues and gems, bouncing balls, sprite HUD) runs on the
 * fixed-pipeline OpenGL ES 1.x backend or on the OpenGL ES 2.0 shader backend. The HUD shows which one is active;
 * the last button restarts the demo with the other one (the choice is remembered). With ES 2.0 lighting is computed
 * per pixel instead of per vertex, so statues and balls look smoother; the level itself looks the same (baked).
 */
public final class BackendDemoScreen extends ScreenAdapter implements Ui.Listener {
    private static final float VIRTUAL_HEIGHT = 320f;
    private static final int MAX_BALLS = 24;
    private static final int MAX_GEMS = 32;
    private static final float BALL_RADIUS = 0.6f;

    private final BackendSwitcher switcher;
    private Engine engine;
    private Renderer renderer;
    private Scene scene;
    private final Camera camera = new Camera();
    private PhysicsWorld world;
    private ResourceManager res;
    private Level level;
    private SpriteBatch batch;
    private Ui ui;
    private final Mat4 hudProjection = new Mat4();
    private final InputEvent event = new InputEvent();
    private final Ray ray = new Ray();

    private int labelHud;
    private int btnLmap;
    private int btnLight;
    private int btnReload;
    private int btnBackend;
    private boolean onGles2;
    private boolean gles2Available;

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

    public BackendDemoScreen(BackendSwitcher switcher) {
        this.switcher = switcher;
    }

    public void init(Engine engine, Renderer r) {
        this.engine = engine;
        this.renderer = r;
        onGles2 = r.getBackendName().indexOf("2.0") >= 0;
        gles2Available = switcher.isGles2Available();

        scene = new Scene(512);
        scene.lightingEnabled = true;
        world = new PhysicsWorld(256, -20f, 0f, -20f, 20f, 12f, 20f, 3f);
        res = new ResourceManager(engine.getAssets(), r, 16, 16);

        level = loadLevel();
        collectGems();

        float[][] colors = {{0.95f, 0.35f, 0.30f}, {0.30f, 0.55f, 0.95f}, {0.95f, 0.80f, 0.25f}};
        int[] ballMeshes = new int[3];
        for (int i = 0; i < 3; i++) {
            ballMeshes[i] = r.createMesh(Shapes.sphere(1f, 16, 10, colors[i][0], colors[i][1], colors[i][2]));
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
        int uiTex = res.getTexture("ui.png", false, false);
        if (fontTex < 0 || uiTex < 0) {
            throw new IllegalStateException(res.getLastError());
        }
        BitmapFont font = new BitmapFont(TextureRegion.grid(fontTex,
                r.getTextureWidth(fontTex), r.getTextureHeight(fontTex), 8, 16, 16, 6), 32);
        TextureRegion white = new TextureRegion().set(uiTex, r.getTextureWidth(uiTex),
                r.getTextureHeight(uiTex), 0, 0, 8, 8);
        buildUi(font, white);

        updateCamera();
        scene.update();
    }

    private void buildUi(BitmapFont font, TextureRegion white) {
        ui = new Ui(8, 192, batch, font, white);
        ui.setListener(this);
        ui.setViewport(virtualW, VIRTUAL_HEIGHT, 800, 480);
        labelHud = ui.addLabel("", 6f, 6f, Ui.LEFT, Ui.TOP, 1f);
        btnLmap = ui.addButton("LMAP ON", 8f, -8f, 118f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnLight = ui.addButton("LIGHT ON", 132f, -8f, 118f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnReload = ui.addButton("RELOAD", 256f, -8f, 118f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnBackend = ui.addButton(onGles2 ? "USE GLES 1" : (gles2Available ? "USE GLES 2" : "GLES 2 N/A"),
                380f, -8f, 118f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        ui.setEnabled(btnBackend, onGles2 || gles2Available);
    }

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
        level = loadLevel();
        old.unload(scene, world, res);
        collectGems();
        reloads++;
    }

    // ---- Ui.Listener --------------------------------------------------------------

    public void onClick(int id) {
        if (id == btnLmap) {
            lightmaps = !lightmaps;
            renderer.setLightmapsEnabled(lightmaps);
            ui.setText(btnLmap, lightmaps ? "LMAP ON" : "LMAP OFF");
        } else if (id == btnLight) {
            scene.lightingEnabled = !scene.lightingEnabled;
            ui.setText(btnLight, scene.lightingEnabled ? "LIGHT ON" : "LIGHT OFF");
        } else if (id == btnReload) {
            reloadLevel();
        } else if (id == btnBackend) {
            switcher.switchBackend(onGles2 ? EngineView.BACKEND_GLES1 : EngineView.BACKEND_GLES2);
        }
    }

    public void onValueChanged(int id, float value) {
    }

    // ---- game loop ----------------------------------------------------------------

    public void update() {
        tick++;
        while (engine.input.poll(event)) {
            if (ui.handle(event)) {
                continue;
            }
            if (event.pointer != 0) {
                continue; // the orbit/tap gesture uses the first finger only
            }
            switch (event.type) {
                case InputEvent.DOWN:
                    touching = true;
                    dragging = false;
                    downX = event.x;
                    downY = event.y;
                    lastX = event.x;
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
            gems[i].setRotation(0f, tick * 2f + i * 40f, 0f);
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

    public void render(Renderer r) {
        int sw = r.getWidth();
        int sh = r.getHeight();
        if (sh > 0 && (sw != screenW || sh != screenH)) {
            screenW = sw;
            screenH = sh;
            virtualW = VIRTUAL_HEIGHT * sw / sh;
            camera.setPerspective(50f, (float) sw / (float) sh, 1f, 160f);
            hudProjection.setOrtho(0f, virtualW, VIRTUAL_HEIGHT, 0f, -1f, 1f);
            ui.setViewport(virtualW, VIRTUAL_HEIGHT, sw, sh);
        }

        r.clear(0.10f, 0.12f, 0.17f);
        scene.render(r, camera);

        ui.text(labelHud).clear().append("FPS ").append(engine.stats.fps)
                .append("  TPS ").append(engine.stats.tps).append('\n')
                .append(r.getBackendName()).append('\n')
                .append("Drawn ").append(scene.statDrawn).append("  culled ").append(scene.statCulled).append('\n')
                .append("Res: tex ").append(res.getTextureCount()).append("  models ").append(res.getModelCount())
                .append("  refs ").append(res.getTotalRefs()).append('\n')
                .append("Lightmaps ").append(r.supportsLightmaps() ? "yes" : "no")
                .append("  VBO ").append(r.usesBufferObjects() ? "yes" : "no")
                .append("  reloads ").append(reloads);
        batch.begin(hudProjection);
        ui.draw();
        batch.end();
    }
}
