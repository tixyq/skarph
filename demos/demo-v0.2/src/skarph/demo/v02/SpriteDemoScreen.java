// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.demo.v02;

import skarph.core.BitmapFont;
import skarph.core.Engine;
import skarph.core.FrameAnimation;
import skarph.core.InputEvent;
import skarph.core.Mat4;
import skarph.core.Pool;
import skarph.core.Renderer;
import skarph.core.ScreenAdapter;
import skarph.core.SpriteBatch;
import skarph.core.TextBuffer;
import skarph.core.TextureRegion;

/**
 * Tech demo v0.2: 2D scene.
 * - orthographic camera with a fixed virtual height of 320 units
 * - tiled background, sprite sheet, frame animations driven by the tick counter
 * - walkers with fake (blob) shadows, spinning coins
 * - touch/drag spawns fading stars from an object pool
 * - bitmap font with FPS/TPS counters (built with TextBuffer, no String concatenation)
 */
public final class SpriteDemoScreen extends ScreenAdapter {
    private static final float VIRTUAL_HEIGHT = 320f;
    private static final int TILE = 32;
    private static final int WALKERS = 6;
    private static final int COINS = 8;
    private static final int MAX_PARTICLES = 32;
    private static final int PARTICLE_LIFE = 60;
    private static final int TICK_WRAP = 960000; // multiple of every animation period

    private static final class Particle implements Pool.Poolable {
        float x;
        float y;
        int age;

        public void reset() {
            x = 0f;
            y = 0f;
            age = 0;
        }
    }

    private Engine engine;
    private SpriteBatch batch;
    private BitmapFont font;
    private FrameAnimation walkAnim;
    private FrameAnimation coinAnim;
    private TextureRegion tileA;
    private TextureRegion tileB;
    private TextureRegion star;
    private TextureRegion shadow;

    private final Mat4 projection = new Mat4();
    private final InputEvent event = new InputEvent();
    private final TextBuffer text = new TextBuffer(96);
    private final TextBuffer hint = new TextBuffer(48);

    private Pool<Particle> particlePool;
    private final Particle[] live = new Particle[MAX_PARTICLES];
    private int liveCount;

    private final float[] walkerX = new float[WALKERS];
    private final float[] walkerY = new float[WALKERS];
    private final float[] walkerVx = new float[WALKERS];
    private final int[] walkerOffset = new int[WALKERS];
    private final float[] coinX = new float[COINS];
    private final float[] coinY = new float[COINS];

    private int tick;
    private int screenW;
    private int screenH;
    private float virtualW = VIRTUAL_HEIGHT * 1.5f;
    private boolean dragging;

    public void init(Engine engine, Renderer renderer) {
        this.engine = engine;

        int atlasTex = renderer.loadTexture("atlas.png", false);
        int fontTex = renderer.loadTexture("font.png", false);
        if (atlasTex < 0 || fontTex < 0) {
            throw new IllegalStateException("texture loading failed (see logcat, tag engine)");
        }

        // atlas.png: 4x4 cells of 32x32. row 0 walker, row 1 coin, row 2 tiles/star/shadow
        TextureRegion[] a = TextureRegion.grid(atlasTex,
                renderer.getTextureWidth(atlasTex), renderer.getTextureHeight(atlasTex),
                32, 32, 4, 4);
        walkAnim = new FrameAnimation(
                new TextureRegion[] {a[0], a[1], a[2], a[3]}, 8, true);
        coinAnim = new FrameAnimation(
                new TextureRegion[] {a[4], a[5], a[6], a[7]}, 6, true);
        tileA = a[8];
        tileB = a[9];
        star = a[10];
        shadow = a[11];

        // font.png: 16x6 cells of 8x16, ASCII from 32
        TextureRegion[] g = TextureRegion.grid(fontTex,
                renderer.getTextureWidth(fontTex), renderer.getTextureHeight(fontTex),
                8, 16, 16, 6);
        font = new BitmapFont(g, 32);

        batch = new SpriteBatch(renderer, 1024);

        particlePool = new Pool<Particle>(new Pool.Factory<Particle>() {
            public Particle create() {
                return new Particle();
            }
        }, MAX_PARTICLES);

        for (int i = 0; i < WALKERS; i++) {
            walkerX[i] = 40f + i * 70f;
            walkerY[i] = 95f + i * 38f;
            float speed = 0.6f + 0.35f * (i % 3);
            walkerVx[i] = (i % 2 == 0) ? speed : -speed;
            walkerOffset[i] = i * 5;
        }
        for (int i = 0; i < COINS; i++) {
            coinX[i] = 40f + i * 52f;
            coinY[i] = 40f + ((i & 1) == 0 ? 0f : 14f);
        }
        hint.append("Tap or drag to spawn stars");
    }

