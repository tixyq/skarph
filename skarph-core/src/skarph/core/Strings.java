// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Localization: a table of texts by key, loaded from a UTF-8 file of "key=value" lines ('#' starts a comment, "\n" in
 * a value is a line break, "{0}" "{1}" are arguments). Switch the language by loading another file; lookups and
 * formatting never allocate.
 * <pre>
 *   Strings tr = new Strings(256);
 *   tr.load(assets.readBytes("lang/ru.txt"));
 *   label.append(tr.get("menu.start"));
 *   tr.format(buf, "score.fmt", 1200, 3);       // score.fmt=Счёт: {0}, жизней: {1}
 * </pre>
 * A missing key returns the key itself, so gaps are visible on screen instead of crashing.
 */
public final class Strings {
    private String[] keys;
    private String[] values;
    private int count;

    public Strings(int capacity) {
        int n = 16;
        while (n < capacity * 2) {
            n <<= 1;
        }
        keys = new String[n];
        values = new String[n];
    }

    /** Replaces all texts with those of the file. Allocates (loading time). @return the number of texts */
    public int load(byte[] utf8) {
        clear();
        if (utf8 == null) {
            return 0;
        }
        String all;
        try {
            all = new String(utf8, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return 0;
        }
        int n = all.length();
        int i = 0;
        if (n > 0 && all.charAt(0) == '﻿') {
            i = 1;
        }
        while (i < n) {
            int e = all.indexOf('\n', i);
            if (e < 0) {
                e = n;
            }
            int end = e;
            if (end > i && all.charAt(end - 1) == '\r') {
                end--;
            }
            if (end > i && all.charAt(i) != '#') {
                int eq = all.indexOf('=', i);
                if (eq > i && eq < end) {
                    put(all.substring(i, eq).trim(), unescape(all.substring(eq + 1, end)));
                }
            }
            i = e + 1;
        }
        return count;
    }

    private static String unescape(String v) {
        if (v.indexOf('\\') < 0) {
            return v;
        }
        StringBuffer sb = new StringBuffer(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char d = v.charAt(++i);
                sb.append(d == 'n' ? '\n' : d);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public void clear() {
        for (int i = 0; i < keys.length; i++) {
            keys[i] = null;
            values[i] = null;
        }
        count = 0;
    }

    /** Adds or replaces one text. Allocates only if the table must grow. */
    public void put(String key, String value) {
        if ((count + 1) * 2 > keys.length) {
            grow();
        }
        int m = keys.length - 1;
        int i = key.hashCode() & m;
        while (keys[i] != null && !keys[i].equals(key)) {
            i = (i + 1) & m;
        }
        if (keys[i] == null) {
            keys[i] = key;
            count++;
        }
        values[i] = value;
    }

    private void grow() {
        String[] ok = keys;
        String[] ov = values;
        keys = new String[ok.length * 2];
        values = new String[ok.length * 2];
        count = 0;
        for (int i = 0; i < ok.length; i++) {
            if (ok[i] != null) {
                put(ok[i], ov[i]);
            }
        }
    }

    public boolean has(String key) {
        return find(key) >= 0;
    }

    public int size() {
        return count;
    }

    private int find(String key) {
        int m = keys.length - 1;
        int i = key.hashCode() & m;
        while (keys[i] != null) {
            if (keys[i].equals(key)) {
                return i;
            }
            i = (i + 1) & m;
        }
        return -1;
    }

    /** The text, or the key if there is none. */
    public String get(String key) {
        int i = find(key);
        return i < 0 ? key : values[i];
    }

    public TextBuffer format(TextBuffer out, String key, int a0) {
        return format(out, key, a0, 0, 0);
    }

    public TextBuffer format(TextBuffer out, String key, int a0, int a1) {
        return format(out, key, a0, a1, 0);
    }

    /** Appends the text with {0}, {1}, {2} replaced by the numbers. */
    public TextBuffer format(TextBuffer out, String key, int a0, int a1, int a2) {
        String t = get(key);
        int n = t.length();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (c == '{' && i + 2 < n && t.charAt(i + 2) == '}') {
                char d = t.charAt(i + 1);
                if (d >= '0' && d <= '2') {
                    out.append(d == '0' ? a0 : (d == '1' ? a1 : a2));
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out;
    }

    /** Appends the text with {0} replaced by a string. */
    public TextBuffer format(TextBuffer out, String key, String s0) {
        String t = get(key);
        int n = t.length();
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (c == '{' && i + 2 < n && t.charAt(i + 1) == '0' && t.charAt(i + 2) == '}') {
                out.append(s0);
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out;
    }
}
