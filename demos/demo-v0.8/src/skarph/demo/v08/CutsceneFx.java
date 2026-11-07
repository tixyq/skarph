// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.demo.v08;

import skarph.core.BillboardBatch;
import skarph.core.Camera;
import skarph.core.FastMath;
import skarph.core.FrameAnimation;
import skarph.core.ParticleSystem;
import skarph.core.ParticleType;
import skarph.core.Renderer;
import skarph.core.TextureRegion;

/**
 * The effects of the town cutscene, driven by the scene number and the scene tick of the Timeline:
 * chimney smoke over the houses, flames on the lamps, glowing orbs circling the obelisk with fake blob shadows,
 * gate dust (GATE), gold sparks at the obelisk (PLAZA), torch sparks and smoke on the tower (TOWER), fireworks (FINALE)
 * and a short white flash on every hard cut. Everything is in ticks (frame animation for the flames, positions by tick
 * number), no allocation after the constructor. Plain Java, no android.*.
 */
final class CutsceneFx {
    static final int SCENE_FLYOVER = 0;
    static final int SCENE_GATE = 1;
    static final int SCENE_PLAZA = 2;
    static final int SCENE_TOWER = 3;
    static final int SCENE_FINALE = 4;

    static final int POOL = 320;
    static final int FLASH_TICKS = 12;
    private static final int ORBS = 4;
    private static final int LAMPS = 8;
    private static final int HOUSES = 8;
    private static final float PLATFORM_TOP = 1.2f;

    private final ParticleSystem ps = new ParticleSystem(POOL);
    private final BillboardBatch bb;
    private final TextureRegion blob;
    private final TextureRegion glow;
    private final FrameAnimation flame;
    private final ParticleType smoke;
    private final ParticleType dust;
    private final ParticleType torchSmoke;
    private final ParticleType gold;
    private final ParticleType torchSpark;
    private final ParticleType[] firework = new ParticleType[3];

    private final float[] houseX = new float[HOUSES];
    private final float[] houseZ = new float[HOUSES];
    private final float[] lampX = new float[LAMPS];
    private final float[] lampZ = new float[LAMPS];
    private static final float[] FW_X = {-14f, 12f, 2f, -6f, 18f, -18f};
    private static final float[] FW_Y = {22f, 24f, 26f, 20f, 23f, 25f};
    private static final float[] FW_Z = {8f, -10f, 18f, -16f, 6f, -2f};

    private int tick;
    private float flash;
    /** Quality: 1 = full effects (HIGH), 2 = half (MEDIUM), 4 = quarter (LOW). */
    private int thin = 1;
    private int flamesDrawn = LAMPS;

    /** @param tex the effects atlas (fx.png), texW x texH pixels */
    CutsceneFx(Renderer r, int tex, int texW, int texH) {
        bb = new BillboardBatch(r, POOL + 32);
        TextureRegion puff = new TextureRegion().set(tex, texW, texH, 0, 0, 32, 32);
        TextureRegion spark = new TextureRegion().set(tex, texW, texH, 32, 0, 32, 32);
        glow = new TextureRegion().set(tex, texW, texH, 64, 0, 32, 32);
        blob = new TextureRegion().set(tex, texW, texH, 96, 0, 32, 32);
        TextureRegion[] frames = new TextureRegion[4];
        for (int i = 0; i < 4; i++) {
            frames[i] = new TextureRegion().set(tex, texW, texH, i * 32, 32, 32, 32);
        }
        flame = new FrameAnimation(frames, 4, true);

        smoke = new ParticleType(puff);
        smoke.drag = 0.985f;
        smoke.sizeStart = 0.5f;
        smoke.sizeEnd = 2.8f;
        smoke.alphaStart = 0.5f;
        smoke.alphaEnd = 0f;
        smoke.r = 0.85f;
        smoke.g = 0.85f;
        smoke.b = 0.88f;

        dust = new ParticleType(puff);
        dust.gravity = 0.0004f;
        dust.drag = 0.96f;
        dust.sizeStart = 0.8f;
        dust.sizeEnd = 2.4f;
        dust.alphaStart = 0.5f;
        dust.alphaEnd = 0f;
        dust.r = 0.75f;
        dust.g = 0.65f;
        dust.b = 0.5f;

        torchSmoke = new ParticleType(puff);
        torchSmoke.drag = 0.98f;
        torchSmoke.sizeStart = 0.6f;
        torchSmoke.sizeEnd = 3.2f;
        torchSmoke.alphaStart = 0.45f;
        torchSmoke.alphaEnd = 0f;
        torchSmoke.r = 0.35f;
        torchSmoke.g = 0.33f;
        torchSmoke.b = 0.33f;

        gold = new ParticleType(spark);
        gold.additive = true;
        gold.gravity = -0.0035f;
        gold.drag = 0.98f;
        gold.sizeStart = 0.45f;
        gold.sizeEnd = 0.1f;
        gold.r = 1f;
        gold.g = 0.75f;
        gold.b = 0.3f;

        torchSpark = new ParticleType(spark);
        torchSpark.additive = true;
        torchSpark.gravity = -0.003f;
        torchSpark.drag = 0.97f;
        torchSpark.sizeStart = 0.35f;
        torchSpark.sizeEnd = 0.08f;
        torchSpark.r = 1f;
        torchSpark.g = 0.5f;
        torchSpark.b = 0.15f;

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
        for (int k = 0; k < HOUSES; k++) {                       // houses of the map: a ring of radius 20
            float a = 22.5f + 45f * k;
            houseX[k] = 20f * FastMath.cos(a);
            houseZ[k] = 20f * FastMath.sin(a);
        }
        for (int k = 0; k < LAMPS; k++) {                        // lamps: a ring of radius 11 around the platform
            float a = 22.5f + 45f * k;
            lampX[k] = 11f * FastMath.cos(a);
            lampZ[k] = 11f * FastMath.sin(a);
        }
        restart();
    }

