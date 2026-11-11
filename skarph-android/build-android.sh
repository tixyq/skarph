#!/bin/sh
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
# Builds a signed APK of an Android program: engine + Android layer + your sources, resources and assets.

usage() {
cat <<'HELP'
build-android.sh: builds a signed APK of an Android program.

USAGE
  skarph-android/build-android.sh APP_DIR [options]
  skarph-android/build-android.sh help | -help | --help | -h      this text (also without any argument)

ARGUMENTS
  APP_DIR    the folder of the program, laid out like the folders in demos/:
               AndroidManifest.xml    required
               src/                   required: your Java sources
               assets/                optional: models, textures, sounds
               res/                   optional: launcher icons and other Android resources
             The sources of the engine (skarph-core/src, skarph-android/src) are compiled in automatically.

OPTIONS
  -o, --output FILE      the APK to write (default: build/apk/NAME.apk in the project, NAME = folder name of APP_DIR)
  --src DIR              one more folder with Java sources; may be repeated
  --android-jar FILE     the android.jar to compile and link against. Default: $ANDROID_JAR, else the newest
                         platforms/android-*/android.jar of the SDK. Any recent android.jar works
  --sdk DIR              the Android SDK. Default: $ANDROID_HOME, else $ANDROID_SDK_ROOT
  --build-tools DIR      the folder with aapt2, d8, zipalign and apksigner. Default: the newest build-tools/* of the
                         SDK (build-tools 30 or newer); tools that are not there are taken from the PATH
  --min-sdk N            lowest Android API level; default: minSdkVersion of the manifest
  --target-sdk N         target API level; default: targetSdkVersion of the manifest
  --keystore FILE        the key to sign with. Default: a debug key, ~/.skarph/debug.keystore, created on first use
  --ks-pass SPEC         keystore password in apksigner syntax: pass:TEXT, env:NAME, file:PATH or stdin
                         (default for the debug key: pass:android; for your own key apksigner asks)
  --key-alias NAME       key alias in the keystore (default for the debug key: skarphdebug)
  --key-pass SPEC        password of the key, same syntax (default: the keystore password)

NEEDS
  A JDK (javac and keytool, 8 or newer). From the Android SDK: build-tools (aapt2, d8, zipalign, apksigner) and one
  platform (android.jar). Install them with:
      sdkmanager "build-tools;34.0.0" "platforms;android-34"
  and either set ANDROID_HOME or pass --sdk, or pass --android-jar and --build-tools. The script also needs
  zip (or Python 3 as a fallback).

EXAMPLES
  skarph-android/build-android.sh demos/demo-v1.0 --sdk ~/Android/Sdk
  skarph-android/build-android.sh demos/demo-v0.8 -o /tmp/town.apk --android-jar ~/android.jar --build-tools ~/bt
  skarph-android/build-android.sh my-game --keystore my.keystore --key-alias release --ks-pass env:KS_PASS

STEPS (printed while it runs)
  javac (Java 8 bytecode) -> aapt2 compile + link (manifest, resources, assets) -> d8 (classes.dex) ->
  zipalign -> apksigner sign + verify. Work files are in build/apk/NAME-work.
  Install with:  adb install -r build/apk/NAME.apk

NOTES
  .ogg, .wav and .mp3 files in assets are stored uncompressed in the APK, which the audio code needs (docs/MANUAL.md).
  Android 14 and newer refuse programs whose targetSdkVersion is below 23: raise it in the manifest, or install
  with  adb install --bypass-low-target-sdk-block.
  More: docs/MANUAL.md, section 2 and section 12.
HELP
}

# Help only: no arguments or a help word. A build needs at least the program folder.
case "$1" in help|-h|-help|--help) usage; exit 0 ;; esac
if [ $# -eq 0 ]; then usage; exit 0; fi

ROOT=$(cd "$(dirname "$0")/.." && pwd)
abs() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$PWD" "$1" ;; esac; }
fail() { echo "error: $*" >&2; exit 1; }
need_value() { [ $# -ge 2 ] || fail "$1 needs a value. Run with -help for the help."; }

APP=""
OUTPUT=""
EXTRA_SRC=""
ANDROID_JAR_OPT=""
SDK_OPT=""
BT_OPT=""
MIN_SDK=""
TARGET_SDK=""
KEYSTORE=""
KS_PASS=""
KEY_ALIAS=""
KEY_PASS=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o|--output)     need_value "$@"; OUTPUT=$(abs "$2"); shift 2 ;;
    --src)           need_value "$@"; EXTRA_SRC="$EXTRA_SRC
$(abs "$2")"; shift 2 ;;
    --android-jar)   need_value "$@"; ANDROID_JAR_OPT=$(abs "$2"); shift 2 ;;
    --sdk)           need_value "$@"; SDK_OPT=$(abs "$2"); shift 2 ;;
    --build-tools)   need_value "$@"; BT_OPT=$(abs "$2"); shift 2 ;;
    --min-sdk)       need_value "$@"; MIN_SDK=$2; shift 2 ;;
    --target-sdk)    need_value "$@"; TARGET_SDK=$2; shift 2 ;;
    --keystore)      need_value "$@"; KEYSTORE=$(abs "$2"); shift 2 ;;
    --ks-pass)       need_value "$@"; KS_PASS=$2; shift 2 ;;
    --key-alias)     need_value "$@"; KEY_ALIAS=$2; shift 2 ;;
    --key-pass)      need_value "$@"; KEY_PASS=$2; shift 2 ;;
    -*)              fail "unknown option $1. Run with -help for the help." ;;
    *)               [ -z "$APP" ] || fail "only one program folder is expected, got a second one: $1"; APP=$(abs "$1"); shift ;;
  esac
done

[ -n "$APP" ] || fail "the program folder (APP_DIR) is missing. Run with -help for the help."
[ -d "$APP" ] || fail "$APP is not a folder"
MANIFEST=$APP/AndroidManifest.xml
[ -f "$MANIFEST" ] || fail "$MANIFEST not found (the program folder needs an AndroidManifest.xml)"
[ -d "$APP/src" ] || fail "$APP/src not found (the program folder needs a src folder with the Java sources)"
NAME=$(basename "$APP")
[ -n "$OUTPUT" ] || OUTPUT=$ROOT/build/apk/$NAME.apk
WORK=$ROOT/build/apk/$NAME-work

# the numbers of the manifest
attr() { sed -n "s/.*$1=\"\([^\"]*\)\".*/\1/p" "$MANIFEST" | head -1; }
[ -n "$MIN_SDK" ] || MIN_SDK=$(attr minSdkVersion)
[ -n "$TARGET_SDK" ] || TARGET_SDK=$(attr targetSdkVersion)
[ -n "$MIN_SDK" ] || MIN_SDK=3
[ -n "$TARGET_SDK" ] || TARGET_SDK=$MIN_SDK
case "$MIN_SDK$TARGET_SDK" in *[!0-9]*) fail "the API levels must be numbers (min $MIN_SDK, target $TARGET_SDK)" ;; esac

