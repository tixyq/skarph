// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/** Audio that does nothing: the default, so code never needs null checks (also used in tests). */
public final class NullAudio implements Audio {
    public int loadSound(String assetPath) {
        return 0;
    }

    public void unloadSound(int sound) {
    }

    public int playSound(int sound, float volume, float pitch, boolean loop) {
        return -1;
    }

    public void stopStream(int stream) {
    }

    public boolean playMusic(String assetPath, boolean loop) {
        return true;
    }

    public void stopMusic() {
    }

    public void setMusicVolume(float volume) {
    }

    public boolean isMusicPlaying() {
        return false;
    }

    public void pauseAll() {
    }

    public void resumeAll() {
    }

    public void release() {
    }
}