    /** Back to the start of the cutscene: no particles, the same random numbers again. */
    void restart() {
        ps.clear();
        ps.setSeed(7);
        tick = 0;
        flash = 0f;
    }

    /** Quality level 0 (LOW) .. 2 (HIGH): thins the emitters (rates and burst sizes) and the number of drawn flames. */
    void setQuality(int level) {
        thin = level >= 2 ? 1 : (level == 1 ? 2 : 4);
        flamesDrawn = level >= 2 ? LAMPS : (level == 1 ? 6 : 4);
    }

    private int cnt(int n) {
        int c = n / thin;
        return c < 1 ? 1 : c;
    }

    void clear() {
        ps.clear();
        flash = 0f;
    }

    /**
     * Once per logic tick.
     * @param scene     timeline scene number, or -1 (free orbit: only the ambient effects run)
     * @param sceneTick ticks since the start of that scene
     */
    void update(int scene, int sceneTick) {
        tick++;
        if (tick % (6 * thin) == 0) {                            // chimney smoke: one house every 6 ticks
            int h = (tick / 6) & (HOUSES - 1);
            ps.emit(smoke, houseX[h] + 1.2f, 6.6f, houseZ[h], 0.004f, 0.022f, 0.002f, 130);
        }
        if (tick % (20 * thin) == 0) {                           // a spark from a lamp flame
            int l = (tick / 20) & (LAMPS - 1);
            ps.burst(torchSpark, lampX[l], 4.2f, lampZ[l], 1, 0.03f, 30, 50);
        }
        switch (scene) {
            case SCENE_GATE:
                if (sceneTick % 40 == 0) {
                    ps.burst(dust, -3.8f, 0.4f, 26.5f, cnt(6), 0.08f, 40, 70);
                    ps.burst(dust, 3.8f, 0.4f, 26.5f, cnt(6), 0.08f, 40, 70);
                }
                break;
            case SCENE_PLAZA:
                if (sceneTick % 5 == 0) {
                    ps.burst(gold, 0f, 9.2f, 0f, cnt(2), 0.08f, 40, 70);
                }
                break;
            case SCENE_TOWER:
                if (sceneTick % 4 == 0) {
                    ps.burst(torchSpark, -26f, 16.9f, -26f, cnt(2), 0.06f, 30, 60);
                }
                if (sceneTick % (8 * thin) == 0) {
                    ps.emit(torchSmoke, -26f, 17.2f, -26f, 0.006f, 0.03f, 0.004f, 110);
                }
                break;
            case SCENE_FINALE:
                if (sceneTick % 40 == 10) {
                    int k = sceneTick / 40;
                    int n = k % FW_X.length;
                    ps.burst(firework[k % 3], FW_X[n], FW_Y[n], FW_Z[n], cnt(24), 0.3f, 45, 75);
                }
                break;
            default:
                break;
        }
        boolean cut = scene == SCENE_FLYOVER || scene == SCENE_GATE || scene == SCENE_TOWER || scene == SCENE_FINALE;
        flash = cut && sceneTick < FLASH_TICKS ? 0.55f * (FLASH_TICKS - sceneTick) / FLASH_TICKS : 0f;
        ps.update();
    }

    /** White flash strength 0..0.55 of the current tick (on a hard cut it fades out in FLASH_TICKS ticks). */
    float getFlash() {
        return flash;
    }

    int getParticleCount() {
        return ps.getActiveCount();
    }

    int getTick() {
        return tick;
    }

    private float orbX(int k) {
        return 4.6f * FastMath.cos(tick * 1.5f + 90f * k);
    }

    private float orbZ(int k) {
        return 4.6f * FastMath.sin(tick * 1.5f + 90f * k);
    }

    private float orbY(int k) {
        return 2.7f + 0.5f * FastMath.sin(tick * 4f + 90f * k);
    }

    /** After the meshes, BEFORE SpriteBatch.begin(): fake shadows, particles, flames and orbs in two or three draw calls. */
    void draw(Camera cam) {
        bb.begin();
        bb.setAdditive(false);
        for (int k = 0; k < ORBS; k++) {                         // fake blob shadows: flat, dark, smaller and paler when high
            float h = orbY(k) - PLATFORM_TOP;
            bb.setColor(0f, 0f, 0f, 0.45f - 0.08f * h);
            bb.drawFlat(blob, orbX(k), PLATFORM_TOP + 0.07f, orbZ(k), 1.7f - 0.25f * h, 1.7f - 0.25f * h);
        }
        ps.draw(bb, cam);                                        // smoke, dust (alpha), then sparks (additive)
        bb.setAdditive(true);
        bb.setColor(1f, 0.6f, 0.25f, 1f);
        for (int k = 0; k < flamesDrawn; k++) {
            bb.draw(flame.getFrame(tick + k * 3), lampX[k], 4.3f, lampZ[k], 0.9f, 1.1f);
        }
        bb.setColor(0.35f, 0.8f, 1f, 1f);
        for (int k = 0; k < ORBS; k++) {
            bb.draw(glow, orbX(k), orbY(k), orbZ(k), 1.2f, 1.2f);
        }
        bb.end();
    }
}
