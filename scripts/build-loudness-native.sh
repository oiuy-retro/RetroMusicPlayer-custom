#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-/workspace/tools/sdk}/ndk/28.0.13004108}"
PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=24
SRC_EBUR="$ROOT/third_party/libebur128/ebur128/ebur128.c"
SRC_JNI="$ROOT/app/src/main/cpp/loudness_jni.cpp"
INC="$ROOT/third_party/libebur128/ebur128"
OUT="$ROOT/app/src/main/jniLibs"
mkdir -p "$OUT"

build_abi() {
  local abi="$1" triple="$2"
  local clang="$PREBUILT/${triple}${API}-clang"
  local clangxx="$PREBUILT/${triple}${API}-clang++"
  local odir="$ROOT/app/build/tmp-native/$abi"
  mkdir -p "$odir" "$OUT/$abi"
  echo "Building $abi with $clang"
  "$clang" -c "$SRC_EBUR" -I"$INC" -O2 -fPIC -o "$odir/ebur128.o"
  "$clangxx" -c "$SRC_JNI" -I"$INC" -O2 -fPIC -std=c++17 -o "$odir/loudness_jni.o"
  "$clangxx" -shared -O2 -o "$OUT/$abi/libretroloudness.so" \
    "$odir/ebur128.o" "$odir/loudness_jni.o" -llog -lm
}

build_abi arm64-v8a aarch64-linux-android
build_abi armeabi-v7a armv7a-linux-androideabi
build_abi x86 i686-linux-android
build_abi x86_64 x86_64-linux-android
echo "Native libs:"
find "$OUT" -name '*.so' -ls
