// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.demo.v10;

import skarph.core.BillboardBatch;
import skarph.core.Camera;
import skarph.core.FastMath;
import skarph.core.ParticleSystem;
import skarph.core.ParticleType;
import skarph.core.Renderer;
import skarph.core.TextureRegion;

/**
 * The effects of the night port, driven by the scene number and the scene tick of the Timeline:
 * falling snow around the camera, steam from the ship funnel, sea mist over the fjord, warm halos on the 20 street lamps,
 * the turning beam of the lighthouse, welding sparks at the cranes (QUAY), three navigation lights circling the flagpole
 * with fake moon blob shadows on the platform, fireworks over the port (FINALE) and a cold flash on every hard cut.
 * Everything is in ticks (halo flicker and beam angle by tick number, no interpolation), no allocation after the
 * constructor. The sounds that belong to the effects (horn, bell, crackle, launch, boom, cut) are started from here
 * through {@link PortSound}. Plain Java, no android.*.
 *
 * The effects atlas is the one of v0.8: puff, spark, glow, blob.
 */
final class PortFx {
    static final int SCENE_FLYOVER = 0;
    static final int SCENE_QUAY = 1;
    static final int SCENE_PIER = 2;
    static final int SCENE_SHIP = 3;
    static final int SCENE_FINALE = 4;

    static final int POOL = 200;
    static final int SNOW_POOL = 240;
    static final int FLASH_TICKS = 12;
    /** Ticks for one turn of the lighthouse beam (the bell rings once per half turn). */
    static final int BEAM_PERIOD = 300;
    private static final int BEAM_STEPS = 6;
    private static final int ORBS = 3;
    private static final float LANTERN_X = -41.5f;
    private static final float LANTERN_Y = 10.05f;
    private static final float PLATFORM_TOP = 1.2f;
    /** Where the moon's light throws a shadow per unit of height (light direction of map.lvl, flattened). */
    private static final float SHADOW_DX = -0.6f;
    private static final float SHADOW_DZ = 0.64f;

    /** The 20 street lamps of the port map (x, z); the lamp heads are at y = 3.85. */
    private static final float[] LAMP_X = {
        -23.8f, -23.8f, -23.8f, -23.8f, -23.8f, -23.8f, 12f, 12f, 20f, 20f,
        24f, 24f, 11f, -11f, 0f, 0f, -29f, -35f, -3f, -3f};
    private static final float[] LAMP_Z = {
        -19f, -15f, -7f, 7f, 15f, 19f, -8.2f, 8.2f, -8.2f, 8.2f,
        -5f, 5f, 0f, 0f, 11f, -11f, -2.45f, -2.45f, 15.5f, -15.5f};
    private static final int LAMPS = 20;
    private static final float[] FLICKER = {1f, 0.92f, 1f, 0.84f, 0.95f, 1f, 0.88f, 0.97f};
    private static final float[] FW_X = {-10f, 10f, 0f, -18f, 17f, -5f};
    private static final float[] FW_Y = {13f, 16f, 11f, 15f, 12f, 17f};
    private static final float[] FW_Z = {-12f, 10f, 16f, 6f, -9f, -18f};
    /** Welding spots on the legs of the two cranes. */
    private static final float[] WELD_Z = {-9.6f, 12.4f};

    private final ParticleSystem ps = new ParticleSystem(POOL);
    private final ParticleSystem snow = new ParticleSystem(SNOW_POOL);
    private final BillboardBatch bb;
    private final PortSound snd;
    private final TextureRegion blob;
    private final TextureRegion glow;
    private final ParticleType smoke;
    private final ParticleType mist;
    private final ParticleType flake;
    private final ParticleType weld;
    private final ParticleType[] firework = new ParticleType[3];

    private int tick;
    private int seed = 12345;
    private float flash;
    private boolean weldOn;
    private int weldSpot;
    /** Quality: 2 = full effects (HIGH), 1 = medium, 0 = low. */
    private int level = 2;
    private int thin = 1;
    private int lampsDrawn = LAMPS;

