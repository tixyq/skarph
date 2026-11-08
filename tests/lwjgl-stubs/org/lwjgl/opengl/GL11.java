package org.lwjgl.opengl;
import java.nio.ByteBuffer;
public class GL11 {
    public static final int GL_UNPACK_ALIGNMENT = 0xCF5, GL_RGBA = 0x1908, GL_UNSIGNED_BYTE = 0x1401, GL_TEXTURE_2D = 0xDE1;
    public static int glGenTextures() { return 0; }
    public static void glBindTexture(int t, int tex) { }
    public static void glDeleteTextures(int tex) { }
    public static void glTexParameteri(int t, int p, int v) { }
    public static void glPixelStorei(int p, int v) { }
    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) { }
    public static void glDrawElements(int mode, int count, int type, long indices) { }
    public static void glEnable(int c) { }
    public static void glDisable(int c) { }
    public static void glBlendFunc(int a, int b) { }
    public static void glDepthMask(boolean b) { }
    public static void glFrontFace(int m) { }
    public static void glCullFace(int m) { }
    public static void glViewport(int x, int y, int w, int h) { }
    public static void glClearColor(float r, float g, float b, float a) { }
    public static void glClear(int m) { }
    public static int glGetError() { return 0; }
    public static String glGetString(int n) { return null; }
}
