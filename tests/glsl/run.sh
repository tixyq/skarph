#!/bin/sh
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
# Real GLSL compiler checks of every shader variant. Needs: build/android (compiled engine), and optionally
#   glslangValidator          reference compiler, GLSL ES 1.00 rules
#   gcc + libEGL + libGLESv2  Mesa (llvmpipe) headless EGL: compile, link and RENDER every variant, compare colors
# Missing tools are skipped, not failed.
OUT=build
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
