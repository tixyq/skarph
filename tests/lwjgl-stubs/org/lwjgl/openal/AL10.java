package org.lwjgl.openal;
import java.nio.*;
public class AL10 {
    public static final int AL_BUFFER = 0x1009, AL_LOOPING = 0x1007, AL_GAIN = 0x100A, AL_PITCH = 0x1003, AL_SOURCE_STATE = 0x1010,
        AL_PLAYING = 0x1012, AL_PAUSED = 0x1013, AL_STOPPED = 0x1014, AL_FORMAT_MONO16 = 0x1101, AL_FORMAT_STEREO16 = 0x1103,
        AL_TRUE = 1, AL_FALSE = 0, AL_NO_ERROR = 0;
    public static int alGenBuffers() { return 0; }
    public static int alGenSources() { return 0; }
    public static void alBufferData(int b, int f, ShortBuffer d, int rate) { }
    public static void alSourcei(int s, int p, int v) { }
    public static void alSourcef(int s, int p, float v) { }
    public static void alSourcePlay(int s) { }
    public static void alSourceStop(int s) { }
    public static void alSourcePause(int s) { }
    public static int alGetSourcei(int s, int p) { return 0; }
    public static void alDeleteSources(int s) { }
    public static void alDeleteBuffers(int b) { }
    public static int alGetError() { return 0; }
}
