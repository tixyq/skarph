// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Binary model file formats. parse() reads both and tells them apart by the magic; write() produces MDL1 (exact,
 * big) and writeMdl2() produces MDL2 (small). Both are little endian.
 *
 * <p>MDL1:
 * <pre>
 *   4 bytes  magic 'M','D','L','1'
 *   int32    vertexCount (1..65535)
 *   int32    indexCount  (multiple of 3)
 *   int32    flags: 1 = normals, 2 = colors, 4 = uv0, 8 = uv1 (lightmap)
 *   string   diffuse texture name   (int32 byte length, UTF-8 bytes, padded to 4 bytes; length 0 = none)
 *   string   lightmap texture name
 *   float[vertexCount*3]  positions
 *   float[vertexCount*3]  normals      (if flag 1)
 *   byte [vertexCount*4]  colors RGBA  (if flag 2)
 *   float[vertexCount*2]  uv0          (if flag 4)
 *   float[vertexCount*2]  uv1          (if flag 8)
 *   uint16[indexCount]    indices
 * </pre>
 *
 * <p>MDL2 is about 5 to 10 times smaller:
 * <pre>
 *   4 bytes  magic 'M','D','L','2'
 *   uint8    flags: 1 = normals, 2 = colors (absent = all white), 4 = uv0, 8 = uv1,
 *            16 = precise (float32 instead of 16 bit positions/UVs and 8 bit normals), 32 = body is a zlib stream
 *   uint16   vertexCount (1..65535)
 *   uint32   indexCount  (multiple of 3)
 *   string   diffuse texture name   (uint8 byte length, UTF-8 bytes, no padding; length 0 = none)
 *   string   lightmap texture name
 *   body (zlib stream when flag 32, otherwise plain), in this order:
 *     positions  quantized: float min[3], float max[3], then uint16 x[vc], y[vc], z[vc] spanning min..max
 *                precise:   float x[vc], y[vc], z[vc]
 *     normals    (flag 1) quantized: int8 x, y per vertex, octahedral mapping (decode: x, y = byte / 127,
 *                z = 1 - |x| - |y|, if z < 0 fold: x' = (1 - |y|) * sign(x), y' = (1 - |x|) * sign(y); normalize)
 *                precise: float x[vc], y[vc], z[vc]
 *     colors     (flag 2) byte RGBA per vertex
 *     uv0, uv1   (flags 4, 8) quantized: float min[2], float max[2], then uint16 u[vc], v[vc] spanning min..max
 *                precise: float u[vc], v[vc]
 *     indices    indexCount varints (7 bits per byte, low first, high bit = more): zigzag of the difference to
 *                the previous index (the first one to 0); zigzag(d) = d &gt;= 0 ? 2d : -2d - 1
 * </pre>
 * parse() reads from a byte array (load-time code, allocates).
 * The tools/obj2mdl.py, tools/gltf2mdl.py and the lightmap bakers write MDL2 (tools/mdl.py converts between both).
 */
public final class ModelFormat {
    public static final int FLAG_NORMALS = 1;
    public static final int FLAG_COLORS = 2;
    public static final int FLAG_UV0 = 4;
    public static final int FLAG_UV1 = 8;
    /** MDL2 only: positions, normals and UVs are float32 instead of 16 bit / 8 bit. */
    public static final int FLAG_PRECISE = 16;
    /** MDL2 only: the body after the header is a zlib stream. */
    public static final int FLAG_DEFLATE = 32;

    private ModelFormat() {
    }

    /** Parses an MDL1 or MDL2 file. @throws IllegalArgumentException if the data is not a valid model file */
    public static ModelData parse(byte[] data) {
        if (data == null || data.length < 11) {
            throw new IllegalArgumentException("model file is too short");
        }
        if (data[0] != 'M' || data[1] != 'D' || data[2] != 'L') {
            throw new IllegalArgumentException("not a model file (bad magic)");
        }
        if (data[3] == '2') {
            return parseV2(data);
        }
        if (data[3] != '1') {
            throw new IllegalArgumentException("not a model file (bad magic)");
        }
        return parseV1(data);
    }