# ---- find the Android SDK parts
SDK=${SDK_OPT:-${ANDROID_HOME:-$ANDROID_SDK_ROOT}}
newest() { ls -d "$1"/* 2>/dev/null | sort -V 2>/dev/null | tail -1; }   # (sort -V: newest version number last)
JAR=${ANDROID_JAR_OPT:-$ANDROID_JAR}
if [ -z "$JAR" ] && [ -n "$SDK" ]; then
  P=$(newest "$SDK/platforms"); [ -z "$P" ] || JAR=$P/android.jar
fi
[ -n "$JAR" ] || fail "no android.jar: pass --android-jar FILE, or --sdk DIR (or set ANDROID_HOME) with a platform installed:
       sdkmanager \"platforms;android-34\""
[ -f "$JAR" ] || fail "$JAR not found"
BT=$BT_OPT
if [ -z "$BT" ] && [ -n "$SDK" ]; then BT=$(newest "$SDK/build-tools"); fi
tool() {   # tool NAME: the file in the build-tools folder, else the one on the PATH
  if [ -n "$BT" ] && [ -x "$BT/$1" ]; then printf '%s' "$BT/$1"; return; fi
  command -v "$1" 2>/dev/null
}
AAPT2=$(tool aapt2); D8=$(tool d8); ZIPALIGN=$(tool zipalign); APKSIGNER=$(tool apksigner)
MISSING=""
for t in aapt2:"$AAPT2" d8:"$D8" zipalign:"$ZIPALIGN" apksigner:"$APKSIGNER"; do
  [ -n "${t#*:}" ] || MISSING="$MISSING ${t%%:*}"
done
[ -z "$MISSING" ] || fail "not found:$MISSING. They are part of the Android SDK build-tools: pass --build-tools DIR, or --sdk DIR
       (or set ANDROID_HOME), after  sdkmanager \"build-tools;34.0.0\""
command -v javac >/dev/null 2>&1 || fail "javac not found: a JDK (8 or newer) is needed"
command -v keytool >/dev/null 2>&1 || fail "keytool not found: a JDK (8 or newer) is needed"
if ! command -v zip >/dev/null 2>&1 && ! command -v python3 >/dev/null 2>&1; then
  fail "neither zip nor python3 found: one of them is needed to add classes.dex to the APK"
fi

echo "program:       $APP (min API $MIN_SDK, target API $TARGET_SDK)"
echo "android.jar:   $JAR"
echo "build-tools:   ${BT:-from the PATH}"
echo "output:        $OUTPUT"

rm -rf "$WORK" && mkdir -p "$WORK/classes" "$WORK/dex" "$(dirname "$OUTPUT")"

# ---- 1. Java -> class files (Java 8 bytecode; --release keeps JDK-only calls out of the bytecode)
echo "== javac"
find "$ROOT/skarph-core/src" "$ROOT/skarph-android/src" "$APP/src" -name '*.java' > "$WORK/sources.txt"
printf '%s\n' "$EXTRA_SRC" | while IFS= read -r d; do
  [ -z "$d" ] || find "$d" -name '*.java'
done >> "$WORK/sources.txt"
sed 's/.*/"&"/' "$WORK/sources.txt" > "$WORK/sources.arg"
if javac --release 8 -version >/dev/null 2>&1; then JOPT="--release 8"; else JOPT="-source 8 -target 8"; fi
javac -nowarn $JOPT -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.arg" || fail "javac failed"

# ---- 2. resources, manifest, assets -> an APK without code
echo "== aapt2"
set -- link -o "$WORK/base.apk" -I "$JAR" --manifest "$MANIFEST" --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" --auto-add-overlay
if [ -d "$APP/res" ]; then
  "$AAPT2" compile --dir "$APP/res" -o "$WORK/res.zip" || fail "aapt2 compile failed"
  set -- "$@" -R "$WORK/res.zip"
fi
[ ! -d "$APP/assets" ] || set -- "$@" -A "$APP/assets"
"$AAPT2" "$@" || fail "aapt2 link failed"

# ---- 3. class files -> classes.dex, added to the APK
echo "== d8"
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
set --
while IFS= read -r f; do set -- "$@" "$f"; done < "$WORK/classes.txt"
"$D8" --lib "$JAR" --min-api "$MIN_SDK" --output "$WORK/dex" "$@" || fail "d8 failed"
[ -f "$WORK/dex/classes.dex" ] || fail "d8 wrote no classes.dex"
if command -v zip >/dev/null 2>&1; then
  (cd "$WORK/dex" && zip -q -X "$WORK/base.apk" classes.dex) || fail "cannot add classes.dex to the APK"
else
  python3 - "$WORK/base.apk" "$WORK/dex/classes.dex" <<'PY' || fail "cannot add classes.dex to the APK"
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], "classes.dex")
PY
fi

