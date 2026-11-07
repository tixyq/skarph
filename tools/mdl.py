# Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
"""Reader and writer for the binary model format MDL1 (see skarph.core.ModelFormat for the layout). No packages needed."""
import struct

FLAG_NORMALS = 1
FLAG_COLORS = 2
FLAG_UV0 = 4
FLAG_UV1 = 8


def _string(s):
    raw = (s or "").encode("utf-8")
    return struct.pack("<i", len(raw)) + raw + b"\0" * ((4 - len(raw) % 4) % 4)


def write_mdl(path, positions, indices, normals=None, colors=None,
              uv0=None, uv1=None, diffuse="", lightmap=""):
    """positions: [(x,y,z)], indices: [i0,i1,i2,...], normals: [(x,y,z)],
    colors: [(r,g,b,a)] floats 0..1 (default white), uv0/uv1: [(u,v)] (v = 0 is the TOP of the image)."""
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
    flags = FLAG_COLORS
    if normals is not None:
        flags |= FLAG_NORMALS
    if uv0 is not None:
        flags |= FLAG_UV0
    if uv1 is not None:
        flags |= FLAG_UV1
    out = bytearray()
    out += b"MDL1" + struct.pack("<iii", vc, len(indices), flags)
    out += _string(diffuse) + _string(lightmap)
    for p in positions:
        out += struct.pack("<fff", *p)
    if normals is not None:
        for n in normals:
            out += struct.pack("<fff", *n)
    for i in range(vc):
        c = colors[i] if colors is not None else (1.0, 1.0, 1.0, 1.0)
        out += bytes(max(0, min(255, int(round(v * 255)))) for v in c)
    if uv0 is not None:
        for t in uv0:
            out += struct.pack("<ff", *t)
    if uv1 is not None:
        for t in uv1:
            out += struct.pack("<ff", *t)
    out += struct.pack("<%dH" % len(indices), *indices)
    with open(path, "wb") as f:
        f.write(out)
    return len(out)


def _read_string(data, off):
    (n,) = struct.unpack_from("<i", data, off)
    off += 4
    return data[off:off + n].decode("utf-8"), off + ((n + 3) & ~3)


def read_mdl(path):
    """Reads an MDL1 file. Returns a dict: positions, normals, colors, uv0, uv1 (lists of tuples, None when the file has
    none), indices (flat list), diffuse and lightmap (texture names, "" when absent)."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"MDL1":
        raise ValueError("%s is not an MDL1 file" % path)
    vc, ic, flags = struct.unpack_from("<iii", data, 4)
    diffuse, off = _read_string(data, 16)
    lightmap, off = _read_string(data, off)

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
            "indices": indices, "diffuse": diffuse, "lightmap": lightmap}
