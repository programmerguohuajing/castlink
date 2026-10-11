#!/bin/bash
# 重新编译 libplayfair.so（4 ABI），输出到 app/src/main/jniLibs/。
# 源码：tools/native-src（PhairPlay Apache-2.0，见 THIRD-PARTY-NOTICES）。
# v0.3.8 关键：必须加 -lm——modified_md5.c 用了 sin()，不链接 libm 会在
# arm64 真机上 dlopen 失败（cannot locate symbol "sin"，Fold3 实测）。
set -e
NDK=~/toolchains/android-ndk-r28c
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
SRC="$(cd "$(dirname "$0")/native-src" && pwd)"
OUT="$(cd "$(dirname "$0")/../app/src/main/jniLibs" && pwd)"
export JAVA_HOME=~/toolchains/jdk17

for t in "aarch64-linux-android26:arm64-v8a" \
         "armv7a-linux-androideabi26:armeabi-v7a" \
         "x86_64-linux-android26:x86_64" \
         "i686-linux-android26:x86"; do
  target=${t%%:*}; abi=${t##*:}
  "$CC" --target="$target" -shared -fPIC -O2 -lm \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$SRC/fairplay_jni.c" "$SRC"/playfair/*.c \
    -o "$OUT/$abi/libplayfair.so"
  echo "built $abi"
done
echo "done → $OUT"
