// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Small typed key/value store (int, float, boolean, String) that can be saved to a Storage
 * file and loaded back. Fixed capacity. get/put with literal keys do not allocate;
 * save()/load() allocate (do them on pause, at start-up, never per frame).
 *
 * File format: one entry per line, "i key 5", "f key 0.5", "b key 1", "s key some text".
 * Keys must not contain white space.
 */
public final class Settings {
    private final String[] keys;
    private final char[] types;
    private final int[] ints;
    private final float[] floats;
    private final String[] strings;
    private int count;

    public Settings(int capacity) {
        keys = new String[capacity];
        types = new char[capacity];
        ints = new int[capacity];
        floats = new float[capacity];
        strings = new String[capacity];
    }

    public int size() {
        return count;
    }

    private int find(String key) {
        for (int i = 0; i < count; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private int slot(String key, char type) {
        for (int i = 0; i < key.length(); i++) {
            if (Character.isWhitespace(key.charAt(i))) {
                throw new IllegalArgumentException("key must not contain white space: '" + key + "'");
            }
        }
        if (key.length() == 0) {
            throw new IllegalArgumentException("empty key");
        }
        int i = find(key);
        if (i < 0) {
            if (count == keys.length) {
                return -1;
            }
            i = count++;
            keys[i] = key;
        }
        types[i] = type;
        return i;
    }

    /** All put methods return false if the store is full and the key is new. */
    public boolean putInt(String key, int value) {
        int i = slot(key, 'i');
        if (i < 0) {
            return false;
        }
        ints[i] = value;
        return true;
    }

    public boolean putBoolean(String key, boolean value) {
        int i = slot(key, 'b');
        if (i < 0) {
            return false;
        }
        ints[i] = value ? 1 : 0;
        return true;
    }

    public boolean putFloat(String key, float value) {
        int i = slot(key, 'f');
        if (i < 0) {
            return false;
        }
        floats[i] = value;
        return true;
    }

    public boolean putString(String key, String value) {
        int i = slot(key, 's');
        if (i < 0) {
            return false;
        }
        strings[i] = value;
        return true;
    }

    public int getInt(String key, int def) {
        int i = find(key);
        return (i >= 0 && types[i] == 'i') ? ints[i] : def;
    }

    public boolean getBoolean(String key, boolean def) {
        int i = find(key);
        return (i >= 0 && types[i] == 'b') ? ints[i] != 0 : def;
    }

    public float getFloat(String key, float def) {
        int i = find(key);
        return (i >= 0 && types[i] == 'f') ? floats[i] : def;
    }

    public String getString(String key, String def) {
        int i = find(key);
        return (i >= 0 && types[i] == 's') ? strings[i] : def;
    }

    public boolean contains(String key) {
        return find(key) >= 0;
    }

    public void clear() {
        for (int i = 0; i < count; i++) {
            keys[i] = null;
            strings[i] = null;
        }
        count = 0;
    }

    public byte[] toBytes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(types[i]).append(' ').append(keys[i]).append(' ');
            switch (types[i]) {
                case 'i':
                case 'b':
                    sb.append(ints[i]);
                    break;
                case 'f':
                    sb.append(floats[i]);
                    break;
                default:
                    sb.append(escape(strings[i]));
                    break;
            }
            sb.append('\n');
        }
        try {
            return sb.toString().getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not supported");
        }
    }

    /** Parses data written by toBytes(), adding the entries. Bad lines are skipped. @return entries read */
    public int fromBytes(byte[] data) {
        String text;
        try {
            text = new String(data, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not supported");
        }
        int read = 0;
        String[] lines = text.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String[] p = lines[n].split(" ", 3);
            if (p.length < 2 || p[0].length() != 1) {
                continue;
            }
            String val = p.length > 2 ? p[2] : "";
            try {
                switch (p[0].charAt(0)) {
                    case 'i':
                        read += putInt(p[1], Integer.parseInt(val)) ? 1 : 0;
                        break;
                    case 'b':
                        read += putBoolean(p[1], !val.equals("0")) ? 1 : 0;
                        break;
                    case 'f':
                        read += putFloat(p[1], Float.parseFloat(val)) ? 1 : 0;
                        break;
                    case 's':
                        read += putString(p[1], unescape(val)) ? 1 : 0;
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException e) {
                // skip the damaged line
            }
        }
        return read;
    }

    public boolean save(Storage storage, String name) {
        return storage.save(name, toBytes());
    }

    /** @return false if the file does not exist. */
    public boolean load(Storage storage, String name) {
        byte[] d = storage.load(name);
        if (d == null) {
            return false;
        }
        fromBytes(d);
        return true;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                sb.append(d == 'n' ? '\n' : (d == 'r' ? '\r' : d));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
