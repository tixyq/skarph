// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.demo.v06;

import skarph.core.Audio;
import skarph.core.BitmapFont;
import skarph.core.Engine;
import skarph.core.FastMath;
import skarph.core.FrameAnimation;
import skarph.core.InputEvent;
import skarph.core.Mat4;
import skarph.core.Renderer;
import skarph.core.ScreenAdapter;
import skarph.core.Settings;
import skarph.core.SpriteBatch;
import skarph.core.TextureRegion;
import skarph.core.TouchState;
import skarph.core.Ui;

/**
 * Tech demo v0.6: platform features.
 *
 * - a menu built with the engine's UI layer: buttons, sliders, toggles, labels, pages
 * - sound effects and a music loop (volume and on/off are settings)
 * - settings and statistics are saved (Settings + Storage) and restored on the next start
 * - touch lab: every finger gets a glowing cursor (multi-touch), a tap drops a coin with a sound,
 *   pinching with two fingers changes the coin size
 */
public final class MenuDemoScreen extends ScreenAdapter implements Ui.Listener {
    private static final float VIRTUAL_HEIGHT = 320f;
    private static final int PAGE_MENU = 0;
    private static final int PAGE_SETTINGS = 1;
    private static final int PAGE_PLAY = 2;
    private static final String SETTINGS_FILE = "settings.txt";
    private static final int MAX_COINS = 48;
    private static final int TILE = 32;

    private Engine engine;
    private Audio audio;
    private final Settings settings = new Settings(16);
    private SpriteBatch batch;
    private BitmapFont font;
    private Ui ui;
    private final TouchState touch = new TouchState();
    private final InputEvent event = new InputEvent();
    private final Mat4 projection = new Mat4();

    private TextureRegion tile;
    private TextureRegion glow;
    private FrameAnimation coinAnim;

    // widget ids
    private int btnPlay;
    private int btnSettings;
    private int btnBackSettings;
    private int btnBackPlay;
    private int sliderMusic;
    private int sliderSfx;
    private int toggleMusic;
    private int toggleSfx;
    private int labelStats;
    private int labelHud;
    private int labelPlay;

    // sounds
    private int sndClick;
    private int sndBack;
    private int sndCoin;
    private int sndToggle;

    // settings (mirrors of the saved values)
    private float musicVolume;
    private float sfxVolume;
    private boolean musicOn;
    private boolean sfxOn;
    private int coinsTotal;
    private int rounds;

    // touch lab
    private final float[] coinX = new float[MAX_COINS];
    private final float[] coinY = new float[MAX_COINS];
    private final float[] coinVx = new float[MAX_COINS];
    private final float[] coinVy = new float[MAX_COINS];
    private final float[] coinSize = new float[MAX_COINS];
    private final int[] coinAge = new int[MAX_COINS];
    private final boolean[] coinActive = new boolean[MAX_COINS];
    private int coinsThisRound;
    private float coinScale = 1f;

    private int page = PAGE_MENU;
    private int tick;
    private int lastTickSound;
    private int screenW;
    private int screenH;
    private float virtualW = VIRTUAL_HEIGHT * 1.5f;