# ---- 4. align, then sign (the signature must come after zipalign)
echo "== zipalign"
rm -f "$WORK/aligned.apk"
"$ZIPALIGN" -f -p 4 "$WORK/base.apk" "$WORK/aligned.apk" || fail "zipalign failed"

echo "== apksigner"
if [ -z "$KEYSTORE" ]; then
  KEYSTORE=$HOME/.skarph/debug.keystore
  [ -n "$KS_PASS" ] || KS_PASS=pass:android
  [ -n "$KEY_ALIAS" ] || KEY_ALIAS=skarphdebug
  if [ ! -f "$KEYSTORE" ]; then
    echo "   creating the debug key $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Skarph debug,O=Skarph,C=US" >/dev/null 2>&1 \
        || fail "keytool could not create the debug key"
  fi
  [ -n "$KEY_PASS" ] || KEY_PASS=pass:android
fi
[ -f "$KEYSTORE" ] || fail "$KEYSTORE not found"
set -- sign --ks "$KEYSTORE" --out "$OUTPUT"
[ -z "$KS_PASS" ] || set -- "$@" --ks-pass "$KS_PASS"
[ -z "$KEY_ALIAS" ] || set -- "$@" --ks-key-alias "$KEY_ALIAS"
[ -z "$KEY_PASS" ] || set -- "$@" --key-pass "$KEY_PASS"
"$APKSIGNER" "$@" "$WORK/aligned.apk" || fail "apksigner sign failed"
"$APKSIGNER" verify "$OUTPUT" || fail "the signature of $OUTPUT does not verify"

echo "== $OUTPUT ($(wc -c < "$OUTPUT") bytes, signature verified)"
echo "install it:  adb install -r $OUTPUT"
