// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Platform independent engine core. The platform layer calls frame() once per
 * rendered frame, always from the same (render) thread.
 */
public final class Engine {
    public final InputQueue input = new InputQueue(256);
    public final Stats stats = new Stats();

    private final TickClock clock = new TickClock();
    private Assets assets;
    private Audio audio = new NullAudio();
    private Storage storage = new MemoryStorage();
    private final Object lock = new Object();
    private Screen screen;
    private Screen pending;

    /** Set by the platform layer. */
    public void setAssets(Assets assets) {
        this.assets = assets;
    }

    /** Set by the platform layer. */
    public void setAudio(Audio audio) {
        this.audio = audio != null ? audio : new NullAudio();
    }

    /** Sound output. Never null (a NullAudio that does nothing is the default). */
    public Audio getAudio() {
        return audio;
    }

    /** Set by the platform layer. */
    public void setStorage(Storage storage) {
        this.storage = storage != null ? storage : new MemoryStorage();
    }

    /** Persistent storage for settings and saved games. Never null (in-memory by default). */
    public Storage getStorage() {
        return storage;
    }

    /** Access to the application's data files (textures, models, levels). */
    public Assets getAssets() {
        return assets;
    }

    /** Switch screen. Applied at the start of the next frame. Thread-safe. */
    public void setScreen(Screen s) {
        synchronized (lock) {
            pending = s;
        }
    }

    public void frame(long nowNanos, Renderer r) {
        Screen p;
        synchronized (lock) {
            p = pending;
            pending = null;
        }
        if (p != null) {
            if (screen != null) {
                screen.dispose();
            }
            screen = p;
            screen.init(this, r);
            clock.reset();
        }
        if (screen == null) {
            r.clear(0f, 0f, 0f);
            stats.frame(nowNanos, 0);
            return;
        }
        int n = clock.poll(nowNanos);
        for (int i = 0; i < n; i++) {
            screen.update();
        }
        stats.frame(nowNanos, n);
        screen.render(r);
    }

    /** Call on the render thread when the app is paused (sent to the background, window minimized). */
    public void pause() {
        if (screen != null) {
            screen.pause(); // the right moment to save settings: the app may be closed now
        }
        audio.pauseAll();
    }

    /** Call on the render thread when the app comes back. */
    public void resume() {
        clock.reset();
        input.clear();
        audio.resumeAll();
        if (screen != null) {
            screen.resume();
        }
    }

    public long getTotalTicks() {
        return clock.getTotalTicks();
    }
}
