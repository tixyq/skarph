package org.lwjgl.glfw;
import java.nio.ByteBuffer;
public class GLFWImage {
    public GLFWImage set(int width, int height, ByteBuffer pixels) { return this; }
    public static Buffer malloc(int capacity) { return new Buffer(capacity); }
    public static class Buffer {
        private final GLFWImage[] items;
        Buffer(int n) { items = new GLFWImage[n]; for (int i = 0; i < n; i++) items[i] = new GLFWImage(); }
        public GLFWImage get(int i) { return items[i]; }
        public void free() { }
    }
}
