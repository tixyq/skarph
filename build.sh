#!/bin/sh
# Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
# Compiles everything, checks the platform API levels and runs the tests.
#
#   ./build.sh                    needs only a JDK: core, plain-Java demo screens, most tests
#
#   ANDROID_JAR=/path/android-19/android.jar \
#   ANDROID_API3=/path/android-3/android.jar \
#   ANDROID_API5=/path/android-5/android.jar \
#   ANDROID_API9=/path/android-9/android.jar ./build.sh
#
# ANDROID_JAR   any recent android.jar: compiles the platform layer and all demos, enables the extra tests
# ANDROID_API3  a genuine API 3 android.jar: core, platform layer and demos must compile against it with zero errors
#               (except the isolated classes below)
# ANDROID_API5  a genuine API 5 jar: MultiTouchHandler, the only class that uses multi-touch methods
# ANDROID_API9  a genuine API 9 jar: AndroidGl2 + Gles2Setup, the only classes that use android.opengl.GLES20
#               (ANDROID_API8, optional, proves that they really need API 9)
#
# Check that a jar is really old: javap -cp android.jar android.view.MotionEvent | grep getPointerCount
# must print nothing for API 3 and 4.
set -e
OUT=build
rm -rf $OUT && mkdir -p $OUT/core $OUT/android $OUT/tests $OUT/api $OUT/mt $OUT/gl2

CORE=$(find skarph-core/src -name '*.java')
ANDROID=$(find skarph-android/src -name '*.java')
DEMOS=$(find demos/demo-v[01].*/src -name '*.java')

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

  echo "== API level check: skarph-android + demos against API 3 (must be clean)"
  javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_API3" -classpath $OUT/core:$OUT/mt:$OUT/gl2 \
      -d $OUT/api $(echo "$ANDROID" | grep -v "MultiTouchHandler\|AndroidGl2\|Gles2Setup") $DEMOS
  echo "   ok: skarph-android and all demos compile against API 3 (Android 1.5)"
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
  echo "== skarph-android + demos (runnable classes)"
  javac -nowarn -source 8 -target 8 -classpath "$ANDROID_JAR:$OUT/core" -d $OUT/android $ANDROID $DEMOS
fi

if [ -z "$ANDROID_JAR" ]; then
  echo "== skarph-android: only the plain Java classes (no android.jar given; enough for Gles2Test and PlatformTest)"
  A=skarph-android/src/skarph/android
  javac -nowarn -source 8 -target 8 -classpath $OUT/core -d $OUT/android \
      $A/GLES2Renderer.java $A/GLES2Shaders.java $A/Gl2.java $A/GlImage.java $A/GlImageLoader.java $A/GlLog.java
fi

echo "== tests"
javac -nowarn -cp $OUT/core:$OUT/android -d $OUT/tests tests/CoreTest.java tests/PhysicsTest.java tests/ContentTest.java tests/PlatformTest.java tests/Gles2Test.java tests/CameraTrackTest.java tests/TownMapTest.java tests/PortMapTest.java
java -cp $OUT/core:$OUT/tests CoreTest
java -cp $OUT/core:$OUT/tests PhysicsTest
java -cp $OUT/core:$OUT/tests ContentTest demos/demo-v0.5/assets
java -cp $OUT/core:$OUT/tests TownMapTest demos/demo-v0.8/assets
java -cp $OUT/core:$OUT/tests PortMapTest demos/demo-v1.0/assets
echo "== town demo screen (plain Java, runs without android.jar)"
mkdir -p $OUT/town
javac -nowarn -cp $OUT/core:$OUT/tests -d $OUT/town demos/demo-v0.8/src/skarph/demo/v08/BackendSwitcher.java demos/demo-v0.8/src/skarph/demo/v08/StressDemoScreen.java demos/demo-v0.8/src/skarph/demo/v08/CutsceneFx.java demos/demo-v0.8/src/skarph/demo/v08/PhysicsShow.java tests/TownDemoTest.java
java -cp $OUT/core:$OUT/tests:$OUT/town TownDemoTest demos/demo-v0.8/assets
echo "== port demo screen (plain Java, runs without android.jar)"
mkdir -p $OUT/port
javac -nowarn -cp $OUT/core:$OUT/tests -d $OUT/port demos/demo-v1.0/src/skarph/demo/v10/BackendSwitcher.java demos/demo-v1.0/src/skarph/demo/v10/PortDemoScreen.java demos/demo-v1.0/src/skarph/demo/v10/PortFx.java demos/demo-v1.0/src/skarph/demo/v10/PortSound.java demos/demo-v1.0/src/skarph/demo/v10/PhysicsShow.java tests/PortDemoTest.java
java -cp $OUT/core:$OUT/tests:$OUT/port PortDemoTest demos/demo-v1.0/assets
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
  sh tests/glsl/run.sh
fi

if [ -n "$ANDROID_JAR" ]; then
  echo "== GL replay: the real backends demo through GLES2Renderer on Mesa (skipped when the tools are missing)"
  sh tests/glsl/replay.sh
fi

if [ -n "$ANDROID_JAR" ]; then
  echo "== demo smoke test (all demo screens, headless, 4000 frames each)"
  javac -nowarn -cp $OUT/core:$OUT/android:$OUT/tests -d $OUT/tests tests/DemoSmokeTest.java
  java -cp $OUT/core:$OUT/android:$OUT/tests DemoSmokeTest
fi
