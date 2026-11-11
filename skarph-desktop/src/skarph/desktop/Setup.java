// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Makes sure that the LWJGL jars and the native libraries of this system are on disk, downloading what is missing.
 * Everything lives in one cache folder that all Skarph programs share, so it is downloaded once per computer:
 * <pre>
 *   lwjgl-3.3.6/jars/NAME.jar          the verified jars (and NAME.jar.sha1 with the sum they were verified with)
 *   lwjgl-3.3.6/windows-x64/natives/   the .dll / .so / .dylib files taken out of the natives jars
 *   lwjgl-3.3.6/windows-x64/ready      written last: its presence means that the folder above is complete
 * </pre>
 * Files that the person put in a folder (-Dskarph.libs=dir, or a folder skarph-libs next to the program) are used before
 * any download, so the program also starts without a network.
 */
public final class Setup {
    private Setup() {
    }

    public static final class Result {
        /** The LWJGL API jars, in the order they are needed on the class path. */
        public final List<File> jars;
        /** The folder for -Dorg.lwjgl.librarypath. */
        public final File nativesDir;

        Result(List<File> jars, File nativesDir) {
            this.jars = jars;
            this.nativesDir = nativesDir;
        }
    }

    /** Settings that tests change; the program uses ensure(platform, cacheRoot, progress, localDirs). */
    public static Result ensure(Platform p, File cacheRoot, Progress progress, List<File> localDirs) throws SetupException {
        return ensure(p, cacheRoot, new HttpTransport(), Libraries.mirrors(), Pins.load(), localDirs, 2, 1500L, progress);
    }

