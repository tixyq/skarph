# Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
"""Lightmap baker for big maps: a terrain height map and building models. numpy + Pillow.

The engine has no real time shadows: sun shadows, soft penumbra, sky occlusion and contact darkening are all baked here.
The lightmap holds the COMPLETE lighting (ambient + sun + lamps), the engine multiplies vertex color x diffuse x lightmap
and never lights lightmapped meshes dynamically.

  bake_map.py terrain heightmap.png out_lm.png --size 160 --height 24 [--lightmap-size 512] [--mdl in.mdl out.mdl]
      One planar lightmap for a whole height map. heightmap.png is a square 8 or 16 bit grayscale image (row 0 = most
      negative z, column 0 = most negative x, white = --height above --base). The terrain is centred on the origin and
      --size world units wide. A terrain model must use uv1 = ((x + size / 2) / size, (z + size / 2) / size);
      --mdl writes exactly that into a copy of your terrain model and names the lightmap in it.

  bake_map.py model in.mdl out.mdl out_lm.png [--density 3.0]
      Buildings and props: every quad (two triangles a b c / a c d) and every loose triangle of the model gets its own
      rectangle in a lightmap atlas, so the output model has unshared vertices and a new uv1. It must have at most 65535
      vertices after that (4 per quad, 3 per triangle); split bigger models.

Both commands take the same light and shadow options:
  --sun dx dy dz            direction TO the sun or moon              (default 0.55 1 0.35)
  --sun-color r g b         color of the sun                          (default 0.62 0.57 0.45)
  --ambient r g b           sky light                                 (default 0.42 0.46 0.55)
  --lamp x y z radius r g b intensity   point light without shadows, repeatable
  --occluder model.mdl      the triangles of this model cast shadows, repeatable (slow with thousands of triangles)
  --box minx miny minz maxx maxy maxz   a box that casts shadows, repeatable (fast)
  --sphere x y z radius     a sphere that casts shadows, repeatable (tree crowns, rocks)
  --heightmap/--size/--height/--base    (model only) the terrain that shadows the model
  --ground-y Y | --no-ground            (model only) the ground plane used for contact darkening, default y = 0
A model is its own occluder; --no-self-shadow turns that off. A big map takes about a minute and needs no GPU.

As a module: configure(), HeightField, Occluders, bake_terrain(), bake_patches() and PatchMesh.
"""
import argparse
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bake_level import _pack
from mdl import read_mdl, write_mdl

SUN_TO_LIGHT = np.array([0.55, 1.0, 0.35]) / np.linalg.norm([0.55, 1.0, 0.35])
SUN_COLOR = np.array([0.62, 0.57, 0.45])
AMBIENT = np.array([0.42, 0.46, 0.55])
SOFT = 0.045                      # sun disc radius for the soft penumbra (about 2.6 degrees)
LAMPS = np.zeros((0, 8))          # point lights: x y z radius r g b intensity (no shadows). Empty = none
GROUND_Y = 0.0                    # ground plane for contact darkening of patches; None = no ground


def configure(sun_to_light, sun_color, ambient, lamps=()):
    """Changes the light of the bake (sun or moon, ambient, point lights). Call before baking."""
    global SUN_TO_LIGHT, SUN_COLOR, AMBIENT, LAMPS
    SUN_TO_LIGHT = np.array(sun_to_light, float) / np.linalg.norm(sun_to_light)
    SUN_COLOR = np.array(sun_color, float)
    AMBIENT = np.array(ambient, float)
    LAMPS = np.array(lamps, float).reshape(-1, 8)


