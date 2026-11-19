// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Adds buses, per stream volume / pitch, positional 3D sound, music fades and "is it still playing" on top of any platform
 * {@link Audio}. The platform hosts wrap their backend in it, so {@code engine.getAudio()} has all of the 1.2 API.
 * <pre>
 *   Audio a = engine.getAudio();
 *   a.setBusVolume(Audio.BUS_MUSIC, 0.5f);                       // an options screen slider
 *   int s = a.playSound3D(boom, 1f, 1f, false, x, y, z, 2f, 30f);
 *   a.setListener(camX, camY, camZ, camYaw);                     // every tick
 *   a.crossfadeMusic("music/level2.ogg", true, 90);              // 1.5 s
 * </pre>
 * Stream ids returned by playSound are safe: they carry a generation number, so an id of a stream that ended (and whose
 * slot was reused) is ignored instead of changing somebody else's sound. Allocation free after construction. Voices:
 * {@value #VOICES}; when all are taken the oldest one-shot is stolen.
 * <p>
 * Fades are sequential (the old track fades out, then the new one starts and fades in): the backends stream one music
 * track at a time.
 */
public final class AudioMixer implements Audio {
    /** Simultaneous streams the mixer tracks. */
    public static final int VOICES = 32;
    private static final int SLOT_BITS = 5;
    private static final int SLOT_MASK = VOICES - 1;
    private static final int ASSUMED_TICKS = 180;   // slot occupancy of a one-shot of unknown length when the backend cannot tell

    private static final int FADE_NONE = 0;
    private static final int FADE_OUT = 1;           // then stop, or start pendingPath
    private static final int FADE_IN = 2;

    private final Audio out;
    private final float[] bus = new float[BUS_COUNT];
    private int[] duration = new int[64];            // by sound handle: ticks, 0 = unknown

    // voices
    private final int[] raw = new int[VOICES];
    private final int[] gen = new int[VOICES];
    private final boolean[] used = new boolean[VOICES];
    private final boolean[] loops = new boolean[VOICES];
    private final boolean[] is3d = new boolean[VOICES];
    private final boolean[] knownLen = new boolean[VOICES];
    private final int[] voiceBus = new int[VOICES];
    private final int[] endTick = new int[VOICES];
    private final int[] startTick = new int[VOICES];
    private final float[] vol = new float[VOICES];
    private final float[] px = new float[VOICES];
    private final float[] py = new float[VOICES];
    private final float[] pz = new float[VOICES];
    private final float[] ref = new float[VOICES];
    private final float[] max = new float[VOICES];
    private final float[] lastL = new float[VOICES];
    private final float[] lastR = new float[VOICES];
    private int nextGen = 1;
    private int now;
    private boolean any3d;

    // listener
    private float lx, ly, lz;
    private float rightX = 1f, rightZ = 0f;

    // music
    private float musicBase = 1f;
    private int fadeMode;
    private int fadeTicks;
    private int fadeLeft;
    private float fadeGain = 1f;
    private String pendingPath;
    private boolean pendingLoop;
    private boolean musicStarted;

    public AudioMixer(Audio backend) {
        if (backend == null) {
            throw new IllegalArgumentException("backend");
        }
        this.out = backend;
        for (int i = 0; i < BUS_COUNT; i++) {
            bus[i] = 1f;
        }
    }

    /** The wrapped platform audio. */
    public Audio getBackend() {
        return out;
    }

    // ------------------------------------------------------------------ sounds

    public int loadSound(String assetPath) {
        return out.loadSound(assetPath);
    }

    public void unloadSound(int sound) {
        out.unloadSound(sound);
    }

    public int playSound(int sound, float volume, float pitch, boolean loop) {
        return start(sound, volume, pitch, loop, BUS_SFX, false, 0f, 0f, 0f, 0f, 0f);
    }

    public int playSound(int sound, float volume, float pitch, boolean loop, int b) {
        return start(sound, volume, pitch, loop, b, false, 0f, 0f, 0f, 0f, 0f);
    }

    public int playSound3D(int sound, float volume, float pitch, boolean loop, float x, float y, float z,
                           float refDist, float maxDist) {
        return start(sound, volume, pitch, loop, BUS_SFX, true, x, y, z, refDist, maxDist);
    }

    private int start(int sound, float volume, float pitch, boolean loop, int b, boolean pos,
                      float x, float y, float z, float refDist, float maxDist) {
        int slot = freeSlot();
        if (slot < 0) {
            return -1;
        }
        int bi = b < 1 || b >= BUS_COUNT ? BUS_SFX : b;
        float v = clamp01(volume);
        float l = v;
        float r = v;
        if (pos) {
            ref[slot] = refDist < 0.01f ? 0.01f : refDist;
            max[slot] = maxDist <= ref[slot] ? ref[slot] + 0.01f : maxDist;
            px[slot] = x;
            py[slot] = y;
            pz[slot] = z;
            float g = bus[BUS_MASTER] * bus[bi] * v;
            pan(slot, g);
            l = outL;
            r = outR;
        } else {
            float g = bus[BUS_MASTER] * bus[bi] * v;
            l = g;
            r = g;
        }
        // the backend takes one volume at start: pass the larger side, then set the pair
        int id = out.playSound(sound, l > r ? l : r, pitch, loop);
        if (id < 0) {
            return -1;
        }
        for (int i = 0; i < VOICES; i++) {
            if (used[i] && raw[i] == id) {
                used[i] = false;                    // the backend reused the voice of a finished sound: that slot is dead
            }
        }
        raw[slot] = id;
        used[slot] = true;
        loops[slot] = loop;
        is3d[slot] = pos;
        voiceBus[slot] = bi;
        vol[slot] = v;
        startTick[slot] = now;
        int d = sound >= 0 && sound < duration.length ? duration[sound] : 0;
        if (pitch > 0.01f && d > 0) {
            d = (int) (d / pitch) + 1;
        }
        knownLen[slot] = d > 0;
        endTick[slot] = loop ? Integer.MAX_VALUE : now + (d > 0 ? d : ASSUMED_TICKS);
        lastL[slot] = l;
        lastR[slot] = r;
        if (pos) {
            any3d = true;
            out.setStreamGain(id, l, r);
        }
        gen[slot] = nextGen;
        int result = (nextGen << SLOT_BITS) | slot;
        nextGen++;
        if (nextGen > 0x3FFFFF) {
            nextGen = 1;
        }
        return result;
    }

    private int freeSlot() {
        int oldest = -1;
        int oldestTick = Integer.MAX_VALUE;
        for (int i = 0; i < VOICES; i++) {
            if (!used[i]) {
                return i;
            }
            if (!loops[i] && startTick[i] < oldestTick) {
                oldestTick = startTick[i];
                oldest = i;
            }
        }
        if (oldest >= 0) {
            out.stopStream(raw[oldest]);
            used[oldest] = false;
        }
        return oldest;
    }

    /** @return the slot of a live stream id, or -1 */
    private int slotOf(int stream) {
        if (stream < 0) {
            return -1;
        }
        int slot = stream & SLOT_MASK;
        return used[slot] && gen[slot] == (stream >>> SLOT_BITS) ? slot : -1;
    }

    public void stopStream(int stream) {
        int s = slotOf(stream);
        if (s >= 0) {
            out.stopStream(raw[s]);
            used[s] = false;
        }
    }

    public void setStreamVolume(int stream, float volume) {
        int s = slotOf(stream);
        if (s >= 0) {
            vol[s] = clamp01(volume);
            apply(s);
        }
    }

    public void setStreamPitch(int stream, float pitch) {
        int s = slotOf(stream);
        if (s >= 0) {
            out.setStreamRate(raw[s], pitch < 0.5f ? 0.5f : (pitch > 2f ? 2f : pitch));
        }
    }

    public boolean isStreamPlaying(int stream) {
        int s = slotOf(stream);
        if (s < 0) {
            return false;
        }
        if (loops[s]) {
            return true;
        }
        if (out.canQueryStreams()) {
            return out.isStreamActive(raw[s]);
        }
        return knownLen[s] && now < endTick[s];   // a one-shot of unknown length reports "not playing" (see setSoundDuration)
    }

    public void setSoundDuration(int sound, float seconds) {
        if (sound < 0) {
            return;
        }
        if (sound >= duration.length) {
            int n = duration.length;
            while (n <= sound) {
                n *= 2;
            }
            int[] bigger = new int[n];
            System.arraycopy(duration, 0, bigger, 0, duration.length);
            duration = bigger;
        }
        duration[sound] = seconds <= 0f ? 0 : (int) (seconds * 60f) + 1;
    }

    // ------------------------------------------------------------------ buses

    public void setBusVolume(int b, float volume) {
        if (b < 0 || b >= BUS_COUNT) {
            return;
        }
        bus[b] = clamp01(volume);
        for (int i = 0; i < VOICES; i++) {
            if (used[i]) {
                apply(i);
            }
        }
        applyMusic();
    }

    public float getBusVolume(int b) {
        return b >= 0 && b < BUS_COUNT ? bus[b] : 1f;
    }

    private float outL;
    private float outR;

    /** Left / right volume of a 3D voice at the current listener; result in outL / outR. */
    private void pan(int s, float gain) {
        float dx = px[s] - lx;
        float dy = py[s] - ly;
        float dz = pz[s] - lz;
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float att;
        if (d <= ref[s]) {
            att = 1f;
        } else if (d >= max[s]) {
            att = 0f;
        } else {
            att = 1f - (d - ref[s]) / (max[s] - ref[s]);
        }
        float p = 0f;                                   // -1 left .. +1 right
        float horiz = dx * dx + dz * dz;
        if (horiz > 1e-6f) {
            p = (dx * rightX + dz * rightZ) / (float) Math.sqrt(horiz);
            // sources that are close are less "directional"
            if (d < ref[s]) {
                p *= d / ref[s];
            }
        }
        float g = gain * att;
        outL = p > 0f ? g * (1f - p * 0.7f) : g;
        outR = p < 0f ? g * (1f + p * 0.7f) : g;
    }

    private void apply(int s) {
        float g = bus[BUS_MASTER] * bus[voiceBus[s]] * vol[s];
        float l;
        float r;
        if (is3d[s]) {
            pan(s, g);
            l = outL;
            r = outR;
        } else {
            l = g;
            r = g;
        }
        if (Math.abs(l - lastL[s]) > 0.004f || Math.abs(r - lastR[s]) > 0.004f) {
            lastL[s] = l;
            lastR[s] = r;
            out.setStreamGain(raw[s], l, r);
        }
    }

    // ------------------------------------------------------------------ 3D

    public void setListener(float x, float y, float z, float yawDegrees) {
        lx = x;
        ly = y;
        lz = z;
        rightX = FastMath.cos(yawDegrees);
        rightZ = -FastMath.sin(yawDegrees);
    }

    public void setStreamPosition(int stream, float x, float y, float z) {
        int s = slotOf(stream);
        if (s >= 0 && is3d[s]) {
            px[s] = x;
            py[s] = y;
            pz[s] = z;
        }
    }

    // ------------------------------------------------------------------ music

    public boolean playMusic(String assetPath, boolean loop) {
        fadeMode = FADE_NONE;
        fadeGain = 1f;
        pendingPath = null;
        boolean ok = out.playMusic(assetPath, loop);
        musicStarted = ok;
        applyMusic();
        return ok;
    }

    public void stopMusic() {
        fadeMode = FADE_NONE;
        fadeGain = 1f;
        pendingPath = null;
        musicStarted = false;
        out.stopMusic();
    }

    public void setMusicVolume(float volume) {
        musicBase = clamp01(volume);
        applyMusic();
    }

    public boolean isMusicPlaying() {
        return out.isMusicPlaying();
    }

    private void applyMusic() {
        out.setMusicVolume(musicBase * bus[BUS_MUSIC] * bus[BUS_MASTER] * fadeGain);
    }

    public boolean crossfadeMusic(String assetPath, boolean loop, int ticks) {
        if (ticks < 2 || !musicStarted || !out.isMusicPlaying()) {
            boolean ok = playMusic(assetPath, loop);
            if (ok && ticks >= 2) {
                fadeMode = FADE_IN;
                fadeTicks = ticks;
                fadeLeft = ticks;
                fadeGain = 0f;
                applyMusic();
            }
            return ok;
        }
        pendingPath = assetPath;
        pendingLoop = loop;
        fadeMode = FADE_OUT;
        fadeTicks = ticks / 2;
        fadeLeft = fadeTicks;
        return true;
    }

    public void fadeMusicOut(int ticks) {
        if (ticks < 2 || !musicStarted) {
            stopMusic();
            return;
        }
        pendingPath = null;
        fadeMode = FADE_OUT;
        fadeTicks = ticks;
        fadeLeft = ticks;
    }

    public void fadeMusicIn(int ticks) {
        if (ticks < 2) {
            fadeMode = FADE_NONE;
            fadeGain = 1f;
        } else {
            fadeMode = FADE_IN;
            fadeTicks = ticks;
            fadeLeft = ticks;
            fadeGain = 0f;
        }
        applyMusic();
    }

    // ------------------------------------------------------------------ per tick

    public void update() {
        now++;
        out.update();
        // reclaim finished voices; re-pan moving 3D ones
        boolean query = out.canQueryStreams();
        boolean check = (now & 3) == 0;
        for (int i = 0; i < VOICES; i++) {
            if (!used[i]) {
                continue;
            }
            if (!loops[i]) {
                if (query) {
                    if (check && now - startTick[i] > 2 && !out.isStreamActive(raw[i])) {
                        used[i] = false;
                        continue;
                    }
                } else if (now >= endTick[i]) {
                    used[i] = false;
                    continue;
                }
            }
            if (is3d[i] && any3d) {
                apply(i);
            }
        }
        if (fadeMode != FADE_NONE) {
            fadeLeft--;
            float t = fadeTicks > 0 ? (float) fadeLeft / fadeTicks : 0f;
            if (fadeMode == FADE_OUT) {
                fadeGain = t < 0f ? 0f : t;
                applyMusic();
                if (fadeLeft <= 0) {
                    String next = pendingPath;
                    pendingPath = null;
                    out.stopMusic();
                    musicStarted = false;
                    if (next != null) {
                        musicStarted = out.playMusic(next, pendingLoop);
                        fadeMode = FADE_IN;
                        fadeLeft = fadeTicks;
                        fadeGain = 0f;
                    } else {
                        fadeMode = FADE_NONE;
                        fadeGain = 1f;
                    }
                    applyMusic();
                }
            } else {
                fadeGain = t <= 0f ? 1f : 1f - t;
                applyMusic();
                if (fadeLeft <= 0) {
                    fadeMode = FADE_NONE;
                    fadeGain = 1f;
                    applyMusic();
                }
            }
        }
    }

    public void pauseAll() {
        out.pauseAll();
    }

    public void resumeAll() {
        out.resumeAll();
    }

    public void release() {
        for (int i = 0; i < VOICES; i++) {
            used[i] = false;
        }
        out.release();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
