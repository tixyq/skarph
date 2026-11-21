#!/bin/sh
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
# Compiles the engine and both platform layers and runs every test.

usage() {
cat <<'HELP'
run-tests.sh: compiles the engine and both platform layers, checks the Android API levels and runs all tests.

USAGE
  tests/run-tests.sh OUT_DIR [options]
  tests/run-tests.sh help | -help | --help | -h      this text (also without any argument)

ARGUMENTS
  OUT_DIR   where the compiled classes and the test output go, for example  build  (the script can be run from
            anywhere). It only replaces the folders it makes itself inside OUT_DIR (core, android, tests, ...);
            nothing else in OUT_DIR is touched.

OPTIONS
  Without options only a JDK (8 or newer) is needed: the engine, the headless tests and
  the desktop layer are plain Java. Each option adds checks; every option also has an environment variable.

  --android-jar FILE   [ANDROID_JAR]   any recent android.jar: compiles the Android layer, runs
                                       Gles1FogTest and the GLSL checks
  --api3 FILE          [ANDROID_API3]  a GENUINE API 3 android.jar: the core and the Android layer must
                                       compile against it with zero errors
  --api5 FILE          [ANDROID_API5]  a genuine API 5 jar: MultiTouchHandler (the only class with multi-touch calls)
  --api8 FILE          [ANDROID_API8]  a genuine API 8 jar (optional): proves that AndroidGl2 really needs API 9
  --api9 FILE          [ANDROID_API9]  a genuine API 9 jar: AndroidGl2 and Gles2Setup (the only GLES20 classes)
  --lwjgl CP           [LWJGL_CP]      the LWJGL 3.3.6 jars (lwjgl, glfw, opengl, openal, stb), absolute paths
                                       separated by ':': the desktop window, OpenGL and sound classes compile against
                                       the real library; without it they are checked against the signature stubs in
                                       tests/lwjgl-stubs
  An option wins over its environment variable.
  Check that a jar is really old:  javap -cp android.jar android.view.MotionEvent | grep getPointerCount
  must print nothing for API 3 and 4.

EXAMPLES
  tests/run-tests.sh build
  tests/run-tests.sh build --android-jar ~/Android/Sdk/platforms/android-34/android.jar
  tests/run-tests.sh /tmp/skarph --android-jar a.jar --api3 android-3.jar --api5 android-5.jar --api9 android-9.jar

WHAT RUNS (in this order; the sections that need a jar are skipped without it)
  core compiles; CoreTest, HttpTest (the network against real local servers), NetTest (UDP against real local sockets), ServerTest (the server, the server browser and the server loop against real clients), PhysicsTest; docs/API.md is up to date; camera track tool + CameraTrackTest; PlatformTest;
  Gles2Test; DesktopTest, the desktop window/OpenGL/sound classes against stubs; with a jar: Gles1FogTest, the GLSL
  checks (tests/glsl/run.sh). The output ends with
  "ALL ... PASSED" lines; the script stops with an error at the first failure.
  More: docs/MANUAL.md, section 12.
HELP
}

# Help only: no arguments or a help word.
case "$1" in help|-h|-help|--help) usage; exit 0 ;; esac
if [ $# -eq 0 ]; then usage; exit 0; fi

abs() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$PWD" "$1" ;; esac; }
fail() { echo "error: $*" >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/.." && pwd)

OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --android-jar|--api3|--api5|--api8|--api9|--lwjgl)
      [ $# -ge 2 ] || fail "$1 needs a value. Run with -help for the help."
      case "$1" in
        --android-jar) ANDROID_JAR=$(abs "$2") ;;
        --api3) ANDROID_API3=$(abs "$2") ;;
        --api5) ANDROID_API5=$(abs "$2") ;;
        --api8) ANDROID_API8=$(abs "$2") ;;
        --api9) ANDROID_API9=$(abs "$2") ;;
        --lwjgl) LWJGL_CP=$2 ;;
      esac
      shift 2 ;;
    -*) fail "unknown option $1. Run with -help for the help." ;;
    *)  [ -z "$OUT" ] || fail "only one OUT_DIR is expected, got a second one: $1"; OUT=$(abs "$1"); shift ;;
  esac
