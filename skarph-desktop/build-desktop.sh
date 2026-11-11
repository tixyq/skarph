#!/bin/sh
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
# Builds ONE portable jar that runs on Windows, Linux and macOS (java -jar NAME.jar, Java 8 or newer).

usage() {
cat <<'HELP'
build-desktop.sh: builds one portable jar of a program for Windows, Linux and macOS.

USAGE
  skarph-desktop/build-desktop.sh GAME_CLASS "SRC_DIRS" ASSETS_DIR OUT.jar [TITLE]
  skarph-desktop/build-desktop.sh help | -help | --help | -h      this text (also without any argument)

ARGUMENTS
  GAME_CLASS   the class with the game, e.g. skarph.demo.v10.DesktopDemo
  SRC_DIRS     the folders with your Java sources, in quotes, separated by spaces. Files that import android.*
               (the Activity launchers) are left out of the jar
  ASSETS_DIR   the folder with your data files (models, textures, sounds); it ends up inside the jar
  OUT.jar      the jar to write (its folder is created)
  TITLE        window title (default: Skarph)
  Relative paths are relative to the folder you run the script from; the script can be run from anywhere.

ENVIRONMENT
  LWJGL_CP     the LWJGL 3.3.6 API jars (lwjgl, glfw, opengl, openal, stb), absolute paths separated by ':'.
               Without it the script downloads and checks them once (needs a network, see below).

EXAMPLE (the demo-v1.0 jar)
  skarph-desktop/build-desktop.sh skarph.demo.v10.DesktopDemo \
      "demos/demo-v1.0/src demos/demo-v1.0/desktop" demos/demo-v1.0/assets \
      build/desktop/skarph-demo-v1.0.jar "Skarph demo v1.0"
  java -jar build/desktop/skarph-demo-v1.0.jar        (other checks: --check-links, --setup-only)

WHAT IT DOES
  The jar holds the engine, the desktop layer, your program and its assets, but no LWJGL: on the first start the
  program downloads LWJGL and the native libraries of the system it runs on (checked by SHA-1, cached, shared by all
  Skarph programs). This script needs a network once, to get the LWJGL API jars it compiles against; they are fetched
  and checked by the program's own downloader. Work files go to build/desktop in the project folder.
  More: docs/MANUAL.md, section 14.
HELP
}

# Help only: no arguments or a help word. An incomplete argument list builds nothing either.
case "$1" in help|-h|-help|--help) usage; exit 0 ;; esac
if [ $# -eq 0 ]; then usage; exit 0; fi
if [ $# -lt 4 ]; then
  echo "error: 4 arguments are needed (GAME_CLASS \"SRC_DIRS\" ASSETS_DIR OUT.jar), got $#. Run with -help for the help." >&2
  exit 2
fi

set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)

# paths of the arguments are relative to the folder the script was started from: make them absolute, then work in ROOT
abs() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$PWD" "$1" ;; esac; }
GAME=$1
SRC=""; for d in $2; do SRC="$SRC $(abs "$d")"; done
ASSETS=$(abs "$3")
JAR=$(abs "$4")
TITLE=${5:-Skarph}
cd "$ROOT"

# Without --release, a newer JDK compiles against its own class library: calls such as ShortBuffer.position(int) then end up
# in the bytecode and fail on Java 8 with NoSuchMethodError.
if javac --release 8 -version >/dev/null 2>&1; then JOPT="--release 8"; else JOPT="-source 8 -target 8"; fi
OUT=build/desktop
A=skarph-android/src/skarph/android
# (a loop, not grep -L: its exit status differs between grep versions and set -e would stop the script without a word)
FILES=$(find $SRC -name '*.java' | while read f; do grep -q '^import android\.' "$f" || echo "$f"; done)
rm -rf $OUT/classes && mkdir -p $OUT/classes "$(dirname "$JAR")"

echo "== engine, desktop layer (plain Java)"
javac -nowarn $JOPT -d $OUT/classes $(find skarph-core/src -name '*.java') \
    $A/GLES2Renderer.java $A/GLES2Shaders.java $A/Gl2.java $A/GlImage.java $A/GlImageLoader.java $A/GlLog.java \
    $(find skarph-desktop/src/skarph/desktop -maxdepth 1 -name '*.java')

if [ -z "$LWJGL_CP" ]; then
  echo "== LWJGL API jars (downloaded and checked, or taken from the cache)"
  LWJGL_CP=$(java -cp $OUT/classes skarph.desktop.Launcher --setup-only)
fi

echo "== window, OpenGL and sound classes, game"
javac -nowarn $JOPT -cp "$OUT/classes:$LWJGL_CP" -d $OUT/classes skarph-desktop/src/skarph/desktop/host/*.java
javac -nowarn $JOPT -cp $OUT/classes -d $OUT/classes $FILES

cp -r skarph-desktop/res/. $OUT/classes/
cp icon.png $OUT/classes/skarph/desktop/icon.png   # window icon
mkdir -p $OUT/classes/assets && cp -r "$ASSETS"/. $OUT/classes/assets/
printf 'Main-Class: skarph.desktop.Launcher\nSkarph-Game: %s\nSkarph-Title: %s\n' "$GAME" "$TITLE" > $OUT/manifest.txt
jar cfm "$JAR" $OUT/manifest.txt -C $OUT/classes .
echo "== $JAR ($(wc -c < "$JAR") bytes)"

PINS=skarph-desktop/res/skarph/desktop/lwjgl-3.3.6.sha1
if ! grep -qv '^#' $PINS 2>/dev/null; then
  echo "note: the LWJGL checksums are not pinned yet. The jar works (it checks the .sha1 files of Maven Central), but run"
  echo "      tools/pin_lwjgl.py $OUT/classes   once (needs a network) and build again to pin them into the jar."
fi
echo "run it:  java -jar $JAR        (other checks: --check-links, --setup-only)"
