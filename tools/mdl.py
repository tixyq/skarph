# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
"""Reader and writer for the binary model formats MDL1 and MDL2 (see skarph.core.ModelFormat for the layouts).

MDL2 is the default: 16 bit positions and UVs, 8 bit normals, delta coded indices, everything deflated. A model is
about 5 to 10 times smaller than the same MDL1 file. MDL1 stays readable and writable (version=1) for old engines.

  mdl.py convert in.mdl out.mdl [--mdl1] [--precise]    rewrites a model in another format
  mdl.py info model.mdl [...]                           prints the format, counts and size

No packages needed.
"""
import argparse
import math
import struct
import sys
import zlib

import cli

FLAG_NORMALS = 1
FLAG_COLORS = 2
FLAG_UV0 = 4
FLAG_UV1 = 8
FLAG_PRECISE = 16   # MDL2 only: float32 positions, normals and UVs instead of 16 / 8 bit
FLAG_DEFLATE = 32   # MDL2 only: the body is a zlib stream


# ---------------------------------------------------------------- command line helpers for the other tools

def add_format_args(ap):
    """Adds --mdl1 and --precise to an argparse parser; pass the result of format_options() to write_mdl."""
    ap.add_argument("--mdl1", action="store_true", help="write the old, big MDL1 format instead of MDL2")
    ap.add_argument("--precise", action="store_true",
                    help="MDL2 with float32 positions, normals and UVs (lossless, about twice as big)")


def format_options(args):
    return {"version": 1 if args.mdl1 else 2, "precise": args.precise}


# ---------------------------------------------------------------- writing

def _f32(x):
    return struct.unpack("<f", struct.pack("<f", x))[0]


def _string1(s):
    raw = (s or "").encode("utf-8")
    return struct.pack("<i", len(raw)) + raw + b"\0" * ((4 - len(raw) % 4) % 4)


def _string2(s):
    raw = (s or "").encode("utf-8")
    if len(raw) > 255:
        raise ValueError("texture name is longer than 255 bytes: %s" % s)
    return bytes([len(raw)]) + raw


def _varint(n):
    out = bytearray()
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def _sign(v):
    return -1.0 if v < 0 else 1.0


def _oct_encode(n):
    """Unit vector -> two signed bytes (octahedral mapping). Mirrors skarph.core.ModelFormat."""
    x, y, z = n
    l1 = abs(x) + abs(y) + abs(z)
    if l1 == 0:
        x, y, z, l1 = 0.0, 0.0, 1.0, 1.0
    x, y, z = x / l1, y / l1, z / l1
    if z < 0:
        x, y = (1 - abs(y)) * _sign(x), (1 - abs(x)) * _sign(y)
    return (max(-127, min(127, int(round(x * 127)))), max(-127, min(127, int(round(y * 127)))))


def _oct_decode(bx, by):
    x, y = bx / 127.0, by / 127.0
    z = 1 - abs(x) - abs(y)
    if z < 0:
        x, y = (1 - abs(y)) * _sign(x), (1 - abs(x)) * _sign(y)
    l = math.sqrt(x * x + y * y + z * z)
    return (x / l, y / l, z / l)


def _quantize(values, per, out):
    """values: list of tuples with `per` floats. Appends min, max (float32) and one planar uint16 array per axis."""
    lo = [_f32(min(v[k] for v in values)) for k in range(per)]
    hi = [_f32(max(v[k] for v in values)) for k in range(per)]
    out += struct.pack("<%df" % per, *lo) + struct.pack("<%df" % per, *hi)
    for k in range(per):
        rng = hi[k] - lo[k]
        if rng > 0:
            q = [max(0, min(65535, int(round((v[k] - lo[k]) / rng * 65535)))) for v in values]
        else:
            q = [0] * len(values)
        out += struct.pack("<%dH" % len(values), *q)


def _planar_floats(values, per, out):
    for k in range(per):
        out += struct.pack("<%df" % len(values), *[v[k] for v in values])


def _check(positions, indices, normals, colors, uv0, uv1):
    vc = len(positions)
    if vc < 1 or vc > 65535:
        raise ValueError("vertex count %d is out of range 1..65535 (16 bit indices)" % vc)
    if len(indices) < 3 or len(indices) % 3:
        raise ValueError("index count must be a positive multiple of 3")
    if max(indices) >= vc or min(indices) < 0:
        raise ValueError("index out of range")
    for name, arr in (("normals", normals), ("colors", colors), ("uv0", uv0), ("uv1", uv1)):
        if arr is not None and len(arr) != vc:
            raise ValueError("%s length %d != vertex count %d" % (name, len(arr), vc))


def _to_bytes(c):
    return bytes(max(0, min(255, int(round(v * 255)))) for v in c)


