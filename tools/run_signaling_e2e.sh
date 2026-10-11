#!/bin/bash
# 信令 PIN_LOCKED 端到端：真实 SignalingServer（verifier 非空）←Piped 字节泵→
# 真实 sender/SignalingClient（注入内存 Piped 流）。不引 android.jar。
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
OUT=/tmp/siglocke2e-out
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== [1/3] javac (android.util.Log 桩) ==="
javac -encoding UTF-8 -d "$OUT" "$PROJ/tools/sender_tests/android/util/Log.java"

echo "=== [2/3] kotlinc (真实 SignalingServer + SignalingClient + E2E 测试) ==="
"$JAVA_HOME/bin/java" -cp "$KOTLINC_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$STDLIB:$OUT" \
  -jvm-target 17 \
  -d "$OUT" \
  "$PROJ/app/src/main/java/com/castlink/receiver/protocol/SignalingServer.kt" \
  "$PROJ/app/src/main/java/com/castlink/receiver/protocol/PinVerifier.kt" \
  "$PROJ/app/src/main/java/com/castlink/receiver/sender/SignalingClient.kt" \
  "$PROJ/tools/tests/SignalLockoutE2eTest.kt" 2>&1 | grep -v "^warning" || true

echo "=== [3/3] 运行 E2E 测试 ==="
"$JAVA_HOME/bin/java" -cp "$OUT:$STDLIB" SignalLockoutE2eTestKt
