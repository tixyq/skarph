# Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
"""Builds a level out of axis-aligned boxes and BAKES its lighting into a lightmap. numpy + Pillow.

    bake_level.py level.txt out.mdl [--diffuse stone.png] [--lightmap out_lm.png] [--tile 2.0] [--density 3.0]

Writes the model (uv0 = tiling diffuse texture, uv1 = lightmap atlas) and the lightmap image (ambient occlusion, soft
shadows from a sun and point lights). Real-time shadows do not exist in the engine: all shadows come from this bake.
--diffuse is the texture NAME stored in the model (the file lives in the app assets). --tile is the world size of one
repeat of the diffuse texture, --density the lightmap texels per world unit.

The level file is plain text, one item per line ('#' starts a comment):
    box     minx miny minz  maxx maxy maxz  [name]   a box; its bottom face is skipped when it stands on y = 0
    sun     dx dy dz  r g b                          direction TO the sun, light color        (default 0.5 1 0.3  0.9 0.85 0.7)
    ambient r g b                                    sky light                                (default 0.35 0.4 0.5)
    point   x y z  r g b  radius                     a point light with shadows, any number of them

As a module: bake(boxes, out_model, out_lightmap, diffuse_name, lightmap_name, sun_to_light, sun_color, ambient,
point_lights) with Box objects and point lights as ((x, y, z), (r, g, b), radius).
"""
import argparse
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mdl import write_mdl

TILE_SIZE = 2.0          # world units covered by one repeat of the diffuse texture
DENSITY = 3.0            # lightmap texels per world unit
MAX_FACE_TEXELS = 128


class Box:
    def __init__(self, mn, mx, faces=None, name=""):
        self.mn = np.array(mn, dtype=np.float64)
        self.mx = np.array(mx, dtype=np.float64)
        self.name = name
        if faces is None:  # all faces, except the bottom when the box stands on the floor
            faces = [(a, s) for a in range(3) for s in (-1, 1)]
            if self.mn[1] <= 0.001:
                faces.remove((1, -1))
        self.faces = faces

    @property
    def center(self):
        return (self.mn + self.mx) / 2

    @property
    def half(self):
        return (self.mx - self.mn) / 2


def _faces(boxes):
    """Yields dicts: origin, U, V (world vectors), normal. cross(U, V) == normal (counter-clockwise)."""
    for b in boxes:
        for a, s in b.faces:
            t1, t2 = (a + 1) % 3, (a + 2) % 3
            origin = b.mn.copy()
            origin[a] = b.mx[a] if s > 0 else b.mn[a]
            u = np.zeros(3)
            v = np.zeros(3)
            u[t1] = b.mx[t1] - b.mn[t1]
            v[t2] = b.mx[t2] - b.mn[t2]
            n = np.zeros(3)
            n[a] = s
            if np.dot(np.cross(u, v), n) < 0:      # wrong winding: swap the two tangent axes
                u, v = np.zeros(3), np.zeros(3)
                u[t2] = b.mx[t2] - b.mn[t2]
                v[t1] = b.mx[t1] - b.mn[t1]
            yield {"origin": origin, "U": u, "V": v, "N": n}


def _pack(rects, sizes=(256, 512, 1024)):
    """Shelf packing. rects: list of (w, h) already including the gutter. Returns (atlas size, positions)."""
    order = sorted(range(len(rects)), key=lambda i: -rects[i][1])
    for size in sizes:
        pos = [None] * len(rects)
        x = y = shelf = 0
        ok = True
        for i in order:
            w, h = rects[i]
            if x + w > size:
                x, y, shelf = 0, y + shelf, 0
            if y + h > size:
                ok = False
                break
            pos[i] = (x, y)
            x += w
            shelf = max(shelf, h)
        if ok:
            return size, pos
    raise ValueError("lightmap does not fit into %d x %d, lower DENSITY" % (sizes[-1], sizes[-1]))