def _write_v1(positions, indices, normals, colors, uv0, uv1, diffuse, lightmap):
    vc = len(positions)
    flags = FLAG_COLORS
    if normals is not None:
        flags |= FLAG_NORMALS
    if uv0 is not None:
        flags |= FLAG_UV0
    if uv1 is not None:
        flags |= FLAG_UV1
    out = bytearray()
    out += b"MDL1" + struct.pack("<iii", vc, len(indices), flags)
    out += _string1(diffuse) + _string1(lightmap)
    for p in positions:
        out += struct.pack("<fff", *p)
    if normals is not None:
        for n in normals:
            out += struct.pack("<fff", *n)
    for i in range(vc):
        out += _to_bytes(colors[i] if colors is not None else (1.0, 1.0, 1.0, 1.0))
    if uv0 is not None:
        for t in uv0:
            out += struct.pack("<ff", *t)
    if uv1 is not None:
        for t in uv1:
            out += struct.pack("<ff", *t)
    out += struct.pack("<%dH" % len(indices), *indices)
    return bytes(out)


def _write_v2(positions, indices, normals, colors, uv0, uv1, diffuse, lightmap, precise, deflate):
    vc = len(positions)
    col_bytes = None
    if colors is not None:
        col_bytes = [_to_bytes(c) for c in colors]
        if all(b == b"\xff\xff\xff\xff" for b in col_bytes):
            col_bytes = None            # all white is the default: not stored
    flags = 0
    flags |= FLAG_NORMALS if normals is not None else 0
    flags |= FLAG_COLORS if col_bytes is not None else 0
    flags |= FLAG_UV0 if uv0 is not None else 0
    flags |= FLAG_UV1 if uv1 is not None else 0
    flags |= FLAG_PRECISE if precise else 0
    flags |= FLAG_DEFLATE if deflate else 0

    body = bytearray()
    if precise:
        _planar_floats(positions, 3, body)
        if normals is not None:
            _planar_floats(normals, 3, body)
    else:
        _quantize(positions, 3, body)
        if normals is not None:
            for n in normals:
                body += struct.pack("<bb", *_oct_encode(n))
    if col_bytes is not None:
        for b in col_bytes:
            body += b
    for uv in (uv0, uv1):
        if uv is not None:
            if precise:
                _planar_floats(uv, 2, body)
            else:
                _quantize(uv, 2, body)
    prev = 0
    for i in indices:
        d = i - prev
        body += _varint((d << 1) if d >= 0 else ((-d << 1) - 1))
        prev = i
    if deflate:
        body = zlib.compress(bytes(body), 9)
    head = b"MDL2" + struct.pack("<BHI", flags, vc, len(indices)) + _string2(diffuse) + _string2(lightmap)
    return head + bytes(body)


def write_mdl(path, positions, indices, normals=None, colors=None,
              uv0=None, uv1=None, diffuse="", lightmap="", version=2, precise=False):
    """positions: [(x,y,z)], indices: [i0,i1,i2,...], normals: [(x,y,z)],
    colors: [(r,g,b,a)] floats 0..1 (default white), uv0/uv1: [(u,v)] (v = 0 is the TOP of the image).
    version 2 (default) writes MDL2; precise=True keeps float32 positions, normals and UVs (lossless).
    version 1 writes the old MDL1. Returns the file size in bytes."""
    _check(positions, indices, normals, colors, uv0, uv1)
    if version == 1:
        data = _write_v1(positions, indices, normals, colors, uv0, uv1, diffuse, lightmap)
    elif version == 2:
        data = _write_v2(positions, indices, normals, colors, uv0, uv1, diffuse, lightmap, precise, True)
    else:
        raise ValueError("unknown model version %r (1 or 2)" % (version,))
    with open(path, "wb") as f:
        f.write(data)
    return len(data)


# ---------------------------------------------------------------- reading

def _read_string1(data, off):
    (n,) = struct.unpack_from("<i", data, off)
    off += 4
    return data[off:off + n].decode("utf-8"), off + ((n + 3) & ~3)


def _read_v1(data):
    vc, ic, flags = struct.unpack_from("<iii", data, 4)
    diffuse, off = _read_string1(data, 16)
    lightmap, off = _read_string1(data, off)

    def floats(per):
        nonlocal off
        vals = struct.unpack_from("<%df" % (vc * per), data, off)
        off += 4 * vc * per
        return [vals[i * per:(i + 1) * per] for i in range(vc)]

    positions = floats(3)
    normals = floats(3) if flags & FLAG_NORMALS else None
    colors = None
    if flags & FLAG_COLORS:
        raw = data[off:off + vc * 4]
        off += vc * 4
        colors = [tuple(raw[i * 4 + k] / 255.0 for k in range(4)) for i in range(vc)]
    uv0 = floats(2) if flags & FLAG_UV0 else None
    uv1 = floats(2) if flags & FLAG_UV1 else None
    indices = list(struct.unpack_from("<%dH" % ic, data, off))
    return {"positions": positions, "normals": normals, "colors": colors, "uv0": uv0, "uv1": uv1,
            "indices": indices, "diffuse": diffuse, "lightmap": lightmap, "version": 1, "precise": True}


