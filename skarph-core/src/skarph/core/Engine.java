// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Platform independent engine core. The platform layer calls frame() once per
 * rendered frame, always from the same (render) thread.
 */
public final class Engine {
    /** The engine version. */
    public static final String VERSION = "1.1";

    public final InputQueue input = new InputQueue(256);
    public final Stats stats = new Stats();

    private final TickClock clock = new TickClock();
    private Assets assets;
    private Audio audio = new NullAudio();
    private Storage storage = new MemoryStorage();
    private Http http;
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

    /**
     * HTTP and HTTPS for fetching small pieces of information in the background (see {@link Http}). Never null: the
     * first call creates a {@link UrlHttp} with its default options; use setHttp() before that to change them.
     */
    public synchronized Http getHttp() {
        if (http == null) {
            http = new UrlHttp();
        }
        return http;
    }

    /** Replaces the network (options of a UrlHttp, or a fake in tests); null brings the default back. */
    public synchronized void setHttp(Http http) {
        if (this.http != null && this.http != http) {
            this.http.release();
        }
        this.http = http;
    }

    /** Called by the platform layer when the program ends: stops the network thread, if one was ever started. */
    public synchronized void releaseHttp() {
        if (http != null) {
            http.release();
            http = null;
        }
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