class Baker:
    def __init__(self, boxes, sun_to_light, sun_color, ambient, point_lights):
        self.boxes = boxes
        self.bmin = np.array([b.mn for b in boxes])[None]
        self.bmax = np.array([b.mx for b in boxes])[None]
        self.sun_dir = np.array(sun_to_light, dtype=np.float64)
        self.sun_dir /= np.linalg.norm(self.sun_dir)
        self.sun_color = np.array(sun_color)
        self.ambient = np.array(ambient)
        self.points = point_lights

    def occluded(self, o, d, tmax):
        """Vectorized ray vs all boxes. o, d: (n,3); tmax: (n,). Returns bool (n,)."""
        inv = 1.0 / np.where(np.abs(d) < 1e-9, 1e-9, d)
        out = np.zeros(len(o), dtype=bool)
        step = 2048
        for s in range(0, len(o), step):
            oo = o[s:s + step, None, :]
            iv = inv[s:s + step, None, :]
            t1 = (self.bmin - oo) * iv
            t2 = (self.bmax - oo) * iv
            tn = np.minimum(t1, t2).max(axis=2)
            tf = np.maximum(t1, t2).min(axis=2)
            hit = (tn <= tf) & (tf > 1e-3) & (tn < tmax[s:s + step, None])
            out[s:s + step] = hit.any(axis=1)
        return out

    def bake_face(self, face, w, h):
        i = (np.arange(w) + 0.5) / w
        j = (np.arange(h) + 0.5) / h
        gi, gj = np.meshgrid(i, j)                       # (h, w)
        p = (face["origin"][None, None, :] + gi[..., None] * face["U"] + gj[..., None] * face["V"]).reshape(-1, 3)
        n = face["N"]
        o = p + n * 0.03
        count = len(p)
        color = np.zeros((count, 3))

        # ambient with ambient occlusion: a few short rays over the hemisphere
        t = np.cross(n, [0.0, 1.0, 0.0] if abs(n[1]) < 0.9 else [1.0, 0.0, 0.0])
        t /= np.linalg.norm(t)
        bt = np.cross(n, t)
        dirs = []
        for dx, dy in ((0.6, 0), (-0.6, 0), (0, 0.6), (0, -0.6), (0.42, 0.42), (-0.42, 0.42), (0.42, -0.42), (-0.42, -0.42)):
            d = t * dx + bt * dy + n * 0.8
            dirs.append(d / np.linalg.norm(d))
        hits = np.zeros(count)
        for d in dirs:
            hits += self.occluded(o, np.tile(d, (count, 1)), np.full(count, 2.5))
        ao = 1.0 - 0.6 * hits / len(dirs)
        color += self.ambient[None, :] * ao[:, None]

        # sun
        ndl = max(0.0, float(np.dot(n, self.sun_dir)))
        if ndl > 0:
            sh = self.occluded(o, np.tile(self.sun_dir, (count, 1)), np.full(count, 1e6))
            color += (self.sun_color * ndl)[None, :] * (~sh)[:, None]

        # point lights
        for pos, col, radius in self.points:
            to = np.array(pos) - o
            dist = np.linalg.norm(to, axis=1)
            d = to / np.maximum(dist, 1e-6)[:, None]
            ndl = np.maximum(0.0, d @ n)
            att = np.maximum(0.0, 1.0 - dist / radius) ** 2
            live = (ndl > 0) & (att > 0)
            if live.any():
                sh = np.zeros(count, dtype=bool)
                sh[live] = self.occluded(o[live], d[live], dist[live] - 0.05)
                color += np.array(col)[None, :] * (att * ndl * (~sh))[:, None]
        return np.clip(color, 0.0, 1.0).reshape(h, w, 3)


def bake(boxes, out_model, out_lightmap, diffuse_name, lightmap_name, sun_to_light, sun_color,
         ambient, point_lights):
    faces = list(_faces(boxes))
    sizes = []
    for f in faces:
        w = int(min(MAX_FACE_TEXELS, max(2, math.ceil(np.linalg.norm(f["U"]) * DENSITY))))
        h = int(min(MAX_FACE_TEXELS, max(2, math.ceil(np.linalg.norm(f["V"]) * DENSITY))))
        sizes.append((w, h))
    atlas_size, pos = _pack([(w + 2, h + 2) for w, h in sizes])
    atlas = np.zeros((atlas_size, atlas_size, 3))
    baker = Baker(boxes, sun_to_light, sun_color, ambient, point_lights)

    positions, normals, uv0, uv1, indices = [], [], [], [], []
    for k, f in enumerate(faces):
        w, h = sizes[k]
        px, py = pos[k][0] + 1, pos[k][1] + 1
        tex = baker.bake_face(f, w, h)
        atlas[py:py + h, px:px + w] = tex
        atlas[py - 1, px:px + w] = tex[0]            # gutter: replicate the edges so that
        atlas[py + h, px:px + w] = tex[-1]           # linear filtering never reads a neighbour
        atlas[py:py + h, px - 1] = tex[:, 0]
        atlas[py:py + h, px + w] = tex[:, -1]
        atlas[py - 1, px - 1] = tex[0, 0]
        atlas[py - 1, px + w] = tex[0, -1]
        atlas[py + h, px - 1] = tex[-1, 0]
        atlas[py + h, px + w] = tex[-1, -1]

        base = len(positions)
        corners = [(0, 0), (1, 0), (1, 1), (0, 1)]
        ud = f["U"] / np.linalg.norm(f["U"])
        vd = f["V"] / np.linalg.norm(f["V"])
        for cu, cv in corners:
            p = f["origin"] + f["U"] * cu + f["V"] * cv
            positions.append(tuple(p))
            normals.append(tuple(f["N"]))
            uv0.append((float(np.dot(p, ud)) / TILE_SIZE, float(np.dot(p, vd)) / TILE_SIZE))
            uv1.append(((px + cu * w) / atlas_size, (py + cv * h) / atlas_size))
        indices += [base, base + 1, base + 2, base, base + 2, base + 3]

    img = Image.fromarray((atlas * 255 + 0.5).astype(np.uint8), "RGB")
    img.save(out_lightmap)
    size = write_mdl(out_model, positions, indices, normals=normals, uv0=uv0, uv1=uv1,
                     diffuse=diffuse_name, lightmap=lightmap_name)
    return {"faces": len(faces), "vertices": len(positions), "atlas": atlas_size,
            "bytes": size, "texels": sum(w * h for w, h in sizes)}