def _read_v2(data):
    flags, vc, ic = struct.unpack_from("<BHI", data, 4)
    off = 11
    names = []
    for _ in range(2):
        n = data[off]
        names.append(data[off + 1:off + 1 + n].decode("utf-8"))
        off += 1 + n
    body = zlib.decompress(data[off:]) if flags & FLAG_DEFLATE else data[off:]
    precise = bool(flags & FLAG_PRECISE)
    pos = 0

    def planar(per):
        nonlocal pos
        vals = struct.unpack_from("<%df" % (vc * per), body, pos)
        pos += 4 * vc * per
        return [tuple(vals[k * vc + i] for k in range(per)) for i in range(vc)]

    def quantized(per):
        nonlocal pos
        lo = struct.unpack_from("<%df" % per, body, pos)
        hi = struct.unpack_from("<%df" % per, body, pos + 4 * per)
        pos += 8 * per
        axes = []
        for k in range(per):
            q = struct.unpack_from("<%dH" % vc, body, pos)
            pos += 2 * vc
            step = _f32((hi[k] - lo[k]) / 65535.0)
            axes.append([lo[k] + v * step for v in q])
        return [tuple(axes[k][i] for k in range(per)) for i in range(vc)]

    positions = planar(3) if precise else quantized(3)
    normals = None
    if flags & FLAG_NORMALS:
        if precise:
            normals = planar(3)
        else:
            raw = struct.unpack_from("<%db" % (vc * 2), body, pos)
            pos += vc * 2
            normals = [_oct_decode(raw[i * 2], raw[i * 2 + 1]) for i in range(vc)]
    if flags & FLAG_COLORS:
        raw = body[pos:pos + vc * 4]
        pos += vc * 4
        colors = [tuple(raw[i * 4 + k] / 255.0 for k in range(4)) for i in range(vc)]
    else:
        colors = [(1.0, 1.0, 1.0, 1.0)] * vc
    uv0 = uv1 = None
    if flags & FLAG_UV0:
        uv0 = planar(2) if precise else quantized(2)
    if flags & FLAG_UV1:
        uv1 = planar(2) if precise else quantized(2)
    indices = []
    prev = 0
    for _ in range(ic):
        n = shift = 0
        while True:
            b = body[pos]
            pos += 1
            n |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        prev += (n >> 1) if not n & 1 else -((n + 1) >> 1)
        indices.append(prev)
    return {"positions": positions, "normals": normals, "colors": colors, "uv0": uv0, "uv1": uv1,
            "indices": indices, "diffuse": names[0], "lightmap": names[1], "version": 2, "precise": precise}


def read_mdl(path):
    """Reads an MDL1 or MDL2 file. Returns a dict: positions, normals, colors, uv0, uv1 (lists of tuples, None when the
    file has none), indices (flat list), diffuse and lightmap (texture names, "" when absent), version (1 or 2) and
    precise (False for a quantized MDL2). MDL2 values are the decoded (slightly rounded) ones."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] == b"MDL1":
        return _read_v1(data)
    if data[:4] == b"MDL2":
        return _read_v2(data)
    raise ValueError("%s is not an MDL1 or MDL2 file" % path)


# ---------------------------------------------------------------- command line

def _convert(a):
    m = read_mdl(a.input)
    if m["version"] == 2 and not m["precise"] and not a.mdl1 and a.precise:
        print("note: %s is already quantized, --precise cannot bring the lost precision back" % a.input, file=sys.stderr)
    size = write_mdl(a.output, m["positions"], m["indices"], normals=m["normals"], colors=m["colors"], uv0=m["uv0"],
                     uv1=m["uv1"], diffuse=m["diffuse"], lightmap=m["lightmap"], **format_options(a))
    import os
    before = os.path.getsize(a.input)
    print("%s: %d -> %d bytes (%.0f%%)" % (a.output, before, size, 100.0 * size / before))


def _info(a):
    import os
    for path in a.files:
        m = read_mdl(path)
        kind = "MDL1" if m["version"] == 1 else ("MDL2 precise" if m["precise"] else "MDL2")
        print("%s: %s, %d vertices, %d triangles, normals=%s uv0=%s uv1=%s, diffuse=%r lightmap=%r, %d bytes" % (
            path, kind, len(m["positions"]), len(m["indices"]) // 3, m["normals"] is not None, m["uv0"] is not None,
            m["uv1"] is not None, m["diffuse"], m["lightmap"], os.path.getsize(path)))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command")
    c = sub.add_parser("convert", help="rewrite a model (MDL1 or MDL2) in MDL2 or MDL1")
    c.add_argument("input")
    c.add_argument("output")
    add_format_args(c)
    i = sub.add_parser("info", help="print format and sizes of models")
    i.add_argument("files", nargs="+")
    sub = ap._subparsers._group_actions[0]
    if cli.run_help(ap, sys.argv[1:] if argv is None else argv, sub):
        return
    a = ap.parse_args(argv)
    if a.command == "convert":
        _convert(a)
    elif a.command == "info":
        _info(a)
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
