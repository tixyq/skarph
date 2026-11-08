// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop.host;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.libc.LibCStdlib;

import skarph.android.GlLog;
import skarph.core.Assets;
import skarph.core.Audio;
import skarph.desktop.WavDecoder;

/**
 * skarph.core.Audio on OpenAL. Effects are decoded when loaded and play on 32 sources that are reused when they stop.
 * Music is decoded on a background thread and starts a moment later, from pump(), which the host calls once per frame
 * (all OpenAL calls stay on the render thread). Formats: .wav (PCM) and .ogg (Vorbis); .mp3 is not supported.
 */
final class OpenAlAudio implements Audio {
    private static final int SOURCES = 32;

    private final Assets assets;
    private final GlLog log;
    private final long device;
    private final long context;
    private final int[] sources = new int[SOURCES];
    private final int musicSource;
    private int[] soundBuffers = new int[16]; // handle - 1 = index, 0 = free
    private int musicBuffer;
    private boolean musicLoop;
    private float musicVolume = 1f;
    private boolean paused;
    private int musicRequest;                 // bumped by every playMusic / stopMusic
    private volatile Decoded decoded;         // result of the background thread
    private volatile boolean decoding;

    private static final class Decoded {
        final int request;
        final WavDecoder.Pcm pcm; // null = failed

        Decoded(int request, WavDecoder.Pcm pcm) {
            this.request = request;
            this.pcm = pcm;
        }
    }

    private OpenAlAudio(Assets assets, GlLog log, long device, long context) {
        this.assets = assets;
        this.log = log;
        this.device = device;
        this.context = context;
        for (int i = 0; i < SOURCES; i++) {
            sources[i] = AL10.alGenSources();
        }
        musicSource = AL10.alGenSources();
    }