def lamp_light(p, n):
    """Light of all lamps on points p (k,3) with normals n ((3,) or (k,3)): N.L x smooth falloff, NO shadows
    (a lamp ray has a different direction for every texel, so the vectorized tests cannot be used)."""
    total = np.zeros((len(p), 3))
    n = np.broadcast_to(np.asarray(n, float), (len(p), 3))
    for x, y, z, r, cr, cg, cb, k in LAMPS:
        v = np.array([x, y, z]) - p
        d2 = np.einsum("ij,ij->i", v, v)
        near = np.nonzero(d2 < r * r)[0]
        if not len(near):
            continue
        d = np.sqrt(d2[near]) + 1e-6
        ndl = np.maximum(0.0, np.einsum("ij,ij->i", n[near], v[near]) / d)
        att = (1.0 - d2[near] / (r * r)) ** 2
        total[near] += np.array([cr, cg, cb])[None, :] * (k * ndl * att)[:, None]
    return total


# ------------------------------------------------------------------ height field
class HeightField:
    """Bilinear height lookup on the terrain mesh grid."""

    def __init__(self, heights, size):
        self.h = np.asarray(heights, dtype=np.float64)          # (n+1, n+1), row = z, column = x
        self.n = self.h.shape[0] - 1
        self.size = size
        self.cell = size / self.n

    def __call__(self, x, z):
        fx = np.clip((x + self.size / 2) / self.cell, 0, self.n - 1e-6)
        fz = np.clip((z + self.size / 2) / self.cell, 0, self.n - 1e-6)
        i, j = fx.astype(int), fz.astype(int)
        u, v = fx - i, fz - j
        h = self.h
        return (h[j, i] * (1 - u) * (1 - v) + h[j, i + 1] * u * (1 - v)
                + h[j + 1, i] * (1 - u) * v + h[j + 1, i + 1] * u * v)

    def inside(self, x, z):
        r = self.size / 2
        return (np.abs(x) < r) & (np.abs(z) < r)

    def blocked(self, o, d, length=70.0, bias=0.03):
        """Does a ray from o (n,3) along d hit the terrain surface within `length`?"""
        hit = np.zeros(len(o), dtype=bool)
        t = 0.6
        while t < length:
            p = o + d * t
            hit |= self.inside(p[:, 0], p[:, 2]) & (self(p[:, 0], p[:, 2]) > p[:, 1] + bias)
            t *= 1.12
            t += 0.25
        return hit


