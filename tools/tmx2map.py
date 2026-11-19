#!/usr/bin/env python3
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
"""Converts a Tiled (https://www.mapeditor.org) .tmx map to the SMP1 file that skarph.core.TileMap.load() reads.

  python3 tools/tmx2map.py level1.tmx assets/maps/level1.map

Supports orthogonal maps (finite), tile layers in CSV or base64 (plain, zlib or gzip), embedded or external (.tsx)
tilesets, object layers (rectangles and points), layer parallax and visibility. Tile properties (boolean) give tile flags:
solid, oneway, ladder, hazard, water, user1, user2, user3. A layer named "collision" (or with a boolean property
collision=true) is the collision layer. Only the first tileset's ids are used (tile id = gid - firstgid + 1), so keep
one tileset per map. Flipped / rotated tiles are not supported (the flip bits are dropped).
"""
import base64
import gzip
import os
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

FLAGS = {"solid": 1, "oneway": 2, "ladder": 4, "hazard": 8, "water": 16, "user1": 32, "user2": 64, "user3": 128}


def props(node):
    out = {}
    p = node.find("properties")
    if p is not None:
        for e in p.findall("property"):
            out[e.get("name")] = e.get("value")
    return out


def truthy(v):
    return str(v).lower() in ("true", "1", "yes")


def decode_layer(data, w, h):
    enc = data.get("encoding")
    text = data.text or ""
    if enc == "csv":
        ids = [int(x) for x in text.replace("\n", "").split(",") if x.strip() != ""]
    elif enc == "base64":
        raw = base64.b64decode(text.strip())
        comp = data.get("compression")
        if comp == "zlib":
            raw = zlib.decompress(raw)
        elif comp == "gzip":
            raw = gzip.decompress(raw)
        elif comp:
            sys.exit("compression %s is not supported (use CSV, zlib or gzip)" % comp)
        ids = list(struct.unpack("<%dI" % (len(raw) // 4), raw))
    else:
        ids = [int(t.get("gid", "0")) for t in data.findall("tile")]
    if len(ids) != w * h:
        sys.exit("layer data has %d tiles, expected %d" % (len(ids), w * h))
    return ids


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]
    root = ET.parse(src).getroot()
    if root.get("orientation", "orthogonal") != "orthogonal":
        sys.exit("only orthogonal maps are supported")
    if root.get("infinite") == "1":
        sys.exit("infinite maps are not supported: Map properties > uncheck Infinite")
    w, h = int(root.get("width")), int(root.get("height"))
    tw, th = int(root.get("tilewidth")), int(root.get("tileheight"))
    ts = root.find("tileset")
    if ts is None:
        sys.exit("no tileset")
    first = int(ts.get("firstgid", "1"))
    if ts.get("source"):
        ts = ET.parse(os.path.join(os.path.dirname(src), ts.get("source"))).getroot()
    flags = {}
    for t in ts.findall("tile"):
        f = 0
        for k, v in props(t).items():
            if k in FLAGS and truthy(v):
                f |= FLAGS[k]
        if f:
            flags[int(t.get("id")) + 1] = f
    layers, objects = [], []
    coll = None
    for node in root:
        if node.tag == "layer":
            p = props(node)
            ids = decode_layer(node.find("data"), w, h)
            ids = [0 if g == 0 else (g & 0x1FFFFFFF) - first + 1 for g in ids]
            if any(i < 0 or i > 65535 for i in ids):
                sys.exit("tile ids out of range (more than one tileset?)")
            fl = 1 if node.get("visible", "1") != "0" else 0
            if node.get("name", "").lower() == "collision" or truthy(p.get("collision", "false")):
                fl |= 2
                coll = len(layers)
            px = int(round(float(node.get("parallaxx", "1")) * 1000))
            py = int(round(float(node.get("parallaxy", "1")) * 1000))
            layers.append((fl, px, py, ids))
        elif node.tag == "objectgroup":
            for o in node.findall("object"):
                objects.append((int(o.get("id")), float(o.get("x")), float(o.get("y")), float(o.get("width", "0")),
                                float(o.get("height", "0")), o.get("name", ""), o.get("type") or o.get("class") or ""))
    if coll is None and layers:
        sys.stderr.write("warning: no layer named 'collision': the first layer is used for collisions\n")
        layers[0] = (layers[0][0] | 2,) + layers[0][1:]
    if len(layers) > 255:
        sys.exit("too many layers")
    out = b"SMP1" + struct.pack(">HHHHBHH", w, h, tw, th, len(layers), len(flags), len(objects))
    for i, f in sorted(flags.items()):
        out += struct.pack(">HB", i, f)
    for fl, px, py, ids in layers:
        out += struct.pack(">Bhh", fl, px, py) + struct.pack(">%dH" % len(ids), *ids)
    for i, x, y, ow, oh, name, typ in objects:
        nb, tb = name.encode("utf-8")[:255], typ.encode("utf-8")[:255]
        out += struct.pack(">H4f", i, x, y, ow, oh) + bytes([len(nb)]) + nb + bytes([len(tb)]) + tb
    with open(dst, "wb") as f:
        f.write(out)
    print("%dx%d tiles, %d layers, %d objects, %d flagged tiles -> %s" % (w, h, len(layers), len(objects), len(flags), dst))


main()
