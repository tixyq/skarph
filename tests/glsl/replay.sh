#!/bin/sh
# Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
# Records the GL calls of the real backends demo (GLES2Renderer, real assets) and plays them back on Mesa
# (headless EGL): no GL error, no shader error is allowed. Saves two frames as PNG into build/replay.
# Needs: build/android + build/tests (run build.sh first), gcc, EGL + GLES2 headers, Python with Pillow (for the PNGs).
OUT=build
DIR=$OUT/replay
mkdir -p $DIR
if ! command -v gcc > /dev/null 2>&1 || [ ! -f /usr/include/EGL/egl.h ] || [ ! -f /usr/include/GLES2/gl2.h ]; then
  echo "SKIP GL replay (needs gcc, EGL and GLES2 headers)"; exit 0
fi
javac -nowarn -cp $OUT/core:$OUT/android:$OUT/tests -d $DIR tests/glsl/TraceDemo.java 2>&1 | grep -v "^Note"
FRAMES=150
java -Djava.awt.headless=true -cp $DIR:$OUT/core:$OUT/android:$OUT/tests TraceDemo demos/demo-v0.7/assets $DIR/trace.bin $FRAMES || exit 1
gcc -O1 -o $DIR/replay tests/glsl/replay.c -lEGL -lGLESv2 -lm 2>/dev/null || { echo "SKIP GL replay (cannot build the player)"; exit 0; }
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless $DIR/replay $DIR/trace.bin $DIR/frame 1 $FRAMES > $DIR/replay.log 2>&1
STATUS=$?
cat $DIR/replay.log
if command -v python3 > /dev/null 2>&1; then
  python3 - << PY
try:
    from PIL import Image
    import glob
    for p in glob.glob("$DIR/frame_*.ppm"):
        Image.open(p).save(p.replace(".ppm", ".png"))
except Exception as e:
    print("PNG conversion skipped:", e)
PY
fi
exit $STATUS
