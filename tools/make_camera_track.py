#!/usr/bin/env python3
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
"""Bakes a camera track (format CAM1, see skarph.core.CameraTrack): ONE pose per tick, 60 ticks per second.
The engine does no interpolation, so all smoothing happens here.

Keyframe text file (one key per line, '#' starts a comment):
    time_seconds  eye_x eye_y eye_z  target_x target_y target_z  [fov_degrees [cut]]
  fov defaults to 60. The word `cut` after the fov makes a HARD CUT: the pose jumps to this key at its time instead
  of moving to it from the previous key. Keys must be in time order. Between keys the motion is a Catmull-Rom spline
  (smooth through every key); cut segments are never smoothed across.

Usage:
    make_camera_track.py keys.txt out.cam            bake
    make_camera_track.py --info track.cam            print tick count, duration and the first/last pose
"""
import math
import struct
import sys

TPS = 60
MAX_TICKS = 216000
FLOATS = 7


def write_cam(path, poses):
    """poses: list of 7-tuples (ex, ey, ez, tx, ty, tz, fov), one per tick."""
    if not 1 <= len(poses) <= MAX_TICKS:
        raise ValueError("tick count %d out of range 1..%d" % (len(poses), MAX_TICKS))
    out = bytearray(b"CAM1" + struct.pack("<i", len(poses)))
    for i, p in enumerate(poses):
        if len(p) != FLOATS or any(math.isnan(v) or math.isinf(v) for v in p):
            raise ValueError("tick %d: bad pose %r" % (i, p))
        if not 1.0 <= p[6] <= 170.0:
            raise ValueError("tick %d: fov %r out of range 1..170" % (i, p[6]))
        if (p[3] - p[0]) ** 2 + (p[5] - p[2]) ** 2 < 1e-8:
            raise ValueError("tick %d: camera looks straight up/down or eye == target" % i)
        out += struct.pack("<7f", *p)
    with open(path, "wb") as f:
        f.write(out)


def read_cam(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"CAM1":
        raise ValueError("not a camera track")
    n = struct.unpack_from("<i", data, 4)[0]
    if len(data) < 8 + n * 28:
        raise ValueError("truncated")
    return [struct.unpack_from("<7f", data, 8 + i * 28) for i in range(n)]


def parse_keys(text):
    keys = []
    for ln, line in enumerate(text.splitlines(), 1):
        line = line.split("#")[0].strip()
        if not line:
            continue
        t = line.split()
        try:
            if len(t) < 7:
                raise ValueError("need time and 6 numbers")
            vals = [float(v) for v in t[:7]]
            fov = float(t[7]) if len(t) > 7 and t[7] != "cut" else 60.0
            cut = "cut" in t[7:]
        except ValueError as e:
            raise ValueError("line %d: %s" % (ln, e))
        if keys and vals[0] < keys[-1][0]:
            raise ValueError("line %d: keys must be in time order" % ln)
        keys.append((vals[0], vals[1:] + [fov], cut))
    if not keys:
        raise ValueError("no keys")
    return keys


def catmull(p0, p1, p2, p3, u):
    u2, u3 = u * u, u * u * u
    return 0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3)


def bake(keys):
    n = len(keys)
    total = int(round(keys[-1][0] * TPS)) + 1
    poses = []
    seg = 0
    for tick in range(total):
        t = tick / TPS
        while seg + 1 < n and keys[seg + 1][0] <= t + 1e-9:
            seg += 1
        if seg + 1 >= n:
            poses.append(tuple(keys[-1][1]))
            continue
        k1, k2 = keys[seg], keys[seg + 1]
        if k2[2]:                               # a cut at the next key: hold this pose until it
            poses.append(tuple(k1[1]))
            continue
        span = k2[0] - k1[0]
        u = (t - k1[0]) / span if span > 0 else 1.0
        k0 = keys[seg - 1] if seg > 0 and not k1[2] else k1
        k3 = keys[seg + 2] if seg + 2 < n and not keys[seg + 2][2] else k2
        poses.append(tuple(catmull(k0[1][i], k1[1][i], k2[1][i], k3[1][i], u) for i in range(FLOATS)))
    return poses


def main(argv):
    if len(argv) == 1 or argv[1] in ("-h", "-help", "--help"):
        print(__doc__)
        return 0
    if len(argv) == 3 and argv[1] == "--info":
        p = read_cam(argv[2])
        print("%d ticks = %.2f s" % (len(p), len(p) / TPS))
        print("first", p[0])
        print("last ", p[-1])
    elif len(argv) == 3 and not argv[1].startswith("--"):
        with open(argv[1]) as f:
            write_cam(argv[2], bake(parse_keys(f.read())))
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