def _numbers(args, count, key):
    if len(args) != count:
        raise ValueError("'%s' needs %d numbers" % (key, count))
    return [float(x) for x in args]


def parse_level(text):
    """Returns boxes, sun_to_light, sun_color, ambient, point_lights from the text of a level file."""
    boxes, points = [], []
    sun_to_light, sun_color, ambient = (0.5, 1.0, 0.3), (0.9, 0.85, 0.7), (0.35, 0.4, 0.5)
    for no, line in enumerate(text.splitlines(), 1):
        t = line.split("#")[0].split()
        if not t:
            continue
        key, args = t[0], t[1:]
        try:
            if key == "box":
                if len(args) < 6:
                    raise ValueError("'box' needs 6 numbers")
                v = _numbers(args[:6], 6, key)
                if not all(v[i] < v[i + 3] for i in range(3)):
                    raise ValueError("'box' minimum must be smaller than maximum on every axis")
                boxes.append(Box(v[:3], v[3:], name=" ".join(args[6:])))
            elif key == "sun":
                v = _numbers(args, 6, key)
                sun_to_light, sun_color = tuple(v[:3]), tuple(v[3:])
            elif key == "ambient":
                ambient = tuple(_numbers(args, 3, key))
            elif key == "point":
                v = _numbers(args, 7, key)
                if v[6] <= 0:
                    raise ValueError("'point' radius must be positive")
                points.append((tuple(v[:3]), tuple(v[3:6]), v[6]))
            else:
                raise ValueError("unknown keyword '%s'" % key)
        except ValueError as e:
            raise ValueError("line %d: %s" % (no, e))
    if not boxes:
        raise ValueError("the level has no boxes")
    return boxes, sun_to_light, sun_color, ambient, points


def main(argv=None):
    global TILE_SIZE, DENSITY
    ap = argparse.ArgumentParser(description="Bakes the lighting of a box level into a lightmap (see the module header).")
    ap.add_argument("level", help="level text file")
    ap.add_argument("out", help="output model, .mdl")
    ap.add_argument("--lightmap", help="output lightmap PNG (default: next to the model, <name>_lm.png)")
    ap.add_argument("--diffuse", default="", help="diffuse texture name stored in the model (default: none)")
    ap.add_argument("--tile", type=float, default=TILE_SIZE, help="world units per repeat of the diffuse texture")
    ap.add_argument("--density", type=float, default=DENSITY, help="lightmap texels per world unit")
    ap.add_argument("-help", action="help", help=argparse.SUPPRESS)
    if not (sys.argv[1:] if argv is None else argv):
        ap.print_help()
        return 0
    a = ap.parse_args(argv)
    if a.tile <= 0 or a.density <= 0:
        ap.error("--tile and --density must be positive")
    TILE_SIZE, DENSITY = a.tile, a.density
    lightmap = a.lightmap or os.path.splitext(a.out)[0] + "_lm.png"
    try:
        with open(a.level, encoding="utf-8") as f:
            boxes, sun, sun_color, ambient, points = parse_level(f.read())
        info = bake(boxes, a.out, lightmap, a.diffuse, os.path.basename(lightmap), sun, sun_color, ambient, points)
    except (OSError, ValueError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 1
    print("%s: %d faces, %d vertices, %d bytes" % (a.out, info["faces"], info["vertices"], info["bytes"]))
    print("%s: %d x %d, %d texels used" % (lightmap, info["atlas"], info["atlas"], info["texels"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