done
[ -n "$OUT" ] || fail "OUT_DIR is missing. Run with -help for the help."
for f in "$ANDROID_JAR" "$ANDROID_API3" "$ANDROID_API5" "$ANDROID_API8" "$ANDROID_API9"; do
  [ -z "$f" ] || [ -f "$f" ] || fail "$f not found"
done

set -e
cd "$ROOT"
for d in core android tests api mt gl2 desktop hosts hosts-real shaders; do rm -rf "$OUT/$d"; done
mkdir -p "$OUT/core" "$OUT/android" "$OUT/tests" "$OUT/api" "$OUT/mt" "$OUT/gl2"

CORE=$(find skarph-core/src -name '*.java')
ANDROID=$(find skarph-android/src -name '*.java')

echo "== skarph-core (pure Java)"
javac -nowarn -source 8 -target 8 -d $OUT/core $CORE

MT_JAR="${ANDROID_API5:-$ANDROID_JAR}"
if [ -n "$MT_JAR" ]; then
  echo "== MultiTouchHandler against $( [ -n "$ANDROID_API5" ] && echo 'API 5' || echo 'ANDROID_JAR' ) (must compile)"
  javac -nowarn -source 8 -target 8 -classpath "$MT_JAR:$OUT/core" -d $OUT/mt \
      skarph-android/src/skarph/android/MultiTouchHandler.java
fi

GL2_JAR="${ANDROID_API9:-$ANDROID_JAR}"
GL2_SRC="skarph-android/src/skarph/android/AndroidGl2.java skarph-android/src/skarph/android/Gles2Setup.java"
if [ -n "$GL2_JAR" ]; then
  echo "== AndroidGl2 + Gles2Setup against $( [ -n "$ANDROID_API9" ] && echo 'API 9' || echo 'ANDROID_JAR' ) (must compile)"
  # (the API 9 jar is a stripped one without javax.microedition: the API 3 jar supplies those classes after it)
  javac -nowarn -source 8 -target 8 -classpath "$GL2_JAR:${ANDROID_API3:-$ANDROID_JAR}:$OUT/core" -sourcepath skarph-android/src -d $OUT/gl2 $GL2_SRC
fi

if [ -n "$ANDROID_API3" ]; then
  echo "== API level check: skarph-core against API 3 (must be clean)"
  javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_API3" -d $OUT/api $CORE

  echo "== API level check: skarph-android against API 3 (must be clean)"
  javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_API3" -classpath $OUT/core:$OUT/mt:$OUT/gl2 \
      -d $OUT/api $(echo "$ANDROID" | grep -v "MultiTouchHandler\|AndroidGl2\|Gles2Setup")
  echo "   ok: skarph-android compiles against API 3 (Android 1.5)"
fi

if [ -n "$ANDROID_API8" ]; then
  if javac -nowarn -source 8 -target 8 -classpath "$ANDROID_API8:${ANDROID_API3:-$ANDROID_JAR}:$OUT/core" -sourcepath skarph-android/src -d $OUT/api \
      skarph-android/src/skarph/android/AndroidGl2.java > /dev/null 2>&1; then
    echo "AndroidGl2 unexpectedly compiles against API 8: the ES 2.0 backend would not need API 9"; exit 1
  fi
  echo "   ok: AndroidGl2 does NOT compile against API 8 (the ES 2.0 backend really needs Android 2.3)"
fi

if [ -n "$ANDROID_API5" ]; then
  if [ -n "$ANDROID_API3" ]; then
    if javac -nowarn -source 8 -target 8 -classpath "$ANDROID_API3:$OUT/core" -d $OUT/api \
        skarph-android/src/skarph/android/MultiTouchHandler.java > /dev/null 2>&1; then
      echo "MultiTouchHandler unexpectedly compiles against API 3: the isolation check is meaningless"; exit 1
    fi
    echo "   ok: it does NOT compile against API 3 (so it really needs the isolation)"
  fi
fi

