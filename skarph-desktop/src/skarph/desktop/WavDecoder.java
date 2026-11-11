// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

/** Reads .wav files (PCM 8, 16, 24 or 32 bit, or 32 bit float; mono or stereo) into 16 bit samples. Pure Java. */
public final class WavDecoder {
    private WavDecoder() {
    }

    public static final class Pcm {
        /** Interleaved 16 bit samples. */
        public final short[] samples;
        public final int channels;
        public final int sampleRate;

        public Pcm(short[] samples, int channels, int sampleRate) {
            this.samples = samples;
            this.channels = channels;
            this.sampleRate = sampleRate;
        }
    }

    /** @return the sound, or null if the data is not a wav file that this decoder understands */
    public static Pcm decode(byte[] d) {
        if (d == null || d.length < 12 || !tag(d, 0, "RIFF") || !tag(d, 8, "WAVE")) {
            return null;
        }
        int format = 0;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int pos = 12;
        while (pos + 8 <= d.length) {
            long size = u32(d, pos + 4);
            int body = pos + 8;
            if (tag(d, pos, "fmt ") && size >= 16 && body + 16 <= d.length) {
                format = u16(d, body);
                channels = u16(d, body + 2);
                rate = (int) u32(d, body + 4);
                bits = u16(d, body + 14);
                if (format == 0xFFFE && size >= 26 && body + 26 <= d.length) {
                    format = u16(d, body + 24); // WAVE_FORMAT_EXTENSIBLE: the real format is the start of the sub format
                }
            } else if (tag(d, pos, "data")) {
                if ((format != 1 && format != 3) || channels < 1 || channels > 2 || rate < 1000) {
                    return null;
                }
                long avail = Math.min(size, (long) d.length - body);
                return convert(d, body, (int) avail, format, channels, rate, bits);
            }
            long next = (long) body + size + (size & 1);
            if (next > d.length) {
                break;
            }
            pos = (int) next;
        }
        return null;
    }

    private static Pcm convert(byte[] d, int off, int len, int format, int channels, int rate, int bits) {
        int bytes = bits / 8;
        boolean ok = format == 1 ? (bits == 8 || bits == 16 || bits == 24 || bits == 32) : bits == 32;
        if (!ok) {
            return null;
        }
        int frameBytes = bytes * channels;
        int frames = len / frameBytes;
        short[] out = new short[frames * channels];
        int p = off;
        for (int i = 0; i < out.length; i++) {
            int v;
            if (format == 3) {
                float f = Float.intBitsToFloat((int) u32(d, p));
                v = Math.round(Math.max(-1f, Math.min(1f, f)) * 32767f);
            } else if (bits == 8) {
                v = ((d[p] & 0xFF) - 128) << 8;
            } else if (bits == 16) {
                v = (short) u16(d, p);
            } else if (bits == 24) {
                v = (short) u16(d, p + 1);
            } else {
                v = (short) u16(d, p + 2);
            }
            out[i] = (short) v;
            p += bytes;
        }
        return new Pcm(out, channels, rate);
    }

    private static boolean tag(byte[] d, int pos, String s) {
        if (pos + 4 > d.length) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (d[pos + i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] d, int p) {
        return (u16(d, p) | ((long) u16(d, p + 2) << 16)) & 0xFFFFFFFFL;
    }
}
