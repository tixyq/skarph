// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Platform independent sound. Short effects are loaded into memory and can overlap;
 * one music track streams from a file. Volumes are 0..1.
 * Use .ogg, .wav or .mp3 files for the sounds.
 */
public interface Audio {
    /** Bus numbers for setBusVolume() / playSound(..., bus). MASTER scales everything. */
    int BUS_MASTER = 0;
    int BUS_SFX = 1;
    int BUS_MUSIC = 2;
    int BUS_UI = 3;
    int BUS_COUNT = 4;

    /** Loads a short sound effect (loading may continue in the background). @return handle, or -1 */
    int loadSound(String assetPath);

    void unloadSound(int sound);

    /**
     * Plays an effect. @param pitch playback speed, 1 = normal (0.5 .. 2)
     * @return stream id for stopStream(), or -1 if the sound could not be started
     *         (for example it has not finished loading yet)
     */
    int playSound(int sound, float volume, float pitch, boolean loop);

    void stopStream(int stream);

    /** Starts streaming music (stops the previous track). @return false on failure */
    boolean playMusic(String assetPath, boolean loop);

    void stopMusic();

    void setMusicVolume(float volume);

    boolean isMusicPlaying();

    /**
     * Called by the engine once per logic tick (60 per second), before Screen.update(): drives fades and moving 3D
     * sources. Never allocates. Default: nothing.
     */
    default void update() {
    }

    // ---- 1.2: everything below has a default that does nothing, so old Audio implementations keep compiling.
    // AudioMixer (wrap a platform Audio in it; the platform hosts do) implements all of it on top of any backend.

    /** Plays an effect on a bus (BUS_SFX, BUS_UI ...). The 4 argument playSound() uses BUS_SFX. */
    default int playSound(int sound, float volume, float pitch, boolean loop, int bus) {
        return playSound(sound, volume, pitch, loop);
    }

    /** Changes the volume (0..1) of a playing stream. Stale ids (the stream ended) are ignored. */
    default void setStreamVolume(int stream, float volume) {
    }

    default void setStreamPitch(int stream, float pitch) {
    }

    /** True while the stream plays. Exact for loops and on desktop; on Android a one-shot needs setSoundDuration(). */
    default boolean isStreamPlaying(int stream) {
        return false;
    }

    /** Tells the mixer how long a sound is, in seconds, so that isStreamPlaying() works for one-shots on Android. */
    default void setSoundDuration(int sound, float seconds) {
    }

    /** Bus volume 0..1 (BUS_MASTER scales all buses). Applies to sounds that are already playing. */
    default void setBusVolume(int bus, float volume) {
    }

    default float getBusVolume(int bus) {
        return 1f;
    }

    /** The listener for 3D sounds: position and the direction it faces as a yaw in degrees (the same yaw as Node's rotY). */
    default void setListener(float x, float y, float z, float yawDegrees) {
    }

    /**
     * Plays a sound at a position: louder when close (full inside refDist, silent beyond maxDist) and panned left / right
     * relative to the listener. Moving the source: setStreamPosition(). Loops are re-panned every tick.
     */
    default int playSound3D(int sound, float volume, float pitch, boolean loop, float x, float y, float z,
                            float refDist, float maxDist) {
        return playSound(sound, volume, pitch, loop);
    }

    default void setStreamPosition(int stream, float x, float y, float z) {
    }

    /** Fades the current music out and the new one in (one after the other, ticks / 2 each). Starts at once if silent. */
    default boolean crossfadeMusic(String assetPath, boolean loop, int ticks) {
        return playMusic(assetPath, loop);
    }

    /** Fades the music out over this many ticks, then stops it. */
    default void fadeMusicOut(int ticks) {
        stopMusic();
    }

    /** Fades the current music in from silence over this many ticks. */
    default void fadeMusicIn(int ticks) {
    }

    // ---- raw operations a platform backend may implement (used by AudioMixer; games do not call them)

    /** Left / right volume of a stream (0..1 each); a backend without panning uses the larger one. */
    default void setStreamGain(int stream, float left, float right) {
    }

    default void setStreamRate(int stream, float pitch) {
    }

    /** True if isStreamActive() is exact (the backend can tell when a one-shot ended). */
    default boolean canQueryStreams() {
        return false;
    }

    default boolean isStreamActive(int stream) {
        return false;
    }

    /** Called by the engine when the app is paused. */
    void pauseAll();

    /** Called by the engine when the app is resumed. */
    void resumeAll();

    /** Frees every native resource (call when the program ends). */
    void release();
}
