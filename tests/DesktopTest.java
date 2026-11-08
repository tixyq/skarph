// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpServer;

import skarph.android.GLES2Shaders;
import skarph.desktop.*;

/** Headless tests of the desktop layer: platforms, links, download and verification, cache, assets, storage, wav, png. */
public class DesktopTest {
    static int failed;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static File tmp() throws IOException {
        return Files.createTempDirectory("skarph-test").toFile();
    }

    static byte[] zip(String... nameAndContent) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        for (int i = 0; i < nameAndContent.length; i += 2) {
            z.putNextEntry(new ZipEntry(nameAndContent[i]));
            z.write(nameAndContent[i + 1].getBytes("UTF-8"));
            z.closeEntry();
        }
        z.close();
        return bo.toByteArray();
    }

    static String sha1(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** A fake Maven repository: url -> bytes; counts requests; mirror prefixes can be broken in several ways. */
    static final class FakeRepo implements Transport {
        final Map<String, byte[]> files = new HashMap<String, byte[]>();
        final Set<String> down = new HashSet<String>();      // mirrors that refuse everything
        final Set<String> corrupt = new HashSet<String>();   // mirrors that serve damaged jars
        final Set<String> cutOff = new HashSet<String>();    // mirrors whose stream ends early
        boolean allDown;
        int requests;

        void publish(String mirror, Platform p) throws Exception {
            for (Libraries.Artifact a : Libraries.forPlatform(p)) {
                byte[] data = a.isNatives()
                        ? zip(p.os.id + "/" + p.arch.id + "/org/lwjgl/" + (a.module.equals("lwjgl") ? "" : a.module.substring(6) + "/") + lib(p, a),
                              "native of " + a, p.os.id + "/" + p.arch.id + "/org/lwjgl/" + a.module + ".sha1", "ignored",
                              "../../evil.so", "must stay inside")
                        : zip("META-INF/MANIFEST.MF", "api of " + a);
                files.put(a.url(mirror), data);
                files.put(a.url(mirror) + ".sha1", (sha1(data) + "\n").getBytes("US-ASCII"));
            }
        }

        static String lib(Platform p, Libraries.Artifact a) {
            String ext = p.os == Platform.Os.WINDOWS ? ".dll" : p.os == Platform.Os.MACOS ? ".dylib" : ".so";
            String prefix = p.os == Platform.Os.WINDOWS ? "" : "lib";
            return prefix + a.module.replace('-', '_') + ext;
        }

        public Response open(String url) throws IOException {
            requests++;
            if (allDown) throw new IOException("network is down");
            for (String m : down) if (url.startsWith(m)) throw new IOException("HTTP 503");
            byte[] d = files.get(url);
            if (d == null) throw new IOException("HTTP 404");
            for (String m : corrupt) if (url.startsWith(m) && !url.endsWith(".sha1")) { d = d.clone(); d[d.length / 2] ^= 0x55; }
            for (String m : cutOff) {
                if (url.startsWith(m) && !url.endsWith(".sha1")) {
                    return new Response(new ByteArrayInputStream(d, 0, d.length / 2), d.length);
                }
            }
            return new Response(new ByteArrayInputStream(d), d.length);
        }
    }

    static final class Quiet implements Progress {
        int statuses;
        public void status(String t) { statuses++; }
        public void progress(long d, long t) { }
        public void done() { }
    }

    static final String M1 = "https://one.test/maven2/";
    static final String M2 = "https://two.test/maven2/";
    static final String M3 = "https://three.test/maven2/";

    static Setup.Result run(Platform p, File cache, Transport t, Map<String, String> pins, List<File> local) throws SetupException {
        return Setup.ensure(p, cache, t, Arrays.asList(M1, M2, M3), pins, local, 2, 0L, new Quiet());
    }

    static boolean fails(Platform p, File cache, Transport t, Map<String, String> pins, String mustContain) throws Exception {
        try {
            run(p, cache, t, pins, new ArrayList<File>());
            return false;
        } catch (SetupException e) {
            return e.getMessage().contains(mustContain);
        }
    }

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.0001f;
    }

    static void iconTests() throws Exception {
        byte[] png = java.nio.file.Files.readAllBytes(new java.io.File("icon.png").toPath());
        java.util.List<java.awt.image.BufferedImage> icons = WindowIcon.scaled(png);
        check(icons.size() == WindowIcon.SIZES.length, "icon: icon.png gives every size");
        boolean sizesOk = true;
        for (int i = 0; i < icons.size(); i++) {
            sizesOk &= icons.get(i).getWidth() == WindowIcon.SIZES[i] && icons.get(i).getHeight() == WindowIcon.SIZES[i];
        }
        check(sizesOk, "icon: the sizes are the asked ones");
        java.nio.ByteBuffer px = WindowIcon.rgba(icons.get(icons.size() - 1));
        check(px.remaining() == 16 * 16 * 4 && px.position() == 0, "icon: RGBA bytes, 4 per pixel");
        check(WindowIcon.scaled(new byte[] {1, 2, 3}).isEmpty(), "icon: bytes that are no image give no icon, no error");
    }

    static void inputTests() {
        DesktopInput in = new DesktopInput();
        check(!in.isKeyDown(DesktopInput.KEY_W), "input: no key is down at the start");
        in.keyEvent(DesktopInput.KEY_W, true);
        in.keyEvent(DesktopInput.KEY_LEFT, true);
        check(in.isKeyDown(DesktopInput.KEY_W) && in.isKeyDown(DesktopInput.KEY_LEFT) && !in.isKeyDown(DesktopInput.KEY_A),
                "input: pressed keys are down, others are not");
        in.keyEvent(DesktopInput.KEY_W, false);
        check(!in.isKeyDown(DesktopInput.KEY_W), "input: a released key is up");
        in.keyEvent(-1, true);
        in.keyEvent(100000, true);
        check(!in.isKeyDown(-1) && !in.isKeyDown(100000), "input: key codes out of range are ignored");

        in.cursorMoved(10, 10);
        in.cursorMoved(50, 80);
        check(in.takeMouseDx() == 0f && in.takeMouseDy() == 0f, "input: a free cursor gives no mouse delta");
        check(!in.syncCapture(), "input: no request, no change");
        in.setCursorCaptured(true);
        check(in.isCursorCaptureWanted() && !in.isCaptureActive(), "input: a request is not active before the window applies it");
        check(in.syncCapture() && in.isCaptureActive(), "input: the window applies the request once");
        check(!in.syncCapture(), "input: and only once");
        in.cursorMoved(400, 300);
        check(in.takeMouseDx() == 0f && in.takeMouseDy() == 0f, "input: the first position after the capture is not a jump");
        in.cursorMoved(430, 290);
        in.cursorMoved(440, 295);
        check(near(in.takeMouseDx(), 40f) && near(in.takeMouseDy(), -5f), "input: movements add up until they are taken");
        check(in.takeMouseDx() == 0f && in.takeMouseDy() == 0f, "input: taking resets the delta");
        in.cursorMoved(450, 295);
        in.focusChanged(false);
        in.cursorMoved(1000, 1000);
        check(near(in.takeMouseDx(), 10f) && in.takeMouseDy() == 0f && !in.isKeyDown(DesktopInput.KEY_LEFT),
                "input: losing the focus releases the keys and the next position is not a jump");
        in.cursorMoved(1010, 1000);
        in.setCursorCaptured(false);
        check(in.syncCapture() && !in.isCaptureActive(), "input: the capture is released");
        check(in.takeMouseDx() == 0f, "input: a released capture drops the unread delta");
        in.cursorMoved(5, 5);
        in.cursorMoved(9, 9);
        check(in.takeMouseDx() == 0f, "input: the free cursor gives no delta again");
    }

    public static void main(String[] args) throws Exception {
        String assets = args.length > 0 ? args[0] : "demos/demo-v1.0/assets";
        platforms();
        links();
        setupTests();
        httpTests();
        miscTests(assets);
        inputTests();
        iconTests();
        System.out.println(failed == 0 ? "ALL DESKTOP TESTS PASSED" : failed + " DESKTOP TESTS FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void platforms() throws Exception {
        String[][] table = {
            {"Windows 10", "amd64", "windows-x64", "natives-windows"},
            {"Windows 11", "aarch64", "windows-arm64", "natives-windows-arm64"},
            {"Windows 7", "x86", "windows-x86", "natives-windows-x86"},
            {"Linux", "amd64", "linux-x64", "natives-linux"},
            {"Linux", "aarch64", "linux-arm64", "natives-linux-arm64"},
            {"Linux", "arm", "linux-arm32", "natives-linux-arm32"},
            {"Linux", "armv7l", "linux-arm32", "natives-linux-arm32"},
            {"Mac OS X", "x86_64", "macos-x64", "natives-macos"},
            {"Mac OS X", "aarch64", "macos-arm64", "natives-macos-arm64"},
        };
        boolean ok = true;
        for (String[] r : table) {
            Platform p = Platform.of(r[0], r[1]);
            ok &= p.id().equals(r[2]) && p.classifier.equals(r[3]);
        }
        check(ok, "platforms: 9 supported system/CPU pairs map to the right natives");
        String[][] bad = {{"Mac OS X", "x86"}, {"Windows 10", "arm"}, {"FreeBSD", "amd64"}, {"Linux", "riscv64"}};
        boolean allThrow = true;
        for (String[] r : bad) {
            try {
                Platform.of(r[0], r[1]);
                allThrow = false;
            } catch (SetupException e) {
                allThrow &= e.getMessage().contains("not supported") && e.getMessage().contains("linux-x64");
            }
        }
        check(allThrow, "platforms: unsupported systems give a clear message that lists the supported ones");
        check(Platform.all().size() == 8, "platforms: 8 combinations in all()");
    }

    static void links() throws Exception {
        List<Libraries.Artifact> all = Libraries.everything();
        check(all.size() == 5 + 5 * 8, "links: 5 API jars + 5 natives jars for each of 8 systems = 45 files (" + all.size() + ")");
        Pattern url = Pattern.compile("^https://[a-z0-9.-]+/maven2/org/lwjgl/(lwjgl[a-z-]*)/3\\.3\\.6/\\1-3\\.3\\.6"
                + "(-natives-(windows|linux|macos)(-(x86|arm64|arm32))?)?\\.jar$");
        boolean shape = true;
        Set<String> names = new HashSet<String>();
        for (Libraries.Artifact a : all) {
            for (String m : Libraries.defaultMirrors()) shape &= url.matcher(a.url(m)).matches();
            names.add(a.fileName());
        }
        check(shape, "links: every URL on every built-in mirror has exactly the Maven Central layout");
        check(names.size() == all.size(), "links: no file is listed twice");
        Libraries.Artifact glfw = new Libraries.Artifact("lwjgl-glfw", "natives-linux-arm64");
        check(glfw.url("https://repo1.maven.org/maven2/").equals(
                "https://repo1.maven.org/maven2/org/lwjgl/lwjgl-glfw/3.3.6/lwjgl-glfw-3.3.6-natives-linux-arm64.jar"), "links: sample URL");
        boolean rejects = false;
        try {
            new Libraries.Artifact("lwjgl/../x", null);
        } catch (IllegalArgumentException e) {
            rejects = true;
        }
        check(rejects, "links: a name with a slash or dots-and-slash is refused");
        List<String> ms = Libraries.mirrors();
        check(ms.size() >= 3 && ms.get(0).startsWith("https://repo1.maven.org/"), "links: built-in mirrors, repo1 first");
        System.setProperty("skarph.mirror", "http://corp.test/m2, https://repo1.maven.org/maven2/");
        List<String> ms2 = Libraries.mirrors();
        System.clearProperty("skarph.mirror");
        check(ms2.get(0).equals("http://corp.test/m2/") && ms2.get(1).equals("https://repo1.maven.org/maven2/") && ms2.size() == ms.size() + 1,
                "links: -Dskarph.mirror goes first, gets its slash, no duplicates");
        Map<String, String> pins = Pins.parse("# c\n" + "a".repeat(40) + "  x-3.3.6.jar\n" + "short  y.jar\n" + "B".repeat(40) + " *z.jar\n");
        check(pins.size() == 2 && pins.get("z.jar").equals("b".repeat(40)), "links: pin file parser (comments, bad lines, binary marker)");

        FakeRepo repo = new FakeRepo();
        for (Platform p : Platform.all()) repo.publish(M1, p);
        check(LinkCheck.run(repo, Arrays.asList(M1), new PrintStream(new ByteArrayOutputStream())) == 0, "linkcheck: all files reachable -> 0");
        check(LinkCheck.run(repo, Arrays.asList(M1, M2), new PrintStream(new ByteArrayOutputStream())) == 0,
                "linkcheck: a mirror that lacks files is only 'partial', not a failure");
        repo.files.remove(new Libraries.Artifact("lwjgl-stb", "natives-macos").url(M1));
        check(LinkCheck.run(repo, Arrays.asList(M1), new PrintStream(new ByteArrayOutputStream())) == 1, "linkcheck: a file missing everywhere -> 1");
    }

    static void setupTests() throws Exception {
        Platform lin = Platform.of("Linux", "amd64");
        Platform win = Platform.of("Windows 10", "amd64");

        // happy path, then a second start without any network
        FakeRepo repo = new FakeRepo();
        repo.publish(M1, lin);
        File cache = tmp();
        Setup.Result r = run(lin, cache, repo, new HashMap<String, String>(), new ArrayList<File>());
        check(r.jars.size() == 5 && r.jars.get(0).getName().equals("lwjgl-3.3.6.jar"), "setup: five API jars");
        String[] libs = r.nativesDir.list();
        Arrays.sort(libs);
        check(Arrays.asList(libs).contains("liblwjgl.so") && Arrays.asList(libs).contains("liblwjgl_glfw.so")
                && !Arrays.asList(libs).contains("lwjgl.sha1") && !Arrays.asList(libs).contains("evil.so") == false, "setup: natives are extracted flat, other files are not");
        check(!new File(cache, "evil.so").exists() && !new File(cache.getParentFile(), "evil.so").exists()
                && !new File(cache, "lwjgl-3.3.6/evil.so").exists(), "setup: an entry named ../../evil.so cannot leave the folder");
        int before = repo.requests;
        repo.allDown = true;
        Setup.Result again = run(lin, cache, repo, new HashMap<String, String>(), new ArrayList<File>());
        check(again.jars.size() == 5 && repo.requests == before, "setup: second start needs no network at all");
        repo.allDown = false;

        // damaged cache is repaired
        new File(r.nativesDir, "liblwjgl.so").delete();
        run(lin, cache, repo, new HashMap<String, String>(), new ArrayList<File>());
        check(new File(r.nativesDir, "liblwjgl.so").isFile(), "setup: a deleted native library is restored");
        File jar = r.jars.get(1);
        new File(cache, "lwjgl-3.3.6/" + lin.id() + "/ready").delete();
        try (RandomAccessFile f = new RandomAccessFile(jar, "rw")) { f.seek(f.length() / 2); f.write(0x42); }
        int req = repo.requests;
        run(lin, cache, repo, new HashMap<String, String>(), new ArrayList<File>());
        check(repo.requests > req && Downloader_sha1(jar).equals(sha1(repo.files.get(new Libraries.Artifact("lwjgl-glfw", null).url(M1)))),
                "setup: a jar that no longer matches its checksum is downloaded again");

        // mirrors
        repo = new FakeRepo();
        repo.publish(M2, lin);
        repo.down.add(M1);
        check(run(lin, tmp(), repo, new HashMap<String, String>(), new ArrayList<File>()).jars.size() == 5, "mirrors: first mirror down, second serves");
        repo = new FakeRepo();
        repo.publish(M1, lin);
        repo.publish(M2, lin);
        repo.corrupt.add(M1);
        check(run(lin, tmp(), repo, new HashMap<String, String>(), new ArrayList<File>()).jars.size() == 5, "mirrors: damaged bytes from the first mirror are refused, second serves");
        repo = new FakeRepo();
        repo.publish(M1, lin);
        repo.publish(M3, lin);
        repo.cutOff.add(M1);
        File c3 = tmp();
        check(run(lin, c3, repo, new HashMap<String, String>(), new ArrayList<File>()).jars.size() == 5, "mirrors: a cut-off download is refused, the next mirror serves");
        String[] jarsLeft = new File(c3, "lwjgl-3.3.6/jars").list();
        boolean noPart = true;
        for (String n : jarsLeft) noPart &= !n.endsWith(".part");
        check(noPart, "mirrors: no .part files stay behind");
        repo = new FakeRepo();
        repo.publish(M1, lin);
        repo.corrupt.add(M1);
        check(fails(lin, tmp(), repo, new HashMap<String, String>(), M1 + "org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6.jar"),
                "mirrors: when nothing works the error names the URL and the reason");
        repo = new FakeRepo();
        repo.allDown = true;
        check(fails(lin, tmp(), repo, new HashMap<String, String>(), "Could not get the checksum"), "mirrors: no network gives a clear error");

        // pins
        repo = new FakeRepo();
        repo.publish(M1, lin);
        Map<String, String> pins = new HashMap<String, String>();
        for (Libraries.Artifact a : Libraries.forPlatform(lin)) pins.put(a.fileName(), sha1(repo.files.get(a.url(M1))));
        int rq = repo.requests;
        check(run(lin, tmp(), repo, pins, new ArrayList<File>()).jars.size() == 5 && repo.requests - rq == 10, "pins: right pins -> accepted, no .sha1 requests");
        pins.put("lwjgl-3.3.6.jar", "0".repeat(40));
        check(fails(lin, tmp(), repo, pins, "wrong checksum"), "pins: a file that is not the pinned one is refused even if the mirror's .sha1 agrees with it");

        // local folder
        repo = new FakeRepo();
        repo.publish(M1, win);
        File local = tmp();
        for (Libraries.Artifact a : Libraries.forPlatform(win)) Files.write(new File(local, a.fileName()).toPath(), repo.files.get(a.url(M1)));
        FakeRepo none = new FakeRepo();
        none.allDown = true;
        Setup.Result lr = run(win, tmp(), none, new HashMap<String, String>(), Arrays.asList(local));
        check(lr.jars.size() == 5 && none.requests == 0 && new File(lr.nativesDir, "lwjgl.dll").isFile(), "local: files from skarph-libs are used without any request");

        // a natives jar without libraries
        repo = new FakeRepo();
        repo.publish(M1, lin);
        Libraries.Artifact stb = new Libraries.Artifact("lwjgl-stb", "natives-linux");
        byte[] empty = zip("readme.txt", "no libraries here");
        repo.files.put(stb.url(M1), empty);
        repo.files.put(stb.url(M1) + ".sha1", sha1(empty).getBytes());
        check(fails(lin, tmp(), repo, new HashMap<String, String>(), "no native libraries"), "setup: a natives jar without libraries is an error");
    }

    static String Downloader_sha1(File f) throws Exception {
        return sha1(Files.readAllBytes(f.toPath()));
    }

    static void httpTests() throws Exception {
        final Platform lin = Platform.of("Linux", "amd64");
        final FakeRepo repo = new FakeRepo();
        repo.publish("http://x/maven2/", lin);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String root = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/maven2/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] d = repo.files.get("http://x" + path);
            if (d == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, d.length);
            ex.getResponseBody().write(d);
            ex.close();
        });
        server.createContext("/old/", ex -> {
            ex.getResponseHeaders().add("Location", root + "/maven2/" + ex.getRequestURI().getPath().substring(5));
            ex.sendResponseHeaders(301, -1);
            ex.close();
        });
        server.start();
        try {
            HttpTransport t = new HttpTransport();
            String url = new Libraries.Artifact("lwjgl", null).url(root + "/maven2/");
            Transport.Response r = t.open(url);
            byte[] got = r.in.readAllBytes();
            check(got.length == r.length && got.length > 0, "http: 200 with content length");
            Transport.Response redirected = t.open(new Libraries.Artifact("lwjgl", null).url(root + "/old/"));
            check(redirected.in.readAllBytes().length == got.length, "http: redirect is followed");
            boolean notFound = false;
            try { t.open(root + "/maven2/nothing.jar"); } catch (IOException e) { notFound = e.getMessage().contains("404"); }
            check(notFound, "http: 404 is an IOException with the status");
            boolean refused = false;
            try { t.open("http://127.0.0.1:1/x"); } catch (IOException e) { refused = true; }
            check(refused, "http: refused connection is an IOException");
            Setup.Result res = Setup.ensure(lin, tmp(), t, Arrays.asList("http://127.0.0.1:1/maven2/", root + "/maven2/"),
                    new HashMap<String, String>(), new ArrayList<File>(), 1, 0L, new Quiet());
            check(res.jars.size() == 5, "http: whole setup over HTTP, first mirror refuses, second serves");
        } finally {
            server.stop(0);
        }
        // Launcher --setup-only offline, in a separate process, with -Dskarph.libs
        Platform here = Platform.detect();
        FakeRepo r2 = new FakeRepo();
        r2.publish(M1, here);
        File libs = tmp();
        for (Libraries.Artifact a : Libraries.forPlatform(here)) Files.write(new File(libs, a.fileName()).toPath(), r2.files.get(a.url(M1)));
        File cache = tmp();
        String cp = System.getProperty("java.class.path");
        Process p = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-Dskarph.cache=" + cache,
                "-Dskarph.libs=" + libs, "-Dskarph.mirror=http://127.0.0.1:1/", "-cp", cp, "skarph.desktop.Launcher", "--setup-only")
                .redirectErrorStream(false).start();
        String stdout = new String(p.getInputStream().readAllBytes(), "UTF-8").trim();
        int code = p.waitFor();
        check(code == 0 && stdout.split(File.pathSeparator).length == 5 && stdout.contains("lwjgl-glfw-3.3.6.jar"),
                "launcher: --setup-only offline prints the five API jars (exit " + code + ")");
    }

    static void miscTests(String assetsDir) throws Exception {
        // app dirs
        Map<String, String> env = new HashMap<String, String>();
        check(AppDirs.cacheDir(Platform.Os.LINUX, "skarph", env, "/home/u").getPath().equals("/home/u/.cache/skarph"), "dirs: linux cache default");
        env.put("XDG_CACHE_HOME", "/x/c");
        check(AppDirs.cacheDir(Platform.Os.LINUX, "skarph", env, "/home/u").getPath().equals("/x/c/skarph"), "dirs: XDG_CACHE_HOME");
        env.put("XDG_DATA_HOME", "relative");
        check(AppDirs.dataDir(Platform.Os.LINUX, "g", env, "/home/u").getPath().equals("/home/u/.local/share/g"), "dirs: relative XDG path is ignored");
        check(AppDirs.cacheDir(Platform.Os.MACOS, "skarph", env, "/Users/u").getPath().equals("/Users/u/Library/Caches/skarph")
                && AppDirs.dataDir(Platform.Os.MACOS, "g", env, "/Users/u").getPath().equals("/Users/u/Library/Application Support/g"), "dirs: macOS");

        // assets
        File dir = tmp();
        Files.write(new File(dir, "a.txt").toPath(), "A".getBytes());
        new File(dir, "sub").mkdir();
        Files.write(new File(dir, "sub/b.txt").toPath(), "B".getBytes());
        DesktopAssets as = new DesktopAssets(DesktopTest.class.getClassLoader(), null, dir);
        check(new String(as.readBytes("a.txt")).equals("A") && new String(as.readBytes("sub/b.txt")).equals("B") && as.readBytes("nope") == null, "assets: folder lookup");
        check(as.readBytes("../a.txt") == null && as.readBytes("/etc/passwd") == null && as.readBytes("sub\\b.txt") == null && as.readBytes("sub//b.txt") == null
                && as.readBytes("C:x") == null && as.readBytes(null) == null, "assets: paths that leave the folder are refused");

        // storage
        File sdir = new File(tmp(), "saves");
        DesktopStorage st = new DesktopStorage(sdir);
        check(st.load("s.dat") == null && st.save("s.dat", new byte[] {1, 2, 3}) && Arrays.equals(st.load("s.dat"), new byte[] {1, 2, 3}), "storage: save and load");
        check(st.save("s.dat", new byte[] {9}) && Arrays.equals(st.load("s.dat"), new byte[] {9}) && new File(sdir, "s.dat.tmp").exists() == false, "storage: replace, no temp file left");
        check(!st.save("../x", new byte[1]) && !st.save("a/b", new byte[1]) && st.load("..") == null && !st.delete("../s.dat"), "storage: names with a path are refused");
        check(st.delete("s.dat") && st.load("s.dat") == null, "storage: delete");

        // wav
        boolean wavs = true;
        int count = 0;
        File[] files = new File(assetsDir).listFiles();
        for (File f : files) {
            if (f.getName().endsWith(".wav")) {
                WavDecoder.Pcm p = WavDecoder.decode(Files.readAllBytes(f.toPath()));
                try (javax.sound.sampled.AudioInputStream in = javax.sound.sampled.AudioSystem.getAudioInputStream(f)) {
                    wavs &= p != null && p.samples.length == in.getFrameLength() * p.channels && p.sampleRate == (int) in.getFormat().getSampleRate();
                    // compare the first samples with what Java's own decoder reads
                    byte[] raw = in.readAllBytes();
                    for (int i = 0; i < Math.min(50, p.samples.length) && wavs; i++) {
                        wavs &= p.samples[i] == (short) ((raw[2 * i] & 0xFF) | (raw[2 * i + 1] << 8));
                    }
                }
                count++;
            }
        }
        check(wavs && count >= 5, "wav: the demo sounds (" + count + ") decode to the same samples as javax.sound");
        byte[] w8 = wav(1, 8, 8000, new byte[] {(byte) 128, (byte) 255, 0});
        WavDecoder.Pcm p8 = WavDecoder.decode(w8);
        check(p8 != null && p8.samples.length == 3 && p8.samples[0] == 0 && p8.samples[1] == 127 << 8 && p8.samples[2] == -32768, "wav: 8 bit");
        WavDecoder.Pcm st2 = WavDecoder.decode(wav(2, 16, 44100, new byte[] {1, 0, 2, 0, 3, 0, 4, 0}));
        check(st2 != null && st2.channels == 2 && st2.samples.length == 4 && st2.samples[3] == 4, "wav: stereo 16 bit");
        byte[] fl = new byte[4];
        int bits = Float.floatToIntBits(0.5f);
        for (int i = 0; i < 4; i++) fl[i] = (byte) (bits >> (8 * i));
        WavDecoder.Pcm pf = WavDecoder.decode(wav(1, 32, 22050, fl, 3));
        check(pf != null && Math.abs(pf.samples[0] - 16384) <= 1, "wav: 32 bit float");
        check(WavDecoder.decode(new byte[] {1, 2, 3}) == null && WavDecoder.decode("RIFFxxxxWAVE".getBytes()) == null
                && WavDecoder.decode(wav(6, 16, 8000, new byte[4])) == null && WavDecoder.decode(Arrays.copyOf(wav(1, 16, 8000, new byte[] {1, 0, 2, 0, 3, 0, 4, 0}), 46)).samples.length == 1
                && WavDecoder.decode(Arrays.copyOf(w8, 30)) == null,
                "wav: garbage and unsupported formats give null, a cut file is read as far as it goes");

        // png
        ImageDecoder.Image img = ImageDecoder.decode(Files.readAllBytes(new File(assetsDir, "snow.png").toPath()));
        check(img != null && img.width == 128 && img.height == 128 && img.rgba.isDirect() && img.rgba.remaining() == 128 * 128 * 4, "png: demo texture decodes to 128x128 RGBA");
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, 0x80FF2010);
        bi.setRGB(1, 0, 0xFF0000FF);
        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "png", pngBytes);
        ImageDecoder.Image small = ImageDecoder.decode(pngBytes.toByteArray());
        byte[] px = new byte[8];
        small.rgba.get(px);
        check(small.width == 2 && small.height == 1 && (px[0] & 255) == 0xFF && (px[1] & 255) == 0x20 && (px[2] & 255) == 0x10 && (px[3] & 255) == 0x80
                && (px[7] & 255) == 255 && (px[6] & 255) == 255, "png: R, G, B, A order, alpha not premultiplied, top row first");
        check(ImageDecoder.decode(new byte[] {1, 2, 3}) == null, "png: garbage gives null");

        // shaders
        boolean shaders = true;
        List<String> sources = new ArrayList<String>();
        for (int f = 0; f < GLES2Shaders.VARIANTS; f++) {
            sources.add(GLES2Shaders.meshVertex(f));
            sources.add(GLES2Shaders.meshFragment(f));
        }
        sources.add(GLES2Shaders.spriteVertex());
        sources.add(GLES2Shaders.spriteFragment());
        for (String s : sources) {
            String d = ShaderTranslator.toDesktop(s);
            shaders &= d.startsWith("#version 110\n") && !Pattern.compile("(?m)^\\s*precision\\b").matcher(d).find()
                    && d.contains("void main()") && d.length() > s.length() - 60;
        }
        check(shaders, "shaders: all " + sources.size() + " sources become GLSL 1.10 without precision statements");
        check(ShaderTranslator.toDesktop("precision mediump float;\nvarying vec2 v;\n").contains("varying vec2 v;"), "shaders: other lines stay");

        // config
        GameConfig c = new GameConfig();
        check(c.width == 1280 && c.height == 720 && c.vsync && c.escapeCloses && !c.fullscreen, "config: defaults");
    }

    static byte[] wav(int format, int bits, int rate, byte[] data) {
        return wav(format, bits, rate, data, 1);
    }

    /** channels from the call with 'format' 2 = stereo PCM, 6 = unsupported (A-law), else mono. */
    static byte[] wav(int format, int bits, int rate, byte[] data, int fmtCode) {
        boolean stereo = format == 2;
        int code = format == 6 ? 6 : fmtCode == 3 ? 3 : 1;
        int ch = stereo ? 2 : 1;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try {
            DataOutputStream d = new DataOutputStream(o);
            d.writeBytes("RIFF");
            le32(d, 36 + data.length);
            d.writeBytes("WAVEfmt ");
            le32(d, 16);
            le16(d, code);
            le16(d, ch);
            le32(d, rate);
            le32(d, rate * ch * bits / 8);
            le16(d, ch * bits / 8);
            le16(d, bits);
            d.writeBytes("data");
            le32(d, data.length);
            d.write(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return o.toByteArray();
    }

    static void le16(DataOutputStream d, int v) throws IOException { d.write(v); d.write(v >> 8); }
    static void le32(DataOutputStream d, int v) throws IOException { le16(d, v); le16(d, v >> 16); }
}