    /**
     * @param tex the effects atlas (fx.png, the v0.8 one), texW x texH pixels
     * @param snd sound output of the demo (a PortSound on a NullAudio is silent)
     */
    PortFx(Renderer r, int tex, int texW, int texH, PortSound snd) {
        this.snd = snd;
        bb = new BillboardBatch(r, 512);                         // the limit: POOL + SNOW_POOL + at most 50 glows and shadows
        TextureRegion puff = new TextureRegion().set(tex, texW, texH, 0, 0, 32, 32);
        TextureRegion spark = new TextureRegion().set(tex, texW, texH, 32, 0, 32, 32);
        glow = new TextureRegion().set(tex, texW, texH, 64, 0, 32, 32);
        blob = new TextureRegion().set(tex, texW, texH, 96, 0, 32, 32);

        smoke = new ParticleType(puff);                          // funnel steam, pale blue grey in the moon light
        smoke.drag = 0.99f;
        smoke.sizeStart = 0.6f;
        smoke.sizeEnd = 3.4f;
        smoke.alphaStart = 0.45f;
        smoke.alphaEnd = 0f;
        smoke.r = 0.5f;
        smoke.g = 0.55f;
        smoke.b = 0.66f;

        mist = new ParticleType(puff);                           // low sea mist
        mist.drag = 1f;
        mist.sizeStart = 3f;
        mist.sizeEnd = 7f;
        mist.alphaStart = 0.2f;
        mist.alphaEnd = 0f;
        mist.r = 0.45f;
        mist.g = 0.58f;
        mist.b = 0.78f;

        flake = new ParticleType(spark);                         // snow: small white dots, alpha blended
        flake.sizeStart = 0.12f;
        flake.sizeEnd = 0.12f;
        flake.alphaStart = 0.85f;
        flake.alphaEnd = 0.85f;
        flake.r = 0.9f;
        flake.g = 0.95f;
        flake.b = 1f;

        weld = new ParticleType(spark);
        weld.additive = true;
        weld.gravity = -0.004f;
        weld.drag = 0.97f;
        weld.sizeStart = 0.3f;
        weld.sizeEnd = 0.06f;
        weld.r = 1f;
        weld.g = 0.55f;
        weld.b = 0.18f;

        float[][] colors = {{1f, 0.25f, 0.2f}, {0.3f, 1f, 0.4f}, {0.35f, 0.55f, 1f}};
        for (int i = 0; i < 3; i++) {
            ParticleType t = new ParticleType(spark);
            t.additive = true;
            t.gravity = -0.0045f;
            t.drag = 0.955f;
            t.sizeStart = 0.9f;
            t.sizeEnd = 0.15f;
            t.r = colors[i][0];
            t.g = colors[i][1];
            t.b = colors[i][2];
            firework[i] = t;
        }
        restart();
    }

    /** Back to the start of the cutscene: no particles, the same random numbers again. */
    void restart() {
        clear();
        ps.setSeed(7);
        snow.setSeed(9);
        seed = 12345;
        tick = 0;
    }

    void clear() {
        ps.clear();
        snow.clear();
        flash = 0f;
        weldOn = false;
    }

    /** Quality level 0 (LOW) .. 2 (HIGH): thins snow, steam and sparks and the number of lamp halos. */
    void setQuality(int q) {
        level = q < 0 ? 0 : (q > 2 ? 2 : q);
        thin = level >= 2 ? 1 : (level == 1 ? 2 : 4);
        lampsDrawn = level >= 2 ? LAMPS : (level == 1 ? 14 : 8);
    }

    private float rnd() {
        seed = seed * 1103515245 + 12345;                        // own generator: positions by tick, no allocation
        return ((seed >>> 8) & 0xFFFF) / 65536f;
    }

