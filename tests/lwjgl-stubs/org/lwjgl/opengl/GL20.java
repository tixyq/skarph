package org.lwjgl.opengl;
public class GL20 {
    public static int glCreateShader(int t) { return 0; }
    public static void glShaderSource(int s, CharSequence src) { }
    public static void glCompileShader(int s) { }
    public static int glGetShaderi(int s, int p) { return 0; }
    public static String glGetShaderInfoLog(int s) { return null; }
    public static void glDeleteShader(int s) { }
    public static int glCreateProgram() { return 0; }
    public static void glAttachShader(int p, int s) { }
    public static void glBindAttribLocation(int p, int i, CharSequence n) { }
    public static void glLinkProgram(int p) { }
    public static int glGetProgrami(int p, int n) { return 0; }
    public static String glGetProgramInfoLog(int p) { return null; }
    public static void glUseProgram(int p) { }
    public static void glDeleteProgram(int p) { }
    public static int glGetUniformLocation(int p, CharSequence n) { return 0; }
    public static void glUniform1i(int l, int v) { }
    public static void glUniform3f(int l, float x, float y, float z) { }
    public static void glUniformMatrix3fv(int l, boolean t, float[] v) { }
    public static void glUniformMatrix4fv(int l, boolean t, float[] v) { }
    public static void glEnableVertexAttribArray(int i) { }
    public static void glDisableVertexAttribArray(int i) { }
    public static void glVertexAttribPointer(int i, int s, int t, boolean n, int stride, long p) { }
}