    public void init(Engine engine, Renderer r) {
        this.engine = engine;
        this.audio = engine.getAudio();

        // saved state from the previous run (defaults on the first start)
        settings.load(engine.getStorage(), SETTINGS_FILE);
        musicVolume = settings.getFloat("musicVolume", 0.6f);
        sfxVolume = settings.getFloat("sfxVolume", 0.8f);
        musicOn = settings.getBoolean("musicOn", true);
        sfxOn = settings.getBoolean("sfxOn", true);
        coinsTotal = settings.getInt("coins", 0);
        rounds = settings.getInt("rounds", 0);

        int atlasTex = r.loadTexture("atlas.png", false);
        int fontTex = r.loadTexture("font.png", false);
        int uiTex = r.loadTexture("ui.png", false);
        if (atlasTex < 0 || fontTex < 0 || uiTex < 0) {
            throw new IllegalStateException("texture loading failed (see logcat, tag engine)");
        }
        TextureRegion[] a = TextureRegion.grid(atlasTex, r.getTextureWidth(atlasTex),
                r.getTextureHeight(atlasTex), 32, 32, 4, 4);
        coinAnim = new FrameAnimation(new TextureRegion[] {a[4], a[5], a[6], a[7]}, 6, true);
        tile = a[8];
        glow = a[10];
        font = new BitmapFont(TextureRegion.grid(fontTex, r.getTextureWidth(fontTex),
                r.getTextureHeight(fontTex), 8, 16, 16, 6), 32);
        TextureRegion white = new TextureRegion().set(uiTex, r.getTextureWidth(uiTex),
                r.getTextureHeight(uiTex), 0, 0, 8, 8);
        batch = new SpriteBatch(r, 768);

        sndClick = audio.loadSound("click.wav");
        sndBack = audio.loadSound("back.wav");
        sndCoin = audio.loadSound("coin.wav");
        sndToggle = audio.loadSound("toggle.wav");

        buildUi(white);
        audio.setMusicVolume(musicVolume);
        if (musicOn) {
            audio.playMusic("music.ogg", true);
        }
        showPage(PAGE_MENU);
    }

    private void buildUi(TextureRegion white) {
        ui = new Ui(32, 64, batch, font, white);
        ui.setListener(this);
        ui.setViewport(virtualW, VIRTUAL_HEIGHT, 800, 480);

        labelHud = ui.addLabel("", 6f, 6f, Ui.LEFT, Ui.TOP, 1f); // ALWAYS visible

        // ---- menu page
        int title = ui.addLabel("ENGINE DEMO", 0f, -100f, Ui.CENTER, Ui.MIDDLE, 3f);
        int sub = ui.addLabel("audio * ui * multi-touch", 0f, -66f, Ui.CENTER, Ui.MIDDLE, 1f);
        btnPlay = ui.addButton("PLAY", 0f, -10f, 220f, 48f, Ui.CENTER, Ui.MIDDLE, 2f);
        btnSettings = ui.addButton("SETTINGS", 0f, 50f, 220f, 48f, Ui.CENTER, Ui.MIDDLE, 2f);
        labelStats = ui.addLabel("", 0f, -14f, Ui.CENTER, Ui.BOTTOM, 1f);
        ui.setGroup(title, PAGE_MENU);
        ui.setGroup(sub, PAGE_MENU);
        ui.setGroup(btnPlay, PAGE_MENU);
        ui.setGroup(btnSettings, PAGE_MENU);
        ui.setGroup(labelStats, PAGE_MENU);

        // ---- settings page
        int panel = ui.addPanel(0f, 0f, 400f, 264f, Ui.CENTER, Ui.MIDDLE);
        int sTitle = ui.addLabel("SETTINGS", 0f, -108f, Ui.CENTER, Ui.MIDDLE, 2f);
        int lMusic = ui.addLabel("Music volume", 0f, -66f, Ui.CENTER, Ui.MIDDLE, 1f);
        sliderMusic = ui.addSlider(0f, -42f, 320f, 24f, Ui.CENTER, Ui.MIDDLE, musicVolume);
        int lSfx = ui.addLabel("Effects volume", 0f, -8f, Ui.CENTER, Ui.MIDDLE, 1f);
        sliderSfx = ui.addSlider(0f, 16f, 320f, 24f, Ui.CENTER, Ui.MIDDLE, sfxVolume);
        toggleMusic = ui.addToggle("MUSIC", -85f, 62f, 160f, 36f, Ui.CENTER, Ui.MIDDLE, 1f, musicOn);
        toggleSfx = ui.addToggle("EFFECTS", 85f, 62f, 160f, 36f, Ui.CENTER, Ui.MIDDLE, 1f, sfxOn);
        btnBackSettings = ui.addButton("BACK", 0f, 110f, 160f, 34f, Ui.CENTER, Ui.MIDDLE, 1f);
        int[] settingsWidgets = {panel, sTitle, lMusic, sliderMusic, lSfx, sliderSfx,
            toggleMusic, toggleSfx, btnBackSettings};
        for (int i = 0; i < settingsWidgets.length; i++) {
            ui.setGroup(settingsWidgets[i], PAGE_SETTINGS);
        }
        ui.setBlocking(panel, true);

        // ---- touch lab page
        btnBackPlay = ui.addButton("BACK", -8f, 8f, 110f, 36f, Ui.RIGHT, Ui.TOP, 1f);
        labelPlay = ui.addLabel("", 0f, -14f, Ui.CENTER, Ui.BOTTOM, 1f);
        int hint = ui.addLabel("Touch with SEVERAL fingers\nTap: coin    Pinch: coin size", 0f, -105f, Ui.CENTER, Ui.MIDDLE, 1f);
        ui.setGroup(btnBackPlay, PAGE_PLAY);
        ui.setGroup(labelPlay, PAGE_PLAY);
        ui.setGroup(hint, PAGE_PLAY);
    }