# ------------------------------------------------------------------ occluders
class Occluders:
    def __init__(self, boxes, tris, spheres):
        self.bmin = np.array([b[0] for b in boxes], float).reshape(-1, 3)
        self.bmax = np.array([b[1] for b in boxes], float).reshape(-1, 3)
        t = np.array(tris, float).reshape(-1, 3, 3)
        self.v0 = t[:, 0]
        self.e1 = t[:, 1] - t[:, 0]
        self.e2 = t[:, 2] - t[:, 0]
        s = np.array(spheres, float).reshape(-1, 4)
        self.sc, self.sr = s[:, :3], s[:, 3]

    def occluded(self, o, d, tmax, spheres=True, step=1024):
        """Ray from every o (n,3) along ONE direction d (3,): does it hit anything within tmax (scalar or (n,))?"""
        n = len(o)
        tmax = np.broadcast_to(np.asarray(tmax, float), (n,))
        out = np.zeros(n, dtype=bool)
        inv = 1.0 / np.where(np.abs(d) < 1e-9, 1e-9, d)
        step = max(16, min(step, 1500000 // max(len(self.v0), len(self.bmin), len(self.sc), 1)))
        if len(self.v0):
            pvec = np.cross(d, self.e2)                       # (m, 3): the same for every ray
            det = np.einsum("ij,ij->i", self.e1, pvec)
            ok = np.abs(det) > 1e-9
            idet = np.where(ok, 1.0 / np.where(ok, det, 1.0), 0.0)
        for s in range(0, n, step):
            oo = o[s:s + step, None, :]
            tm = tmax[s:s + step, None]
            res = np.zeros(len(oo), dtype=bool)
            if len(self.bmin):
                t1 = (self.bmin[None] - oo) * inv
                t2 = (self.bmax[None] - oo) * inv
                tn = np.minimum(t1, t2).max(axis=2)
                tf = np.maximum(t1, t2).min(axis=2)
                res |= ((tn <= tf) & (tf > 1e-3) & (tn < tm)).any(axis=1)
            if len(self.v0):
                tv = oo - self.v0[None]
                u = np.einsum("cmj,mj->cm", tv, pvec) * idet
                q = np.cross(tv, self.e1[None])
                v = (q @ d) * idet
                t = np.einsum("cmj,mj->cm", q, self.e2) * idet
                res |= (ok[None] & (u >= 0) & (v >= 0) & (u + v <= 1) & (t > 1e-3) & (t < tm)).any(axis=1)
            if spheres and len(self.sc):
                oc = oo - self.sc[None]
                b = oc @ d
                c = np.einsum("cmj,cmj->cm", oc, oc) - self.sr[None] ** 2
                disc = b * b - c
                sq = np.sqrt(np.maximum(disc, 0))
                res |= ((disc > 0) & (-b + sq > 1e-3) & (-b - sq < tm)).any(axis=1)
            out[s:s + step] = res
        return out


def sun_dirs(count=3):
    """The sun direction plus jittered ones around it (soft shadow edges)."""
    t = np.cross(SUN_TO_LIGHT, [0, 1, 0]); t /= np.linalg.norm(t)
    b = np.cross(SUN_TO_LIGHT, t)
    out = [SUN_TO_LIGHT]
    for k in range(count - 1):
        a = 2 * math.pi * k / (count - 1) + 0.6
        d = SUN_TO_LIGHT + SOFT * (math.cos(a) * t + math.sin(a) * b)
        out.append(d / np.linalg.norm(d))
    return out


def sun_visibility(o, n, occ, hf, region=None, spheres=True):
    """Fraction of the sun that reaches each point (0 .. 1), soft over a few jittered directions, times N.L."""
    ndl = np.maximum(0.0, n @ SUN_TO_LIGHT)
    vis = np.zeros(len(o))
    live = ndl > 1e-3
    dirs = sun_dirs()
    for d in dirs:
        blocked = np.zeros(len(o), dtype=bool)
        idx = np.nonzero(live)[0]
        if len(idx):
            blocked[idx] = hf.blocked(o[idx], d)
        idx = np.nonzero(live & ~blocked & (region if region is not None else True))[0]
        if len(idx):
            blocked[idx] = occ.occluded(o[idx], d, 1e4, spheres)
        vis += ~blocked
    return ndl * vis / len(dirs)


# ------------------------------------------------------------------ terrain
def bake_terrain(hf, occ, out_png, size=512):
    xs = -hf.size / 2 + (np.arange(size) + 0.5) / size * hf.size
    gx, gz = np.meshgrid(xs, xs)
    x, z = gx.ravel(), gz.ravel()
    y = hf(x, z)
    e = hf.cell
    nx = hf(x - e, z) - hf(x + e, z)
    nz = hf(x, z - e) - hf(x, z + e)
    n = np.stack([nx, np.full_like(x, 2 * e), nz], axis=1)
    n /= np.linalg.norm(n, axis=1)[:, None]
    o = np.stack([x, y + 0.05, z], axis=1)

    # sky visibility from the horizon angle in 8 directions
    vis = np.zeros(len(x))
    for k in range(8):
        a = math.pi * k / 4
        dx, dz = math.cos(a), math.sin(a)
        slope = np.zeros(len(x))
        for dist in (1.5, 3.0, 6.0, 12.0, 24.0):
            slope = np.maximum(slope, (hf(x + dx * dist, z + dz * dist) - y) / dist)
        vis += 1.0 - np.sin(np.arctan(slope))
    vis = 0.45 + 0.55 * (vis / 8)
    # the buildings and trees darken the ground next to them through the sun shadow only (no extra AO)
    sun = sun_visibility(o, n, occ, hf)
    color = AMBIENT[None, :] * (0.55 + 0.45 * n[:, 1])[:, None] * vis[:, None] + SUN_COLOR[None, :] * sun[:, None]
    if len(LAMPS):
        color = color + lamp_light(o, n)
    img = np.clip(color, 0, 1).reshape(size, size, 3)
    Image.fromarray((img * 255 + 0.5).astype(np.uint8), "RGB").save(out_png)
    return {"size": size}


# ------------------------------------------------------------------ patches (buildings)
def _hemisphere(n):
    t = np.cross(n, [0.0, 1.0, 0.0] if abs(n[1]) < 0.9 else [1.0, 0.0, 0.0])
    t /= np.linalg.norm(t)
    bt = np.cross(n, t)
    dirs = []
    for dx, dy in ((0.7, 0), (-0.7, 0), (0, 0.7), (0, -0.7), (0.5, 0.5), (-0.5, -0.5)):
        d = t * dx + bt * dy + n * 0.75
        dirs.append(d / np.linalg.norm(d))
    return dirs


def bake_points(p, n, occ, hf):
    """Complete lighting of surface points p (k,3) with a common normal n (3,)."""
    o = p + n * 0.04
    nn = np.tile(n, (len(p), 1))
    hits = np.zeros(len(p))
    dirs = _hemisphere(n)
    for d in dirs:
        h = occ.occluded(o, d, 3.0, spheres=False)
        if GROUND_Y is not None and d[1] < -0.01:         # the ground plane
            above = o[:, 1] - GROUND_Y
            h |= (-above / d[1] < 3.0) & (above > 0)
        hits += h
    ao = 1.0 - 0.55 * hits / len(dirs)
    sky = 0.55 + 0.45 * max(n[1], 0.0) + 0.1 * max(-n[1], 0.0)
    sun = sun_visibility(o, nn, occ, hf, spheres=False)
    out = AMBIENT[None, :] * (sky * ao)[:, None] + SUN_COLOR[None, :] * sun[:, None]
    if len(LAMPS):
        out = out + lamp_light(o, n) * (0.6 + 0.4 * ao)[:, None]      # lamps: a little less light in the corners
    return np.clip(out, 0, 1)


def _quad_points(c, w, h):
    u = ((np.arange(w) + 0.5) / w)[None, :]
    v = ((np.arange(h) + 0.5) / h)[:, None]
    a, b, cc, d = (np.array(q) for q in c)
    p = ((1 - u) * (1 - v))[..., None] * a + (u * (1 - v))[..., None] * b + (u * v)[..., None] * cc \
        + ((1 - u) * v)[..., None] * d
    return p.reshape(-1, 3)


def _tri_points(t, w, h):
    u = np.broadcast_to(((np.arange(w) + 0.5) / w)[None, :], (h, w)).copy()
    v = np.broadcast_to(((np.arange(h) + 0.5) / h)[:, None], (h, w)).copy()
    over = u + v > 1.0                                   # texels beyond the hypotenuse mirror back inside
    u2, v2 = np.where(over, 1 - v, u), np.where(over, 1 - u, v)
    a, b, c = (np.array(q) for q in t)
    return (a + u2[..., None] * (b - a) + v2[..., None] * (c - a)).reshape(-1, 3)


def bake_patches(mesher, occ, hf, out_png, density=3.0, glow=()):
    """Gives every quad/triangle of the mesher a rectangle in an atlas and bakes it.
    Returns the uv1 list (one per vertex), the atlas size and the number of texels."""
    while True:
        rects = []
        for kind, base in mesher.patches:
            P = [np.array(mesher.pos[base + k]) for k in range(4 if kind == "q" else 3)]
            if kind == "q":
                w = int(min(96, max(2, math.ceil(max(np.linalg.norm(P[1] - P[0]), np.linalg.norm(P[2] - P[3])) * density))))
                h = int(min(96, max(2, math.ceil(max(np.linalg.norm(P[3] - P[0]), np.linalg.norm(P[2] - P[1])) * density))))
            else:
                w = int(min(96, max(2, math.ceil(np.linalg.norm(P[1] - P[0]) * density))))
                h = int(min(96, max(2, math.ceil(np.linalg.norm(P[2] - P[0]) * density))))
            rects.append((w, h))
        try:
            size, pos = _pack([(w + 2, h + 2) for w, h in rects], sizes=(256, 512, 1024))
            break
        except ValueError:
            density *= 0.85
    atlas = np.zeros((size, size, 3))
    uv1 = [None] * len(mesher.pos)
    for k, (kind, base) in enumerate(mesher.patches):
        w, h = rects[k]
        px, py = pos[k][0] + 1, pos[k][1] + 1
        P = [tuple(mesher.pos[base + i]) for i in range(4 if kind == "q" else 3)]
        n = np.array(mesher.nor[base])
        pts = _quad_points(P, w, h) if kind == "q" else _tri_points(P, w, h)
        if k in glow:                                     # self lit: windows, lamp heads (lightmap = full white)
            tex = np.ones((h, w, 3))
        else:
            tex = bake_points(pts, n, occ, hf).reshape(h, w, 3)
        atlas[py:py + h, px:px + w] = tex
        atlas[py - 1, px:px + w] = tex[0]
        atlas[py + h, px:px + w] = tex[-1]
        atlas[py:py + h, px - 1] = tex[:, 0]
        atlas[py:py + h, px + w] = tex[:, -1]
        atlas[py - 1, px - 1] = tex[0, 0]
        atlas[py - 1, px + w] = tex[0, -1]
        atlas[py + h, px - 1] = tex[-1, 0]
        atlas[py + h, px + w] = tex[-1, -1]
        corners = ((0, 0), (1, 0), (1, 1), (0, 1)) if kind == "q" else ((0, 0), (1, 0), (0, 1))
        for i, (cu, cv) in enumerate(corners):
            uv1[base + i] = ((px + cu * w) / size, (py + cv * h) / size)
    Image.fromarray((atlas * 255 + 0.5).astype(np.uint8), "RGB").save(out_png)
    return uv1, size, sum(w * h for w, h in rects), density


class NoTerrain:
    """Stands in for the height field when a model is baked without a terrain."""

    def blocked(self, o, d, length=70.0, bias=0.03):
        return np.zeros(len(o), dtype=bool)


def _patch_normal(a, b, c):
    n = np.cross(b - a, c - a)
    length = np.linalg.norm(n)
    return n / length if length > 1e-12 else None


def _quad_ok(p):
    """Is a, b, c, d a flat convex quad (then the bilinear patch mapping is exact)?"""
    n = _patch_normal(p[0], p[1], p[2])
    if n is None:
        return False
    scale = max(np.linalg.norm(p[(i + 1) % 4] - p[i]) for i in range(4))
    if abs(np.dot(p[3] - p[0], n)) > 1e-3 * scale:
        return False
    for i in range(4):
        if np.dot(np.cross(p[(i + 1) % 4] - p[i], p[(i + 2) % 4] - p[(i + 1) % 4]), n) <= 0:
            return False
    return True


class PatchMesh:
    """A model cut into patches for bake_patches(). Two triangles a b c / a c d that form a flat convex quad become one quad
    patch, every other triangle a triangle patch; degenerate triangles are dropped. Every patch owns its vertices."""

    def __init__(self, model):
        verts = [np.array(p, float) for p in model["positions"]]
        src_nor = model["normals"]
        tris = [tuple(model["indices"][i:i + 3]) for i in range(0, len(model["indices"]), 3)]
        self.model = model
        self.pos, self.nor, self.src, self.patches, self.indices = [], [], [], [], []
        k = 0
        while k < len(tris):
            a, b, c = tris[k]
            n = _patch_normal(verts[a], verts[b], verts[c])
            if n is None:
                k += 1
                continue
            if src_nor is not None and np.dot(n, src_nor[a]) < 0:
                n = -n
            quad = None
            if k + 1 < len(tris):
                a2, c2, d = tris[k + 1]
                if a2 == a and c2 == c and d not in (a, b, c) and _quad_ok([verts[a], verts[b], verts[c], verts[d]]):
                    quad = (a, b, c, d)
            ids = quad or (a, b, c)
            base = len(self.pos)
            for i in ids:
                self.pos.append(verts[i])
                self.nor.append(n)
                self.src.append(i)
            self.patches.append(("q" if quad else "t", base))
            self.indices += [base, base + 1, base + 2] + ([base, base + 2, base + 3] if quad else [])
            k += 2 if quad else 1

    def triangles(self):
        return [(self.pos[self.indices[i]], self.pos[self.indices[i + 1]], self.pos[self.indices[i + 2]])
                for i in range(0, len(self.indices), 3)]

    def write(self, path, uv1, lightmap_name):
        m, src = self.model, self.src
        pick = lambda arr: None if arr is None else [arr[i] for i in src]
        return write_mdl(path, [tuple(float(x) for x in p) for p in self.pos], self.indices, normals=pick(m["normals"]),
                         colors=pick(m["colors"]), uv0=pick(m["uv0"]), uv1=uv1, diffuse=m["diffuse"],
                         lightmap=lightmap_name)


def load_heightfield(path, size, height, base=0.0):
    """Reads a square 8 or 16 bit grayscale PNG as a height field of `size` world units."""
    img = Image.open(path)
    if img.mode not in ("L", "I", "I;16", "I;16B", "I;16L"):
        img = img.convert("L")
    a = np.asarray(img, dtype=np.float64)
    if a.ndim != 2 or a.shape[0] != a.shape[1] or a.shape[0] < 3:
        raise ValueError("%s must be a square grayscale image of at least 3 x 3 pixels" % path)
    return HeightField(base + a / (255.0 if img.mode == "L" else 65535.0) * height, size)


def read_triangles(path):
    m = read_mdl(path)
    p, idx = m["positions"], m["indices"]
    return [(p[idx[i]], p[idx[i + 1]], p[idx[i + 2]]) for i in range(0, len(idx), 3)]


def _add_light_args(ap):
    ap.add_argument("--sun", type=float, nargs=3, metavar=("X", "Y", "Z"), help="direction to the sun")
    ap.add_argument("--sun-color", type=float, nargs=3, metavar=("R", "G", "B"))
    ap.add_argument("--ambient", type=float, nargs=3, metavar=("R", "G", "B"))
    ap.add_argument("--lamp", type=float, nargs=8, action="append", metavar="V",
                    help="x y z radius r g b intensity (no shadows), repeatable")
    ap.add_argument("--occluder", action="append", metavar="MDL", help="model whose triangles cast shadows, repeatable")
    ap.add_argument("--box", type=float, nargs=6, action="append", metavar="V",
                    help="minx miny minz maxx maxy maxz, repeatable")
    ap.add_argument("--sphere", type=float, nargs=4, action="append", metavar="V", help="x y z radius, repeatable")


def _apply_light(a):
    configure(a.sun or SUN_TO_LIGHT, a.sun_color or SUN_COLOR, a.ambient or AMBIENT, a.lamp or ())


def _occluders(a, own_triangles=()):
    tris = list(own_triangles)
    for path in a.occluder or ():
        tris += read_triangles(path)
    boxes = [(v[:3], v[3:]) for v in a.box or ()]
    return Occluders(boxes, tris, a.sphere or [])


def _terrain_cmd(a):
    if a.size <= 0 or a.height < 0 or a.lightmap_size < 16:
        raise ValueError("--size must be positive, --height not negative, --lightmap-size at least 16")
    _apply_light(a)
    hf = load_heightfield(a.heightmap, a.size, a.height, a.base)
    bake_terrain(hf, _occluders(a), a.out, a.lightmap_size)
    print("%s: %d x %d" % (a.out, a.lightmap_size, a.lightmap_size))
    if a.mdl:
        m = read_mdl(a.mdl[0])
        half = a.size / 2.0
        uv1 = [((p[0] + half) / a.size, (p[2] + half) / a.size) for p in m["positions"]]
        write_mdl(a.mdl[1], m["positions"], m["indices"], normals=m["normals"], colors=m["colors"], uv0=m["uv0"],
                  uv1=uv1, diffuse=m["diffuse"], lightmap=os.path.basename(a.out))
        print("%s: uv1 set for %d vertices" % (a.mdl[1], len(uv1)))


def _model_cmd(a):
    global GROUND_Y
    if a.density <= 0:
        raise ValueError("--density must be positive")
    _apply_light(a)
    GROUND_Y = None if a.no_ground else a.ground_y
    mesh = PatchMesh(read_mdl(a.model))
    if not mesh.patches:
        raise ValueError("%s has no usable triangles" % a.model)
    hf = load_heightfield(a.heightmap, a.size, a.height, a.base) if a.heightmap else NoTerrain()
    occ = _occluders(a, () if a.no_self_shadow else mesh.triangles())
    uv1, size, texels, density = bake_patches(mesh, occ, hf, a.out_lightmap, a.density)
    nbytes = mesh.write(a.out, uv1, os.path.basename(a.out_lightmap))
    print("%s: %d patches, %d vertices, %d bytes" % (a.out, len(mesh.patches), len(mesh.pos), nbytes))
    print("%s: %d x %d, %d texels, %.2f texels per unit" % (a.out_lightmap, size, size, texels, density))


def main(argv=None):
    ap = argparse.ArgumentParser(description="Lightmap baker for big maps (see the module header).")
    sub = ap.add_subparsers(dest="command")
    t = sub.add_parser("terrain", help="bake the lightmap of a height map")
    t.add_argument("heightmap")
    t.add_argument("out", help="output lightmap PNG")
    t.add_argument("--size", type=float, required=True, help="terrain width in world units")
    t.add_argument("--height", type=float, required=True, help="world height of a white pixel above --base")
    t.add_argument("--base", type=float, default=0.0, help="world height of a black pixel")
    t.add_argument("--lightmap-size", type=int, default=512)
    t.add_argument("--mdl", nargs=2, metavar=("IN", "OUT"), help="copy a terrain model and set its uv1 and lightmap name")
    _add_light_args(t)
    m = sub.add_parser("model", help="bake the lightmap of a building model")
    m.add_argument("model")
    m.add_argument("out", help="output model with the new uv1")
    m.add_argument("out_lightmap", help="output lightmap PNG")
    m.add_argument("--density", type=float, default=3.0, help="lightmap texels per world unit")
    m.add_argument("--heightmap", help="terrain that casts shadows on the model (needs --size and --height)")
    m.add_argument("--size", type=float, default=0.0)
    m.add_argument("--height", type=float, default=0.0)
    m.add_argument("--base", type=float, default=0.0)
    m.add_argument("--ground-y", type=float, default=0.0)
    m.add_argument("--no-ground", action="store_true")
    m.add_argument("--no-self-shadow", action="store_true")
    _add_light_args(m)
    a = ap.parse_args(argv)
    if a.command is None:
        ap.print_help()
        return 1
    if a.command == "model" and a.heightmap and a.size <= 0:
        ap.error("--heightmap needs --size (and --height)")
    try:
        (_terrain_cmd if a.command == "terrain" else _model_cmd)(a)
    except (OSError, ValueError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
