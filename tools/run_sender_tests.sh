#!/bin/bash
# CastLink 发送端 JVM 协议自测（不引 android.jar）：
# 用 Log 桩编译真实的 protocol/SignalingServer.kt + protocol/RtpReceiver.kt，
# 与真实的 sender/RtpPacketizer.kt + sender/SignalingClient.kt 做往返测试。
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
TESTDIR="$PROJ/tools/sender_tests"
OUT="$TESTDIR/out"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "=== [1/3] javac (android.util.Log 桩) ==="
javac -encoding UTF-8 -d "$OUT" "$TESTDIR/android/util/Log.java"

echo "=== [2/3] kotlinc (真实 protocol + 真实 sender 纯逻辑 + 测试) ==="
"$JAVA_HOME/bin/java" -cp "$KOTLINC_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$STDLIB:$OUT" \
  -jvm-target 17 \
  -d "$OUT" \
  "$PROJ/app/src/main/java/com/castlink/receiver/protocol/SignalingServer.kt" \
  "$PROJ/app/src/main/java/com/castlink/receiver/protocol/PinVerifier.kt" \
  "$PROJ/app/src/main/java/com/castlink/receiver/protocol/RtpReceiver.kt" \
  "$PROJ/app/src/main/java/com/castlink/receiver/sender/RtpPacketizer.kt" \
  "$PROJ/app/src/main/java/com/castlink/receiver/sender/SignalingClient.kt" \
  "$TESTDIR/SenderProtocolTest.kt"

echo "=== [3/3] 运行测试 ==="
"$JAVA_HOME/bin/java" -cp "$OUT:$STDLIB" com.castlink.receiver.sender.SenderProtocolTestKt
