package org.lwjgl.glfw;
/** Compile-time stand-in for the LWJGL 3.3 API that skarph-desktop uses (signatures only). Never shipped. */
public final class GLFW {
    public static final int GLFW_FALSE = 0, GLFW_TRUE = 1, GLFW_VISIBLE = 0x20004, GLFW_MOUSE_BUTTON_LEFT = 0,
        GLFW_PRESS = 1, GLFW_RELEASE = 0, GLFW_KEY_UNKNOWN = -1, GLFW_KEY_W = 87, GLFW_KEY_A = 65, GLFW_KEY_S = 83, GLFW_KEY_D = 68,
        GLFW_KEY_UP = 265, GLFW_KEY_DOWN = 264, GLFW_KEY_LEFT = 263, GLFW_KEY_RIGHT = 262, GLFW_KEY_ESCAPE = 256, GLFW_KEY_ENTER = 257, GLFW_KEY_F11 = 300,
        GLFW_MOD_ALT = 4, GLFW_DONT_CARE = -1, GLFW_CURSOR = 0x33001, GLFW_RAW_MOUSE_MOTION = 0x33005,
        GLFW_CURSOR_NORMAL = 0x34001, GLFW_CURSOR_DISABLED = 0x34003;
    public static boolean glfwInit() { return false; }
    public static void glfwTerminate() { }
    public static void glfwDefaultWindowHints() { }
    public static void glfwWindowHint(int hint, int value) { }
    public static long glfwCreateWindow(int w, int h, CharSequence title, long monitor, long share) { return 0; }
    public static long glfwGetPrimaryMonitor() { return 0; }
    public static GLFWVidMode glfwGetVideoMode(long monitor) { return null; }
    public static void glfwSetWindowPos(long window, int x, int y) { }
    public static void glfwMakeContextCurrent(long window) { }
    public static void glfwSwapInterval(int interval) { }
    public static void glfwSetWindowShouldClose(long window, boolean value) { }
    public static boolean glfwWindowShouldClose(long window) { return false; }
    public static void glfwGetFramebufferSize(long window, int[] w, int[] h) { }
    public static void glfwGetWindowSize(long window, int[] w, int[] h) { }
    public static void glfwGetWindowPos(long window, int[] x, int[] y) { }
    public static void glfwGetCursorPos(long window, double[] x, double[] y) { }
    public static void glfwSetWindowIcon(long window, GLFWImage.Buffer images) { }
    public static void glfwSetInputMode(long window, int mode, int value) { }
    public static boolean glfwRawMouseMotionSupported() { return false; }
    public static void glfwShowWindow(long window) { }
    public static void glfwDestroyWindow(long window) { }
    public static void glfwPollEvents() { }
    public static void glfwWaitEventsTimeout(double timeout) { }
    public static void glfwSwapBuffers(long window) { }
    public static void glfwSetWindowTitle(long window, CharSequence title) { }
    public static void glfwSetWindowMonitor(long window, long monitor, int x, int y, int w, int h, int refresh) { }
    public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI cb) { return null; }
    public static GLFWCursorPosCallback glfwSetCursorPosCallback(long window, GLFWCursorPosCallbackI cb) { return null; }
    public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long window, GLFWMouseButtonCallbackI cb) { return null; }
    public static GLFWKeyCallback glfwSetKeyCallback(long window, GLFWKeyCallbackI cb) { return null; }
    public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI cb) { return null; }
    public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long window, GLFWWindowFocusCallbackI cb) { return null; }
}
