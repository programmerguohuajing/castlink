#!/bin/bash
# v0.3.0 JVM 级逻辑测试（不引 android.jar）：
#   1. PinLockout 状态机
#   2. FilePushServer HTTP roundtrip
#   3. AlacDecoder 参数校验路径
set -e
T=~/toolchains
export JAVA_HOME="$T/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
M2="$HOME/toolchains/m2repo"
KOTLINC_JAR="$M2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar"
STDLIB="$M2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar"
COROUTINES="$M2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar"
SCRIPT_RT="$M2/org/jetbrains/kotlin/kotlin-script-runtime/2.0.21/kotlin-script-runtime-2.0.21.jar"
TROVE="$M2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"
ANNOT="$M2/org/jetbrains/annotations/13.0/annotations-13.0.jar"
KOTLINC_CP="$KOTLINC_JAR:$STDLIB:$COROUTINES:$SCRIPT_RT:$TROVE:$ANNOT"

PROJ=~/workspace/castlink
SRC=$PROJ/app/src/main/java/com/castlink/receiver
TESTS=$PROJ/tools/tests
OUT=/tmp/jvmtest-out
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== kotlinc (JVM only, no android.jar) ==="
"$JAVA_HOME/bin/java" -cp "$KOTLINC_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -cp "$STDLIB" \
  -jvm-target 17 \
  -d "$OUT" \
  "$SRC/util/PinLockout.kt" \
  "$SRC/protocol/FilePushServer.kt" \
  "$SRC/protocol/PinVerifier.kt" \
  "$SRC/airplay/handshake/AlacDecoder.kt" \
  "$TESTS/stubs/Logger.kt" \
  "$TESTS/PinLockoutTest.kt" \
  "$TESTS/FilePushServerTest.kt" \
  "$TESTS/FilePushEdgeTest.kt" \
  "$TESTS/AlacDecoderTest.kt" 2>&1 | grep -v "^warning" || true

RUN_CP="$OUT:$STDLIB"
echo; echo "=== PinLockoutTest ==="
"$JAVA_HOME/bin/java" -cp "$RUN_CP" PinLockoutTestKt
echo; echo "=== FilePushServerTest ==="
"$JAVA_HOME/bin/java" -cp "$RUN_CP" FilePushServerTestKt
echo; echo "=== FilePushEdgeTest ==="
"$JAVA_HOME/bin/java" -cp "$RUN_CP" FilePushEdgeTestKt
echo; echo "=== AlacDecoderTest ==="
"$JAVA_HOME/bin/java" -cp "$RUN_CP" AlacDecoderTestKt
