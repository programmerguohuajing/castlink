#!/bin/bash
# CastLink 沙箱工具链安装：JDK17 + Android cmdline-tools + SDK 包 + Gradle
# 前提：~/toolchains/downloads/ 下已有 jdk17.tar.gz、cmdtools.zip、gradle.zip
set -e
T=~/toolchains
echo "=== JDK 17 ==="
mkdir -p "$T/jdk17"
tar -xzf "$T/downloads/jdk17.tar.gz" -C "$T/jdk17" --strip-components=1
"$T/jdk17/bin/java" -version 2>&1 | head -2

echo "=== cmdline-tools ==="
mkdir -p "$T/android-sdk/cmdline-tools"
if command -v unzip >/dev/null; then
  unzip -q -o "$T/downloads/cmdtools.zip" -d "$T/android-sdk/cmdline-tools"
else
  python3 -c "import zipfile; zipfile.ZipFile('$T/downloads/cmdtools.zip').extractall('$T/android-sdk/cmdline-tools')"
fi
# sdkmanager 要求目录结构为 cmdline-tools/latest/
if [ -d "$T/android-sdk/cmdline-tools/cmdline-tools" ] && [ ! -d "$T/android-sdk/cmdline-tools/latest" ]; then
  mv "$T/android-sdk/cmdline-tools/cmdline-tools" "$T/android-sdk/cmdline-tools/latest"
fi

echo "=== Gradle 8.10.2 ==="
mkdir -p "$T/gradle"
if command -v unzip >/dev/null; then
  unzip -q -o "$T/downloads/gradle.zip" -d "$T/gradle"
else
  python3 -c "import zipfile; zipfile.ZipFile('$T/downloads/gradle.zip').extractall('$T/gradle')"
fi

export JAVA_HOME="$T/jdk17"
export PATH="$JAVA_HOME/bin:$T/android-sdk/cmdline-tools/latest/bin:$PATH"
export ANDROID_HOME="$T/android-sdk"
export ANDROID_SDK_ROOT="$T/android-sdk"
# Java 不读 http_proxy 环境变量，必须显式给代理参数（curl 会自动读）
export JAVA_OPTS="-Dhttps.proxyHost=hatch-egress-proxy -Dhttps.proxyPort=3128 -Dhttp.proxyHost=hatch-egress-proxy -Dhttp.proxyPort=3128"

echo "=== 接受 licenses 并安装 SDK 包 ==="
yes | sdkmanager --sdk_root="$T/android-sdk" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$T/android-sdk" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "=== SDK 内容 ==="
ls "$T/android-sdk"
echo "SETUP_OK"
