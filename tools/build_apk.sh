#!/bin/bash
# CastLink 离线构建 debug APK（沙箱专用）
# 前提：setup_sdk.sh 已跑过；tools/fetch_deps.py 已跑过（~/toolchains/m2repo 有内容）
set -e
T=~/toolchains
export JAVA_HOME="$T/jdk17"
export ANDROID_HOME="$T/android-sdk"
export ANDROID_SDK_ROOT="$T/android-sdk"
export PATH="$T/gradle/gradle-8.10.2/bin:$JAVA_HOME/bin:$PATH"

cd ~/workspace/castlink
gradle :app:assembleDebug \
  -PcastlinkLocalRepo="$HOME/toolchains/m2repo" \
  --offline \
  --console=plain \
  "$@"

echo "=== 产物 ==="
ls -la app/build/outputs/apk/debug/
