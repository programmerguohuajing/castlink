#!/bin/bash
# CastLink 手动构建 debug APK（绕过 Gradle daemon 的沙箱网络问题）
# 工具链：aapt2 + kotlin-compiler-embeddable + d8 + zipalign + apksigner
set -e
T=~/toolchains
export JAVA_HOME="$T/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$T/android-sdk"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
M2="$HOME/toolchains/m2repo"
KOTLINC_JAR="$M2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar"
STDLIB="$M2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar"
ZXING="$M2/com/google/zxing/core/3.5.3/core-3.5.3.jar"
# v0.2.0 AirPlay 移植新增依赖（见 THIRD-PARTY-NOTICES）
COROUTINES_CORE="$M2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar"
COROUTINES_ANDROID="$M2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.6.4/kotlinx-coroutines-android-1.6.4.jar"
BCPROV="$M2/org/bouncycastle/bcprov-jdk18on/1.78.1/bcprov-jdk18on-1.78.1.jar"
DDPLIST="$M2/com/googlecode/plist/dd-plist/1.28/dd-plist-1.28.jar"
APP_CP="$AJAR:$STDLIB:$ZXING:$COROUTINES_CORE:$COROUTINES_ANDROID:$BCPROV:$DDPLIST"

PROJ=~/workspace/castlink
OUT=$PROJ/build/manual
VERSION_NAME="0.1.10"
VERSION_CODE=31
APK_NAME="CastLink-v${VERSION_NAME}.apk"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "=== [1/8] aapt2 compile (res) ==="
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$OUT/compiled_res.zip"

echo "=== [2/8] aapt2 link (manifest + res -> base.apk + R.java) ==="
# 手动构建没有 AGP 的 namespace 注入，临时给 manifest 加 package 属性（不改源码）
MANIFEST_TMP="$OUT/AndroidManifest.xml"
sed 's|<manifest xmlns:android=|<manifest package="com.castlink.receiver" xmlns:android=|' \
  "$PROJ/app/src/main/AndroidManifest.xml" > "$MANIFEST_TMP"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$MANIFEST_TMP" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code $VERSION_CODE \
  --version-name "$VERSION_NAME" \
  "$OUT/compiled_res.zip"

echo "=== [3/8] kotlinc (.kt + R.java -> .class) ==="
find "$PROJ/app/src/main/java" -name "*.kt" > "$OUT/sources.txt"
find "$OUT/gen" -name "*.java" >> "$OUT/sources.txt"
wc -l "$OUT/sources.txt"
# compiler 运行需要的 jar（kotlin-compiler-embeddable 自身不自带）
COROUTINES="$M2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar"
SCRIPT_RT="$M2/org/jetbrains/kotlin/kotlin-script-runtime/2.0.21/kotlin-script-runtime-2.0.21.jar"
TROVE="$M2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"
ANNOT="$M2/org/jetbrains/annotations/13.0/annotations-13.0.jar"
KOTLINC_CP="$KOTLINC_JAR:$STDLIB:$COROUTINES:$SCRIPT_RT:$TROVE:$ANNOT"
"$JAVA_HOME/bin/java" -cp "$KOTLINC_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$APP_CP" \
  -jvm-target 17 \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "=== [4/8] d8 (.class -> classes.dex) ==="
"$BT/d8" --min-api 26 \
  --lib "$AJAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class") \
  "$STDLIB" "$ZXING" "$COROUTINES_CORE" "$COROUTINES_ANDROID" "$BCPROV" "$DDPLIST"
ls -la "$OUT/dex/"

echo "=== [5/8] 打包 classes.dex + libplayfair.so 进 APK ==="
cp "$OUT/base.apk" "$OUT/app-debug-unsigned.apk"
python3 -c "
import zipfile, os, glob
with zipfile.ZipFile('$OUT/app-debug-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('$OUT/dex/classes.dex', 'classes.dex')
    for so in sorted(glob.glob('$PROJ/app/src/main/jniLibs/*/*.so')):
        abi = os.path.basename(os.path.dirname(so))
        # v0.3.7：.so 必须不压缩存储（ZIP_STORED）。压缩存储时部分 TV 盒子的包管理器
        # 解压失败 → System.loadLibrary 报 UnsatisfiedLinkError（E900V22C 实测）。
        z.write(so, 'lib/%s/%s' % (abi, os.path.basename(so)), compress_type=zipfile.ZIP_STORED)
        print('added lib/%s/%s (stored)' % (abi, os.path.basename(so)))
print('dex+so added')
"

echo "=== [6/8] zipalign ==="
"$BT/zipalign" -f 4 "$OUT/app-debug-unsigned.apk" "$OUT/app-debug-aligned.apk"

echo "=== [7/8] apksigner (debug 签名) ==="
# v0.3.5：keystore 移到 $PROJ/build（$OUT 每次构建会被清空，此前每次签名都不同导致无法覆盖安装）
KS="$PROJ/build/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/$APK_NAME" "$OUT/app-debug-aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/$APK_NAME" | head -5

echo "=== [8/8] 拷贝产物到工程根 ==="
cp "$OUT/$APK_NAME" "$PROJ/$APK_NAME"

echo "=== 产物 ==="
ls -la "$OUT/$APK_NAME" "$PROJ/$APK_NAME"
echo "=== badging ==="
"$BT/aapt2" dump badging "$OUT/$APK_NAME" 2>/dev/null | head -8 || \
"$BT/aapt" dump badging "$OUT/$APK_NAME" | head -8
