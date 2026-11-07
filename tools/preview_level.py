#!/usr/bin/env python3
# Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
"""Software preview of a lightmapped model (no GPU needed): renders it the way the engine does
(diffuse texture x lightmap, back faces culled) from an orbit-style camera and writes a PNG.

usage: preview_level.py model.mdl output.png [--eye x y z] [--target x y z] [--size 900]
Textures are looked up next to the model file."""
import argparse
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mdl


def read_mdl(path):
    m = mdl.read_mdl(path)
    pos = np.array(m["positions"], dtype=np.float64).reshape(-1, 3)
    nor = np.array(m["normals"], dtype=np.float64).reshape(-1, 3) if m["normals"] else None
    col = np.array(m["colors"], dtype=np.float64).reshape(-1, 4) if m["colors"] else None
    uv0 = np.array(m["uv0"], dtype=np.float64).reshape(-1, 2) if m["uv0"] else None
    uv1 = np.array(m["uv1"], dtype=np.float64).reshape(-1, 2) if m["uv1"] else None
    idx = np.array(m["indices"], dtype=int).reshape(-1, 3)
    return pos, nor, col, uv0, uv1, idx, [m["diffuse"] or None, m["lightmap"] or None]


def sample(img, uv, repeat):
    h, w = img.shape[:2]
    u, v = uv[..., 0], uv[..., 1]
    if repeat:
        u, v = u % 1.0, v % 1.0
    x = np.clip((u * w).astype(int), 0, w - 1)
    y = np.clip((v * h).astype(int), 0, h - 1)
    return img[y, x]


def load_scene(path):
    """A .mdl gives one model; a .lvl gives every 'object' line (position, yaw, scale; pitch/roll are ignored)."""
    base = os.path.dirname(os.path.abspath(path))
    if not path.endswith(".lvl"):
        return [(path, np.zeros(3), 0.0, np.ones(3))]
    out = []
    for line in open(path):
        t = line.split("#")[0].split()
        if t and t[0] == "object":
            v = [float(x) for x in t[2:]] + [0, 0, 0, 0, 0, 0, 1, 1, 1][len(t) - 2:]
            out.append((os.path.join(base, t[1]), np.array(v[0:3]), v[4], np.array(v[6:9])))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model", nargs="+", help="one or more .mdl or .lvl files, the LAST argument is the output PNG")
    ap.add_argument("--eye", type=float, nargs=3, default=(0, 34, 42))
    ap.add_argument("--target", type=float, nargs=3, default=(0, 0.5, 0))
    ap.add_argument("--size", type=int, default=900)
    ap.add_argument("--fov", type=float, default=50.0)
    ap.add_argument("--sky", type=float, nargs=3, default=(0.04, 0.05, 0.07), help="background color 0..1")
    a = ap.parse_args()
    out_path = a.model.pop()
    W = H = a.size
    img = np.zeros((H, W, 3)); img[:] = a.sky
    zbuf = np.full((H, W), 1e9)
    eye, tgt = np.array(a.eye), np.array(a.target)
    f = tgt - eye; f /= np.linalg.norm(f)
    s = np.cross(f, [0, 1, 0]); s /= np.linalg.norm(s)
    u = np.cross(s, f)
    t = 1.0 / np.tan(np.radians(a.fov) / 2)
    cache = {}
    items = []
    for m in a.model:
        items += load_scene(m)
    for path, move, yaw, scale in items:
        if path not in cache:
            cache[path] = read_mdl(path)
        pos, nor, col, uv0, uv1, idx, names = cache[path]
        base = os.path.dirname(os.path.abspath(path))
        diffuse = np.asarray(Image.open(os.path.join(base, names[0])).convert("RGB")) / 255.0 if names[0] else None
        lightmap = np.asarray(Image.open(os.path.join(base, names[1])).convert("RGB")) / 255.0 if names[1] else None
        c, sn = np.cos(np.radians(yaw)), np.sin(np.radians(yaw))
        world = (pos * scale) @ np.array([[c, 0, -sn], [0, 1, 0], [sn, 0, c]]) + move
        render_model(img, zbuf, world, col, uv0, uv1, idx, diffuse, lightmap, eye, s, u, f, t, W, H)
    Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).save(out_path)
    print("wrote", out_path)


def render_model(img, zbuf, pos, col, uv0, uv1, idx, diffuse, lightmap, eye, s, u, f, t, W, H):
    rel = pos - eye
    cam = np.stack([rel @ s, rel @ u, rel @ f], axis=1)      # x right, y up, z forward
    z = np.maximum(cam[:, 2], 1e-3)
    sx = (cam[:, 0] * t / z * 0.5 + 0.5) * W
    sy = (0.5 - cam[:, 1] * t / z * 0.5) * H
    for tri in idx:
        if (cam[tri, 2] <= 0.01).any():
            continue
        x, y = sx[tri], sy[tri]
        area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0])
        if area >= 0:             # screen y points down, so counter-clockwise triangles have negative area
            continue
        x0, x1 = max(int(x.min()), 0), min(int(x.max()) + 1, W - 1)
        y0, y1 = max(int(y.min()), 0), min(int(y.max()) + 1, H - 1)
        if x0 > x1 or y0 > y1:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        w0 = ((x[1] - gx) * (y[2] - gy) - (x[2] - gx) * (y[1] - gy)) / area
        w1 = ((x[2] - gx) * (y[0] - gy) - (x[0] - gx) * (y[2] - gy)) / area
        w2 = 1 - w0 - w1
        inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        if not inside.any():
            continue
        invz = w0 / z[tri[0]] + w1 / z[tri[1]] + w2 / z[tri[2]]   # perspective correct interpolation
        depth = 1.0 / invz
        def interp(attr):
            return (attr[tri[0]] * (w0 / z[tri[0]])[..., None] + attr[tri[1]] * (w1 / z[tri[1]])[..., None]
                    + attr[tri[2]] * (w2 / z[tri[2]])[..., None]) * depth[..., None]
        color = np.ones(gx.shape + (3,))
        if col is not None:
            color = color * interp(col[:, :3])
        if diffuse is not None and uv0 is not None:
            color = color * sample(diffuse, interp(uv0), True)
        if lightmap is not None and uv1 is not None:
            color = color * sample(lightmap, interp(uv1), False)
        sub = zbuf[y0:y1 + 1, x0:x1 + 1]
        win = inside & (depth < sub)
        sub[win] = depth[win]
        img[y0:y1 + 1, x0:x1 + 1][win] = color[win]


if __name__ == "__main__":
    main()
