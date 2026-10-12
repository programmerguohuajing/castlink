#!/bin/bash
# CastLink Legacy 构建（Android 4.4+/API 19 纯接收器）
# 复用 build_manual.sh 的工具链，独立 source set：app/src/legacy
# 工具链：aapt2 + kotlin-compiler-embeddable + d8 + zipalign + apksigner
set -e

T=~/toolchains
JAVA_HOME=$T/jdk17
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$T/android-sdk"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
M2="$HOME/toolchains/m2repo"

KOTLINC_JAR="$M2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar"
STDLIB="$M2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar"
ZXING="$M2/com/google/zxing/core/3.5.3/core-3.5.3.jar"
APP_CP="$AJAR:$STDLIB:$ZXING"

PROJ=~/workspace/castlink
OUT=$PROJ/build/legacy
VERSION_NAME="0.1.1"
VERSION_CODE=2
APK_NAME="CastLink-legacy-v${VERSION_NAME}.apk"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "=== [1/7] aapt2 compile (legacy res + main mipmaps) ==="
"$BT/aapt2" compile --dir "$PROJ/app/src/legacy/res" -o "$OUT/compiled_res.zip"
# 复用主线的 launcher 图标（mipmap PNG；anydpi-v26 的 adaptive icon 引用主线资源且 API 19 用不上，跳过）
mkdir -p "$OUT/mipmap_tmp"
for d in "$PROJ"/app/src/main/res/mipmap-hdpi "$PROJ"/app/src/main/res/mipmap-mdpi "$PROJ"/app/src/main/res/mipmap-xhdpi "$PROJ"/app/src/main/res/mipmap-xxhdpi "$PROJ"/app/src/main/res/mipmap-xxxhdpi; do
  [ -d "$d" ] && cp -r "$d" "$OUT/mipmap_tmp/"
done
"$BT/aapt2" compile --dir "$OUT/mipmap_tmp" -o "$OUT/compiled_mipmap.zip"

echo "=== [2/7] aapt2 link (manifest + res -> base.apk + R.java) ==="
MANIFEST_TMP="$OUT/AndroidManifest.xml"
sed 's|<manifest xmlns:android=|<manifest package=\"com.castlink.receiver\" xmlns:android=|' \
  "$PROJ/app/src/legacy/AndroidManifest.xml" > "$MANIFEST_TMP"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$MANIFEST_TMP" \
  --java "$OUT/gen" \
  --min-sdk-version 19 \
  --target-sdk-version 34 \
  --version-code $VERSION_CODE \
  --version-name "$VERSION_NAME" \
  "$OUT/compiled_res.zip" "$OUT/compiled_mipmap.zip"

echo "=== [3/7] kotlinc (legacy + shared -> .class) ==="
# Legacy 专有源码
find "$PROJ/app/src/legacy/java" -name "*.kt" > "$OUT/sources.txt"
# 主线共享源码（API 19 兼容子集）
for f in \
  app/src/main/java/com/castlink/receiver/protocol/RtpReceiver.kt \
  app/src/main/java/com/castlink/receiver/protocol/SignalingServer.kt \
  app/src/main/java/com/castlink/receiver/protocol/PinVerifier.kt \
  app/src/main/java/com/castlink/receiver/protocol/NativeStreamBridge.kt \
  app/src/main/java/com/castlink/receiver/protocol/FilePushServer.kt \
  app/src/main/java/com/castlink/receiver/discovery/NsdHelper.kt \
  app/src/main/java/com/castlink/receiver/util/QrUtil.kt ; do
  echo "$PROJ/$f" >> "$OUT/sources.txt"
done
# model 包（纯数据类）
find "$PROJ/app/src/main/java/com/castlink/receiver/model" -name "*.kt" >> "$OUT/sources.txt"
find "$OUT/gen" -name "*.java" >> "$OUT/sources.txt"
wc -l "$OUT/sources.txt"

COROUTINES="$M2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar"
SCRIPT_RT="$M2/org/jetbrains/kotlin/kotlin-script-runtime/2.0.21/kotlin-script-runtime-2.0.21.jar"
TROVE="$M2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"
ANNOT="$M2/org/jetbrains/annotations/13.0/annotations-13.0.jar"
KOTLINC_CP="$KOTLINC_JAR:$STDLIB:$COROUTINES:$SCRIPT_RT:$TROVE:$ANNOT"
"$JAVA_HOME/bin/java" -cp "$KOTLINC_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$APP_CP" \
  -jvm-target 1.8 \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "=== [4/7] d8 (.class -> classes.dex, min-api 19) ==="
"$BT/d8" --min-api 19 \
  --lib "$AJAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class") \
  "$STDLIB" "$ZXING"
ls -la "$OUT/dex/"

echo "=== [5/7] 打包 classes.dex 进 APK ==="
cp "$OUT/base.apk" "$OUT/app-unsigned.apk"
python3 -c "
import zipfile
with zipfile.ZipFile('$OUT/app-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('$OUT/dex/classes.dex', 'classes.dex')
print('dex packed')
"

echo "=== [6/7] zipalign ==="
"$BT/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "=== [7/7] apksigner（稳定 debug 签名，与主线同 keystore） ==="
KS="$PROJ/build/debug.keystore"
if [ ! -f "$KS" ]; then
  echo "keystore 缺失，无法签名" >&2
  exit 1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$PROJ/$APK_NAME" "$OUT/app-aligned.apk"

echo "=== 完成：$PROJ/$APK_NAME ==="
ls -la "$PROJ/$APK_NAME"