    private int cnt(int n) {
        int c = n / thin;
        return c < 1 ? 1 : c;
    }

    private void snowFlake(float ex, float ey, float ez, float spread, float yLo, float yHi) {
        snow.emit(flake, ex + (rnd() * 2f - 1f) * spread, ey + yLo + rnd() * (yHi - yLo), ez + (rnd() * 2f - 1f) * spread,
                0.012f + 0.006f * rnd(), -0.04f - 0.015f * rnd(), 0.004f, 210);
    }

    /**
     * Once per logic tick (after the camera of this tick is set).
     * @param scene     timeline scene number, or -1 (free orbit: only the ambient effects run)
     * @param sceneTick ticks since the start of that scene
     * @param ex ey ez  the eye of the camera (the snow falls around it)
     */
    void update(int scene, int sceneTick, float ex, float ey, float ez) {
        tick++;
        // ---- ambient: snow around the camera, funnel steam, sea mist
        if (scene >= 0 && sceneTick == 0) {                      // a hard cut: the old snow is around the old eye
            int n = 30 / thin;
            for (int i = 0; i < n; i++) {
                snowFlake(ex, ey, ez, 18f, -6f, 9f);
            }
        }
        if (tick % thin == 0) {
            snowFlake(ex, ey, ez, 18f, 4f, 10f);
        }
        if (tick % (5 * thin) == 0) {
            ps.emit(smoke, -29.3f + 0.3f * (rnd() - 0.5f), 7.4f, 6.5f, 0.008f, 0.02f, -0.004f, 150);
        }
        if (tick % (10 * thin) == 0) {
            ps.emit(mist, -56f + 26f * rnd(), -0.4f + 0.5f * rnd(), -14f + 28f * rnd(), 0.006f, 0f, 0.002f, 200);
        }
        // ---- scene effects
        weldOn = false;
        switch (scene) {
            case SCENE_FLYOVER:
                if (sceneTick == 200) {
                    snd.play(PortSound.HORN, 0.35f, 0.8f);       // far away: a quiet horn
                }
                break;
            case SCENE_QUAY:
                weldOn = sceneTick % 100 < 40;
                weldSpot = (sceneTick / 100) & 1;               // the two cranes weld in turn
                if (sceneTick % 100 == 0) {
                    snd.play(PortSound.CRACKLE, 0.5f, 1f);
                }
                if (weldOn && sceneTick % 4 == 0) {
                    ps.burst(weld, -18f, 1.0f, WELD_Z[weldSpot], cnt(3), 0.07f, 20, 40);
                }
                break;
            case SCENE_SHIP:
                if (sceneTick == 30) {
                    snd.play(PortSound.HORN, 0.9f, 0.85f);
                }
                break;
            case SCENE_FINALE:
                if (sceneTick % 60 == 5) {
                    snd.play(PortSound.LAUNCH, 0.5f, 1f);
                }
                if (sceneTick % 60 == 40) {
                    int k = sceneTick / 60;
                    int n = k % FW_X.length;
                    ps.burst(firework[k % 3], FW_X[n], FW_Y[n], FW_Z[n], cnt(24), 0.3f, 45, 75);
                    snd.playDelayed(PortSound.BOOM, 0.7f, 0.9f + 0.1f * (k % 3), 12);
                }
                break;
            default:
                break;
        }
        if (tick % (BEAM_PERIOD / 2) == 0) {
            snd.play(PortSound.BELL, 0.45f, 1f);                 // every half turn of the beam
        }
        boolean cut = scene >= 0 && scene != SCENE_PIER;         // PIER follows QUAY without a cut
        if (cut && sceneTick == 0) {
            snd.play(PortSound.CUT, 0.5f, 1f);
        }
        flash = cut && sceneTick < FLASH_TICKS ? 0.45f * (FLASH_TICKS - sceneTick) / FLASH_TICKS : 0f;
        ps.update();
        snow.update();
    }

