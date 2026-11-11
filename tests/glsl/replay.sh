#!/bin/sh
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
# Records the GL calls of the real backends demo and plays them back on Mesa.

usage() {
cat <<'HELP'
replay.sh: records the GL calls of the real backends demo and plays them back on Mesa (headless EGL).

USAGE
  tests/glsl/replay.sh OUT_DIR [ASSETS_DIR]
  tests/glsl/replay.sh help | -help | --help | -h      this text (also without any argument)

ARGUMENTS
  OUT_DIR     the folder that tests/run-tests.sh filled: it needs core/, android/ and tests/ (built with
              --android-jar). The recording, the log and two frames as PNG go to OUT_DIR/replay. Run it as
                tests/run-tests.sh build --android-jar ANDROID_JAR      (this calls replay.sh itself), or afterwards
                tests/glsl/replay.sh build
  ASSETS_DIR  the assets of the demo to record (default: demos/demo-v0.7/assets)

WHAT IT DOES
  The real GLES2Renderer draws the demo with the real assets; every GL call is recorded and played back through Mesa.
  No GL error and no shader error is allowed (exit code 1 otherwise).
  Needs: gcc, EGL + GLES2 headers (without them it says SKIP and exits with 0), Python with Pillow (only for the PNGs).
HELP
}

case "$1" in help|-h|-help|--help) usage; exit 0 ;; esac
if [ $# -eq 0 ]; then usage; exit 0; fi
[ $# -le 2 ] || { echo "error: at most two arguments (OUT_DIR [ASSETS_DIR]) are expected. Run with -help for the help." >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
case "$1" in /*) OUT=$1 ;; *) OUT=$PWD/$1 ;; esac
ASSETS=${2:-$ROOT/demos/demo-v0.7/assets}
case "$ASSETS" in /*) ;; *) ASSETS=$PWD/$ASSETS ;; esac
for d in core android tests; do
  [ -d "$OUT/$d" ] || { echo "error: $OUT/$d not found: run tests/run-tests.sh with --android-jar first" >&2; exit 2; }
done
cd "$ROOT"
DIR=$OUT/replay
mkdir -p $DIR
if ! command -v gcc > /dev/null 2>&1 || [ ! -f /usr/include/EGL/egl.h ] || [ ! -f /usr/include/GLES2/gl2.h ]; then
  echo "SKIP GL replay (needs gcc, EGL and GLES2 headers)"; exit 0
fi
javac -nowarn -cp $OUT/core:$OUT/android:$OUT/tests -d $DIR tests/glsl/TraceDemo.java 2>&1 | grep -v "^Note"
FRAMES=150
java -Djava.awt.headless=true -cp $DIR:$OUT/core:$OUT/android:$OUT/tests TraceDemo "$ASSETS" $DIR/trace.bin $FRAMES || exit 1
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
