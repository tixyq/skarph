// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.demo.v10;

import skarph.core.Benchmark;
import skarph.core.BitmapFont;
import skarph.core.Camera;
import skarph.core.CameraTrack;
import skarph.core.Engine;
import skarph.core.FastMath;
import skarph.core.InputEvent;
import skarph.core.Level;
import skarph.core.LevelLoader;
import skarph.core.Mat4;
import skarph.core.PhysicsWorld;
import skarph.core.Profiler;
import skarph.core.QualityGovernor;
import skarph.core.Renderer;
import skarph.core.ResourceManager;
import skarph.core.Scene;
import skarph.core.ScreenAdapter;
import skarph.core.Settings;
import skarph.core.Sky;
import skarph.core.SpriteBatch;
import skarph.core.TextBuffer;
import skarph.core.TextureRegion;
import skarph.core.Timeline;
import skarph.core.Ui;

/**
 * Night port demo, a stress test. The baked map (terrain, fjord, water, port, props), a night sky dome, linear fog and
 * the on-screen profiler, flown through by a cutscene: a pre-baked camera track (cutscene.cam, ONE pose per tick, no
 * interpolation) and a scene timeline (cutscene.tl, scenes by tick) that loops. The menu (pages of widgets) lies over the
 * running cutscene; START plays it from tick 0, FREE ORBIT circles the port by itself (drag to turn). The effects (PortFx:
 * snow, steam, mist, lamp halos, lighthouse beam, welding sparks, navigation lights with fake blob shadows, fireworks,
 * flashes on the cuts) and the sound (PortSound: music loop, wind, horn, bell, ...) follow the timeline. The physics show,
 * the quality levels and the benchmark work as in the town demo.
 *
 * HUD buttons: MENU, CUT (cutscene / free orbit), RESTART, FOG, SKY, LMAP (baked light on/off), FX, SFX, PHYS, Q (quality), AUTO, BENCH, the backend switch.
 * The menu has MUSIC and SFX switches too (saved in settings10.txt).
 * Plain Java (no android.*): the Activity provides the GC counter and the backend restart through BackendSwitcher.
 */
public final class PortDemoScreen extends ScreenAdapter implements Ui.Listener {
    private static final float VIRTUAL_HEIGHT = 320f;
    private static final float NEAR = 0.5f;
    private static final int LEVELS = 3;
    private static final String[] QUALITY_NAMES = {"LOW", "MED", "HIGH"};
    private static final float[] FARS = {130f, 170f, 200f};      // far plane: the sky radius (100) stays inside
    private static final float[] FOG_START = {30f, 38f, 45f};
    private static final float[] FOG_END = {90f, 120f, 150f};
    private static final int[] BODY_LIMITS = {24, 48, PhysicsShow.CAPACITY};
    private static final String BENCH_FILE = "bench10.txt";
    private static final int PAGE_MENU = 0;
    private static final int PAGE_HUD = 1;
    private static final String SETTINGS_FILE = "settings10.txt";
    /** Horizon color: also the clear color and the fog color, so that the far mountains melt into the night sky. */
    private static final float HR = 0.05f;
    private static final float HG = 0.08f;
    private static final float HB = 0.17f;

    private final BackendSwitcher switcher;
    private Engine engine;
    private Renderer renderer;
    private Scene scene;
    private final Camera camera = new Camera();
    private ResourceManager res;
    private Level level;
    private Sky sky;
    private SpriteBatch batch;
    private BitmapFont font;
    private Ui ui;
    private TextureRegion whiteRegion;
    private PortFx fx;
    private PortSound snd;
    private PhysicsWorld world;
    private PhysicsShow show;
    private final Benchmark bench = new Benchmark();
    private final Settings prefs = new Settings(8);
    private final int[] bestScores = new int[LEVELS];
    private final QualityGovernor governor = new QualityGovernor(LEVELS, LEVELS - 1);
    private int quality = LEVELS - 1;
    private boolean autoQuality = true;
    private boolean benchResult;
    private boolean newRecord;
    private boolean physOn = true;
    private float far = FARS[LEVELS - 1];
    private int btnPhys;
    private int btnQual;
    private int btnAuto;
    private int btnBench;
    private int labelBench;
    private final Settings settings = new Settings(8);
    private final Profiler profiler = new Profiler();
    private final Mat4 hudProjection = new Mat4();
    private final InputEvent event = new InputEvent();

