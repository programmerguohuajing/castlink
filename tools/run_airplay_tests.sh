#!/bin/bash
# AirPlay 协议 JVM 自测（不引 android.jar；airplay.util.Logger 用 tools/tests/stubs 桩）。
# 重建 v0.2.0 的 50 项协议测试：FairPlay fp-setup 字节向量、PlistCodec roundtrip、
# SdpParser（视频+音频 / 纯音频 / 畸形输入）、Base64Util roundtrip，另加 RaopRsa
# 与 LegacyPairSetupPin 的 JVM 安全部分。
# 注意：FairPlay.decrypt() 的 native 路径（Android ELF）在沙箱 JVM 下无法执行，仅断言
# 未 handshake 前抛 IllegalStateException。
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
DDPLIST="$M2/com/googlecode/plist/dd-plist/1.28/dd-plist-1.28.jar"

PROJ=~/workspace/castlink
AP=$PROJ/app/src/main/java/com/castlink/receiver/airplay
TESTS=$PROJ/tools/tests
OUT=/tmp/airplaytest-out
rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== kotlinc (JVM only, no android.jar) ==="
"$JAVA_HOME/bin/java" -cp "$KOTLINC_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -cp "$STDLIB:$DDPLIST" \
  -jvm-target 17 \
  -d "$OUT" \
  "$AP/util/Base64Util.kt" \
  "$AP/handshake/RaopRsa.kt" \
  "$AP/handshake/FairPlay.kt" \
  "$AP/handshake/PlistCodec.kt" \
  "$AP/handshake/LegacyPairSetupPin.kt" \
  "$AP/SdpParser.kt" \
  "$TESTS/stubs/Logger.kt" \
  "$TESTS/AirPlayProtocolTest.kt" 2>&1 | grep -v "^warning" || true

echo; echo "=== AirPlayProtocolTest ==="
"$JAVA_HOME/bin/java" -cp "$OUT:$STDLIB:$DDPLIST" com.castlink.receiver.airplay.handshake.AirPlayProtocolTestKt
