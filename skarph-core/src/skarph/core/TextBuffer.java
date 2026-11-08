// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Preallocated char buffer for building text without String concatenation.
 * Fixed capacity: extra characters are silently dropped.
 * Only toString() allocates (use it outside hot paths).
 */
public final class TextBuffer {
    private final char[] buf;
    private int len;

    public TextBuffer(int capacity) {
        buf = new char[capacity];
    }

    public TextBuffer clear() {
        len = 0;
        return this;
    }

    public TextBuffer append(char c) {
        if (len < buf.length) {
            buf[len++] = c;
        }
        return this;
    }

    public TextBuffer append(String s) {
        int n = s.length();
        if (n > buf.length - len) {
            n = buf.length - len;
        }
        s.getChars(0, n, buf, len);
        len += n;
        return this;
    }

    public TextBuffer append(int value) {
        return append((long) value);
    }

    public TextBuffer append(long value) {
        if (value < 0) {
            append('-');
            if (value == Long.MIN_VALUE) {
                return append("9223372036854775808");
            }
            value = -value;
        }
        long d = 1;
        while (value / d >= 10) {
            d *= 10;
        }
        while (d > 0) {
            append((char) ('0' + (int) ((value / d) % 10)));
            d /= 10;
        }
        return this;
    }

    /** Appends a float with a fixed number of decimals (0..9). */
    public TextBuffer append(float value, int decimals) {
        if (value != value) {
            return append("NaN");
        }
        if (value < 0f) {
            append('-');
            value = -value;
        }
        if (value > 1e15f) {
            return append("INF");
        }
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        long scaled = (long) (value * scale + 0.5f);
        append(scaled / scale);
        if (decimals > 0) {
            append('.');
            long frac = scaled % scale;
            for (long d = scale / 10; d > 0; d /= 10) {
                append((char) ('0' + (int) ((frac / d) % 10)));
            }
        }
        return this;
    }

    public int length() {
        return len;
    }

    public char charAt(int index) {
        return buf[index];
    }

    /** Copies the text into dst (must be large enough). @return length. */
    public int getChars(char[] dst) {
        System.arraycopy(buf, 0, dst, 0, len);
        return len;
    }

    /** Allocates a new String. Not for hot paths. */
    public String toString() {
        return new String(buf, 0, len);
    }
}
