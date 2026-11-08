// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.io.File;
import java.util.Map;

/** Where a program keeps its files, by the rules of each system. */
public final class AppDirs {
    private AppDirs() {
    }

    /** Downloaded libraries (can be deleted at any time). -Dskarph.cache=FOLDER overrides it. */
    public static File cacheDir(Platform.Os os, String app, Map<String, String> env, String home) {
        switch (os) {
            case WINDOWS:
                return new File(pick(env.get("LOCALAPPDATA"), home + File.separator + "AppData" + File.separator + "Local"), app);
            case MACOS:
                return new File(home + "/Library/Caches", app);
            default:
                return new File(pick(env.get("XDG_CACHE_HOME"), home + "/.cache"), app);
        }
    }

    /** Saved games and settings. */
    public static File dataDir(Platform.Os os, String app, Map<String, String> env, String home) {
        switch (os) {
            case WINDOWS:
                return new File(pick(env.get("APPDATA"), home + File.separator + "AppData" + File.separator + "Roaming"), app);
            case MACOS:
                return new File(home + "/Library/Application Support", app);
            default:
                return new File(pick(env.get("XDG_DATA_HOME"), home + "/.local/share"), app);
        }
    }

    public static File cacheDir(Platform p) {
        String over = System.getProperty("skarph.cache");
        if (over != null && over.length() > 0) {
            return new File(over);
        }
        return cacheDir(p.os, "skarph", System.getenv(), System.getProperty("user.home", "."));
    }

    public static File dataDir(Platform p, String app) {
        return dataDir(p.os, app, System.getenv(), System.getProperty("user.home", "."));
    }

    /** XDG says that a relative path in these variables must be ignored. */
    private static String pick(String value, String fallback) {
        return value != null && new File(value).isAbsolute() ? value : fallback;
    }
}