    private static ModelData parseV1(byte[] data) {
        if (data.length < 20) {
            throw new IllegalArgumentException("model file is too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        ((Buffer) bb).position(4);
        int vc = bb.getInt();
        int ic = bb.getInt();
        int flags = bb.getInt();
        if (vc < 1 || vc > 65535) {
            throw new IllegalArgumentException("bad vertex count " + vc);
        }
        if (ic < 3 || ic % 3 != 0) {
            throw new IllegalArgumentException("bad index count " + ic);
        }
        String diffuse = readString(bb);
        String lightmap = readString(bb);

        long need = (long) vc * 12;
        if ((flags & FLAG_NORMALS) != 0) {
            need += (long) vc * 12;
        }
        if ((flags & FLAG_COLORS) != 0) {
            need += (long) vc * 4;
        }
        if ((flags & FLAG_UV0) != 0) {
            need += (long) vc * 8;
        }
        if ((flags & FLAG_UV1) != 0) {
            need += (long) vc * 8;
        }
        need += (long) ic * 2;
        if (bb.remaining() < need) {
            throw new IllegalArgumentException("model file is truncated");
        }

        float[] positions = new float[vc * 3];
        bb.asFloatBuffer().get(positions);
        ((Buffer) bb).position(bb.position() + vc * 12);

        float[] normals = null;
        if ((flags & FLAG_NORMALS) != 0) {
            normals = new float[vc * 3];
            bb.asFloatBuffer().get(normals);
            ((Buffer) bb).position(bb.position() + vc * 12);
        }

        float[] colors = new float[vc * 4];
        if ((flags & FLAG_COLORS) != 0) {
            for (int i = 0; i < vc * 4; i++) {
                colors[i] = (bb.get() & 0xFF) / 255f;
            }
        } else {
            for (int i = 0; i < colors.length; i++) {
                colors[i] = 1f;
            }
        }

        float[] uv0 = null;
        if ((flags & FLAG_UV0) != 0) {
            uv0 = new float[vc * 2];
            bb.asFloatBuffer().get(uv0);
            ((Buffer) bb).position(bb.position() + vc * 8);
        }
        float[] uv1 = null;
        if ((flags & FLAG_UV1) != 0) {
            uv1 = new float[vc * 2];
            bb.asFloatBuffer().get(uv1);
            ((Buffer) bb).position(bb.position() + vc * 8);
        }

        short[] indices = new short[ic];
        for (int i = 0; i < ic; i++) {
            int idx = bb.getShort() & 0xFFFF;
            if (idx >= vc) {
                throw new IllegalArgumentException("index " + idx + " out of range (vertices: " + vc + ")");
            }
            indices[i] = (short) idx;
        }
        return new ModelData(new MeshData(positions, normals, colors, uv0, uv1, indices),
                diffuse, lightmap);
    }

    // ------------------------------------------------------------------ MDL2

    private static ModelData parseV2(byte[] data) {
        int flags = data[4] & 0xFF;
        int vc = (data[5] & 0xFF) | ((data[6] & 0xFF) << 8);
        int ic = (data[7] & 0xFF) | ((data[8] & 0xFF) << 8) | ((data[9] & 0xFF) << 16) | ((data[10] & 0xFF) << 24);
        if (vc < 1) {
            throw new IllegalArgumentException("bad vertex count " + vc);
        }
        if (ic < 3 || ic % 3 != 0) {
            throw new IllegalArgumentException("bad index count " + ic);
        }
        int off = 11;
        String[] names = new String[2];
        for (int k = 0; k < 2; k++) {
            if (off >= data.length) {
                throw new IllegalArgumentException("model file is truncated");
            }
            int len = data[off++] & 0xFF;
            if (data.length - off < len) {
                throw new IllegalArgumentException("model file is truncated");
            }
            names[k] = len == 0 ? null : utf8String(data, off, len);
            off += len;
        }
        boolean precise = (flags & FLAG_PRECISE) != 0;
        boolean hasNormals = (flags & FLAG_NORMALS) != 0;
        boolean hasColors = (flags & FLAG_COLORS) != 0;
        boolean hasUv0 = (flags & FLAG_UV0) != 0;
        boolean hasUv1 = (flags & FLAG_UV1) != 0;

        // size of everything before the indices; every index takes at least one byte, at most three
        long fixed = precise ? (long) vc * 12 : 24 + (long) vc * 6;
        if (hasNormals) {
            fixed += precise ? (long) vc * 12 : (long) vc * 2;
        }
        if (hasColors) {
            fixed += (long) vc * 4;
        }
        for (int k = 0; k < 2; k++) {
            if (k == 0 ? hasUv0 : hasUv1) {
                fixed += precise ? (long) vc * 8 : 16 + (long) vc * 4;
            }
        }
        long limit = fixed + 3L * ic;

        byte[] body;
        int bodyLen;
        int pos = 0;
        if ((flags & FLAG_DEFLATE) != 0) {
            body = inflate(data, off, data.length - off, limit);
            bodyLen = body.length;
        } else {
            body = data;
            pos = off;
            bodyLen = data.length;
        }
        if ((long) (bodyLen - pos) < fixed + ic) {
            throw new IllegalArgumentException("model file is truncated");
        }
        ByteBuffer bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        ((Buffer) bb).position(pos);

        float[] positions = new float[vc * 3];
        if (precise) {
            readPlanar(bb, positions, vc, 3);
        } else {
            readQuantized(bb, positions, vc, 3);
        }
        float[] normals = null;
        if (hasNormals) {
            normals = new float[vc * 3];
            if (precise) {
                readPlanar(bb, normals, vc, 3);
            } else {
                for (int i = 0; i < vc; i++) {
                    decodeOct(bb.get(), bb.get(), normals, i * 3);
                }
            }
        }
        float[] colors = new float[vc * 4];
        if (hasColors) {
            for (int i = 0; i < vc * 4; i++) {
                colors[i] = (bb.get() & 0xFF) / 255f;
            }
        } else {
            for (int i = 0; i < colors.length; i++) {
                colors[i] = 1f;
            }
        }
        float[] uv0 = null;
        if (hasUv0) {
            uv0 = new float[vc * 2];
            if (precise) {
                readPlanar(bb, uv0, vc, 2);
            } else {
                readQuantized(bb, uv0, vc, 2);
            }
        }
        float[] uv1 = null;
        if (hasUv1) {
            uv1 = new float[vc * 2];
            if (precise) {
                readPlanar(bb, uv1, vc, 2);
            } else {
                readQuantized(bb, uv1, vc, 2);
            }
        }

        short[] indices = new short[ic];
        int prev = 0;
        for (int i = 0; i < ic; i++) {
            int n = 0;
            int shift = 0;
            while (true) {
                if (!bb.hasRemaining()) {
                    throw new IllegalArgumentException("model file is truncated");
                }
                int b = bb.get() & 0xFF;
                n |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 14) {
                    throw new IllegalArgumentException("bad index data");
                }
            }
            prev += (n & 1) == 0 ? (n >>> 1) : -((n + 1) >>> 1);
            if (prev < 0 || prev >= vc) {
                throw new IllegalArgumentException("index " + prev + " out of range (vertices: " + vc + ")");
            }
            indices[i] = (short) prev;
        }
        return new ModelData(new MeshData(positions, normals, colors, uv0, uv1, indices), names[0], names[1]);
    }

    private static void readPlanar(ByteBuffer bb, float[] out, int vc, int per) {
        for (int k = 0; k < per; k++) {
            for (int i = 0; i < vc; i++) {
                float v = bb.getFloat();
                if (Float.isNaN(v) || Float.isInfinite(v)) {
                    throw new IllegalArgumentException("model contains a bad number");
                }
                out[i * per + k] = v;
            }
        }
    }

    private static void readQuantized(ByteBuffer bb, float[] out, int vc, int per) {
        float[] lo = new float[per];
        float[] hi = new float[per];
        for (int k = 0; k < per; k++) {
            lo[k] = bb.getFloat();
        }
        for (int k = 0; k < per; k++) {
            hi[k] = bb.getFloat();
            if (Float.isNaN(lo[k]) || Float.isInfinite(lo[k]) || Float.isNaN(hi[k]) || Float.isInfinite(hi[k])
                    || hi[k] < lo[k]) {
                throw new IllegalArgumentException("model contains a bad range");
            }
        }
        for (int k = 0; k < per; k++) {
            float step = (hi[k] - lo[k]) / 65535f;
            for (int i = 0; i < vc; i++) {
                out[i * per + k] = lo[k] + (bb.getShort() & 0xFFFF) * step;
            }
        }
    }

    private static void decodeOct(int bx, int by, float[] out, int at) {
        float x = bx / 127f;
        float y = by / 127f;
        float z = 1f - Math.abs(x) - Math.abs(y);
        if (z < 0f) {
            float fx = (1f - Math.abs(y)) * (x < 0f ? -1f : 1f);
            float fy = (1f - Math.abs(x)) * (y < 0f ? -1f : 1f);
            x = fx;
            y = fy;
        }
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6f) {
            out[at] = 0f;
            out[at + 1] = 0f;
            out[at + 2] = 1f;
            return;
        }
        out[at] = x / len;
        out[at + 1] = y / len;
        out[at + 2] = z / len;
    }

