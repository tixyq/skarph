// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Binary model file format "MDL1" (little endian):
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
 * parse() reads from a byte array (load-time code, allocates).
 * The tools/obj2mdl.py and tools/gltf2mdl.py converters write this format.
 */
public final class ModelFormat {
    public static final int FLAG_NORMALS = 1;
    public static final int FLAG_COLORS = 2;
    public static final int FLAG_UV0 = 4;
    public static final int FLAG_UV1 = 8;

    private ModelFormat() {
    }

    /** @throws IllegalArgumentException if the data is not a valid model file */
    public static ModelData parse(byte[] data) {
        if (data == null || data.length < 20) {
            throw new IllegalArgumentException("model file is too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data[0] != 'M' || data[1] != 'D' || data[2] != 'L' || data[3] != '1') {
            throw new IllegalArgumentException("not a model file (bad magic)");
        }
        bb.position(4);
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
        bb.position(bb.position() + vc * 12);

        float[] normals = null;
        if ((flags & FLAG_NORMALS) != 0) {
            normals = new float[vc * 3];
            bb.asFloatBuffer().get(normals);
            bb.position(bb.position() + vc * 12);
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
            bb.position(bb.position() + vc * 8);
        }
        float[] uv1 = null;
        if ((flags & FLAG_UV1) != 0) {
            uv1 = new float[vc * 2];
            bb.asFloatBuffer().get(uv1);
            bb.position(bb.position() + vc * 8);
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
        bb.position(bb.position() + (padded - len));
        try {
            return new String(raw, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalArgumentException("UTF-8 not supported");
        }
    }

    /** Serializes a model (used by tests and Java based tools). */
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
