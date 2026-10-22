#!/usr/bin/env bash
# Builds, signs and (optionally) installs Luma without Gradle. macOS, Linux or Git Bash on Windows.
# Required environment: JAVA_HOME, ANDROID_SDK_ROOT (platforms/android-35, build-tools/35.0.0),
# LUMA_KEYSTORE, LUMA_KEYSTORE_PASS. Optional: LUMA_DEVICE (ADB serial) to install and open the APK.
# Run from anywhere: tools/build.sh
set -euo pipefail
# On Windows (Git Bash) the SDK tools are native programs: use D:/style paths and the .bat launchers for d8 and apksigner.
bat=""; pwdflag=""; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) bat=".bat"; pwdflag="-W";; esac
root="$(cd "$(dirname "$0")/.." && pwd $pwdflag)"
: "${JAVA_HOME:?Set JAVA_HOME}" "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}" "${LUMA_KEYSTORE:?Set LUMA_KEYSTORE}" "${LUMA_KEYSTORE_PASS:?Set LUMA_KEYSTORE_PASS}"
tools="$ANDROID_SDK_ROOT/build-tools/35.0.0"; jar="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"; out="$root/build"

rm -rf "$out"; mkdir -p "$out/classes"
find "$root/app/src" -name '*.java' > "$out/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -nowarn -encoding UTF-8 -classpath "$jar" -d "$out/classes" @"$out/sources.txt"
"$JAVA_HOME/bin/jar" cf "$out/classes.jar" -C "$out/classes" .
"$tools/d8$bat" --lib "$jar" --min-api 26 --output "$out" "$out/classes.jar"
"$tools/aapt" package -f -M "$root/app/AndroidManifest.xml" -S "$root/app/res" -A "$root/app/assets" -I "$jar" -F "$out/unsigned.apk"
(cd "$out" && "$JAVA_HOME/bin/jar" uf unsigned.apk classes.dex)
"$tools/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$tools/apksigner$bat" sign --ks "$LUMA_KEYSTORE" --ks-pass "pass:$LUMA_KEYSTORE_PASS" --out "$out/luma.apk" "$out/aligned.apk"
echo "Built $out/luma.apk"

if [ -n "${LUMA_DEVICE:-}" ]; then
  "$ANDROID_SDK_ROOT/platform-tools/adb" -s "$LUMA_DEVICE" install -r "$out/luma.apk"
  "$ANDROID_SDK_ROOT/platform-tools/adb" -s "$LUMA_DEVICE" shell am start -n com.dhyn.luma/com.dhyn.tvhome.LumaLauncher
fi