    private void showPage(int p) {
        page = p;
        ui.showGroup(p);
        touch.reset();
        if (p == PAGE_MENU) {
            ui.text(labelStats).clear().append("Coins collected: ").append(coinsTotal)
                    .append("    Rounds played: ").append(rounds);
        } else if (p == PAGE_PLAY) {
            coinsThisRound = 0;
            for (int i = 0; i < MAX_COINS; i++) {
                coinActive[i] = false;
            }
        }
    }

    private void saveSettings() {
        settings.putFloat("musicVolume", musicVolume);
        settings.putFloat("sfxVolume", sfxVolume);
        settings.putBoolean("musicOn", musicOn);
        settings.putBoolean("sfxOn", sfxOn);
        settings.putInt("coins", coinsTotal);
        settings.putInt("rounds", rounds);
        settings.save(engine.getStorage(), SETTINGS_FILE);
    }

    private void sfx(int sound, float pitch) {
        if (sfxOn) {
            audio.playSound(sound, sfxVolume, pitch, false);
        }
    }

    // ---- Ui.Listener --------------------------------------------------------------

    public void onClick(int id) {
        if (id == btnPlay) {
            sfx(sndClick, 1f);
            rounds++;
            showPage(PAGE_PLAY);
        } else if (id == btnSettings) {
            sfx(sndClick, 1f);
            showPage(PAGE_SETTINGS);
        } else if (id == btnBackSettings || id == btnBackPlay) {
            sfx(sndBack, 1f);
            saveSettings();
            showPage(PAGE_MENU);
        } else if (id == toggleMusic) {
            musicOn = ui.isChecked(toggleMusic);
            sfx(sndToggle, musicOn ? 1.2f : 0.8f);
            if (musicOn) {
                audio.playMusic("music.ogg", true);
            } else {
                audio.stopMusic();
            }
        } else if (id == toggleSfx) {
            sfxOn = ui.isChecked(toggleSfx);
            sfx(sndToggle, sfxOn ? 1.2f : 0.8f);
        }
    }

    public void onValueChanged(int id, float value) {
        if (id == sliderMusic) {
            musicVolume = value;
            audio.setMusicVolume(value);
        } else if (id == sliderSfx) {
            sfxVolume = value;
            if (tick - lastTickSound > 8) { // a tick sound while dragging, not on every event
                lastTickSound = tick;
                sfx(sndToggle, 0.8f + value * 0.8f);
            }
        }
    }

    // ---- game loop ------------------------------------------------------------------

    public void update() {
        tick++;
        while (engine.input.poll(event)) {
            if (ui.handle(event)) {
                continue;
            }
            touch.feed(event);
            if (page == PAGE_PLAY && event.type == InputEvent.DOWN) {
                spawnCoin(event.pointer, event.x, event.y);
            }
        }
        if (page == PAGE_PLAY) {
            float pinch = touch.consumePinch(); // pixels: positive = fingers moved apart
            if (pinch != 0f) {
                coinScale += pinch * 0.006f;
                coinScale = coinScale < 0.5f ? 0.5f : (coinScale > 3f ? 3f : coinScale);
            }
            for (int i = 0; i < MAX_COINS; i++) {
                if (!coinActive[i]) {
                    continue;
                }
                coinAge[i]++;
                coinVy[i] += 0.28f;
                coinX[i] += coinVx[i];
                coinY[i] += coinVy[i];
                if (coinY[i] > VIRTUAL_HEIGHT + 40f) {
                    coinActive[i] = false;
                }
            }
        }
    }

