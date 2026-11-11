#!/usr/bin/env python3
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
"""Makes the Android launcher icons (48, 72 and 96 px) of every app from the project's icon.png.

    python3 tools/make_icons.py --write    writes demos/*/res/drawable*/icon.png
    python3 tools/make_icons.py            without arguments (or -h, -help, --help): only this help

The desktop jar needs nothing from here: skarph-desktop/build-desktop.sh copies icon.png itself. Needs Pillow."""
import glob
import os
import sys

import cli
cli.require(__doc__, "PIL:Pillow")
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DENSITIES = {"drawable": 48, "drawable-hdpi": 72, "drawable-xhdpi": 96}


def main():
    if sys.argv[1:] != ["--write"]:
        print(__doc__)
        return 0 if len(sys.argv) == 1 or sys.argv[1] in cli.HELP_WORDS else 2
    src = Image.open(os.path.join(ROOT, "icon.png")).convert("RGBA")
    res_dirs = sorted(glob.glob(os.path.join(ROOT, "demos", "*", "res")))
    for res in res_dirs:
        for folder, size in DENSITIES.items():
            os.makedirs(os.path.join(res, folder), exist_ok=True)
            src.resize((size, size), Image.LANCZOS).save(os.path.join(res, folder, "icon.png"), optimize=True)
        print("icons:", os.path.relpath(res, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