    private int labelInfo;
    private int labelScene;
    private int btnStart;
    private int btnOrbit;
    private int btnMenuBackend;
    private int btnMenuMusic;
    private int btnMenuSfx;
    private int btnFx;
    private int btnSfx;
    private int btnMenu;
    private int btnCut;
    private int btnRestart;
    private int btnFog;
    private int btnSky;
    private int btnLmap;
    private int btnBackend;
    private boolean onGles2;
    private boolean fogOn = true;
    private boolean skyOn = true;
    private boolean lightmaps = true;
    private boolean fxOn = true;
    private int page = PAGE_MENU;

    private CameraTrack track;
    private Timeline timeline;
    private boolean cutscene = true;
    /** Cutscene tick: the only clock of the camera track and the timeline (counts logic ticks, 60 per second). */
    private int cutTick;
    private int cutLoops;
    private float aspect = 1.5f;
    private float lastFov = -1f;

    private float orbit = 250f;
    private boolean touching;
    private float lastX;
    private int screenW;
    private int screenH;
    private float virtualW = VIRTUAL_HEIGHT * 1.5f;

    public PortDemoScreen(BackendSwitcher switcher) {
        this.switcher = switcher;
    }

    public void init(Engine engine, Renderer r) {
        this.engine = engine;
        this.renderer = r;
        onGles2 = r.getBackendName().indexOf("2.0") >= 0;

        scene = new Scene(Level.MAX_OBJECTS + 16 + PhysicsShow.CAPACITY);
        world = new PhysicsWorld(Level.MAX_BODIES + PhysicsShow.CAPACITY, -26f, 0f, -26f, 26f, 40f, 26f, 3f);
        scene.lightingEnabled = true;          // props are lit dynamically; terrain and buildings carry baked light
        res = new ResourceManager(engine.getAssets(), r, 16, 24);
        level = loadLevel();
        show = new PhysicsShow(r, scene, world);

        sky = new Sky(r, 100f, 12, 6,
                0.01f, 0.02f, 0.07f,           // zenith
                HR, HG, HB,                    // horizon
                0.03f, 0.04f, 0.08f);          // nadir

        batch = new SpriteBatch(r, 512);
        int fontTex = res.getTexture("font.png", false, false);
        int uiTex = res.getTexture("ui.png", false, false);
        if (fontTex < 0 || uiTex < 0) {
            throw new IllegalStateException(res.getLastError());
        }
        font = new BitmapFont(TextureRegion.grid(fontTex,
                r.getTextureWidth(fontTex), r.getTextureHeight(fontTex), 8, 16, 16, 6), 32);
        TextureRegion white = new TextureRegion().set(uiTex, r.getTextureWidth(uiTex),
                r.getTextureHeight(uiTex), 0, 0, 8, 8);
        whiteRegion = white;
        int fxTex = res.getTexture("fx.png", true, false);
        if (fxTex < 0) {
            throw new IllegalStateException(res.getLastError());
        }
        settings.load(engine.getStorage(), SETTINGS_FILE);
        snd = new PortSound(engine.getAudio());
        snd.setSfxOn(settings.getBoolean("sfxOn", true));
        if (!settings.getBoolean("musicOn", true)) {
            snd.setMusicOn(false);
        }
        snd.start();
        fx = new PortFx(r, fxTex, r.getTextureWidth(fxTex), r.getTextureHeight(fxTex), snd);
        buildUi(white);
        showPage(PAGE_MENU);

        loadCutscene();
        prefs.load(engine.getStorage(), BENCH_FILE);
        for (int i = 0; i < LEVELS; i++) {
            bestScores[i] = prefs.getInt("best" + i, 0);
        }
        autoQuality = prefs.getBoolean("auto", true);
        governor.reset();
        applyQuality(prefs.getInt("quality", LEVELS - 1), false);
        ui.setText(btnAuto, autoQuality ? "AUTO ON" : "AUTO OFF");
        updateCamera();
        scene.update();
    }