    private void spawnCoin(int pointer, float px, float py) {
        if (screenH <= 0) {
            return;
        }
        for (int i = 0; i < MAX_COINS; i++) {
            if (!coinActive[i]) {
                float k = VIRTUAL_HEIGHT / screenH;
                coinActive[i] = true;
                coinX[i] = px * k;
                coinY[i] = py * k;
                coinVx[i] = ((tick * 7 + i * 13) % 11 - 5) * 0.25f;
                coinVy[i] = -5.5f;
                coinSize[i] = 32f * coinScale;
                coinAge[i] = i * 3;
                coinsThisRound++;
                coinsTotal++;
                sfx(sndCoin, 0.85f + 0.12f * (pointer & 3));
                return;
            }
        }
    }

    public void render(Renderer r) {
        int sw = r.getWidth();
        int sh = r.getHeight();
        if (sh > 0 && (sw != screenW || sh != screenH)) {
            screenW = sw;
            screenH = sh;
            virtualW = VIRTUAL_HEIGHT * sw / sh;
            projection.setOrtho(0f, virtualW, VIRTUAL_HEIGHT, 0f, -1f, 1f);
            ui.setViewport(virtualW, VIRTUAL_HEIGHT, sw, sh);
        }

        r.clear(0.05f, 0.06f, 0.09f);
        batch.begin(projection);

        // dim tiled background
        batch.setColor(0.45f, 0.45f, 0.52f, 1f);
        int cols = (int) (virtualW / TILE) + 1;
        int rows = (int) (VIRTUAL_HEIGHT / TILE) + 1;
        for (int ty = 0; ty < rows; ty++) {
            for (int tx = 0; tx < cols; tx++) {
                batch.draw(tile, tx * TILE, ty * TILE);
            }
        }
        batch.setColor(1f, 1f, 1f, 1f);

        if (page == PAGE_PLAY) {
            drawTouchLab();
        } else {
            // a few drifting coins to make the menu feel alive
            for (int i = 0; i < 7; i++) {
                float cx = ((tick * (0.5f + 0.1f * i) + i * 90f) % (virtualW + 64f)) - 32f;
                float cy = 60f + i * 30f + 8f * FastMath.sin(tick * 3f + i * 50f);
                batch.draw(coinAnim.getFrame(tick + i * 5), cx, cy);
            }
        }

        ui.text(labelHud).clear().append("FPS ").append(engine.stats.fps)
                .append("  TPS ").append(engine.stats.tps);
        if (page == PAGE_PLAY) {
            ui.text(labelPlay).clear().append("Fingers ").append(touch.getDownCount())
                    .append("   Coins ").append(coinsThisRound)
                    .append("   Coin size x").append(coinScale, 1);
        }
        ui.draw();
        batch.end();
    }

    private void drawTouchLab() {
        for (int i = 0; i < MAX_COINS; i++) {
            if (coinActive[i]) {
                float s = coinSize[i];
                batch.draw(coinAnim.getFrame(coinAge[i]), coinX[i] - s * 0.5f, coinY[i] - s * 0.5f, s, s);
            }
        }
        // a glowing cursor under every finger, colored by pointer id
        float k = screenH > 0 ? VIRTUAL_HEIGHT / screenH : 1f;
        for (int n = 0; n < touch.getDownCount(); n++) {
            int id = touch.getPointerId(n);
            float hue = id * 0.9f;
            batch.setColor(0.6f + 0.4f * FastMath.sin(hue * 57f),
                    0.6f + 0.4f * FastMath.sin(hue * 57f + 120f),
                    0.6f + 0.4f * FastMath.sin(hue * 57f + 240f), 0.9f);
            batch.draw(glow, touch.getX(id) * k - 40f, touch.getY(id) * k - 40f, 80f, 80f);
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    public void pause() {
        saveSettings(); // the process may be killed while we are in the background
    }
}
