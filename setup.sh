#!/usr/bin/env bash
# Sets up everything needed to build Expressive Keyboard on Linux or macOS and builds the APK.
# Installs a JDK and the Android SDK into .tools/ if they aren't already on this machine.
#
#   ./setup.sh             debug build   -> app/build/outputs/apk/debug/
#   ./setup.sh --release   release build -> app/build/outputs/apk/release/
#   ./setup.sh --no-build  only install the toolchain
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
tools="$root/.tools"
mode="debug"
for a in "$@"; do
  case "$a" in
    --release) mode="release" ;;
    --no-build) mode="none" ;;
    *) echo "unknown option: $a" >&2; exit 1 ;;
  esac
done

case "$(uname -s)" in
  Linux) os=linux; sdk_os=linux ;;
  Darwin) os=mac; sdk_os=mac ;;
  *) echo "Use setup.ps1 on Windows." >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64) arch=x64 ;;
  arm64|aarch64) arch=aarch64 ;;
  *) echo "Unsupported CPU: $(uname -m)" >&2; exit 1 ;;
esac

fetch() { curl -fL --retry 3 -o "$2" "$1"; }
java_major() { "$1" -version 2>&1 | head -1 | sed -E 's/[^"]*"([0-9]+).*/\1/'; }

# ---------------------------------------------------------------- JDK 17+
java_home=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && [ "$(java_major "$JAVA_HOME/bin/java")" -ge 17 ] 2>/dev/null; then
  java_home="$JAVA_HOME"
elif command -v java >/dev/null && [ "$(java_major java)" -ge 17 ] 2>/dev/null; then
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)")")"
fi
if [ -z "$java_home" ]; then
  if [ ! -d "$tools/jdk" ]; then
    echo "Downloading JDK 21 (Temurin)..."
    mkdir -p "$tools/jdk"
    fetch "https://api.adoptium.net/v3/binary/latest/21/ga/$os/$arch/jdk/hotspot/normal/eclipse" "$tools/jdk.tar.gz"
    tar -xzf "$tools/jdk.tar.gz" -C "$tools/jdk"
    rm "$tools/jdk.tar.gz"
  fi
  java_home="$(find "$tools/jdk" -mindepth 1 -maxdepth 1 -type d | head -1)"
  [ -d "$java_home/Contents/Home" ] && java_home="$java_home/Contents/Home"
fi
export JAVA_HOME="$java_home"
echo "JDK: $JAVA_HOME"

# ---------------------------------------------------------------- Android SDK
sdk=""
for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "$tools/android-sdk"; do
  if [ -n "$c" ] && [ -d "$c" ]; then sdk="$c"; break; fi
done
[ -z "$sdk" ] && sdk="$tools/android-sdk"
sdkmanager="$(find "$sdk/cmdline-tools" -name sdkmanager -type f 2>/dev/null | head -1 || true)"
if [ -z "$sdkmanager" ]; then
  echo "Downloading Android command-line tools..."
  mkdir -p "$sdk/cmdline-tools"
  fetch "https://dl.google.com/android/repository/commandlinetools-$sdk_os-13114758_latest.zip" "$tools/cmdline-tools.zip"
  unzip -q -o "$tools/cmdline-tools.zip" -d "$sdk/cmdline-tools"
  rm "$tools/cmdline-tools.zip"
  mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
  sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
fi
export ANDROID_HOME="$sdk"
echo "Android SDK: $sdk"

# Accept the Android SDK licenses (these are the hashes sdkmanager writes when you accept them),
# so Gradle can download the platform and build tools it needs on its own.
echo "Accepting Android SDK licenses..."
mkdir -p "$sdk/licenses"
if [ ! -f "$sdk/licenses/android-sdk-license" ]; then
  printf '
24333f8a63b6825ea9c5514f83c2829b004d1fee
d56f5187479451eabf01fb78af6dfcb131a6481e
8933bad161af4178b1185d1a37fbf41ea5269c55' > "$sdk/licenses/android-sdk-license"
fi
[ -d "$sdk/platform-tools" ] || "$sdkmanager" --sdk_root="$sdk" "platform-tools" >/dev/null

echo "sdk.dir=$sdk" > "$root/local.properties"

[ "$mode" = "none" ] && { echo "Toolchain ready."; exit 0; }

# ---------------------------------------------------------------- build
cd "$root"
chmod +x gradlew
if [ "$mode" = "release" ]; then ./gradlew assembleRelease; out=app/build/outputs/apk/release
else ./gradlew assembleDebug; out=app/build/outputs/apk/debug; fi
echo
echo "Done. APKs:"
ls -1 "$out"/*.apk
