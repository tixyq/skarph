// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one place that knows which files are downloaded and where from. Every URL in the program is built here from
 * (module, version, classifier), nobody types a URL by hand, so a typo cannot exist. The files are released versions of
 * Maven Central, which never changes or removes a released file, so a link to one stays valid.
 */
public final class Libraries {
    /** The LWJGL version. To move to another one: change this, run tools/pin_lwjgl.py, run build-desktop.sh. */
    public static final String VERSION = "3.3.6";

    /** The LWJGL modules the desktop layer needs: core, window and input, OpenGL, sound, ogg decoding. */
    public static final String[] MODULES = {"lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal", "lwjgl-stb"};

    /** Mirrors of Maven Central, tried in this order. All serve the same files. */
    private static final String[] DEFAULT_MIRRORS = {
        "https://repo1.maven.org/maven2/",
        "https://repo.maven.apache.org/maven2/",
        "https://maven-central.storage-download.googleapis.com/maven2/",
    };

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]+");

    private Libraries() {
    }

    /** One file of the library set. */
    public static final class Artifact {
        public final String module;
        /** null for the API jar, otherwise the natives classifier. */
        public final String classifier;

        public Artifact(String module, String classifier) {
            check(module);
            if (classifier != null) {
                check(classifier);
            }
            this.module = module;
            this.classifier = classifier;
        }

        private static void check(String part) {
            if (!SAFE.matcher(part).matches()) {
                throw new IllegalArgumentException("bad name: " + part);
            }
        }

        public boolean isNatives() {
            return classifier != null;
        }

        /** For example lwjgl-glfw-3.3.6-natives-linux.jar. */
        public String fileName() {
            return module + "-" + VERSION + (classifier == null ? "" : "-" + classifier) + ".jar";
        }

        /** The path below the repository root. */
        public String path() {
            return "org/lwjgl/" + module + "/" + VERSION + "/" + fileName();
        }

        public String url(String mirror) {
            return mirror + path();
        }

        public String toString() {
            return fileName();
        }
    }

    /** The files one system needs: first the five API jars, then the five natives jars. */
    public static List<Artifact> forPlatform(Platform p) {
        List<Artifact> list = new ArrayList<Artifact>();
        for (String m : MODULES) {
            list.add(new Artifact(m, null));
        }
        for (String m : MODULES) {
            list.add(new Artifact(m, p.classifier));
        }
        return list;
    }

    /** Every file for every supported system, without duplicates. */
    public static List<Artifact> everything() {
        List<Artifact> list = new ArrayList<Artifact>();
        for (String m : MODULES) {
            list.add(new Artifact(m, null));
        }
        for (Platform p : Platform.all()) {
            for (String m : MODULES) {
                list.add(new Artifact(m, p.classifier));
            }
        }
        return list;
    }

    /**
     * The mirrors in the order they are tried: the ones from -Dskarph.mirror=url[,url] or the environment variable
     * SKARPH_MIRROR first (for a company mirror or an offline server), then the built-in ones.
     */
    public static List<String> mirrors() {
        List<String> list = new ArrayList<String>();
        String extra = System.getProperty("skarph.mirror");
        if (extra == null || extra.length() == 0) {
            extra = System.getenv("SKARPH_MIRROR");
        }
        if (extra != null) {
            for (String s : extra.split(",")) {
                String m = normalizeMirror(s);
                if (m != null && !list.contains(m)) {
                    list.add(m);
                }
            }
        }
        for (String s : DEFAULT_MIRRORS) {
            if (!list.contains(s)) {
                list.add(s);
            }
        }
        return list;
    }

    public static List<String> defaultMirrors() {
        return new ArrayList<String>(Arrays.asList(DEFAULT_MIRRORS));
    }

    static String normalizeMirror(String s) {
        String m = s.trim();
        if (m.length() == 0) {
            return null;
        }
        return m.endsWith("/") ? m : m + "/";
    }
}
