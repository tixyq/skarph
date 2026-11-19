// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Loads models, textures and raw files without freezing the game. A background thread reads the files and parses the
 * models (the slow, GL-free part); the render thread then finishes each request in {@link #pump(long)}: it uploads the mesh
 * or texture to the GPU, a little at a time, inside a time budget, so a loading screen keeps animating.
 * <pre>
 *   // init():
 *   loader = new AsyncLoader(engine.getAssets(), resources, 64);
 *   int t1 = loader.requestModel("town.mdl");
 *   int t2 = loader.requestTexture("sky.png", true, false);
 *   // every frame (render thread):
 *   loader.pump(4000000L);                         // at most about 4 ms of GPU work per call
 *   ui: progress bar = loader.getProgress();
 *   if (loader.isIdle()) { ResourceManager.Model m = loader.getModel(t1); ... loader.release(t1); }
 * </pre>
 * The requests themselves allocate nothing (fixed tables, no queues of objects); the worker thread allocates the parsed
 * data, which is unavoidable and happens while loading. A texture is still decoded and uploaded on the render thread
 * (the GL context lives there), so give big textures their own frames: pump() does one such item per call when the
 * budget is already used up. Every result is owned by the ResourceManager: release it there as usual, and release the
 * ticket here.
 */
public final class AsyncLoader {
    /** getState(): the request waits for or is being processed by the worker, or waits for pump(). */
    public static final int PENDING = 0;
    /** getState(): the result can be read. */
    public static final int DONE = 1;
    /** getState(): loading failed (see getError()). */
    public static final int FAILED = 2;
    /** getState(): the ticket is not valid (never issued, or released). */
    public static final int INVALID = 3;

    private static final int KIND_MODEL = 0;
    private static final int KIND_TEXTURE = 1;
    private static final int KIND_BYTES = 2;

    private static final int S_FREE = 0;
    private static final int S_QUEUED = 1;     // waits for the worker
    private static final int S_READY = 2;      // the worker is done, waits for pump()
    private static final int S_DONE = 3;
    private static final int S_FAILED = 4;

    private final Assets assets;
    private final ResourceManager resources;
    private final int capacity;
    private final Object lock = new Object();
    private final Thread worker;

    private final int[] state;
    private final int[] kind;
    private final int[] generation;
    private final String[] name;
    private final boolean[] smooth;
    private final boolean[] repeat;
    private final byte[][] bytes;
    private final ModelData[] parsed;
    private final ResourceManager.Model[] resultModel;
    private final int[] resultTexture;
    private final String[] error;
    private final int[] order;          // slots in request order (ring), so results are finished in the order requested
    private int orderHead;
    private int orderCount;
    private int workerNext;             // number of entries of `order` (from the head) the worker has taken
    private boolean stop;
    private int finished;               // finished since the last time the loader was idle
    private int requested;

    /** @param capacity the most requests that can be open at the same time (at most 4095) */
    public AsyncLoader(Assets assets, ResourceManager resources, int capacity) {
        if (capacity < 1 || capacity > 4095) {
            throw new IllegalArgumentException("capacity 1..4095");
        }
        this.assets = assets;
        this.resources = resources;
        this.capacity = capacity;
        state = new int[capacity];
        kind = new int[capacity];
        generation = new int[capacity];
        name = new String[capacity];
        smooth = new boolean[capacity];
        repeat = new boolean[capacity];
        bytes = new byte[capacity][];
        parsed = new ModelData[capacity];
        resultModel = new ResourceManager.Model[capacity];
        resultTexture = new int[capacity];
        error = new String[capacity];
        order = new int[capacity];
        worker = new Thread(new Runnable() {
            public void run() {
                work();
            }
        }, "skarph-loader");
        worker.setDaemon(true);
        worker.start();
    }

    /** Asks for a model file (MDL1/MDL2) and its textures. @return a ticket (>= 0) or -1 if the table is full */
    public int requestModel(String assetName) {
        return request(KIND_MODEL, assetName, false, false);
    }

    /** Asks for a texture (see Renderer.loadTexture). @return a ticket or -1 */
    public int requestTexture(String assetName, boolean smooth, boolean repeat) {
        return request(KIND_TEXTURE, assetName, smooth, repeat);
    }

    /** Asks for the raw bytes of a file (levels, maps, sounds' data, strings). @return a ticket or -1 */
    public int requestBytes(String assetName) {
        return request(KIND_BYTES, assetName, false, false);
    }

    private int request(int k, String n, boolean sm, boolean rp) {
        synchronized (lock) {
            if (stop) {
                return -1;
            }
            int slot = -1;
            for (int i = 0; i < capacity; i++) {
                if (state[i] == S_FREE) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                return -1;
            }
            state[slot] = S_QUEUED;
            kind[slot] = k;
            name[slot] = n;
            smooth[slot] = sm;
            repeat[slot] = rp;
            bytes[slot] = null;
            parsed[slot] = null;
            resultModel[slot] = null;
            resultTexture[slot] = -1;
            error[slot] = null;
            order[(orderHead + orderCount) % capacity] = slot;
            orderCount++;
            requested++;
            lock.notifyAll();
            return (generation[slot] << 12) | slot;
        }
    }

    private int slotOf(int ticket) {
        if (ticket < 0) {
            return -1;
        }
        int slot = ticket & 4095;
        if (slot >= capacity || state[slot] == S_FREE || generation[slot] != (ticket >> 12)) {
            return -1;
        }
        return slot;
    }

    private void work() {
        while (true) {
            int slot;
            synchronized (lock) {
                while (!stop && workerNext >= orderCount) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (stop) {
                    return;
                }
                slot = order[(orderHead + workerNext) % capacity];
                workerNext++;
            }
            byte[] b = null;
            ModelData md = null;
            String err = null;
            int k = kind[slot];
            if (k != KIND_TEXTURE) {
                b = assets.readBytes(name[slot]);
                if (b == null) {
                    err = "cannot read " + name[slot];
                } else if (k == KIND_MODEL) {
                    try {
                        md = ModelFormat.parse(b);
                        b = null;                   // the bytes are no longer needed
                    } catch (IllegalArgumentException e) {
                        err = name[slot] + ": " + e.getMessage();
                    }
                }
            }
            synchronized (lock) {
                bytes[slot] = b;
                parsed[slot] = md;
                error[slot] = err;
                state[slot] = S_READY;
            }
        }
    }

    /**
     * Finishes ready requests on the render thread, in the order they were requested: uploads models and textures. Stops
     * when {@code budgetNanos} nanoseconds have passed, but always finishes at least one ready request. Call it every
     * frame while loading (also later, if you stream things in). Allocates only what loading a model allocates.
     * @return the number of requests finished by this call
     */
    public int pump(long budgetNanos) {
        long start = System.nanoTime();
        int done = 0;
        while (true) {
            int slot;
            synchronized (lock) {
                if (orderCount == 0) {
                    break;
                }
                slot = order[orderHead];
                if (state[slot] != S_READY) {
                    break;                          // the oldest request is not ready yet: keep the order
                }
                orderHead = (orderHead + 1) % capacity;
                orderCount--;
                workerNext--;
            }
            finish(slot);
            done++;
            if (System.nanoTime() - start >= budgetNanos) {
                break;
            }
        }
        return done;
    }

    private void finish(int slot) {
        String err = error[slot];
        int k = kind[slot];
        ResourceManager.Model m = null;
        int tex = -1;
        if (err == null) {
            if (k == KIND_MODEL) {
                m = resources.getModel(name[slot], parsed[slot]);
                if (m == null) {
                    err = resources.getLastError();
                }
            } else if (k == KIND_TEXTURE) {
                tex = resources.getTexture(name[slot], smooth[slot], repeat[slot]);
                if (tex < 0) {
                    err = resources.getLastError();
                }
            }
        }
        synchronized (lock) {
            parsed[slot] = null;
            resultModel[slot] = m;
            resultTexture[slot] = tex;
            error[slot] = err;
            state[slot] = err == null ? S_DONE : S_FAILED;
            finished++;
        }
    }

    /** PENDING, DONE, FAILED or INVALID. */
    public int getState(int ticket) {
        synchronized (lock) {
            int slot = slotOf(ticket);
            if (slot < 0) {
                return INVALID;
            }
            int s = state[slot];
            return s == S_DONE ? DONE : (s == S_FAILED ? FAILED : PENDING);
        }
    }

    public boolean isDone(int ticket) {
        return getState(ticket) == DONE;
    }

    /** The failure text of a FAILED ticket, else null. */
    public String getError(int ticket) {
        synchronized (lock) {
            int slot = slotOf(ticket);
            return slot >= 0 && state[slot] == S_FAILED ? error[slot] : null;
        }
    }

    /** The loaded model of a DONE model request (reference counted by the ResourceManager), else null. */
    public ResourceManager.Model getModel(int ticket) {
        synchronized (lock) {
            int slot = slotOf(ticket);
            return slot >= 0 && state[slot] == S_DONE ? resultModel[slot] : null;
        }
    }

    /** The texture handle of a DONE texture request, else -1. */
    public int getTexture(int ticket) {
        synchronized (lock) {
            int slot = slotOf(ticket);
            return slot >= 0 && state[slot] == S_DONE ? resultTexture[slot] : -1;
        }
    }

    /** The bytes of a DONE bytes request, else null. */
    public byte[] getBytes(int ticket) {
        synchronized (lock) {
            int slot = slotOf(ticket);
            return slot >= 0 && state[slot] == S_DONE ? bytes[slot] : null;
        }
    }

    /** Frees the ticket's slot (the model or texture stays loaded in the ResourceManager). The ticket becomes INVALID. */
    public void release(int ticket) {
        synchronized (lock) {
            int slot = slotOf(ticket);
            if (slot >= 0 && (state[slot] == S_DONE || state[slot] == S_FAILED)) {
                state[slot] = S_FREE;
                bytes[slot] = null;
                resultModel[slot] = null;
                name[slot] = null;
                generation[slot] = (generation[slot] + 1) & 0x7FFFF;
            }
        }
    }

    /** Requests that are not finished yet. */
    public int getPendingCount() {
        synchronized (lock) {
            return orderCount;
        }
    }

    /** True when no request is waiting (finished ones may still hold their tickets). */
    public boolean isIdle() {
        return getPendingCount() == 0;
    }

    /** 0..1: finished / requested since the last time the loader was idle (for a progress bar). 1 when idle. */
    public float getProgress() {
        synchronized (lock) {
            if (orderCount == 0) {
                finished = 0;
                requested = 0;
                return 1f;
            }
            return requested == 0 ? 1f : (float) finished / requested;
        }
    }

    /** Stops the worker thread. Call when the screen ends; open requests are dropped. */
    public void shutdown() {
        synchronized (lock) {
            stop = true;
            lock.notifyAll();
        }
    }
}
