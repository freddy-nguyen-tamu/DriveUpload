#!/usr/bin/env bash
set -e

if [ $# -lt 1 ]; then
  echo "Usage: ./build-apk.sh /absolute/path/to/drive-upload-release.jks [key-alias]"
  exit 1
fi

ROOT="$(cd "$(dirname "$0")" && pwd)"
KEYSTORE="$(realpath "$1")"
ALIAS="${2:-driveupload}"
TOOLS="$ROOT/.build-tools-linux"
SDK="$TOOLS/android-sdk"
GRADLE="$TOOLS/gradle-8.11.1"

command -v java >/dev/null || { echo "Install JDK 17+ first."; exit 1; }
command -v unzip >/dev/null || { echo "Install unzip first."; exit 1; }
command -v curl >/dev/null || { echo "Install curl first."; exit 1; }

read -rsp "Keystore password: " STOREPASS; echo
read -rsp "Key password for '$ALIAS': " KEYPASS; echo

mkdir -p "$TOOLS"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  curl -L "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" -o "$TOOLS/cmdline.zip"
  rm -rf "$TOOLS/cmdline-temp"
  mkdir -p "$TOOLS/cmdline-temp" "$SDK/cmdline-tools"
  unzip -q "$TOOLS/cmdline.zip" -d "$TOOLS/cmdline-temp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TOOLS/cmdline-temp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$TOOLS/cmdline-temp"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

if [ ! -x "$GRADLE/bin/gradle" ]; then
  curl -L "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip" -o "$TOOLS/gradle.zip"
  unzip -q "$TOOLS/gradle.zip" -d "$TOOLS"
fi

export XONG_KEYSTORE="$KEYSTORE"
export XONG_STORE_PASSWORD="$STOREPASS"
export XONG_KEY_ALIAS="$ALIAS"
export XONG_KEY_PASSWORD="$KEYPASS"

cd "$ROOT"
"$GRADLE/bin/gradle" --no-daemon :app:assembleRelease

echo "APK: $ROOT/app/build/outputs/apk/release/app-release.apk"
