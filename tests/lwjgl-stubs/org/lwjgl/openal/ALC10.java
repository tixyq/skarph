package org.lwjgl.openal;
import java.nio.*;
public class ALC10 {
    public static long alcOpenDevice(ByteBuffer name) { return 0; }
    public static long alcOpenDevice(CharSequence name) { return 0; }
    public static long alcCreateContext(long d, IntBuffer a) { return 0; }
    public static long alcCreateContext(long d, int[] a) { return 0; }
    public static boolean alcMakeContextCurrent(long c) { return false; }
    public static void alcDestroyContext(long c) { }
    public static boolean alcCloseDevice(long d) { return false; }
}
