// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.demo.v10;

import skarph.core.Audio;

/**
 * The sound of the night port demo: eight short effects (WAV, loaded once) and the music loop (OGG, streamed),
 * plus a wind loop under everything. Plain Java on the engine's {@link Audio} interface (a NullAudio does nothing, so the
 * tests and devices without sound need no special case). Effects can be queued with a delay in ticks (the boom of a firework
 * arrives after its flash); the queue is fixed size, nothing is allocated after the constructor.
 * Sound effects and music can be switched off separately; the Audio itself (SoundPool / MediaPlayer on Android) is paused
 * and resumed by the engine when the app leaves and comes back.
 */
final class PortSound {
    static final int CLICK = 0;
    static final int CUT = 1;
    static final int HORN = 2;
    static final int BELL = 3;
    static final int CRACKLE = 4;
    static final int LAUNCH = 5;
    static final int BOOM = 6;
    static final int WIND = 7;
    static final int COUNT = 8;
    private static final String[] FILES = {
        "click.wav", "cut.wav", "horn.wav", "bell.wav", "crackle.wav", "launch.wav", "boom.wav", "wind.wav"
    };
    private static final String MUSIC = "music.ogg";
    private static final int QUEUE = 8;
    private static final float WIND_VOLUME = 0.35f;
    private static final float MUSIC_VOLUME = 0.5f;

    private final Audio audio;
    private final int[] sound = new int[COUNT];
    private boolean sfxOn = true;
    private boolean musicOn = true;
    private int windStream = -1;
    private int windRetry;
    private final int[] qDelay = new int[QUEUE];
    private final int[] qId = new int[QUEUE];
    private final float[] qVolume = new float[QUEUE];
    private final float[] qPitch = new float[QUEUE];
    private int played;

    PortSound(Audio audio) {
        this.audio = audio;
        for (int i = 0; i < QUEUE; i++) {
            qDelay[i] = -1;                                      // -1 = free slot
        }
        for (int i = 0; i < COUNT; i++) {
            sound[i] = audio.loadSound(FILES[i]);
        }
        audio.setMusicVolume(MUSIC_VOLUME);
    }

    /** Starts the music (if on) and the wind loop (if effects are on). Safe to call again. */
    void start() {
        if (musicOn && !audio.isMusicPlaying()) {
            audio.playMusic(MUSIC, true);
        }
        startWind();
    }

    private void startWind() {
        if (sfxOn && windStream < 0 && sound[WIND] >= 0) {
            windStream = audio.playSound(sound[WIND], WIND_VOLUME, 1f, true);   // -1 while the sample is still loading
        }
    }

    void setSfxOn(boolean on) {
        sfxOn = on;
        if (!on) {
            if (windStream >= 0) {
                audio.stopStream(windStream);
                windStream = -1;
            }
            for (int i = 0; i < QUEUE; i++) {
                qDelay[i] = -1;
            }
        } else {
            startWind();
        }
    }

    void setMusicOn(boolean on) {
        musicOn = on;
        if (on) {
            audio.playMusic(MUSIC, true);
        } else {
            audio.stopMusic();
        }
    }

    boolean isSfxOn() {
        return sfxOn;
    }

    boolean isMusicOn() {
        return musicOn;
    }

    /** Effects started since the constructor (for tests and the profiler line). */
    int getPlayedCount() {
        return played;
    }

    /** Plays an effect now. Ignored when effects are off or the sample is not loaded. */
    void play(int id, float volume, float pitch) {
        if (!sfxOn || sound[id] < 0) {
            return;
        }
        audio.playSound(sound[id], volume, pitch, false);
        played++;
    }

    /** Plays an effect after delayTicks logic ticks (the queue holds 8; a full queue drops the new one). */
    void playDelayed(int id, float volume, float pitch, int delayTicks) {
        if (!sfxOn) {
            return;
        }
        for (int i = 0; i < QUEUE; i++) {
            if (qDelay[i] < 0) {
                qDelay[i] = delayTicks;
                qId[i] = id;
                qVolume[i] = volume;
                qPitch[i] = pitch;
                return;
            }
        }
    }

    /** Once per logic tick: delayed effects and a retry of the wind loop (samples load in the background). */
    void update() {
        for (int i = 0; i < QUEUE; i++) {
            if (qDelay[i] >= 0 && --qDelay[i] < 0) {
                play(qId[i], qVolume[i], qPitch[i]);
            }
        }
        if (sfxOn && windStream < 0 && ++windRetry >= 30) {
            windRetry = 0;
            startWind();
        }
    }

    void release() {
        if (windStream >= 0) {
            audio.stopStream(windStream);
            windStream = -1;
        }
        audio.stopMusic();
        for (int i = 0; i < COUNT; i++) {
            if (sound[i] >= 0) {
                audio.unloadSound(sound[i]);
                sound[i] = -1;
            }
        }
    }
}