    private void loadCutscene() {
        byte[] cam = engine.getAssets().readBytes("cutscene.cam");
        byte[] tl = engine.getAssets().readBytes("cutscene.tl");
        if (cam == null || tl == null) {
            throw new IllegalStateException("cutscene.cam / cutscene.tl not found in assets");
        }
        try {
            track = CameraTrack.parse(cam);
            timeline = Timeline.parse(new String(tl, "UTF-8"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cutscene: " + e.getMessage());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("cutscene: no UTF-8");
        }
        if (track.getTickCount() < timeline.getEndTick()) {
            throw new IllegalStateException("cutscene.cam is shorter than cutscene.tl");
        }
    }

    private void buildUi(TextureRegion white) {
        ui = new Ui(24, 240, batch, font, white);   // 23 widgets used
        ui.setListener(this);
        ui.setViewport(virtualW, VIRTUAL_HEIGHT, 800, 480);
        boolean gles2 = switcher.isGles2Available();
        String backendText = onGles2 ? "USE GLES 1" : (gles2 ? "USE GLES 2" : "GLES 2 N/A");

        // ---- menu page (over the running cutscene)
        int title = ui.addLabel("NIGHT PORT", 0f, -92f, Ui.CENTER, Ui.MIDDLE, 3f);
        int sub = ui.addLabel("stress test", 0f, -58f, Ui.CENTER, Ui.MIDDLE, 1f);
        btnStart = ui.addButton("START", 0f, -6f, 220f, 44f, Ui.CENTER, Ui.MIDDLE, 2f);
        btnOrbit = ui.addButton("FREE ORBIT", 0f, 46f, 220f, 44f, Ui.CENTER, Ui.MIDDLE, 2f);
        btnMenuMusic = ui.addButton(snd.isMusicOn() ? "MUSIC ON" : "MUSIC OFF", -58f, 98f, 106f, 34f, Ui.CENTER, Ui.MIDDLE, 1f);
        btnMenuSfx = ui.addButton(snd.isSfxOn() ? "SFX ON" : "SFX OFF", 58f, 98f, 106f, 34f, Ui.CENTER, Ui.MIDDLE, 1f);
        btnMenuBackend = ui.addButton(backendText, 0f, 140f, 220f, 30f, Ui.CENTER, Ui.MIDDLE, 1f);
        ui.setEnabled(btnMenuBackend, onGles2 || gles2);
        int[] menuWidgets = {title, sub, btnStart, btnOrbit, btnMenuMusic, btnMenuSfx, btnMenuBackend};
        for (int i = 0; i < menuWidgets.length; i++) {
            ui.setGroup(menuWidgets[i], PAGE_MENU);
        }

        // ---- HUD page
        labelInfo = ui.addLabel("", 6f, 54f, Ui.LEFT, Ui.TOP, 1f);
        labelScene = ui.addLabel("", 6f, 120f, Ui.LEFT, Ui.TOP, 1f);
        btnMenu = ui.addButton("MENU", -8f, 8f, 100f, 34f, Ui.RIGHT, Ui.TOP, 1f);
        btnCut = ui.addButton("CUT ON", 8f, -48f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnRestart = ui.addButton("RESTART", 114f, -48f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnFog = ui.addButton("FOG ON", 8f, -8f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnSky = ui.addButton("SKY ON", 114f, -8f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnLmap = ui.addButton("LMAP ON", 220f, -8f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnFx = ui.addButton("FX ON", 220f, -48f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnSfx = ui.addButton(snd.isSfxOn() ? "SFX ON" : "SFX OFF", 326f, -48f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnPhys = ui.addButton("PHYS ON", 220f, -88f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnQual = ui.addButton("Q HIGH", 8f, -88f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnAuto = ui.addButton("AUTO ON", 114f, -88f, 100f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        btnBench = ui.addButton("BENCH", -8f, -8f, 100f, 34f, Ui.RIGHT, Ui.BOTTOM, 1f);
        labelBench = ui.addLabel("", -6f, 48f, Ui.RIGHT, Ui.TOP, 1f);
        btnBackend = ui.addButton(backendText, 326f, -8f, 96f, 34f, Ui.LEFT, Ui.BOTTOM, 1f);
        ui.setEnabled(btnBackend, onGles2 || gles2);
        int[] hudWidgets = {labelInfo, labelScene, btnMenu, btnCut, btnRestart, btnFog, btnSky, btnLmap, btnFx, btnSfx, btnPhys, btnQual, btnAuto, btnBench, labelBench, btnBackend};
        for (int i = 0; i < hudWidgets.length; i++) {
            ui.setGroup(hudWidgets[i], PAGE_HUD);
        }
    }

    private void showPage(int p) {
        page = p;
        ui.showGroup(p);
        touching = false;
    }

    private void setCutscene(boolean on) {
        cutscene = on;
        lastFov = -1f;                                           // the orbit uses the default fov: set it again
        ui.setText(btnCut, on ? "CUT ON" : "CUT OFF");
    }

    private Level loadLevel() {
        byte[] data = engine.getAssets().readBytes("map.lvl");
        if (data == null) {
            throw new IllegalStateException("map.lvl not found in assets");
        }
        try {
            return LevelLoader.load(data, res, scene, world);   // the static boxes of the port go into the physics world
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("map.lvl: " + e.getMessage());
        }
    }

    // ---- Ui.Listener --------------------------------------------------------------

    private void saveSettings() {
        settings.putBoolean("sfxOn", snd.isSfxOn());
        settings.putBoolean("musicOn", snd.isMusicOn());
        settings.save(engine.getStorage(), SETTINGS_FILE);
    }

    public void onClick(int id) {
        snd.play(PortSound.CLICK, 0.5f, 1f);                     // silent when SFX is off (or just switched off below)
        if (id == btnStart) {
            setCutscene(true);
            cutTick = 0;
            cutLoops = 0;
            fx.restart();
            show.reset();
            showPage(PAGE_HUD);
        } else if (id == btnOrbit) {
            setCutscene(false);
            showPage(PAGE_HUD);
        } else if (id == btnMenu) {
            setCutscene(true);                                   // the menu lies over the cutscene again
            showPage(PAGE_MENU);
        } else if (id == btnBackend || id == btnMenuBackend) {
            switcher.switchBackend(onGles2 ? BackendSwitcher.GLES1 : BackendSwitcher.GLES2);
        } else if (id == btnFog) {
            fogOn = !fogOn;
            ui.setText(btnFog, fogOn ? "FOG ON" : "FOG OFF");
        } else if (id == btnSky) {
            skyOn = !skyOn;
            ui.setText(btnSky, skyOn ? "SKY ON" : "SKY OFF");
        } else if (id == btnLmap) {
            lightmaps = !lightmaps;
            renderer.setLightmapsEnabled(lightmaps);
            ui.setText(btnLmap, lightmaps ? "LMAP ON" : "LMAP OFF");
        } else if (id == btnCut) {
            setCutscene(!cutscene);
        } else if (id == btnRestart) {
            cutTick = 0;
            cutLoops = 0;
            fx.restart();
            show.reset();
        } else if (id == btnQual) {
            autoQuality = false;                                 // a manual choice stops the automatic one
            ui.setText(btnAuto, "AUTO OFF");
            applyQuality((quality + 1) % LEVELS, true);
        } else if (id == btnAuto) {
            autoQuality = !autoQuality;
            governor.setLevel(quality);
            ui.setText(btnAuto, autoQuality ? "AUTO ON" : "AUTO OFF");
            savePrefs();
        } else if (id == btnBench) {
            if (bench.isRunning()) {
                bench.stop();                                    // abort: no result
                setBenchUi(false);
            } else {
                startBench();
            }
        } else if (id == btnPhys) {
            physOn = !physOn;
            show.reset();
            ui.setText(btnPhys, physOn ? "PHYS ON" : "PHYS OFF");
        } else if (id == btnFx) {
            fxOn = !fxOn;
            fx.clear();
            ui.setText(btnFx, fxOn ? "FX ON" : "FX OFF");
        } else if (id == btnSfx || id == btnMenuSfx) {
            snd.setSfxOn(!snd.isSfxOn());
            String t = snd.isSfxOn() ? "SFX ON" : "SFX OFF";
            ui.setText(btnSfx, t);
            ui.setText(btnMenuSfx, t);
            saveSettings();
        } else if (id == btnMenuMusic) {
            snd.setMusicOn(!snd.isMusicOn());
            ui.setText(btnMenuMusic, snd.isMusicOn() ? "MUSIC ON" : "MUSIC OFF");
            saveSettings();
        }
    }

    public void onValueChanged(int id, float value) {
    }

    // ---- quality ------------------------------------------------------------------

    /** Sets a quality level: view distance and fog, effect density, number of physics bodies. */
    private void applyQuality(int level, boolean save) {
        quality = level < 0 ? 0 : (level >= LEVELS ? LEVELS - 1 : level);
        far = FARS[quality];
        lastFov = -1f;                                           // the projection is set again with the new far plane
        screenW = 0;
        fx.setQuality(quality);
        show.setLimit(BODY_LIMITS[quality]);
        ui.setText(btnQual, "Q " + QUALITY_NAMES[quality]);
        governor.setLevel(quality);
        if (save) {
            savePrefs();
        }
    }

    private void savePrefs() {                                   // on a change only, never per frame
        prefs.putInt("quality", quality);
        prefs.putBoolean("auto", autoQuality);
        prefs.save(engine.getStorage(), BENCH_FILE);
    }

    public int getQuality() {
        return quality;
    }

    public boolean isAutoQuality() {
        return autoQuality;
    }

    // ---- benchmark ----------------------------------------------------------------

    /** The fixed run: the whole cutscene once from tick 0, with the same settings on every device. */
    private void startBench() {
        cutscene = true;
        fogOn = true;
        skyOn = true;
        fxOn = true;
        physOn = true;
        if (!lightmaps) {
            lightmaps = true;
            renderer.setLightmapsEnabled(true);
        }
        lastFov = -1f;
        cutTick = 0;
        cutLoops = 0;
        fx.restart();
        show.reset();
        ui.setText(btnCut, "CUT ON");
        ui.setText(btnFog, "FOG ON");
        ui.setText(btnSky, "SKY ON");
        ui.setText(btnLmap, "LMAP ON");
        ui.setText(btnFx, "FX ON");
        ui.setText(btnPhys, "PHYS ON");
        benchResult = false;
        governor.setLevel(quality);
        bench.start();
        setBenchUi(true);
    }

    /** While the run is on only BENCH (= stop) works: every other button would change the measured scene. */
    private void setBenchUi(boolean running) {
        ui.setText(btnBench, running ? "STOP" : "BENCH");
        int[] ids = {btnMenu, btnCut, btnRestart, btnFx, btnSfx, btnPhys, btnFog, btnSky, btnLmap, btnQual, btnAuto};
        for (int i = 0; i < ids.length; i++) {
            ui.setEnabled(ids[i], !running);
        }
        ui.setEnabled(btnBackend, !running && (onGles2 || switcher.isGles2Available()));
    }

    private void endBench() {
        bench.stop();
        benchResult = true;
        int score = bench.getScore();
        newRecord = score > bestScores[quality];
        if (newRecord) {
            bestScores[quality] = score;
            prefs.putInt("best" + quality, score);               // one record per quality level
            prefs.save(engine.getStorage(), BENCH_FILE);         // once per run: allowed to allocate here
        }
        setBenchUi(false);
    }

    /** True while a benchmark run is on (for tests). */
    public boolean isBenchRunning() {
        return bench.isRunning();
    }

    public int getBenchScore() {
        return benchResult ? bench.getScore() : 0;
    }

    public int getBestScore() {
        return bestScores[quality];
    }

    public int getBodyCount() {
        return show.getActiveCount();
    }

    /** True while the menu page is shown (for tests). */
    public boolean isMenuShown() {
        return page == PAGE_MENU;
    }

    public boolean isCutscene() {
        return cutscene;
    }

    public int getCutTick() {
        return cutTick;
    }

    public int getCutLoops() {
        return cutLoops;
    }

    /** Strength of the flash of the current tick (0 = none), for tests. */
    public float getFlash() {
        return fxOn ? fx.getFlash() : 0f;
    }

    // ---- game loop ----------------------------------------------------------------

    public void update() {
        while (engine.input.poll(event)) {
            if (ui.handle(event)) {
                continue;
            }
            if (event.pointer != 0 || page != PAGE_HUD) {
                continue;
            }
            switch (event.type) {
                case InputEvent.DOWN:
                    touching = true;
                    lastX = event.x;
                    break;
                case InputEvent.MOVE:
                    if (touching) {
                        orbit -= (event.x - lastX) * 0.3f;
                        lastX = event.x;
                    }
                    break;
                case InputEvent.UP:
                    touching = false;
                    break;
                default:
                    break;
            }
        }
        if (cutscene) {
            cutTick++;                                           // the one clock of the camera track and the timeline
            if (timeline.isFinished(cutTick)) {
                cutTick = 0;                                     // loop: the stress test runs until you stop it
                cutLoops++;
                show.reset();                                    // the show starts the same way every loop
            }
        } else {
            if (!touching) {
                orbit += 0.12f;
            }
            if (orbit >= 360f) {
                orbit -= 360f;
            } else if (orbit < 0f) {
                orbit += 360f;
            }
        }
        updateCamera();
        int sc = cutscene ? timeline.sceneAt(cutTick) : -1;      // free orbit: only the ambient effects
        if (physOn) {
            show.update(sc);                                     // steps the world, moves the nodes
        }
        if (fxOn) {
            fx.update(sc, sc >= 0 ? cutTick - timeline.getStart(sc) : 0, camera.eyeX, camera.eyeY, camera.eyeZ);
        }
        snd.update();
        scene.update();
    }

    private void updateCamera() {
        float fov = cutscene ? track.getFov(cutTick) : 55f;
        if (fov != lastFov) {                                    // only fills a matrix: no allocation
            lastFov = fov;
            camera.setPerspective(fov, aspect, NEAR, far);
        }
        if (cutscene) {
            track.apply(cutTick, camera);                        // the pose of this tick, no interpolation
        } else {
            camera.lookAt(55f * FastMath.sin(orbit), 30f, 55f * FastMath.cos(orbit), -5f, 3f, 0f, 0f, 1f, 0f);
        }
    }

    public void render(Renderer r) {
        int sw = r.getWidth();
        int sh = r.getHeight();
        if (sh > 0 && (sw != screenW || sh != screenH)) {
            screenW = sw;
            screenH = sh;
            virtualW = VIRTUAL_HEIGHT * sw / sh;
            aspect = (float) sw / (float) sh;
            camera.setPerspective(lastFov > 0f ? lastFov : 55f, aspect, NEAR, far);
            hudProjection.setOrtho(0f, virtualW, VIRTUAL_HEIGHT, 0f, -1f, 1f);
            ui.setViewport(virtualW, VIRTUAL_HEIGHT, sw, sh);
        }

        if (!bench.isRunning() && autoQuality && governor.frame(engine.stats.nowNanos)) {
            applyQuality(governor.getLevel(), true);            // too slow (or room to spare): one level down (up)
        }
        if (bench.isRunning()) {
            bench.frame(engine.stats.nowNanos);
            if (cutLoops >= 1) {
                endBench();                                     // the cutscene came round once: the run is over
            }
        }
        r.clear(HR, HG, HB);
        r.setCamera(camera.projection, camera.view);
        if (skyOn) {
            sky.draw(r);                                        // first: everything later covers it
        }
        if (fogOn) {
            r.setFog(true, FOG_START[quality], FOG_END[quality], HR, HG, HB);
        } else {
            r.setFog(false, 0f, 0f, 0f, 0f, 0f);
        }
        scene.render(r, camera);
        if (fxOn) {
            fx.draw(camera);                                    // after the meshes, before any SpriteBatch
        }
        r.setFog(false, 0f, 0f, 0f, 0f, 0f);                    // the HUD is never fogged anyway; keep the state clean

        if (page == PAGE_HUD) {
            ui.text(labelInfo).clear().append(r.getBackendName()).append("  Q ").append(QUALITY_NAMES[quality]).append('\n')
                    .append("Drawn ").append(scene.statDrawn).append("  culled ").append(scene.statCulled).append('\n')
                    .append("Res: tex ").append(res.getTextureCount()).append("  models ").append(res.getModelCount()).append('\n')
                    .append("FX particles ").append(fxOn ? fx.getParticleCount() : 0)
                    .append("  bodies ").append(show.getActiveCount());
            TextBuffer bl = ui.text(labelBench).clear();
            if (bench.isRunning()) {
                bl.append("BENCHMARK ").append(cutTick * 100 / timeline.getEndTick()).append("%\n")
                        .append(bench.getSeconds(), 1).append(" s  ").append(bench.getFrames()).append(" frames");
            } else if (benchResult) {
                bl.append(newRecord ? "NEW RECORD\n" : "RESULT\n")
                        .append("AVG ").append(bench.getAvgFps(), 1).append("  MIN ").append(bench.getMinFps(), 1).append('\n')
                        .append("SCORE ").append(bench.getScore()).append("  BEST ").append(bestScores[quality]);
            } else if (bestScores[quality] > 0) {
                bl.append("BEST ").append(bestScores[quality]);
            }
            TextBuffer sl = ui.text(labelScene).clear();
            if (cutscene) {
                int sc = timeline.sceneAt(cutTick);
                sl.append("Scene ").append(sc + 1).append('/').append(timeline.getSceneCount()).append(' ')
                        .append(timeline.getName(sc)).append('\n')
                        .append("Tick ").append(cutTick).append('/').append(timeline.getEndTick())
                        .append("  loop ").append(cutLoops);
            } else {
                sl.append("Free orbit");
            }
        }
        batch.begin(hudProjection);
        if (fxOn && fx.getFlash() > 0f) {                       // cold flash on the hard cuts
            batch.setColor(0.8f, 0.9f, 1f, fx.getFlash());
            batch.draw(whiteRegion, 0f, 0f, virtualW, VIRTUAL_HEIGHT);
            batch.setColor(1f, 1f, 1f, 1f);
        }
        ui.draw();
        profiler.setGcCount(switcher.getGcCount());
        profiler.frame(engine.stats, r);                        // last: counts everything drawn so far
        font.draw(batch, profiler.getText(), 6f, 6f);
        batch.end();
    }

    public void dispose() {
        if (snd != null) {
            snd.release();
        }
        if (sky != null) {
            sky.release(renderer);
        }
        if (show != null) {
            show.release(renderer);
        }
    }
}