    /** @return the audio, or null if there is no sound device or OpenAL cannot be loaded (the game then runs silent) */
    static OpenAlAudio create(Assets assets, GlLog log) {
        long device = 0;
        long context = 0;
        try {
            device = org.lwjgl.openal.ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0) {
                log.warn("no sound device: the program runs without sound");
                return null;
            }
            ALCCapabilities caps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0) {
                ALC10.alcCloseDevice(device);
                log.warn("cannot create a sound context: the program runs without sound");
                return null;
            }
            ALC10.alcMakeContextCurrent(context);
            AL.createCapabilities(caps);
            return new OpenAlAudio(assets, log, device, context);
        } catch (Throwable t) {
            log.warn("sound is not available (" + t + "): the program runs without sound");
            return null;
        }
    }

    public synchronized int loadSound(String assetPath) {
        WavDecoder.Pcm pcm = decode(assets.readBytes(assetPath), assetPath);
        if (pcm == null) {
            log.error("cannot load sound " + assetPath + " (.wav or .ogg is needed)");
            return -1;
        }
        int buffer = upload(pcm);
        if (buffer == 0) {
            return -1;
        }
        for (int i = 0; i < soundBuffers.length; i++) {
            if (soundBuffers[i] == 0) {
                soundBuffers[i] = buffer;
                return i + 1;
            }
        }
        int[] bigger = new int[soundBuffers.length * 2];
        System.arraycopy(soundBuffers, 0, bigger, 0, soundBuffers.length);
        int index = soundBuffers.length;
        bigger[index] = buffer;
        soundBuffers = bigger;
        return index + 1;
    }

    public synchronized void unloadSound(int sound) {
        int i = sound - 1;
        if (i >= 0 && i < soundBuffers.length && soundBuffers[i] != 0) {
            for (int s : sources) {
                if (AL10.alGetSourcei(s, AL10.AL_BUFFER) == soundBuffers[i]) {
                    AL10.alSourceStop(s);
                    AL10.alSourcei(s, AL10.AL_BUFFER, 0);
                }
            }
            AL10.alDeleteBuffers(soundBuffers[i]);
            soundBuffers[i] = 0;
        }
    }

    public synchronized int playSound(int sound, float volume, float pitch, boolean loop) {
        int i = sound - 1;
        if (paused || i < 0 || i >= soundBuffers.length || soundBuffers[i] == 0) {
            return -1;
        }
        for (int s : sources) {
            int state = AL10.alGetSourcei(s, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) {
                AL10.alSourcei(s, AL10.AL_BUFFER, soundBuffers[i]);
                AL10.alSourcef(s, AL10.AL_GAIN, clamp(volume, 0f, 1f));
                AL10.alSourcef(s, AL10.AL_PITCH, clamp(pitch, 0.5f, 2f));
                AL10.alSourcei(s, AL10.AL_LOOPING, loop ? AL10.AL_TRUE : AL10.AL_FALSE);
                AL10.alSourcePlay(s);
                return s;
            }
        }
        return -1; // all 32 voices are busy
    }

    public synchronized void stopStream(int stream) {
        for (int s : sources) {
            if (s == stream) {
                AL10.alSourceStop(s);
                return;
            }
        }
    }

    public synchronized boolean playMusic(String assetPath, boolean loop) {
        stopMusic();
        final byte[] data = assets.readBytes(assetPath);
        if (data == null) {
            log.error("cannot open music " + assetPath);
            return false;
        }
        final int request = ++musicRequest;
        final String path = assetPath;
        musicLoop = loop;
        decoding = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                WavDecoder.Pcm pcm = null;
                try {
                    pcm = decode(data, path);
                } catch (Throwable e) {
                    pcm = null;
                }
                decoded = new Decoded(request, pcm);
            }
        }, "skarph-music-decoder");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public synchronized void stopMusic() {
        musicRequest++; // a result that is still being decoded is now out of date
        decoding = false;
        decoded = null;
        AL10.alSourceStop(musicSource);
        AL10.alSourcei(musicSource, AL10.AL_BUFFER, 0);
        if (musicBuffer != 0) {
            AL10.alDeleteBuffers(musicBuffer);
            musicBuffer = 0;
        }
    }

    public synchronized void setMusicVolume(float volume) {
        musicVolume = clamp(volume, 0f, 1f);
        AL10.alSourcef(musicSource, AL10.AL_GAIN, musicVolume);
    }

    public synchronized boolean isMusicPlaying() {
        return decoding || AL10.alGetSourcei(musicSource, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
    }

    public synchronized void pauseAll() {
        paused = true;
        for (int s : sources) {
            if (AL10.alGetSourcei(s, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                AL10.alSourcePause(s);
            }
        }
        if (AL10.alGetSourcei(musicSource, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
            AL10.alSourcePause(musicSource);
        }
    }

    public synchronized void resumeAll() {
        paused = false;
        for (int s : sources) {
            if (AL10.alGetSourcei(s, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED) {
                AL10.alSourcePlay(s);
            }
        }
        if (AL10.alGetSourcei(musicSource, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED) {
            AL10.alSourcePlay(musicSource);
        }
    }

    /** Called by the host once per frame on the render thread: starts the music that the background thread finished. */
    synchronized void pump() {
        Decoded d = decoded;
        if (d == null) {
            return;
        }
        decoded = null;
        if (d.request != musicRequest) {
            return; // stopMusic() or another playMusic() came in the meantime
        }
        decoding = false;
        if (d.pcm == null) {
            log.error("cannot decode the music");
            return;
        }
        int buffer = upload(d.pcm);
        if (buffer == 0) {
            return;
        }
        musicBuffer = buffer;
        AL10.alSourcei(musicSource, AL10.AL_BUFFER, buffer);
        AL10.alSourcef(musicSource, AL10.AL_GAIN, musicVolume);
        AL10.alSourcei(musicSource, AL10.AL_LOOPING, musicLoop ? AL10.AL_TRUE : AL10.AL_FALSE);
        AL10.alSourcePlay(musicSource);
        if (paused) {
            AL10.alSourcePause(musicSource);
        }
    }

    public synchronized void release() {
        musicRequest++;
        decoded = null;
        for (int s : sources) {
            AL10.alSourceStop(s);
            AL10.alDeleteSources(s);
        }
        AL10.alSourceStop(musicSource);
        AL10.alDeleteSources(musicSource);
        for (int b : soundBuffers) {
            if (b != 0) {
                AL10.alDeleteBuffers(b);
            }
        }
        java.util.Arrays.fill(soundBuffers, 0);
        if (musicBuffer != 0) {
            AL10.alDeleteBuffers(musicBuffer);
            musicBuffer = 0;
        }
        ALC10.alcMakeContextCurrent(0L);
        ALC10.alcDestroyContext(context);
        ALC10.alcCloseDevice(device);
    }

    private int upload(WavDecoder.Pcm pcm) {
        int format = pcm.channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        ShortBuffer data = ByteBuffer.allocateDirect(pcm.samples.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        ((Buffer) data.put(pcm.samples)).flip();
        int buffer = AL10.alGenBuffers();
        AL10.alBufferData(buffer, format, data, pcm.sampleRate);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            AL10.alDeleteBuffers(buffer);
            log.error("OpenAL cannot take this sound");
            return 0;
        }
        return buffer;
    }

    /** WAV by the decoder of this program, Ogg Vorbis by stb_vorbis. Thread safe (no OpenAL calls). */
    private static WavDecoder.Pcm decode(byte[] data, String path) {
        if (data == null) {
            return null;
        }
        if (data.length > 4 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') {
            return WavDecoder.decode(data);
        }
        if (data.length > 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') {
            ByteBuffer in = ByteBuffer.allocateDirect(data.length);
            ((Buffer) in.put(data)).flip();
            IntBuffer channels = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
            IntBuffer rate = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(in, channels, rate);
            if (pcm == null) {
                return null;
            }
            try {
                int ch = channels.get(0);
                if (ch < 1 || ch > 2) {
                    return null;
                }
                short[] samples = new short[pcm.remaining()];
                pcm.get(samples);
                return new WavDecoder.Pcm(samples, ch, rate.get(0));
            } finally {
                LibCStdlib.free(pcm);
            }
        }
        return null;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
