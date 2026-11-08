// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop.host;

import static org.lwjgl.glfw.GLFW.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import skarph.android.GLES2Renderer;
import skarph.core.Assets;
import skarph.core.Engine;
import skarph.core.InputEvent;
import skarph.desktop.AppDirs;
import skarph.desktop.ConsoleLog;
import skarph.desktop.DesktopAssets;
import skarph.desktop.DesktopInput;
import skarph.desktop.DesktopStorage;
import skarph.desktop.Game;
import skarph.desktop.GameConfig;
import skarph.desktop.InputAware;
import skarph.desktop.Platform;
import skarph.desktop.WindowIcon;

/**
 * The window, the OpenGL context, the loop. Runs on the main thread, which GLFW requires. The mouse is a single finger
 * (left button = touch, pointer 0) while the cursor is free. A game that implements InputAware also gets the keys and,
 * on request, a captured cursor for mouse look (DesktopInput). Keys: Escape closes (if the game allows it), F11 or
 * Alt+Enter toggles full screen.
 * Closing, minimizing and restoring go through Engine.pause() / resume(), like Activity.onPause() on Android.
 * Started by skarph.desktop.Launcher, after the libraries are on the class path.
 */
public final class Host {
    private final Game game;
    private final GameConfig config;
    private final ConsoleLog log = new ConsoleLog();
    private final Engine engine = new Engine();
    private final DesktopInput input = new DesktopInput();

    private long window;
    private GLES2Renderer renderer;
    private OpenAlAudio audio;
    private String failure;
    private boolean vsync;
    private boolean iconified;
    private boolean fullscreen;
    private boolean mouseDown;
    private boolean rawMouse;
    private double cursorX;
    private double cursorY;
    private int framebufferWidth;
    private int framebufferHeight;
    private int savedX;
    private int savedY;
    private int savedWidth;
    private int savedHeight;
    private final int[] w1 = new int[1];
    private final int[] h1 = new int[1];