    /** Inflates a zlib stream; fails when it is damaged, cut short or would produce more than limit bytes. */
    private static byte[] inflate(byte[] data, int off, int len, long limit) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data, off, len);
            byte[] out = new byte[(int) Math.min(limit + 16, 1 << 16)];
            int n = 0;
            while (!inf.finished()) {
                if (n == out.length) {
                    if (n >= limit) {
                        throw new IllegalArgumentException("model data is bigger than its header says");
                    }
                    byte[] bigger = new byte[(int) Math.min(limit + 16, (long) n * 2)];
                    System.arraycopy(out, 0, bigger, 0, n);
                    out = bigger;
                }
                int got = inf.inflate(out, n, out.length - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    throw new IllegalArgumentException("model file is truncated");
                }
                n += got;
            }
            byte[] exact = new byte[n];
            System.arraycopy(out, 0, exact, 0, n);
            return exact;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("model data is damaged");
        } finally {
            inf.end();
        }
    }

    private static String utf8String(byte[] data, int off, int len) {
        try {
            return new String(data, off, len, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalArgumentException("UTF-8 not supported");
        }
    }

    /**
     * Serializes a model as MDL2 (used by tests and Java based tools).
     * @param precise true keeps float32 positions, normals and UVs (lossless apart from colors); false rounds them to
     *                16 / 8 bits, which is what the Python tools do by default
     */
    public static byte[] writeMdl2(ModelData m, boolean precise) {
        MeshData d = m.mesh;
        int vc = d.getVertexCount();
        int ic = d.getIndexCount();
        boolean white = true;
        for (int i = 0; white && i < vc * 4; i++) {
            white = Math.round(d.colors[i] * 255f) == 255;
        }
        int flags = (d.normals != null ? FLAG_NORMALS : 0) | (white ? 0 : FLAG_COLORS)
                | (d.uv0 != null ? FLAG_UV0 : 0) | (d.uv1 != null ? FLAG_UV1 : 0)
                | (precise ? FLAG_PRECISE : 0) | FLAG_DEFLATE;
        byte[] dn = utf8(m.diffuseTexture);
        byte[] ln = utf8(m.lightmapTexture);
        if (dn.length > 255 || ln.length > 255) {
            throw new IllegalArgumentException("texture name is longer than 255 bytes");
        }
        ByteBuffer bb = ByteBuffer.allocate(24 + vc * 12 * 2 + vc * 4 + vc * 16 + 32 + ic * 3).order(ByteOrder.LITTLE_ENDIAN);
        if (precise) {
            putPlanar(bb, d.positions, vc, 3);
            if (d.normals != null) {
                putPlanar(bb, d.normals, vc, 3);
            }
        } else {
            putQuantized(bb, d.positions, vc, 3);
            if (d.normals != null) {
                for (int i = 0; i < vc; i++) {
                    encodeOct(d.normals[i * 3], d.normals[i * 3 + 1], d.normals[i * 3 + 2], bb);
                }
            }
        }
        if (!white) {
            for (int i = 0; i < vc * 4; i++) {
                int v = Math.round(d.colors[i] * 255f);
                bb.put((byte) (v < 0 ? 0 : (v > 255 ? 255 : v)));
            }
        }
        for (int k = 0; k < 2; k++) {
            float[] uv = k == 0 ? d.uv0 : d.uv1;
            if (uv != null) {
                if (precise) {
                    putPlanar(bb, uv, vc, 2);
                } else {
                    putQuantized(bb, uv, vc, 2);
                }
            }
        }
        int prev = 0;
        for (int i = 0; i < ic; i++) {
            int idx = d.indices[i] & 0xFFFF;
            int diff = idx - prev;
            int z = diff >= 0 ? diff << 1 : ((-diff) << 1) - 1;
            while (z >= 0x80) {
                bb.put((byte) ((z & 0x7F) | 0x80));
                z >>>= 7;
            }
            bb.put((byte) z);
            prev = idx;
        }
        byte[] raw = new byte[bb.position()];
        System.arraycopy(bb.array(), 0, raw, 0, raw.length);

        Deflater def = new Deflater(9);
        byte[] packed = new byte[raw.length + raw.length / 100 + 64];
        int plen;
        try {
            def.setInput(raw);
            def.finish();
            plen = 0;
            while (!def.finished()) {
                if (plen == packed.length) {
                    byte[] bigger = new byte[packed.length * 2];
                    System.arraycopy(packed, 0, bigger, 0, plen);
                    packed = bigger;
                }
                plen += def.deflate(packed, plen, packed.length - plen);
            }
        } finally {
            def.end();
        }
        byte[] out = new byte[11 + 1 + dn.length + 1 + ln.length + plen];
        out[0] = 'M';
        out[1] = 'D';
        out[2] = 'L';
        out[3] = '2';
        out[4] = (byte) flags;
        out[5] = (byte) vc;
        out[6] = (byte) (vc >>> 8);
        out[7] = (byte) ic;
        out[8] = (byte) (ic >>> 8);
        out[9] = (byte) (ic >>> 16);
        out[10] = (byte) (ic >>> 24);
        int o = 11;
        out[o++] = (byte) dn.length;
        System.arraycopy(dn, 0, out, o, dn.length);
        o += dn.length;
        out[o++] = (byte) ln.length;
        System.arraycopy(ln, 0, out, o, ln.length);
        o += ln.length;
        System.arraycopy(packed, 0, out, o, plen);
        return out;
    }

    private static void putPlanar(ByteBuffer bb, float[] v, int vc, int per) {
        for (int k = 0; k < per; k++) {
            for (int i = 0; i < vc; i++) {
                bb.putFloat(v[i * per + k]);
            }
        }
    }

    private static void putQuantized(ByteBuffer bb, float[] v, int vc, int per) {
        float[] lo = new float[per];
        float[] hi = new float[per];
        for (int k = 0; k < per; k++) {
            lo[k] = Float.MAX_VALUE;
            hi[k] = -Float.MAX_VALUE;
            for (int i = 0; i < vc; i++) {
                lo[k] = Math.min(lo[k], v[i * per + k]);
                hi[k] = Math.max(hi[k], v[i * per + k]);
            }
        }
        for (int k = 0; k < per; k++) {
            bb.putFloat(lo[k]);
        }
        for (int k = 0; k < per; k++) {
            bb.putFloat(hi[k]);
        }
        for (int k = 0; k < per; k++) {
            float range = hi[k] - lo[k];
            for (int i = 0; i < vc; i++) {
                int q = range > 0f ? Math.round((v[i * per + k] - lo[k]) / range * 65535f) : 0;
                bb.putShort((short) (q < 0 ? 0 : (q > 65535 ? 65535 : q)));
            }
        }
    }

    private static void encodeOct(float x, float y, float z, ByteBuffer bb) {
        float l1 = Math.abs(x) + Math.abs(y) + Math.abs(z);
        if (l1 == 0f) {
            x = 0f;
            y = 0f;
            z = 1f;
            l1 = 1f;
        }
        x /= l1;
        y /= l1;
        z /= l1;
        if (z < 0f) {
            float fx = (1f - Math.abs(y)) * (x < 0f ? -1f : 1f);
            float fy = (1f - Math.abs(x)) * (y < 0f ? -1f : 1f);
            x = fx;
            y = fy;
        }
        int bx = Math.round(x * 127f);
        int by = Math.round(y * 127f);
        bb.put((byte) (bx < -127 ? -127 : (bx > 127 ? 127 : bx)));
        bb.put((byte) (by < -127 ? -127 : (by > 127 ? 127 : by)));
    }

    private static String readString(ByteBuffer bb) {
        if (bb.remaining() < 4) {
            throw new IllegalArgumentException("model file is truncated");
        }
        int len = bb.getInt();
        if (len < 0 || len > 1024) {
            throw new IllegalArgumentException("bad string length " + len);
        }
        int padded = (len + 3) & ~3;
        if (bb.remaining() < padded) {
            throw new IllegalArgumentException("model file is truncated");
        }
        if (len == 0) {
            return null;
        }
        byte[] raw = new byte[len];
        bb.get(raw);
        ((Buffer) bb).position(bb.position() + (padded - len));
        try {
            return new String(raw, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalArgumentException("UTF-8 not supported");
        }
    }

    /** Serializes a model as MDL1: exact, but big (used by tests and Java based tools). See writeMdl2() for the small format. */
    public static byte[] write(ModelData m) {
        MeshData d = m.mesh;
        int vc = d.getVertexCount();
        int ic = d.getIndexCount();
        int flags = 0;
        if (d.normals != null) {
            flags |= FLAG_NORMALS;
        }
        flags |= FLAG_COLORS;
        if (d.uv0 != null) {
            flags |= FLAG_UV0;
        }
        if (d.uv1 != null) {
            flags |= FLAG_UV1;
        }
        byte[] dn = utf8(m.diffuseTexture);
        byte[] ln = utf8(m.lightmapTexture);
        int size = 20 + ((dn.length + 3) & ~3) + ((ln.length + 3) & ~3) + 4 + vc * 12 + vc * 4 + ic * 2;
        if (d.normals != null) {
            size += vc * 12;
        }
        if (d.uv0 != null) {
            size += vc * 8;
        }
        if (d.uv1 != null) {
            size += vc * 8;
        }
        ByteBuffer bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'M').put((byte) 'D').put((byte) 'L').put((byte) '1');
        bb.putInt(vc).putInt(ic).putInt(flags);
        putString(bb, dn);
        putString(bb, ln);
        for (int i = 0; i < vc * 3; i++) {
            bb.putFloat(d.positions[i]);
        }
        if (d.normals != null) {
            for (int i = 0; i < vc * 3; i++) {
                bb.putFloat(d.normals[i]);
            }
        }
        for (int i = 0; i < vc * 4; i++) {
            int v = Math.round(d.colors[i] * 255f);
            bb.put((byte) (v < 0 ? 0 : (v > 255 ? 255 : v)));
        }
        if (d.uv0 != null) {
            for (int i = 0; i < vc * 2; i++) {
                bb.putFloat(d.uv0[i]);
            }
        }
        if (d.uv1 != null) {
            for (int i = 0; i < vc * 2; i++) {
                bb.putFloat(d.uv1[i]);
            }
        }
        for (int i = 0; i < ic; i++) {
            bb.putShort(d.indices[i]);
        }
        byte[] out = new byte[bb.position()];
        System.arraycopy(bb.array(), 0, out, 0, out.length);
        return out;
    }

    private static byte[] utf8(String s) {
        if (s == null) {
            return new byte[0];
        }
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not supported");
        }
    }

    private static void putString(ByteBuffer bb, byte[] raw) {
        bb.putInt(raw.length);
        bb.put(raw);
        for (int i = raw.length; (i & 3) != 0; i++) {
            bb.put((byte) 0);
        }
    }
}
