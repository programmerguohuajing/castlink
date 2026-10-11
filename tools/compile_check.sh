#!/bin/bash
# v0.3.0 单验：只跑 aapt2 + kotlinc 编译（不做 d8/打包），0 错误即通过
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
COROUTINES_CORE="$M2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar"
COROUTINES_ANDROID="$M2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.6.4/kotlinx-coroutines-android-1.6.4.jar"
BCPROV="$M2/org/bouncycastle/bcprov-jdk18on/1.78.1/bcprov-jdk18on-1.78.1.jar"
DDPLIST="$M2/com/googlecode/plist/dd-plist/1.28/dd-plist-1.28.jar"
APP_CP="$AJAR:$STDLIB:$ZXING:$COROUTINES_CORE:$COROUTINES_ANDROID:$BCPROV:$DDPLIST"

PROJ=~/workspace/castlink
OUT=$PROJ/build/manual
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$OUT/compiled_res.zip"
MANIFEST_TMP="$OUT/AndroidManifest.xml"
sed 's|<manifest xmlns:android=|<manifest package="com.castlink.receiver" xmlns:android=|' \
  "$PROJ/app/src/main/AndroidManifest.xml" > "$MANIFEST_TMP"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$MANIFEST_TMP" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 2 \
  --version-name "0.2.0" \
  "$OUT/compiled_res.zip"

find "$PROJ/app/src/main/java" -name "*.kt" > "$OUT/sources.txt"
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
  -jvm-target 17 \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"
echo "KOTLINC OK: $(find $OUT/classes -name '*.class' | wc -l) classes"
