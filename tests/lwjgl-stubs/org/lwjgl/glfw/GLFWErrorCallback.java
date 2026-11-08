package org.lwjgl.glfw;
public abstract class GLFWErrorCallback {
    public static GLFWErrorCallback createPrint(java.io.PrintStream s) { return null; }
    public GLFWErrorCallback set() { return this; }
    public void free() { }
}
