#!/usr/bin/env bash
# Build the LSPlt standalone AAR required by app/build.gradle.kts.
# Output: <repo-parent>/LSPlt/build-android-arm64-v8a-16kb/lsplt-standalone-2.1-16kb.aar
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PARENT_DIR="$(dirname "$REPO_ROOT")"
LSPLT_SRC="${LSPLT_SRC:-$PARENT_DIR/LSPlt-src}"
LSPLT_OUT="$PARENT_DIR/LSPlt/build-android-arm64-v8a-16kb"
NDK_VERSION="${LSPLT_NDK_VERSION:-26.2.11394342}"
CMAKE_VERSION="${LSPLT_CMAKE_VERSION:-3.22.1}"

echo "==> Building LSPlt standalone AAR"
echo "    repo=$REPO_ROOT"
echo "    src=$LSPLT_SRC"
echo "    out=$LSPLT_OUT"

if [[ ! -d "$LSPLT_SRC/.git" ]]; then
  rm -rf "$LSPLT_SRC"
  git clone --depth 1 https://github.com/LSPosed/LSPlt.git "$LSPLT_SRC"
fi

# Standalone must stay compatible with the app's c++_static / no-STL consumer.
# NDK 26 + rikka cxx headers is the combination that currently builds cleanly.
python3 - <<'PY' "$LSPLT_SRC"
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
settings = root / "settings.gradle.kts"
text = settings.read_text(encoding="utf-8")
replacements = {
    'val androidNdkVersion by extra("26.2.11394342")': 'val androidNdkVersion by extra("26.2.11394342")',
}
# Ensure NDK/CMake stay on the known-good pair even if upstream bumps them.
text = text.replace('val androidNdkVersion by extra("30.0.16138531")',
                    'val androidNdkVersion by extra("26.2.11394342")')
text = text.replace('val androidCmakeVersion by extra("4.1.2")',
                    'val androidCmakeVersion by extra("3.22.1")')
if 'val androidNdkVersion by extra("26.2.11394342")' not in text:
    text = text.rstrip() + '\nval androidNdkVersion by extra("26.2.11394342")\n'
if 'val androidCmakeVersion by extra("3.22.1")' not in text:
    text = text.rstrip() + '\nval androidCmakeVersion by extra("3.22.1")\n'
settings.write_text(text, encoding="utf-8")

build = root / "lsplt" / "build.gradle.kts"
bt = build.read_text(encoding="utf-8")
bt = bt.replace(
    "val androidNdkVersion: String by rootProject.extra",
    'val androidNdkVersion: String = "26.2.11394342"',
)
bt = bt.replace(
    "val androidCmakeVersion: String by rootProject.extra",
    'val androidCmakeVersion: String = "3.22.1"',
)
if 'val androidNdkVersion: String = "26.2.11394342"' not in bt:
    bt = bt.replace(
        "val androidCompileSdkVersion: Int by rootProject.extra",
        "val androidCompileSdkVersion: Int by rootProject.extra\n"
        'val androidNdkVersion: String = "26.2.11394342"\n'
        'val androidCmakeVersion: String = "3.22.1"',
    )
# Keep only arm64-v8a to match the app module.
bt = bt.replace(
    'abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")',
    'abiFilters("arm64-v8a")',
)
build.write_text(bt, encoding="utf-8")

cmake = root / "lsplt" / "src" / "main" / "jni" / "CMakeLists.txt"
ct = cmake.read_text(encoding="utf-8")
ct = ct.replace("cmake_minimum_required(VERSION 3.4.1)",
                "cmake_minimum_required(VERSION 3.10)")
cmake.write_text(ct, encoding="utf-8")
print("Patched LSPlt build for CI")
PY

if [[ -n "${ANDROID_HOME:-}" ]]; then
  SDK_DIR="$ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  SDK_DIR="$ANDROID_SDK_ROOT"
else
  SDK_DIR="${HOME}/Android/Sdk"
fi

mkdir -p "$LSPLT_SRC"
printf 'sdk.dir=%s\n' "${SDK_DIR//\\//}" > "$LSPLT_SRC/local.properties"

chmod +x "$LSPLT_SRC/gradlew"
(
  cd "$LSPLT_SRC"
  ./gradlew :lsplt:assembleStandalone --no-daemon
)

AAR_SRC="$LSPLT_SRC/lsplt/build/outputs/aar/lsplt-standalone.aar"
if [[ ! -f "$AAR_SRC" ]]; then
  AAR_SRC="$(find "$LSPLT_SRC/lsplt/build/outputs/aar" -name '*.aar' | head -n1)"
fi
test -f "$AAR_SRC"

mkdir -p "$LSPLT_OUT"
cp -f "$AAR_SRC" "$LSPLT_OUT/lsplt-standalone-2.1-16kb.aar"
echo "==> LSPlt AAR ready: $LSPLT_OUT/lsplt-standalone-2.1-16kb.aar"