    public void update() {
        if (++tick >= TICK_WRAP) {
            tick = 0;
        }

        // input (screen pixels -> virtual units)
        while (engine.input.poll(event)) {
            switch (event.type) {
                case InputEvent.DOWN:
                    dragging = true;
                    spawn(event.x, event.y);
                    break;
                case InputEvent.MOVE:
                    if (dragging && (tick & 1) == 0) {
                        spawn(event.x, event.y);
                    }
                    break;
                case InputEvent.UP:
                    dragging = false;
                    break;
                default:
                    break;
            }
        }

        // walkers bounce between the screen edges
        for (int i = 0; i < WALKERS; i++) {
            walkerX[i] += walkerVx[i];
            if (walkerX[i] < 16f) {
                walkerX[i] = 16f;
                walkerVx[i] = -walkerVx[i];
            } else if (walkerX[i] > virtualW - 16f) {
                walkerX[i] = virtualW - 16f;
                walkerVx[i] = -walkerVx[i];
            }
        }

        // particles: move, expire, return to the pool
        for (int i = liveCount - 1; i >= 0; i--) {
            Particle p = live[i];
            p.age++;
            p.y -= 0.7f;
            if (p.age >= PARTICLE_LIFE) {
                particlePool.free(p);
                live[i] = live[--liveCount];
                live[liveCount] = null;
            }
        }
    }

    private void spawn(float px, float py) {
        if (screenH <= 0) {
            return;
        }
        Particle p = particlePool.obtain();
        if (p == null) {
            return; // pool exhausted: skip, never allocate
        }
        float k = VIRTUAL_HEIGHT / screenH;
        p.x = px * k;
        p.y = py * k;
        p.age = 0;
        live[liveCount++] = p;
    }

    public void render(Renderer r) {
        int sw = r.getWidth();
        int sh = r.getHeight();
        if (sh > 0 && (sw != screenW || sh != screenH)) {
            screenW = sw;
            screenH = sh;
            virtualW = VIRTUAL_HEIGHT * sw / sh;
            projection.setOrtho(0f, virtualW, VIRTUAL_HEIGHT, 0f, -1f, 1f);
        }

        r.clear(0.05f, 0.05f, 0.08f);
        batch.begin(projection);

        // background tiles
        batch.setColor(1f, 1f, 1f, 1f);
        int cols = (int) (virtualW / TILE) + 1;
        int rows = (int) (VIRTUAL_HEIGHT / TILE);
        for (int ty = 0; ty < rows; ty++) {
            for (int tx = 0; tx < cols; tx++) {
                batch.draw(((tx + ty) & 1) == 0 ? tileA : tileB, tx * TILE, ty * TILE);
            }
        }

        // fake shadows under walkers
        for (int i = 0; i < WALKERS; i++) {
            batch.draw(shadow, walkerX[i] - 16f, walkerY[i] - 16f, 32f, 32f);
        }

        // coins (each with its own phase)
        for (int i = 0; i < COINS; i++) {
            batch.draw(coinAnim.getFrame(tick + i * 4), coinX[i] - 16f, coinY[i] - 16f);
        }

        // walkers, flipped when walking left
        for (int i = 0; i < WALKERS; i++) {
            TextureRegion f = walkAnim.getFrame(tick + walkerOffset[i]);
            if (walkerVx[i] < 0f) {
                batch.draw(f, walkerX[i] + 16f, walkerY[i] - 30f, -32f, 32f);
            } else {
                batch.draw(f, walkerX[i] - 16f, walkerY[i] - 30f, 32f, 32f);
            }
        }

        // particles: tinted and faded by age
        for (int i = 0; i < liveCount; i++) {
            Particle p = live[i];
            float alpha = 1f - (float) p.age / PARTICLE_LIFE;
            batch.setColor(1f, 0.9f, 0.4f, alpha);
            batch.drawRotated(star, p.x, p.y, 28f, 28f, p.age * 4f);
        }

        // HUD text with a drop shadow
        text.clear().append("FPS ").append(engine.stats.fps)
                .append("  TPS ").append(engine.stats.tps).append('\n')
                .append("Draw calls ").append(batch.getDrawCalls()).append('\n')
                .append("Stars ").append(liveCount);
        batch.setColor(0f, 0f, 0f, 0.8f);
        font.draw(batch, text, 7f, 7f);
        font.draw(batch, hint, 7f, VIRTUAL_HEIGHT - 23f);
        batch.setColor(1f, 1f, 0.3f, 1f);
        font.draw(batch, text, 6f, 6f);
        font.draw(batch, hint, 6f, VIRTUAL_HEIGHT - 24f);

        batch.end();
    }
}
