#!/usr/bin/env python3
# Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
"""Converts a Wavefront OBJ (with optional MTL) into the engine model format.

usage: obj2mdl.py input.obj output.mdl [--scale S] [--diffuse name.png] [--lightmap name.png]

- polygons are triangulated (fan), v/vt/vn index forms and negative indices are supported
- normals: taken from 'vn'; faces without them get flat face normals
- colors: the Kd color of the material of each face (white if the material has a texture)
- texture: --diffuse, otherwise the first map_Kd of the MTL (file name only, no folders)
- V is flipped (OBJ has v = 0 at the bottom, the engine has v = 0 at the top of the image)
- units and axes are kept (Y up, counter-clockwise front faces)
"""
import os
import sys
import math
import argparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mdl import write_mdl


def read_mtl(path):
    mats, cur = {}, None
    if not os.path.exists(path):
        return mats
    with open(path) as f:
        for line in f:
            t = line.split()
            if not t:
                continue
            if t[0] == "newmtl":
                cur = mats[t[1]] = {"kd": (0.8, 0.8, 0.8), "map": None}
            elif cur is not None and t[0] == "Kd" and len(t) >= 4:
                cur["kd"] = tuple(float(x) for x in t[1:4])
            elif cur is not None and t[0] == "map_Kd":
                cur["map"] = os.path.basename(t[-1])
    return mats


def convert(obj_path, scale=1.0, diffuse=None):
    v, vt, vn, mats = [], [], [], {}
    faces = []  # (material name, [(vi, ti, ni), ...])
    mat = None
    base = os.path.dirname(os.path.abspath(obj_path))
    with open(obj_path) as f:
        for line in f:
            t = line.split()
            if not t or t[0].startswith("#"):
                continue
            if t[0] == "v":
                v.append(tuple(float(x) * scale for x in t[1:4]))
            elif t[0] == "vt":
                vt.append((float(t[1]), 1.0 - float(t[2]) if len(t) > 2 else 0.0))
            elif t[0] == "vn":
                vn.append(tuple(float(x) for x in t[1:4]))
            elif t[0] == "mtllib":
                mats.update(read_mtl(os.path.join(base, t[1])))
            elif t[0] == "usemtl":
                mat = t[1]
            elif t[0] == "f":
                refs = []
                for tok in t[1:]:
                    p = (tok.split("/") + ["", ""])[:3]
                    def idx(s, n):
                        if s == "":
                            return None
                        i = int(s)
                        return i - 1 if i > 0 else n + i
                    refs.append((idx(p[0], len(v)), idx(p[1], len(vt)), idx(p[2], len(vn))))
                faces.append((mat, refs))
    if not v or not faces:
        raise ValueError("no geometry found in " + obj_path)

    has_uv = any(r[1] is not None for _, refs in faces for r in refs)
    positions, normals, colors, uvs, indices, cache = [], [], [], [], [], {}
    tex = diffuse
    for mname, refs in faces:
        m = mats.get(mname)
        if tex is None and m and m["map"]:
            tex = m["map"]
        color = (1.0, 1.0, 1.0, 1.0)
        if m and not m["map"]:
            color = m["kd"] + (1.0,)
        # flat face normal, used when the file has no vn for this face
        a, b, c = v[refs[0][0]], v[refs[1][0]], v[refs[2][0]]
        e1 = [b[i] - a[i] for i in range(3)]
        e2 = [c[i] - a[i] for i in range(3)]
        fn = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
        fl = math.sqrt(sum(x * x for x in fn)) or 1.0
        fn = tuple(round(x / fl, 5) for x in fn)
        ids = []
        for vi, ti, ni in refs:
            n = vn[ni] if ni is not None else fn
            key = (vi, ti, n, color)
            if key not in cache:
                cache[key] = len(positions)
                positions.append(v[vi])
                normals.append(n)
                colors.append(color)
                uvs.append(vt[ti] if ti is not None else (0.0, 0.0))
            ids.append(cache[key])
        for k in range(1, len(ids) - 1):  # fan triangulation
            indices += [ids[0], ids[k], ids[k + 1]]
    return positions, indices, normals, colors, (uvs if has_uv else None), tex


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--scale", type=float, default=1.0)
    ap.add_argument("--diffuse", default=None)
    ap.add_argument("--lightmap", default="")
    a = ap.parse_args()
    pos, idx, nor, col, uv, tex = convert(a.input, a.scale, a.diffuse)
    size = write_mdl(a.output, pos, idx, normals=nor, colors=col, uv0=uv,
                     diffuse=tex or "", lightmap=a.lightmap)
    print("%s: %d vertices, %d triangles, %d bytes, texture=%s" % (a.output, len(pos), len(idx) // 3, size, tex))


if __name__ == "__main__":
    main()