    /** Cold white flash strength 0..0.45 of the current tick (on a hard cut it fades out in FLASH_TICKS ticks). */
    float getFlash() {
        return flash;
    }

    int getParticleCount() {
        return ps.getActiveCount() + snow.getActiveCount();
    }

    int getSnowCount() {
        return snow.getActiveCount();
    }

    int getTick() {
        return tick;
    }

    private float orbX(int k) {
        return 4.4f * FastMath.cos(tick * 1.2f + 120f * k);
    }

    private float orbZ(int k) {
        return 4.4f * FastMath.sin(tick * 1.2f + 120f * k);
    }

    private float orbY(int k) {
        return 3.6f + 0.9f * FastMath.sin(tick * 3f + 120f * k);
    }

    /** After the meshes, BEFORE SpriteBatch.begin(): shadows, snow, particles, halos, beam and lights in a few draw calls. */
    void draw(Camera cam) {
        bb.begin();
        bb.setAdditive(false);
        for (int k = 0; k < ORBS; k++) {                         // fake moon blob shadows: dark, smaller and paler when high
            float h = orbY(k) - PLATFORM_TOP;
            bb.setColor(0f, 0f, 0f, 0.42f - 0.06f * h);
            bb.drawFlat(blob, orbX(k) + SHADOW_DX * h, PLATFORM_TOP + 0.07f, orbZ(k) + SHADOW_DZ * h,
                    1.7f - 0.2f * h, 1.7f - 0.2f * h);
        }
        snow.draw(bb, cam);                                      // alpha
        ps.draw(bb, cam);                                        // steam, mist (alpha), then sparks (additive)
        bb.setAdditive(true);
        for (int k = 0; k < lampsDrawn; k++) {                   // lamp halos, flicker by tick number
            float f = 0.55f * FLICKER[((tick / 6) + k * 3) & 7];
            bb.setColor(f, f * 0.62f, f * 0.3f, 1f);
            bb.draw(glow, LAMP_X[k], 3.85f, LAMP_Z[k], 2.6f, 2.6f);
        }
        float lf = FLICKER[(tick / 5) & 7];
        bb.setColor(lf, lf * 0.9f, lf * 0.6f, 1f);
        bb.draw(glow, LANTERN_X, LANTERN_Y, 0f, 3.4f, 3.4f);     // the lantern
        float a = tick * (360f / BEAM_PERIOD);
        int steps = level >= 2 ? BEAM_STEPS : BEAM_STEPS / 2;
        for (int side = 0; side < 2; side++) {                   // two opposite beams of glow spots, growing with distance
            float ca = FastMath.cos(a + 180f * side);
            float sa = FastMath.sin(a + 180f * side);
            for (int k = 0; k < steps; k++) {
                float d = 3f + k * 3.2f * (BEAM_STEPS / (float) steps);
                float s = 1.5f + 0.32f * d;
                float f = 0.5f * (1f - k / (float) (steps + 1));
                bb.setColor(f, f * 0.92f, f * 0.65f, 1f);
                bb.draw(glow, LANTERN_X + d * ca, LANTERN_Y, d * sa, s, s);
            }
        }
        if (weldOn) {
            bb.setColor(1f, 0.6f, 0.25f, 1f);
            bb.draw(glow, -18f, 1.0f, WELD_Z[weldSpot], 1.2f, 1.2f);
        }
        for (int k = 0; k < ORBS; k++) {                         // navigation lights: red, green, white
            if (k == 0) {
                bb.setColor(1f, 0.2f, 0.15f, 1f);
            } else if (k == 1) {
                bb.setColor(0.2f, 1f, 0.3f, 1f);
            } else {
                bb.setColor(0.9f, 0.95f, 1f, 1f);
            }
            bb.draw(glow, orbX(k), orbY(k), orbZ(k), 1.2f, 1.2f);
        }
        bb.end();
    }
}