if [ -n "$ANDROID_JAR" ]; then
  echo "== skarph-android (runnable classes)"
  javac -nowarn -source 8 -target 8 -classpath "$ANDROID_JAR:$OUT/core" -d $OUT/android $ANDROID
fi

if [ -z "$ANDROID_JAR" ]; then
  echo "== skarph-android: only the plain Java classes (no android.jar given; enough for Gles2Test and PlatformTest)"
  A=skarph-android/src/skarph/android
  javac -nowarn -source 8 -target 8 -classpath $OUT/core -d $OUT/android \
      $A/GLES2Renderer.java $A/GLES2Shaders.java $A/Gl2.java $A/GlImage.java $A/GlImageLoader.java $A/GlLog.java
fi

echo "== tests"
javac -nowarn -cp $OUT/core:$OUT/android -d $OUT/tests tests/CoreTest.java tests/PhysicsTest.java tests/PlatformTest.java tests/Gles2Test.java tests/CameraTrackTest.java tests/HttpTest.java tests/NetTest.java tests/skarph/core/NetTestHook.java tests/ServerTest.java examples/RoomServer.java
java -cp $OUT/core:$OUT/tests CoreTest
echo "== network (UrlHttp against real HTTP and HTTPS servers on the loopback interface)"
java -cp $OUT/core:$OUT/tests HttpTest
echo "== network: UDP (UdpSocket and NetChannel against real sockets on the loopback interface)"
java -cp $OUT/core:$OUT/tests NetTest
echo "== network: the server (NetServer, ServerBrowser, ServerLoop and examples/RoomServer against real clients on the loopback interface)"
java -cp $OUT/core:$OUT/tests ServerTest
java -cp $OUT/core:$OUT/tests PhysicsTest
if command -v python3 > /dev/null; then
  python3 tools/gen_api_reference.py --check
  python3 tools/make_camera_track.py tools/sources/sample_track.txt $OUT/sample.cam
  java -cp $OUT/core:$OUT/tests CameraTrackTest $OUT/sample.cam
else
  echo "   (python3 not found: camera track tool test skipped)"
fi
java -cp $OUT/core:$OUT/tests PlatformTest
java -cp $OUT/core:$OUT/android:$OUT/tests Gles2Test

if [ -n "$ANDROID_JAR" ]; then
  echo "== ES 1.x fog, sky, billboards (recording GL10)"
  javac -nowarn -cp "$ANDROID_JAR:$OUT/core:$OUT/android" -d $OUT/tests tests/Gles1FogTest.java
  java -cp "$ANDROID_JAR:$OUT/core:$OUT/android:$OUT/tests" Gles1FogTest
fi

if [ -n "$ANDROID_JAR" ]; then
  echo "== shader checks (real GLSL compilers, skipped when the tools are missing)"
  sh tests/glsl/run.sh "$OUT"
fi

echo "== desktop layer (plain Java: platforms, links, download and checksums, cache, assets, storage, wav, png, shaders)"
mkdir -p $OUT/desktop $OUT/hosts
javac -nowarn -source 8 -target 8 -cp $OUT/core:$OUT/android -d $OUT/desktop $(find skarph-desktop/src/skarph/desktop -maxdepth 1 -name '*.java')
javac -nowarn -cp $OUT/core:$OUT/android:$OUT/desktop -d $OUT/tests tests/DesktopTest.java
java -cp $OUT/core:$OUT/android:$OUT/desktop:$OUT/tests DesktopTest

echo "== desktop window / OpenGL / sound classes against signature stubs"
javac -nowarn -source 8 -target 8 -cp $OUT/core:$OUT/android:$OUT/desktop -d $OUT/hosts \
    $(find tests/lwjgl-stubs -name '*.java') skarph-desktop/src/skarph/desktop/host/*.java
if [ -n "$LWJGL_CP" ]; then
  echo "== desktop classes against the real LWJGL jars (--lwjgl / LWJGL_CP)"
  mkdir -p $OUT/hosts-real
  javac -nowarn -source 8 -target 8 -cp "$OUT/core:$OUT/android:$OUT/desktop:$LWJGL_CP" -d $OUT/hosts-real skarph-desktop/src/skarph/desktop/host/*.java
fi

echo "== all checks passed"
