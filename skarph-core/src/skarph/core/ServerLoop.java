// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.util.concurrent.locks.LockSupport;

/**
 * Main loop of a server program: fixed tick rate, no window, engine or graphics. Each tick calls
 * {@link NetServer#update()}, reports joins, leaves and messages to the {@link ServerHandler} and then calls
 * {@link ServerHandler#onTick}. The loop sleeps between ticks. A tick that overruns is not compensated by additional ticks.
 * <pre>
 * public class MyServer {
 *     public static void main(String[] args) {
 *         NetServer server = NetServer.listen(7777, 0x5A01, 32);
 *         ServerLoop loop = new ServerLoop(server, new ServerAdapter() {
 *             public void onJoin(NetServer s, NetClient c) { System.out.println(c + " joined"); }
 *             public void onMessage(NetServer s, NetClient c, NetMessage m) { s.broadcast(NetServer.ALL, c.getId(), m.data, 0, m.length); }
 *         }, 30);
 *         System.out.println(loop.run() == null ? "stopped" : "failed");   // blocks until loop.stop() (or a failure)
 *     }
 * }
 * </pre>
 * {@link #run()} uses the calling thread, {@link #start()} creates a thread. {@link #stop()} may be called from any thread.
 * An exception thrown by a handler method is printed to {@code System.err} and the loop continues.
 */
public final class ServerLoop {
    private final NetServer server;
    private final ServerHandler handler;
    private final int rate;
    private final long stepNanos;
    private final NetEvent event = new NetEvent();
    private NetMessage message = new NetMessage();
    private volatile boolean stopRequested;
    private volatile boolean running;
    private volatile long tick;
    private volatile float load;
    private Thread thread;

    /**
     * Makes a loop for a server and the handler that is the game.
     *
     * @param server the server (made by {@link NetServer#listen}); the loop closes it when it ends
     * @param handler your game
     * @param ticksPerSecond how often the loop runs (1 to 1000); typically 20 to 60
     */
    public ServerLoop(NetServer server, ServerHandler handler, int ticksPerSecond) {
        this.server = server;
        this.handler = handler;
        this.rate = Math.max(1, Math.min(1000, ticksPerSecond));
        this.stepNanos = 1000000000L / rate;
    }

    /** The biggest message the handler can get (default {@link NetChannel#MAX_PAYLOAD}); {@link NetChannel#MAX_BIG} takes the big reliable messages too. Call it before {@link #run()}. */
    public ServerLoop setMaxMessageSize(int bytes) {
        message = new NetMessage(bytes);
        return this;
    }

    /** Ticks a second. */
    public int getTickRate() {
        return rate;
    }

    /** The number of the last tick that ran (0 before the first). */
    public long getTick() {
        return tick;
    }

    /** True from the start of the loop until it ends. */
    public boolean isRunning() {
        return running;
    }

    /** The share of a tick that the work took recently (0..1, smoothed); near 1 means the server cannot keep up. */
    public float getLoad() {
        return load;
    }

    /** Asks the loop to end after the current tick. Returns at once; thread-safe. */
    public void stop() {
        stopRequested = true;
        LockSupport.unpark(thread);
    }

    /** Runs {@link #run()} on a thread of its own (not a daemon thread: it keeps the program alive until the loop ends). */
    public void start() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                ServerLoop.this.run();
            }
        }, "skarph-server");
        thread = t;
        t.start();
    }

    /**
     * Waits for the server to open its port, calls {@link ServerHandler#onStart}, then loops until {@link #stop()}. Then
     * it closes the server, delivers the leaving of the clients, calls {@link ServerHandler#onStop} and returns.
     *
     * @return null after a normal stop, else why the server could not run (the port is taken, the socket failed)
     */
    public String run() {
        if (running) {
            return "already running";
        }
        running = true;
        thread = Thread.currentThread();
        String error = null;
        try {
            if (!server.awaitRunning(10000)) {
                error = server.getState() == NetServer.FAILED ? server.getError() : "the server did not open in time";
                return error;
            }
            try {
                handler.onStart(server);
            } catch (Throwable t) {
                report("onStart", t);
            }
            long next = System.nanoTime();
            float dt = 1f / rate;
            while (!stopRequested) {
                long t0 = System.nanoTime();
                server.update();
                if (server.getState() != NetServer.RUNNING) {
                    error = server.getState() == NetServer.FAILED ? server.getError() : "the server was closed";
                    break;
                }
                deliver();
                tick++;
                try {
                    handler.onTick(server, tick, dt);
                } catch (Throwable t) {
                    report("onTick", t);
                }
                long used = System.nanoTime() - t0;
                load = load * 0.95f + 0.05f * Math.min(1f, used / (float) stepNanos);
                next += stepNanos;
                long now = System.nanoTime();
                if (next - now < -250000000L) {
                    next = now; // far behind: skip the missed ticks
                }
                sleepUntil(next);
            }
        } finally {
            server.close();
            deliver(); // the leaving of every client, with what they sent last
            try {
                handler.onStop(server);
            } catch (Throwable t) {
                report("onStop", t);
            }
            try {
                Thread.sleep(100); // lets the socket thread send the close datagrams
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running = false;
        }
        return error;
    }

    /** Hands the events and the messages of this tick to the handler. A leaving client's last messages come before its leaving. */
    private void deliver() {
        while (server.pollEvent(event)) {
            NetClient c = event.client;
            if (event.type == NetEvent.JOINED) {
                try {
                    handler.onJoin(server, c);
                } catch (Throwable t) {
                    report("onJoin", t);
                }
            } else {
                if (event.reason != NetServer.LEAVE_KICKED) {
                    drain(c, true); // messages sent before the client left
                }
                try {
                    handler.onLeave(server, c, event.reason);
                } catch (Throwable t) {
                    report("onLeave", t);
                }
            }
        }
        for (int i = 0; i < server.getClientSlots(); i++) {
            NetClient c = server.getClientAt(i);
            if (c != null) {
                drain(c, false);
            }
        }
    }

    /** @param leaving true for a client that is gone: its unread messages are still delivered */
    private void drain(NetClient c, boolean leaving) {
        while ((leaving || c.isPresent()) && c.receive(message)) { // a client kicked by the handler delivers no further messages
            try {
                handler.onMessage(server, c, message);
            } catch (Throwable t) {
                report("onMessage", t);
            }
        }
    }

    private static void sleepUntil(long deadline) {
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            if (left > 2000000L) {
                LockSupport.parkNanos(left - 1000000L); // wakes early; the final millisecond is waited out below
            } else {
                Thread.yield();
            }
        }
    }

    private static void report(String where, Throwable t) {
        System.err.println("ServerLoop: " + where + " threw " + t);
        t.printStackTrace();
    }
}
