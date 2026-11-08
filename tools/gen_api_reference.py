#!/usr/bin/env python3
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
"""Generates docs/API.md: every public class of skarph-core / skarph-android with its public
constructors, methods and fields, and the first sentence of their Javadoc. Plain Python 3, no packages.

    python3 tools/gen_api_reference.py --write    writes docs/API.md
    python3 tools/gen_api_reference.py --check    exit 1 when the file is out of date (build.sh runs it)
    python3 tools/gen_api_reference.py            without arguments (or -h, -help, --help): only this help
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
OUT = os.path.join(ROOT, "docs", "API.md")

GROUPS = [
    ("Loop and input", "Engine Screen ScreenAdapter TickClock InputEvent InputQueue TouchState TouchDecoder Stats"),
    ("Math", "Vec3 Mat4 FastMath Aabb Ray RayHit"),
    ("Memory tools", "Pool IntList IntIntMap TextBuffer"),
    ("Rendering", "Renderer Camera Shapes MeshData Sky Billboards BillboardBatch"),
    ("2D", "SpriteBatch TextureRegion FrameAnimation BitmapFont Ui"),
    ("Scene and content", "Scene Node Level LevelLoader ModelData ModelFormat ResourceManager Assets"),
    ("Physics", "PhysicsWorld Body Collision Contact SpatialGrid"),
    ("Effects and cutscenes", "ParticleSystem ParticleType CameraTrack Timeline"),
    ("Diagnostics and quality", "Profiler Benchmark QualityGovernor"),
    ("Sound and saving", "Audio NullAudio Storage MemoryStorage Settings"),
    ("Platform layer", "EngineView AndroidVersion GLES1Renderer GLES2Renderer AndroidAssets AndroidAudio AndroidStorage AndroidGc AndroidLog BitmapImageLoader"),
]

MEMBER = re.compile(r"^(    |        )(public|protected)\s+(?!(?:static\s+)?(?:final\s+)?(?:abstract\s+)?(?:class|interface|enum)\b)(.*?)\s*(\{|;|=.*;)\s*$")
IFACE_MEMBER = re.compile(r"^(    |        )(?!public|private|static|final|return|@)([\w<>\[\], .]+?\s+\w+\([^)]*\));\s*$")
NESTED = re.compile(r"^    public\s+(?:static\s+)?(?:final\s+)?(?:abstract\s+)?(class|interface)\s+(\w+)")


def first_sentence(lines):
    text = " ".join(l.strip().lstrip("*/ ").strip() for l in lines)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\{@(?:code|link)\s+([^}]*)\}", r"\1", text)
    text = re.sub(r"@return\s+", "Returns ", text)
    text = re.sub(r"@(param|throws)\s.*", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    m = re.match(r"(.+?[.!?])(\s|$)", text)
    return (m.group(1) if m else text).strip()


def parse(path):
    """Returns (class name, summary, members, nested) where nested is a list of (name, summary, members)."""
    raw = open(path, encoding="utf-8").read().split("\n")
    src = []                       # join signatures that continue on the next lines
    i = 0
    while i < len(raw):
        line = raw[i]
        while re.match(r"^( {4}| {8})(public|protected)\b", line) and "(" in line and ")" not in line and i + 1 < len(raw):
            i += 1
            line = line.rstrip() + " " + raw[i].strip()
        src.append(line)
        i += 1
    cls = os.path.basename(path)[:-5]
    summary, members, nested = "", [], []
    cur = members
    is_iface = False
    doc = []
    in_doc = False
    for line in src:
        s = line.strip()
        if s.startswith("/**"):
            in_doc = True
            doc = [s[3:]]
            if s.endswith("*/"):
                in_doc = False
                doc = [s[3:-2]]
            continue
        if in_doc:
            if s.endswith("*/"):
                doc.append(s[:-2])
                in_doc = False
            else:
                doc.append(s)
            continue
        m = re.match(r"^public\s+(?:final\s+|abstract\s+)*(class|interface)\s+" + cls + r"\b", line)
        if m:
            summary = first_sentence(doc) if doc else ""
            is_iface = m.group(1) == "interface"
            doc = []
            continue
        n = NESTED.match(line)
        if n:
            cur = []
            nested.append((cls + "." + n.group(2), first_sentence(doc) if doc else "", cur, n.group(1) == "interface"))
            doc = []
            continue
        if line.startswith("    ") and not line.startswith("     ") and s == "}" :
            pass
        mm = MEMBER.match(line)
        im = IFACE_MEMBER.match(line) if (is_iface or (nested and nested[-1][3] and cur is nested[-1][2])) else None
        if mm:
            cur.append((re.sub(r"\s+", " ", mm.group(3)).strip(), first_sentence(doc) if doc else ""))
        elif im:
            cur.append((re.sub(r"\s+", " ", im.group(2)).strip(), first_sentence(doc) if doc else ""))
        if s and not s.startswith("@"):
            doc = []
        if line == "    }":
            cur = members  # a nested type ended
    return cls, summary, members, [(a, b, c) for a, b, c, _ in nested]


def build():
    files = {}
    for sub in ("skarph-core/src/skarph/core", "skarph-android/src/skarph/android"):
        d = os.path.join(ROOT, sub)
        for f in sorted(os.listdir(d)):
            if f.endswith(".java"):
                files[f[:-5]] = os.path.join(d, f)
    out = ["# API reference", "",
           "Generated by `tools/gen_api_reference.py` from the public members of the sources. Do not edit by hand.",
           "Conventions: time is counted in ticks, angles are in degrees.",
           "Guide with examples: [MANUAL](MANUAL.md).", ""]
    used = set()
    for title, names in GROUPS:
        out += ["## " + title, ""]
        for n in names.split():
            if n not in files:
                continue
            used.add(n)
            cls, summary, members, nested = parse(files[n])
            pkg = "skarph.android" if "skarph-android" in files[n] else "skarph.core"
            out.append("### `%s`" % cls)
            out.append("")
            out.append("`%s.%s`%s" % (pkg, cls, " - " + summary if summary else ""))
            out.append("")
            for title2, rows in ((None, members),) + tuple((nn + ("" if not ss else " - " + ss), mm) for nn, ss, mm in nested):
                if title2:
                    out.append("Nested: `%s`" % title2.split(" - ")[0] + ("" if " - " not in title2 else " - " + title2.split(" - ", 1)[1]))
                    out.append("")
                if rows:
                    out.append("| Member | Description |")
                    out.append("|---|---|")
                    for sig, doc in rows:
                        out.append("| `%s` | %s |" % (sig.replace("|", "\\|"), doc.replace("|", "\\|")))
                    out.append("")
    rest = sorted(set(files) - used)
    if rest:
        out += ["## Internal and platform classes", "",
                "Not meant to be called by games: " + ", ".join("`%s`" % r for r in rest) + ".", ""]
    return "\n".join(out)


if __name__ == "__main__":
    if sys.argv[1:] not in (["--write"], ["--check"]):
        print(__doc__)
        sys.exit(0 if len(sys.argv) == 1 or sys.argv[1] in ("-h", "-help", "--help") else 2)
    text = build()
    if "--check" in sys.argv:
        cur = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
        if cur != text:
            print("docs/API.md is out of date: run python3 tools/gen_api_reference.py --write")
            sys.exit(1)
        print("   ok: docs/API.md is up to date")
    else:
        open(OUT, "w", encoding="utf-8").write(text)
        print("wrote", OUT, len(text), "bytes")