    public static Result ensure(Platform p, File cacheRoot, Transport transport, List<String> mirrors,
                                Map<String, String> pins, List<File> localDirs, int attempts, long retryDelayMs,
                                Progress progress) throws SetupException {
        File base = new File(cacheRoot, "lwjgl-" + Libraries.VERSION);
        File jarsDir = new File(base, "jars");
        File platformDir = new File(base, p.id());
        File nativesDir = new File(platformDir, "natives");
        File ready = new File(platformDir, "ready");
        List<Libraries.Artifact> artifacts = Libraries.forPlatform(p);

        Result quick = readyResult(artifacts, jarsDir, nativesDir, ready);
        if (quick != null) {
            return quick;
        }
        if (!jarsDir.isDirectory() && !jarsDir.mkdirs() || !nativesDir.isDirectory() && !nativesDir.mkdirs()) {
            throw new SetupException("Cannot create the folder " + base + ". Set -Dskarph.cache=FOLDER to use another one.");
        }
        RandomAccessFile lockFile = null;
        FileLock lock = null;
        try {
            lockFile = new RandomAccessFile(new File(base, "lock"), "rw");
            lock = lockFile.getChannel().lock(); // two programs starting together: the second waits, then finds it ready
            Result again = readyResult(artifacts, jarsDir, nativesDir, ready);
            if (again != null) {
                return again;
            }
            Downloader dl = new Downloader(transport, mirrors, pins, attempts, retryDelayMs);
            List<File> apiJars = new ArrayList<File>();
            int index = 0;
            for (Libraries.Artifact a : artifacts) {
                index++;
                progress.status("Preparing " + a + " (" + index + " of " + artifacts.size() + ")");
                File jar = new File(jarsDir, a.fileName());
                obtain(a, jar, dl, pins, localDirs, progress);
                if (a.isNatives()) {
                    int n = extractNatives(jar, nativesDir);
                    if (n == 0) {
                        jar.delete();
                        throw new SetupException(a + " has no native libraries in it. It was deleted: start the program again.");
                    }
                } else {
                    apiJars.add(jar);
                }
            }
            writeReady(ready, artifacts, jarsDir);
            return new Result(apiJars, nativesDir);
        } catch (IOException e) {
            throw new SetupException("Cannot prepare the libraries in " + base + ": " + e.getMessage(), e);
        } finally {
            try {
                if (lock != null) {
                    lock.release();
                }
                if (lockFile != null) {
                    lockFile.close();
                }
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    /** Puts a verified copy of the file at jar (or leaves it, if it is already there and still has its sum). */
    private static void obtain(Libraries.Artifact a, File jar, Downloader dl, Map<String, String> pins,
                               List<File> localDirs, Progress progress) throws SetupException, IOException {
        File sumFile = new File(jar.getPath() + ".sha1");
        if (jar.isFile() && sumFile.isFile()) {
            String stored = readText(sumFile).trim();
            if (Pins.isSha1(stored) && stored.equals(Downloader.sha1Of(jar)) && (!pins.containsKey(a.fileName())
                    || pins.get(a.fileName()).equals(stored))) {
                return; // downloaded and verified earlier, and not damaged since
            }
            jar.delete();
        }
        for (File dir : localDirs) {
            File local = new File(dir, a.fileName());
            if (local.isFile()) {
                String sum = Downloader.sha1Of(local);
                String pinned = pins.get(a.fileName());
                if (pinned == null || pinned.equals(sum)) { // a file that the person put there is trusted unless a pin says no
                    copy(local, jar);
                    writeText(sumFile, sum);
                    return;
                }
            }
        }
        StringBuilder log = new StringBuilder();
        String expected = dl.expectedSha1(a, log);
        dl.download(a, expected, jar, progress);
        writeText(sumFile, expected);
    }

    private static Result readyResult(List<Libraries.Artifact> artifacts, File jarsDir, File nativesDir, File ready) {
        if (!ready.isFile()) {
            return null;
        }
        List<File> jars = new ArrayList<File>();
        for (Libraries.Artifact a : artifacts) {
            File f = new File(jarsDir, a.fileName());
            if (!f.isFile()) {
                return null;
            }
            if (!a.isNatives()) {
                jars.add(f);
            }
        }
        try {
            String[] lines = readText(ready).split("\n");
            for (String line : lines) {
                int colon = line.lastIndexOf(':');
                if (line.trim().length() == 0 || colon < 0) {
                    continue;
                }
                File f = new File(nativesDir, line.substring(0, colon));
                if (!f.isFile() || f.length() != Long.parseLong(line.substring(colon + 1).trim())) {
                    return null;
                }
            }
        } catch (IOException e) {
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return new Result(jars, nativesDir);
    }

    private static void writeReady(File ready, List<Libraries.Artifact> artifacts, File jarsDir) throws IOException {
        File nativesDir = new File(ready.getParentFile(), "natives");
        StringBuilder sb = new StringBuilder();
        File[] files = nativesDir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                if (f.isFile()) {
                    sb.append(f.getName()).append(':').append(f.length()).append('\n');
                }
            }
        }
        writeText(ready, sb.toString());
    }

    /**
     * Takes the .dll / .so / .dylib files out of a natives jar into dir, flat (only the file name is used, so no entry
     * can write outside dir). @return how many files
     */
    static int extractNatives(File jar, File dir) throws IOException {
        int count = 0;
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                String lower = base.toLowerCase(java.util.Locale.ROOT);
                if (!(lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib") || lower.endsWith(".jnilib"))
                        || base.indexOf('\\') >= 0 || base.startsWith(".")) {
                    continue;
                }
                File target = new File(dir, base);
                File tmp = new File(dir, base + ".tmp");
                InputStream in = zip.getInputStream(e);
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                    out.close();
                }
                if (target.exists() && !target.delete()) {
                    throw new IOException("cannot replace " + target);
                }
                if (!tmp.renameTo(target)) {
                    throw new IOException("cannot create " + target);
                }
                count++;
            }
        } finally {
            zip.close();
        }
        return count;
    }

    private static void copy(File from, File to) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
    }

    private static String readText(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeText(File f, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
