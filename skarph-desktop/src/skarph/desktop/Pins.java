// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Known SHA-1 sums of the library files, from the resource lwjgl-<version>.sha1 inside the program jar (written by
 * tools/pin_lwjgl.py). A pinned file is accepted only if its sum is exactly the pinned one, whatever server it came from.
 * Format of a line: 40 hex digits, spaces, file name. Lines that start with # are comments.
 */
public final class Pins {
    private Pins() {
    }

    public static Map<String, String> load() {
        InputStream in = Pins.class.getResourceAsStream("/skarph/desktop/lwjgl-" + Libraries.VERSION + ".sha1");
        if (in == null) {
            return new HashMap<String, String>();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return parse(new String(out.toByteArray(), "UTF-8"));
        } catch (IOException e) {
            return new HashMap<String, String>();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    public static Map<String, String> parse(String text) {
        Map<String, String> map = new HashMap<String, String>();
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.length() == 0 || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\\s+", 2);
            if (parts.length == 2 && isSha1(parts[0])) {
                String name = parts[1].trim();
                if (name.startsWith("*")) {
                    name = name.substring(1);
                }
                map.put(name, parts[0].toLowerCase(Locale.ROOT));
            }
        }
        return map;
    }

    public static boolean isSha1(String s) {
        if (s.length() != 40) {
            return false;
        }
        for (int i = 0; i < 40; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
