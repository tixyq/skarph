// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Platform independent engine core. The platform layer calls frame() once per
 * rendered frame, always from the same (render) thread.
 */
public final class Engine {
    /** The engine version. */
    public static final String VERSION = "1.3";

    public final InputQueue input = new InputQueue(256);
    public final Stats stats = new Stats();

    private final TickClock clock = new TickClock();
    private Assets assets;
    private Audio audio = new NullAudio();
    private Storage storage = new MemoryStorage();
    private Http http;
    private final java.util.ArrayList<UdpSocket> udpSockets = new java.util.ArrayList<UdpSocket>();
    private final java.util.ArrayList<NetServer> netServers = new java.util.ArrayList<NetServer>();
    private final Object lock = new Object();
    private Screen screen;
    private Resizable resizable;
    private Screen pending;
    private int surfaceW = -1;
    private int surfaceH = -1;
    private float alpha = 1f;

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

    /**
     * Opens a UDP socket to {@code host:port} in the background and returns at once (see {@link UdpSocket}). The engine
     * remembers it and closes it in {@link #releaseNet()}. Never throws and never returns null.
     */
    public UdpSocket connectUdp(String host, int port) {
        UdpSocket s = UdpSocket.connect(host, port);
        track(s);
        return s;
    }

    /**
     * Starts an online connection to {@code host:port}: a {@link NetChannel} over its own UDP socket, closed by
     * {@link NetChannel#close()} or at the latest by {@link #releaseNet()}. Call {@code update()} on it every tick.
     * Never throws and never returns null.
     */
    public NetChannel connectNet(String host, int port, int appId) {
        UdpSocket s = UdpSocket.connect(host, port);
        track(s);
        return new NetChannel(s, appId);
    }

    /**
     * Starts a server that listens on {@code port} (UDP) for up to {@code maxClients} clients of the game {@code appId}: a
     * match hosted by the game itself (the host plays through {@link #connectNet} to {@code "127.0.0.1"}). Closed by
     * {@link NetServer#close()} or at the latest by {@link #releaseNet()}. Call {@code update()} on it every tick.
     * A dedicated server needs no engine: see {@link ServerLoop}. Never throws and never returns null.
     */
    public NetServer listenNet(int port, int appId, int maxClients) {
        NetServer s = NetServer.listen(port, appId, maxClients);
        synchronized (netServers) {
            for (int i = netServers.size() - 1; i >= 0; i--) {
                int st = netServers.get(i).getState();
                if (st == NetServer.CLOSED || st == NetServer.FAILED) {
                    netServers.remove(i);
                }
            }
            netServers.add(s);
        }
        return s;
    }

    private void track(UdpSocket s) {
        synchronized (udpSockets) {
            for (int i = udpSockets.size() - 1; i >= 0; i--) {
                int st = udpSockets.get(i).getState();
                if (st == UdpSocket.CLOSED || st == UdpSocket.FAILED) {
                    udpSockets.remove(i); // forget the finished ones
                }
            }
            udpSockets.add(s);
        }
    }

    /**
     * Called by the platform layer when the program ends: closes every UDP socket opened with {@link #connectUdp} or
     * {@link #connectNet} and every server started with {@link #listenNet} (queued datagrams, such as a goodbye, are still sent) and stops the HTTP thread.
     */
    public void releaseNet() {
        synchronized (netServers) {
            for (int i = 0; i < netServers.size(); i++) {
                netServers.get(i).close();
            }
            netServers.clear();
        }
        synchronized (udpSockets) {
            for (int i = 0; i < udpSockets.size(); i++) {
                udpSockets.get(i).close();
            }
            udpSockets.clear();
        }
        releaseHttp();
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
            resizable = p instanceof Resizable ? (Resizable) p : null;
            screen.init(this, r);
            clock.reset();
            surfaceW = -1; // the new screen hears the size right away
            surfaceH = -1;
        }
        if (screen == null) {
            r.clear(0f, 0f, 0f);
            stats.frame(nowNanos, 0);
            return;
        }
        int w = r.getWidth();
        int h = r.getHeight();
        if (w != surfaceW || h != surfaceH) {
            surfaceW = w;
            surfaceH = h;
            if (resizable != null) {
                resizable.resize(w, h);
            }
        }
        int n = clock.poll(nowNanos);
        for (int i = 0; i < n; i++) {
            audio.update();
            screen.update();
        }
        alpha = clock.getAlpha();
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

    /**
     * Progress of the current frame between two logic ticks, 0..1 (see {@link TickClock#getAlpha()}). Read it in
     * render() and pass it to Scene.render(r, camera, alpha) or use it to blend your own previous/current values.
     */
    public float getAlpha() {
        return alpha;
    }

    public long getTotalTicks() {
        return clock.getTotalTicks();
    }
}
