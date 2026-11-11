#!/usr/bin/env python3
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
"""Converts a glTF 2.0 file (.gltf or .glb) into the engine model format.

usage: gltf2mdl.py input.gltf output.mdl [--scale S] [--diffuse name.png] [--lightmap name.png] [--mdl1] [--precise]

- all triangle primitives of all meshes are merged into one model
- reads POSITION, NORMAL (flat normals are generated if missing), TEXCOORD_0, TEXCOORD_1, COLOR_0
- without COLOR_0 the material baseColorFactor is used as vertex color (white if it has a texture)
- texture: --diffuse, otherwise the first baseColorTexture image (file name only)
- node transforms are IGNORED: apply them when exporting
- glTF already uses Y up, counter-clockwise faces and v = 0 at the top of the image
"""
import os
import sys
import json
import struct
import base64
import math
import argparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cli
from mdl import write_mdl, add_format_args, format_options

COMP = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2), 5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}
NORM_DIV = {5120: 127.0, 5121: 255.0, 5122: 32767.0, 5123: 65535.0}


def load(path):
    base = os.path.dirname(os.path.abspath(path))
    with open(path, "rb") as f:
        data = f.read()
    glb_bin = None
    if data[:4] == b"glTF":
        off, js = 12, None
        while off < len(data):
            clen, ctype = struct.unpack_from("<I4s", data, off)
            chunk = data[off + 8:off + 8 + clen]
            if ctype == b"JSON":
                js = json.loads(chunk.decode("utf-8"))
            elif ctype == b"BIN\0":
                glb_bin = chunk
            off += 8 + clen
        doc = js
    else:
        doc = json.loads(data.decode("utf-8"))
    buffers = []
    for i, b in enumerate(doc.get("buffers", [])):
        uri = b.get("uri")
        if uri is None:
            buffers.append(glb_bin)
        elif uri.startswith("data:"):
            buffers.append(base64.b64decode(uri.split(",", 1)[1]))
        else:
            with open(os.path.join(base, uri), "rb") as f:
                buffers.append(f.read())
    return doc, buffers


def read_accessor(doc, buffers, index):
    acc = doc["accessors"][index]
    view = doc["bufferViews"][acc["bufferView"]]
    fmt, size = COMP[acc["componentType"]]
    n = NCOMP[acc["type"]]
    stride = view.get("byteStride") or size * n
    start = view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    buf = buffers[view["buffer"]]
    out = []
    for i in range(acc["count"]):
        vals = struct.unpack_from("<" + fmt * n, buf, start + i * stride)
        if acc.get("normalized") and acc["componentType"] in NORM_DIV:
            vals = tuple(max(-1.0, x / NORM_DIV[acc["componentType"]]) for x in vals)
        out.append(vals if n > 1 else vals[0])
    return out


def convert(path, scale=1.0, diffuse=None):
    doc, buffers = load(path)
    positions, normals, colors, uv0, uv1, indices = [], [], [], [], [], []
    tex = diffuse
    any_uv0 = any_uv1 = False
    for mesh in doc.get("meshes", []):
        for prim in mesh["primitives"]:
            if prim.get("mode", 4) != 4:
                continue
            at = prim["attributes"]
            pos = read_accessor(doc, buffers, at["POSITION"])
            cnt = len(pos)
            idx = read_accessor(doc, buffers, prim["indices"]) if "indices" in prim else list(range(cnt))
            nor = read_accessor(doc, buffers, at["NORMAL"]) if "NORMAL" in at else None
            t0 = read_accessor(doc, buffers, at["TEXCOORD_0"]) if "TEXCOORD_0" in at else None
            t1 = read_accessor(doc, buffers, at["TEXCOORD_1"]) if "TEXCOORD_1" in at else None
            col = read_accessor(doc, buffers, at["COLOR_0"]) if "COLOR_0" in at else None
            color = (1.0, 1.0, 1.0, 1.0)
            if "material" in prim:
                m = doc["materials"][prim["material"]].get("pbrMetallicRoughness", {})
                bt = m.get("baseColorTexture")
                if bt is not None:
                    if tex is None:
                        img = doc["textures"][bt["index"]]["source"]
                        uri = doc["images"][img].get("uri")
                        if uri and not uri.startswith("data:"):
                            tex = os.path.basename(uri)
                else:
                    color = tuple(m.get("baseColorFactor", (1, 1, 1, 1)))
            if nor is None:  # flat normals: un-index the primitive
                npos, nidx = [], []
                nn, nt0, nt1, ncol = [], [], [], []
                for k in range(0, len(idx) - 2, 3):
                    tri = idx[k:k + 3]
                    a, b, c = (pos[i] for i in tri)
                    e1 = [b[j] - a[j] for j in range(3)]
                    e2 = [c[j] - a[j] for j in range(3)]
                    fn = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
                    fl = math.sqrt(sum(x * x for x in fn)) or 1.0
                    fn = tuple(x / fl for x in fn)
                    for i in tri:
                        nidx.append(len(npos))
                        npos.append(pos[i])
                        nn.append(fn)
                        nt0.append(t0[i] if t0 else None)
                        nt1.append(t1[i] if t1 else None)
                        ncol.append(col[i] if col else None)
                pos, idx, nor = npos, nidx, nn
                t0 = nt0 if t0 else None
                t1 = nt1 if t1 else None
                col = ncol if col else None
            base = len(positions)
            for i in range(len(pos)):
                positions.append(tuple(x * scale for x in pos[i]))
                normals.append(tuple(nor[i]))
                if col:
                    c = tuple(col[i]) + ((1.0,) if len(col[i]) == 3 else ())
                else:
                    c = color
                colors.append(c)
                uv0.append(tuple(t0[i]) if t0 else (0.0, 0.0))
                uv1.append(tuple(t1[i]) if t1 else (0.0, 0.0))
            any_uv0 |= t0 is not None
            any_uv1 |= t1 is not None
            indices += [base + i for i in idx]
    if not positions:
        raise ValueError("no triangle primitives found in " + path)
    return positions, indices, normals, colors, (uv0 if any_uv0 else None), (uv1 if any_uv1 else None), tex


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--scale", type=float, default=1.0)
    ap.add_argument("--diffuse", default=None)
    ap.add_argument("--lightmap", default="")
    add_format_args(ap)
    ap.add_argument("-help", action="help", help=argparse.SUPPRESS)
    if cli.run_help(ap):
        return
    a = ap.parse_args()
    pos, idx, nor, col, uv0, uv1, tex = convert(a.input, a.scale, a.diffuse)
    size = write_mdl(a.output, pos, idx, normals=nor, colors=col, uv0=uv0, uv1=uv1,
                     diffuse=tex or "", lightmap=a.lightmap, **format_options(a))
    print("%s: %d vertices, %d triangles, %d bytes, texture=%s" % (a.output, len(pos), len(idx) // 3, size, tex))


if __name__ == "__main__":
    main()
