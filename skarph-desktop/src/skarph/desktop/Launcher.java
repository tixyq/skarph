// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The Main-Class of a portable jar. It runs before any LWJGL class exists: it prepares the libraries (download on the
 * first start, then a cache), puts the LWJGL jars and the program on a class loader, points LWJGL to the native
 * libraries and starts skarph.desktop.host.Host there. On macOS it first starts a second Java process with
 * -XstartOnFirstThread, which the window system needs.
 *
 * <pre>
 *   java -jar program.jar                      start
 *   java -jar program.jar --setup-only         only prepare the libraries (installers, offline preparation)
 *   java -jar program.jar --check-links        ask all servers for all files
 *   java -jar program.jar --game=pkg.MyGame    the Game class, if the manifest has no Skarph-Game
 * </pre>
 * Properties: -Dskarph.cache=FOLDER, -Dskarph.libs=FOLDER (ready made jars, also used without a network),
 * -Dskarph.mirror=URL (another Maven mirror, tried first), -Dskarph.assets=FOLDER, -Dskarph.vsync=false.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (SetupException e) {
            Errors.show("Skarph cannot start", e.getMessage());
            code = 1;
        } catch (Throwable t) {
            t.printStackTrace();
            Errors.show("Skarph cannot start", String.valueOf(t));
            code = 1;
        }
        System.exit(code);
    }

    private static int run(String[] args) throws Exception {
        boolean setupOnly = false;
        boolean checkLinks = false;
        List<String> rest = new ArrayList<String>();
        for (String a : args) {
            if (a.equals("--setup-only")) {
                setupOnly = true;
            } else if (a.equals("--check-links")) {
                checkLinks = true;
            } else if (a.equals("--help") || a.equals("-h")) {
                System.out.println("java -jar PROGRAM.jar [--setup-only] [--check-links] [--game=CLASS]");
                return 0;
            } else {
                rest.add(a);
            }
        }
        if (checkLinks) {
            return LinkCheck.run(new HttpTransport(), Libraries.mirrors(), System.out);
        }
        Platform platform = Platform.detect();
        boolean macParent = platform.os == Platform.Os.MACOS && System.getProperty("skarph.child") == null
                && !startedOnFirstThread();
        Progress progress = setupOnly ? new ConsoleProgress() : new LazyProgress(!(platform.os == Platform.Os.MACOS
                && System.getProperty("skarph.child") != null));
        Setup.Result libs;
        try {
            libs = Setup.ensure(platform, AppDirs.cacheDir(platform), progress, localLibraryFolders());
        } finally {
            progress.done();
        }
        if (setupOnly) {
            StringBuilder cp = new StringBuilder();
            for (File f : libs.jars) {
                if (cp.length() > 0) {
                    cp.append(File.pathSeparatorChar);
                }
                cp.append(f.getAbsolutePath());
            }
            System.out.println(cp);
            return 0;
        }
        for (String a : rest) {
            if (a.startsWith("--game=")) {
                System.setProperty("skarph.game", a.substring("--game=".length()));
            }
        }
        if (System.getProperty("skarph.game") == null) {
            String fromManifest = gameFromManifest();
            if (fromManifest != null) {
                System.setProperty("skarph.game", fromManifest);
            }
        }
        if (macParent) {
            return restartOnFirstThread(args);
        }
        return runHost(libs, rest);
    }

    private static int runHost(Setup.Result libs, List<String> rest) throws Exception {
        System.setProperty("org.lwjgl.librarypath", libs.nativesDir.getAbsolutePath());
        List<URL> urls = new ArrayList<URL>();
        for (File f : libs.jars) {
            urls.add(f.toURI().toURL());
        }
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.length() > 0) {
                urls.add(new File(entry).toURI().toURL());
            }
        }
        // parent = the platform loader: the program classes and the LWJGL classes are all loaded by this one loader
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader().getParent());
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class<?> host = Class.forName("skarph.desktop.host.Host", true, loader);
            host.getMethod("main", String[].class).invoke(null, (Object) rest.toArray(new String[0]));
            return 0;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
    }

    /** macOS: the window system must run on the first thread of the process, and only a JVM flag can do that. */
    private static int restartOnFirstThread(String[] args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(new File(System.getProperty("java.home"), "bin"), "java").getPath());
        cmd.add("-XstartOnFirstThread");
        cmd.add("-Dskarph.child=1");
        String game = System.getProperty("skarph.game");
        if (game != null) {
            cmd.add("-Dskarph.game=" + game);
        }
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!a.startsWith("-Dskarph.game=")) {
                cmd.add(a);
            }
        }
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Launcher.class.getName());
        for (String a : args) {
            cmd.add(a);
        }
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }

    private static boolean startedOnFirstThread() {
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.equals("-XstartOnFirstThread")) {
                return true;
            }
        }
        return false;
    }

    /** -Dskarph.libs=FOLDER, and a folder "skarph-libs" next to the jar (or the current directory). */
    private static List<File> localLibraryFolders() {
        List<File> list = new ArrayList<File>();
        String p = System.getProperty("skarph.libs");
        if (p != null && p.length() > 0) {
            list.add(new File(p));
        }
        File jar = ownJar();
        if (jar != null && jar.getParentFile() != null) {
            list.add(new File(jar.getParentFile(), "skarph-libs"));
        }
        list.add(new File("skarph-libs"));
        return list;
    }

    private static File ownJar() {
        try {
            File f = new File(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String gameFromManifest() {
        File jar = ownJar();
        if (jar == null) {
            return null;
        }
        try {
            JarFile jf = new JarFile(jar);
            try {
                Manifest m = jf.getManifest();
                return m == null ? null : m.getMainAttributes().getValue("Skarph-Game");
            } finally {
                jf.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Shows a window (or console lines) only when there is something to do: a start from the cache shows nothing. */
    private static final class LazyProgress implements Progress {
        private final boolean allowWindow;
        private Progress real;

        LazyProgress(boolean allowWindow) {
            this.allowWindow = allowWindow;
        }

        private Progress real() {
            if (real == null) {
                boolean window = allowWindow && System.console() == null && !java.awt.GraphicsEnvironment.isHeadless();
                if (window) {
                    try {
                        real = new SwingProgress("Skarph: first start");
                    } catch (Throwable t) {
                        real = null;
                    }
                }
                if (real == null) {
                    real = new ConsoleProgress();
                }
            }
            return real;
        }

        public void status(String text) {
            real().status(text);
        }

        public void progress(long done, long total) {
            real().progress(done, total);
        }

        public void done() {
            if (real != null) {
                real.done();
            }
        }
    }
}