    private Host(Game game) {
        this.game = game;
        GameConfig c = game.config();
        this.config = c != null ? c : new GameConfig();
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            System.setProperty("java.awt.headless", "true"); // ImageIO must not start AppKit next to GLFW
        }
        String name = System.getProperty("skarph.game");
        if (name == null || name.length() == 0) {
            throw new IllegalStateException("No game class. Put Skarph-Game: your.package.YourGame into the jar manifest,"
                    + " or start with --game=your.package.YourGame");
        }
        Game game = (Game) Class.forName(name, true, Host.class.getClassLoader()).getDeclaredConstructor().newInstance();
        new Host(game).run();
    }

    private void run() throws Exception {
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("The window system cannot be started (is there a screen?).");
        }
        try {
            createWindow();
            start();
            loop();
        } finally {
            shutdown();
            glfwTerminate();
            errors.free();
        }
        if (failure != null) {
            throw new IllegalStateException("The graphics do not work on this computer: " + failure
                    + "\nTry to update the graphics driver.");
        }
    }

    private void createWindow() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        window = glfwCreateWindow(config.width, config.height, config.title, 0L, 0L);
        if (window == 0L) {
            throw new IllegalStateException("Cannot open a window with OpenGL 2.0. Update the graphics driver.");
        }
        setWindowIcon();
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode mode = monitor != 0L ? glfwGetVideoMode(monitor) : null;
        if (mode != null) {
            glfwSetWindowPos(window, Math.max(0, (mode.width() - config.width) / 2), Math.max(0, (mode.height() - config.height) / 3));
        }
        glfwMakeContextCurrent(window);
        GLCapabilities caps = GL.createCapabilities();
        if (!caps.OpenGL20) {
            throw new IllegalStateException("OpenGL 2.0 or newer is needed.");
        }
        String v = System.getProperty("skarph.vsync");
        vsync = v != null ? !v.equalsIgnoreCase("false") : config.vsync;
        glfwSwapInterval(vsync ? 1 : 0);
    }

    /** Sets icon.png (copied into the jar by the build) as the window icon; systems without window icons (Wayland) ignore it. */
    private void setWindowIcon() {
        try {
            List<BufferedImage> icons = WindowIcon.load();
            if (icons.isEmpty()) {
                return;
            }
            // keep the pixel buffers reachable until glfwSetWindowIcon has copied them; the struct holds only addresses
            List<java.nio.ByteBuffer> pixels = new java.util.ArrayList<java.nio.ByteBuffer>();
            GLFWImage.Buffer images = GLFWImage.malloc(icons.size());
            try {
                for (int i = 0; i < icons.size(); i++) {
                    BufferedImage img = icons.get(i);
                    java.nio.ByteBuffer px = WindowIcon.rgba(img);
                    pixels.add(px);
                    images.get(i).set(img.getWidth(), img.getHeight(), px);
                }
                glfwSetWindowIcon(window, images);
            } finally {
                images.free();
            }
            WindowIcon.applyTaskbar();
        } catch (Throwable ignored) {
            // a missing icon must not stop the start
        }
    }

    private void start() {
        Platform platform;
        try {
            platform = Platform.detect();
        } catch (skarph.desktop.SetupException e) {
            throw new IllegalStateException(e.getMessage());
        }
        String assetsOverride = System.getProperty("skarph.assets");
        Assets assets = new DesktopAssets(Host.class.getClassLoader(),
                assetsOverride != null ? new File(assetsOverride) : null, new File("assets"));
        engine.setAssets(assets);
        engine.setStorage(new DesktopStorage(AppDirs.dataDir(platform, config.appName)));
        audio = OpenAlAudio.create(assets, log);
        if (audio != null) {
            engine.setAudio(audio);
        }
        renderer = new GLES2Renderer(engine, new LwjglGl2(), new ImageIoLoader(assets), log,
                new GLES2Renderer.FailureListener() {
                    public void onFailure(String reason) {
                        failure = reason;
                        glfwSetWindowShouldClose(window, true);
                    }
                });
        if (game instanceof InputAware) {
            ((InputAware) game).setInput(input);
        }
        engine.setScreen(game.createScreen(engine));
        renderer.onSurfaceCreated();
        glfwGetFramebufferSize(window, w1, h1);
        framebufferWidth = w1[0];
        framebufferHeight = h1[0];
        renderer.onSurfaceChanged(framebufferWidth, framebufferHeight);
        installCallbacks();
        double[] cx = new double[1];
        double[] cy = new double[1];
        glfwGetCursorPos(window, cx, cy);
        cursorX = cx[0];
        cursorY = cy[0];
        if (config.fullscreen) {
            toggleFullscreen();
        }
        glfwShowWindow(window);
    }

    private void installCallbacks() {
        glfwSetFramebufferSizeCallback(window, (win, width, height) -> {
            if (width > 0 && height > 0) {
                framebufferWidth = width;
                framebufferHeight = height;
                renderer.onSurfaceChanged(width, height);
            }
        });
        glfwSetCursorPosCallback(window, (win, x, y) -> {
            cursorX = x;
            cursorY = y;
            input.cursorMoved(x, y);
            if (mouseDown && !input.isCaptureActive()) {
                push(InputEvent.MOVE);
            }
        });
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button != GLFW_MOUSE_BUTTON_LEFT) {
                return;
            }
            if (action == GLFW_PRESS && !mouseDown && !input.isCaptureActive()) {
                mouseDown = true;
                push(InputEvent.DOWN);
            } else if (action == GLFW_RELEASE && mouseDown) {
                mouseDown = false;
                push(InputEvent.UP);
            }
        });
        final boolean keyDebug = System.getenv("SKARPH_KEYDEBUG") != null;
        glfwSetKeyCallback(window, (win, key0, scancode, action, mods) -> {
            int key = key0 != GLFW_KEY_UNKNOWN ? key0 : keyFromX11Scancode(scancode);
            if (keyDebug) {
                System.err.println("key=" + key0 + " (used " + key + ") scancode=" + scancode + " action=" + action);
            }
            if (action == GLFW_PRESS) {
                input.keyEvent(key, true);
            } else if (action == GLFW_RELEASE) {
                input.keyEvent(key, false);
            }
            if (action != GLFW_PRESS) {
                return;
            }
            if (key == GLFW_KEY_ESCAPE && config.escapeCloses) {
                glfwSetWindowShouldClose(window, true);
            } else if (key == GLFW_KEY_F11 || (key == GLFW_KEY_ENTER && (mods & GLFW_MOD_ALT) != 0)) {
                toggleFullscreen();
            }
        });
        glfwSetWindowIconifyCallback(window, (win, minimized) -> {
            iconified = minimized;
            if (minimized) {
                engine.pause();
            } else {
                engine.resume();
            }
        });
        glfwSetWindowFocusCallback(window, (win, focused) -> {
            input.focusChanged(focused);
            if (!focused && mouseDown) {
                mouseDown = false;
                push(InputEvent.UP); // the button release goes to another window
            }
        });
    }

    /**
     * GLFW reports GLFW_KEY_UNKNOWN on X servers without a usable xkb layout (Termux:X11). On X11 the scancode is the X keycode
     * (evdev + 8), independent of the layout, so W, A, S, D and the arrows are taken from it.
     */
    private static int keyFromX11Scancode(int sc) {
        switch (sc) {
            case 25: return GLFW_KEY_W;
            case 38: return GLFW_KEY_A;
            case 39: return GLFW_KEY_S;
            case 40: return GLFW_KEY_D;
            case 111: return GLFW_KEY_UP;
            case 116: return GLFW_KEY_DOWN;
            case 113: return GLFW_KEY_LEFT;
            case 114: return GLFW_KEY_RIGHT;
            default: return GLFW_KEY_UNKNOWN;
        }
    }

    /** Pushes the mouse position, in pixels of the framebuffer (a high-DPI screen has more pixels than screen units). */
    private void push(int type) {
        glfwGetWindowSize(window, w1, h1);
        float sx = w1[0] > 0 ? (float) framebufferWidth / w1[0] : 1f;
        float sy = h1[0] > 0 ? (float) framebufferHeight / h1[0] : 1f;
        engine.input.push(type, 0, (float) cursorX * sx, (float) cursorY * sy);
    }

    /** Hides and locks the cursor (mouse look) or gives it back, when the game asked for a change during the frame. */
    private void applyCapture() {
        if (!input.syncCapture()) {
            return;
        }
        if (input.isCaptureActive()) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            if (glfwRawMouseMotionSupported()) {
                glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE); // movement without the system's pointer acceleration
                rawMouse = true;
            }
        } else {
            if (rawMouse) {
                glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_FALSE);
                rawMouse = false;
            }
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL); // GLFW puts the cursor back where it was
        }
    }

    private void toggleFullscreen() {
        if (!fullscreen) {
            long monitor = glfwGetPrimaryMonitor();
            GLFWVidMode mode = monitor != 0L ? glfwGetVideoMode(monitor) : null;
            if (mode == null) {
                return;
            }
            int[] x = new int[1];
            int[] y = new int[1];
            glfwGetWindowPos(window, x, y);
            glfwGetWindowSize(window, w1, h1);
            savedX = x[0];
            savedY = y[0];
            savedWidth = w1[0];
            savedHeight = h1[0];
            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
            fullscreen = true;
        } else {
            glfwSetWindowMonitor(window, 0L, savedX, savedY, savedWidth, savedHeight, GLFW_DONT_CARE);
            fullscreen = false;
        }
        glfwSwapInterval(vsync ? 1 : 0); // some drivers forget it when the mode changes
    }

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            if (iconified) {
                glfwWaitEventsTimeout(0.1);
                continue;
            }
            glfwPollEvents();
            if (iconified || glfwWindowShouldClose(window)) {
                continue;
            }
            renderer.onDrawFrame();
            applyCapture();
            if (audio != null) {
                audio.pump();
            }
            glfwSwapBuffers(window);
            if (engine.stats.changed) {
                engine.stats.changed = false;
                if (config.fpsInTitle) {
                    glfwSetWindowTitle(window, config.title + "  " + engine.stats.fps + " fps, " + engine.stats.tps + " tps");
                }
            }
        }
    }

    private void shutdown() {
        try {
            engine.pause(); // the screen saves its settings here
        } catch (RuntimeException e) {
            log.error("pause failed: " + e);
        }
        if (audio != null) {
            audio.release();
        }
        if (window != 0L) {
            Callbacks.glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
        }
    }
}
