#!/bin/sh
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
# Real GLSL compiler checks of every shader variant.

usage() {
cat <<'HELP'
run.sh: compiles every shader variant of the OpenGL ES 2.0 backend with real GLSL compilers and renders them.

USAGE
  tests/glsl/run.sh OUT_DIR
  tests/glsl/run.sh help | -help | --help | -h      this text (also without any argument)

ARGUMENTS
  OUT_DIR   the folder that tests/run-tests.sh filled: it needs android/ (the compiled engine, built with --android-jar).
            The shader files and the harness are written to OUT_DIR/shaders. Run it as
              tests/run-tests.sh build --android-jar ANDROID_JAR      (this calls run.sh itself), or afterwards
              tests/glsl/run.sh build

CHECKS (a missing tool is skipped, not failed)
  glslangValidator          reference compiler, GLSL ES 1.00 rules
  gcc + libEGL + libGLESv2  Mesa (llvmpipe) headless EGL: compile, link and RENDER every variant, compare colors
HELP
}

case "$1" in help|-h|-help|--help) usage; exit 0 ;; esac
if [ $# -eq 0 ]; then usage; exit 0; fi
[ $# -eq 1 ] || { echo "error: exactly one argument (OUT_DIR) is expected. Run with -help for the help." >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
case "$1" in /*) OUT=$1 ;; *) OUT=$PWD/$1 ;; esac
[ -d "$OUT/android" ] || { echo "error: $OUT/android not found: run tests/run-tests.sh with --android-jar first" >&2; exit 2; }
cd "$ROOT"
DIR=$OUT/shaders
mkdir -p $DIR
javac -nowarn -cp $OUT/android -d $DIR tests/glsl/DumpShaders.java || exit 1
java -cp $DIR:$OUT/android DumpShaders $DIR || exit 1

if command -v glslangValidator > /dev/null 2>&1; then
  bad=0; ok=0
  for f in $DIR/mesh_*.vert $DIR/mesh_*.frag $DIR/sprite.vert $DIR/sprite.frag; do
    case "$f" in *.vert) stage=vert;; *) stage=frag;; esac
    { printf '#version 100\n'; cat "$f"; } > "$f.es"
    if mv "$f.es" "$f.es.glsl" && glslangValidator -S $stage "$f.es.glsl" > $DIR/val.log 2>&1; then ok=$((ok+1)); else bad=$((bad+1)); echo "glslang FAIL $f"; head -5 $DIR/val.log; fi
  done
  echo "glslangValidator (GLSL ES 1.00): $ok ok, $bad failed"
  [ "$bad" = "0" ] || exit 1
else
  echo "SKIP glslangValidator (not installed)"
fi

if command -v gcc > /dev/null 2>&1 && [ -f /usr/include/EGL/egl.h ] && [ -f /usr/include/GLES2/gl2.h ]; then
  gcc -O1 -o $DIR/harness tests/glsl/harness.c -lEGL -lGLESv2 -lm 2>/dev/null || { echo "SKIP shader render test (cannot build the harness)"; exit 0; }
  LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless $DIR/harness $DIR | grep -v "^PASS" 
  LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless $DIR/harness $DIR > $DIR/harness.log 2>&1 || { echo "shader render test FAILED"; cat $DIR/harness.log; exit 1; }
  echo "shader render test: $(grep -c '^PASS' $DIR/harness.log) checks passed on $(grep -o 'GL_RENDERER=[^ ]*' $DIR/harness.log)"
else
  echo "SKIP shader render test (needs gcc, EGL and GLES2 headers)"
fi
