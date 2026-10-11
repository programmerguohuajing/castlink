#!/bin/bash
# 编译 Apple ALAC 解码器（macosforge/alac，Apache-2.0，仅解码器部分）为 libalac.so
# 工具链：~/toolchains/android-ndk-r28c，minSdk 26（API 26）
set -e
NDK=~/toolchains/android-ndk-r28c
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
SRC=~/workspace/castlink/app/src/main/jni/alac
OUT=~/workspace/castlink/app/src/main/jniLibs
API=26

C_SRCS="ALACBitUtilities.c EndianPortable.c ag_dec.c dp_dec.c matrix_dec.c"
CXX_SRCS="ALACDecoder.cpp alac_jni.cpp"

build_abi() {
  local abi=$1 cc_prefix=$2
  local dir=/tmp/alac-build/$abi
  rm -rf "$dir"; mkdir -p "$dir" "$OUT/$abi"
  echo "=== $abi ==="
  for f in $C_SRCS; do
    "$TC/$cc_prefix$API-clang" -c -O2 -fPIC -I"$SRC" "$SRC/$f" -o "$dir/$(basename $f .c).o"
  done
  for f in $CXX_SRCS; do
    "$TC/$cc_prefix$API-clang++" -c -O2 -fPIC -I"$SRC" "$SRC/$f" -o "$dir/$(basename $f .cpp).o"
  done
  # 用 C 驱动链接：JNI 桥仅用 placement new，无 C++ 库符号依赖，
  # 避免 clang++ 驱动默认引入 libc++_shared.so（bionic libc 已提供 __cxa_atexit 等）
  "$TC/$cc_prefix$API-clang" -shared -Wl,--no-undefined \
    "$dir"/*.o -o "$OUT/$abi/libalac.so"
  ls -la "$OUT/$abi/libalac.so"
}

build_abi arm64-v8a  aarch64-linux-android
build_abi armeabi-v7a armv7a-linux-androideabi
build_abi x86_64     x86_64-linux-android
build_abi x86        i686-linux-android

echo "=== NEEDED (expect none / only system libs) ==="
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  echo "-- $abi:"
  $TC/llvm-readelf --dynamic "$OUT/$abi/libalac.so" | grep NEEDED || true
done
