#!/bin/sh
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
# Compiles the engine and runs every headless test except the network ones.
# Usage: tests/run-12-tests.sh [OUT_DIR]     (default /tmp/skarph-out; run it from anywhere)
set -e
cd "$(dirname "$0")/.."
OUT=${1:-/tmp/skarph-out}
rm -rf "$OUT/core" "$OUT/android" "$OUT/tests" "$OUT/a1" "$OUT/ds"
mkdir -p "$OUT/core" "$OUT/android" "$OUT/tests" "$OUT/a1" "$OUT/ds"
javac -nowarn -source 8 -target 8 -d "$OUT/core" $(find skarph-core/src -name '*.java')
A=skarph-android/src/skarph/android
javac -nowarn -source 8 -target 8 -cp "$OUT/core" -d "$OUT/android" $A/GLES2Renderer.java $A/GLES2Shaders.java $A/Gl2.java $A/GlImage.java $A/GlImageLoader.java $A/GlLog.java
# classes that need the Android / LWJGL APIs are compile-checked against signature stubs
javac -nowarn -source 8 -target 8 -cp "$OUT/core" -d "$OUT/a1" $(find tests/android-stubs -name '*.java') $A/GLES1Renderer.java $A/BitmapOptionsCompat.java $A/AndroidAudio.java
javac -nowarn -source 8 -target 8 -cp "$OUT/core:$OUT/android" -d "$OUT/ds" $(find tests/lwjgl-stubs -name '*.java') $(find skarph-desktop/src -name '*.java')
javac -nowarn -cp "$OUT/core:$OUT/android" -d "$OUT/tests" tests/CoreTest.java tests/PhysicsTest.java tests/PlatformTest.java tests/Gles2Test.java \
    tests/Core12Test.java tests/Physics2Test.java tests/AudioTest.java tests/TextUiTest.java tests/Tiles2dTest.java tests/Gles2FeatureTest.java
CP="$OUT/core:$OUT/android:$OUT/tests"
for T in CoreTest PhysicsTest PlatformTest Gles2Test Core12Test Physics2Test AudioTest TextUiTest Tiles2dTest Gles2FeatureTest; do
    java -cp "$CP" $T 2>&1 | grep -v "^PASS\|^Picked" | tee "$OUT/$T.log"
    grep -q "PASSED" "$OUT/$T.log" || { echo "FAILED: $T"; exit 1; }
done
echo "ALL HEADLESS TESTS PASSED"
