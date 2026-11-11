// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Platform independent sound. Short effects are loaded into memory and can overlap;
 * one music track streams from a file. Volumes are 0..1.
 * Use .ogg, .wav or .mp3 files for the sounds.
 */
public interface Audio {
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

    /** Called by the engine when the app is paused. */
    void pauseAll();

    /** Called by the engine when the app is resumed. */
    void resumeAll();

    /** Frees every native resource (call when the program ends). */
    void release();
}
