// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.android;

import java.io.IOException;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.util.Log;

import skarph.core.Audio;

/**
 * skarph.core.Audio for Android: SoundPool for short effects (loaded asynchronously by the system,
 * so playSound() returns -1 until a sound is ready) and MediaPlayer for streamed music.
 * Use .ogg, .wav or .mp3 files in assets: those extensions are stored uncompressed in the APK,
 * which openFd() needs. The SoundPool is created on first use. Methods are synchronized
 * (the game calls them on the GL thread, Activity.onDestroy on the UI thread).
 */
public final class AndroidAudio implements Audio {
    private static final String TAG = "skarph";
    private static final int MAX_STREAMS = 8;
    private static final int MAX_LOOPS = 16;

    private final AssetManager assets;
    private SoundPool pool;
    private MediaPlayer music;
    private boolean musicPaused;
    private float musicVolume = 1f;
    private final int[] loopStreams = new int[MAX_LOOPS];
    private int loopCount;

    public AndroidAudio(AssetManager assets) {
        this.assets = assets;
    }

    private SoundPool pool() {
        if (pool == null) {
            pool = new SoundPool(MAX_STREAMS, AudioManager.STREAM_MUSIC, 0);
        }
        return pool;
    }

    public synchronized int loadSound(String assetPath) {
        AssetFileDescriptor afd = null;
        try {
            afd = assets.openFd(assetPath);
            int id = pool().load(afd, 1);
            return id > 0 ? id : -1;
        } catch (IOException e) {
            Log.e(TAG, "cannot open sound " + assetPath + " (is it .ogg/.wav/.mp3 in assets?)");
            return -1;
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    public synchronized void unloadSound(int sound) {
        if (pool != null && sound > 0) {
            pool.unload(sound);
        }
    }

    public synchronized int playSound(int sound, float volume, float pitch, boolean loop) {
        if (pool == null || sound <= 0) {
            return -1;
        }
        float v = volume < 0f ? 0f : (volume > 1f ? 1f : volume);
        float rate = pitch < 0.5f ? 0.5f : (pitch > 2f ? 2f : pitch);
        int stream = pool.play(sound, v, v, 1, loop ? -1 : 0, rate);
        if (stream == 0) {
            return -1;
        }
        if (loop && loopCount < MAX_LOOPS) {
            loopStreams[loopCount++] = stream;
        }
        return stream;
    }

    public synchronized void stopStream(int stream) {
        if (pool == null || stream <= 0) {
            return;
        }
        pool.stop(stream);
        for (int i = 0; i < loopCount; i++) {
            if (loopStreams[i] == stream) {
                loopStreams[i] = loopStreams[--loopCount];
                break;
            }
        }
    }

    public synchronized boolean playMusic(String assetPath, boolean loop) {
        stopMusic();
        AssetFileDescriptor afd = null;
        MediaPlayer mp = null;
        try {
            afd = assets.openFd(assetPath);
            mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.setLooping(loop);
            mp.setVolume(musicVolume, musicVolume);
            final MediaPlayer player = mp;
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer p) {
                    synchronized (AndroidAudio.this) {
                        if (player == music && !musicPaused) {
                            p.start();
                        }
                    }
                }
            });
            music = mp;
            musicPaused = false;
            mp.prepareAsync(); // does not block the game
            return true;
        } catch (Exception e) {
            Log.e(TAG, "cannot play music " + assetPath + ": " + e);
            if (mp != null) {
                mp.release();
            }
            music = null;
            return false;
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    public synchronized void stopMusic() {
        if (music != null) {
            try {
                music.stop();
            } catch (IllegalStateException ignored) {
                // not prepared yet
            }
            music.release();
            music = null;
        }
        musicPaused = false;
    }

    public synchronized void setMusicVolume(float volume) {
        musicVolume = volume < 0f ? 0f : (volume > 1f ? 1f : volume);
        if (music != null) {
            music.setVolume(musicVolume, musicVolume);
        }
    }

    public synchronized boolean isMusicPlaying() {
        try {
            return music != null && music.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public synchronized void pauseAll() {
        if (music != null && !musicPaused) {
            musicPaused = true;
            try {
                if (music.isPlaying()) {
                    music.pause();
                }
            } catch (IllegalStateException ignored) {
                // still preparing: onPrepared will not start it while paused
            }
        }
        if (pool != null) {
            for (int i = 0; i < loopCount; i++) {
                pool.pause(loopStreams[i]);
            }
        }
    }

    public synchronized void resumeAll() {
        if (music != null && musicPaused) {
            musicPaused = false;
            try {
                music.start();
            } catch (IllegalStateException ignored) {
                // still preparing: onPrepared will start it
            }
        }
        if (pool != null) {
            for (int i = 0; i < loopCount; i++) {
                pool.resume(loopStreams[i]);
            }
        }
    }

    public synchronized void release() {
        stopMusic();
        if (pool != null) {
            pool.release();
            pool = null;
        }
        loopCount = 0;
    }
}
