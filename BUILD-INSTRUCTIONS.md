# CastLink v0.3.0 · 构建说明

## 0. v0.3.0 变更摘要（2026-10-11）

- **安卓发送端**（`sender/` 包，5 文件）：MainActivity 新增"把本机屏幕投出去"→
  SenderActivity（NsdHelper 发现 `_castlink._tcp` 设备 → 输 PIN → MediaProjection
  授权）→ SenderService（前台 Service，MediaCodec H.264 Surface 编码
  1280x720/2Mbps/30fps，IDR 前补 SPS/PPS）→ 自研 RtpPacketizer（单 NAL /
  FU-A 分片，MTU 1200）→ UDP 推流；SignalingClient 走 `HELLO/PIN/BYE` 行协议，
  与接收端 `SignalingServer`/`RtpReceiver` 逐字段对齐。视频-only（与接收端现状一致）。
- **ALAC 音频做实**：macosforge/alac（Apache-2.0，commit c38887c，仅解码器部分）
  源码在 `app/src/main/jni/alac/`，NDK r28c 自研 `alac_jni.cpp`（无 libc++_shared
  依赖）编出 `libalac.so` 四 ABI（arm64-v8a/armeabi-v7a/x86_64/x86），打进 APK；
  `AudioStreamServer` ct=2 走真实 ALAC 路径，静默丢帧已去掉。
  详见 `THIRD-PARTY-NOTICES` 第 6 条。
- **自有协议 PIN 输错锁定**：新建 `util/PinLockout.kt` + `protocol/PinVerifier.kt`
 （3 次错锁 60 秒，SharedPreferences 持久化，成功清零）；`SignalingServer`
  新增可选 `pinVerifier`（null 时回退原行为）；待机页显示"已锁定，请 X 秒后
  重试或扫码"；`SignalingClient` 兼容 `PIN_LOCKED <秒>`。
- **待机 30 秒降亮**：`StandbyActivity` 无操作 30s → screenBrightness 0.05；
  触摸/投屏开始恢复；防烧屏。
- **文件投放**：`protocol/FilePushServer.kt`（自研极简 HTTP，PUT /push/<名> →
  cacheDir/filepush，防 `..` 穿越）；`ui/PlayerActivity.kt`（MediaPlayer 播放，
  如实支持：MP4/H.264、3GP、WebM VP8/VP9、MP3、AAC、FLAC、WAV；MKV/HEVC 不承诺）；
  待机页底栏显示 `http://<ip>:8756`。
- 产物：`CastLink-v0.3.0-debug.apk`（versionCode 3，3.7MB）。
- 无 GPL 引入；`app/build.gradle.kts` 版本号同步为 3 / 0.3.0。

## 0.1 v0.3.0 沙箱构建步骤

```bash
# 1) 编 libalac.so（NDK r28c 在 ~/toolchains/android-ndk-r28c）
bash tools/build_libalac.sh
# 产物：app/src/main/jniLibs/<abi>/libalac.so（四份）

# 2) 打包（手工链，Gradle daemon 在沙箱无解）
bash tools/build_manual.sh
# 产物：CastLink-v0.3.0-debug.apk（工程根 + build/manual/）

# 3) JVM 自测
bash tools/run_jvm_tests.sh      # PinLockout 21 + FilePush 15 + AlacDecoder 12
bash tools/run_sender_tests.sh   # 发送端协议 95 断言
```

验证：`apksigner verify` OK；`aapt2 dump badging`（versionCode 3 /
versionName 0.3.0 / minSdk 26）；`unzip -l` 确认 `lib/*/libplayfair.so` +
`lib/*/libalac.so` 共 8 份。

## 0.2 v0.3.0 已知限制（诚实记录）

- 无真机：MediaProjection 授权流程、编码器行为、VirtualDisplay、前台服务保活、
  ALAC 真解码（nativeDecode 需 ARM 设备）、PIN 锁定 TV 端交互、降亮观感、
  文件投放真机收发，均未验证，需用户真机验证。
- 沙箱拦截 Java IPv4 loopback：JVM 测试走 Piped 流 / `::1` / 反射驱动真实解析
  代码，传输层（TCP 建连、UDP 发包）未在沙箱验证。
- DRM 内容（Netflix/Disney+/Apple TV）不支持，是硬限制。
- 发送端视频-only，无音频推流。

---

# CastLink v0.2.0 · 构建说明（历史）

## 0. v0.2.0 变更摘要（2026-10-11）

- 移植 PhairPlay（Apache-2.0）的 AirPlay 接收核心到
  `app/src/main/java/com/castlink/receiver/airplay/`（33 个 Kotlin 文件，包名已改写，
  文件头有移植声明；详见 `THIRD-PARTY-NOTICES`）。
- FairPlay 握手 + PIN 配对 + PIN 输错锁定做实（`RtspHandler` + `PairingStore`，
  prefs 文件名 `castlink_pairings`）；`protocol/AirPlayReceiver.kt` 旧桩已删除。
- `libplayfair.so` 用 NDK r28c 自行编译（arm64-v8a / armeabi-v7a / x86_64 / x86），
  打进 APK 的 `lib/<abi>/` 下；JNI 函数名已随包名改写。
- 新增 `AirPlayService`（前台 Service，`pinAuthEnabled=true`）与
  `AirPlayUiBridge`（PIN/状态/发送端名/ Surface 回调桥）；待机页四格 PIN 显示
  AirPlay 配对码，控制页 Surface 作为镜像解码目标。
- `NsdHelper` 不再占位注册 `_airplay._tcp`（改由移植的 `MdnsService` 真实广播）。
- 新增依赖：kotlinx-coroutines-core-jvm/android 1.6.4、BouncyCastle
  bcprov-jdk18on 1.78.1（MIT）、dd-plist 1.28（MIT）。
- 产物：`CastLink-v0.2.0-debug.apk`（versionCode 2）。

## 0.1 v0.2.0 沙箱构建步骤

```bash
# 1) 依赖 jar（Maven Central，curl 可达）
#    kotlinx-coroutines-android-1.6.4.jar / bcprov-jdk18on-1.78.1.jar /
#    dd-plist-1.28.jar → ~/toolchains/m2repo（标准 Maven 目录布局）

# 2) 编译 libplayfair.so（NDK r28c 在 ~/toolchains/android-ndk-r28c）
NDK=~/toolchains/android-ndk-r28c
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang
# 先把 fairplay_jni.c 的 JNI 名 sed 为
# Java_com_castlink_receiver_airplay_handshake_FairPlay_nativePlayfairDecrypt
for t in "aarch64-linux-android26:arm64-v8a" "armv7a-linux-androideabi26:armeabi-v7a" \
         "x86_64-linux-android26:x86_64" "i686-linux-android26:x86"; do
  $CC --target=${t%%:*} -shared -fPIC -O2 \
    -I$JAVA_HOME/include -I$JAVA_HOME/include/linux \
    fairplay_jni.c playfair/*.c \
    -o app/src/main/jniLibs/${t##*:}/libplayfair.so
done

# 3) 打包（手工链，Gradle daemon 在沙箱无解）
bash tools/build_manual.sh
# 产物：CastLink-v0.2.0-debug.apk（工程根 + build/manual/）
```

验证：`apksigner verify`、`aapt2 dump badging`（包名/版本/minSdk 26）、
dex 字符串确认 airplay 类、`unzip -l` 确认 `lib/*/libplayfair.so`。
协议逻辑 JVM 自测见 §6。

---

# CastLink MVP v0.1 · 构建说明（历史）

## 1. 环境要求（正常开发机）

| 工具 | 版本 |
|---|---|
| JDK | 17（Gradle 8.x / AGP 8.5 要求） |
| Android SDK | platform-34、build-tools 34.0.0、platform-tools |
| Gradle | 8.10.2（或用 `gradle/wrapper/gradle-wrapper.properties` 的 wrapper） |
| Kotlin | 2.0.21（由 kotlin-gradle-plugin 自动引入） |
| AGP | 8.5.2 |

## 2. 正常构建（有网络）

```bash
cd ~/workspace/castlink
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次构建会自动从 google() / mavenCentral() 下载 AGP、Kotlin 插件与 kotlin-stdlib、zxing。

## 3. 沙箱离线构建（本机用过的方法）

本沙箱的 Gradle Java HTTP 栈走不通 egress 代理（curl 可以），且 Gradle daemon 的 TCP loopback
被拦截。采用以下绕法：

1. 用 curl 手动下载 JDK17 / cmdline-tools / Gradle，见 `tools/setup_sdk.sh`；
2. 用 `tools/fetch_deps.py`（BFS 解析 POM）把 AGP、Kotlin 插件、kotlin-stdlib、zxing 的
   全部传递依赖抓到 `~/toolchains/m2repo`（标准 Maven 目录布局）；
3. 构建：
   ```bash
   bash tools/build_apk.sh
   # 等价于：
   # gradle :app:assembleDebug -PcastlinkLocalRepo=$HOME/toolchains/m2repo --offline
   ```
4. `gradle.properties` 里已配 `-Djava.net.preferIPv6Stack=true` 绕过 loopback 拦截。

## 4. 工程结构

```
castlink/
├── settings.gradle.kts          # castlinkLocalRepo 参数控制离线/在线仓库
├── build.gradle.kts             # 插件版本声明（AGP 8.5.2 / Kotlin 2.0.21）
├── gradle.properties
├── BUILD-INSTRUCTIONS.md
├── tools/
│   ├── fetch_deps.py            # 沙箱离线依赖抓取脚本
│   ├── setup_sdk.sh             # 沙箱工具链安装脚本
│   └── build_apk.sh             # 沙箱离线构建脚本
└── app/
    ├── build.gradle.kts         # minSdk 26 / targetSdk 34，无 AndroidX 依赖
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/castlink/receiver/
        │   ├── CastLinkApp.kt
        │   ├── model/Device.kt
        │   ├── util/{LocaleHelper,NetUtil}.kt
        │   ├── ui/{MainActivity,ControlActivity,StandbyActivity,SettingsActivity}.kt
        │   ├── discovery/NsdHelper.kt
        │   ├── protocol/{SignalingServer,RtpReceiver,AirPlayReceiver}.kt
        │   └── player/{VideoPipeline,LoopbackTest}.kt
        └── res/{layout,values,values-en,values/themes.xml,values/colors.xml}
```

## 6. v0.2.0 验证与已知限制（诚实记录）

已验证：
- `tools/build_manual.sh` 全链通过：aapt2 compile/link、kotlinc 0 error（51 个源码）、
  d8、zipalign、`apksigner verify` 通过；`aapt2 dump badging`：包名
  com.castlink.receiver、versionCode 2、versionName 0.2.0、minSdk 26、targetSdk 34。
- dex 内 187 个 `com/castlink/receiver/airplay/` 类描述符（含 AirPlayReceiver /
  AirPlayService / RtspHandler / MdnsService / FairPlay / PairingSession /
  ProtocolState）；APK 内 `lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libplayfair.so`
  四份俱全；manifest 含 FOREGROUND_SERVICE 权限与 `.airplay.AirPlayService` 声明。
- JVM 协议自测（纯逻辑，不引 android.jar；SdpParser 用的 Logger 为 stdout 桩）：
  FairPlay setup/handshake 字节向量断言、PlistCodec 编解码 roundtrip、
  SdpParser 视频+音频/纯音频/畸形输入、Base64Util roundtrip —— 50 项全过。

未验证（必须明说）：
- 沙箱无真 iPhone，AirPlay 握手无端到端实测。`FairPlay.decrypt()` 的 native 路径
  只在真机上有 `libplayfair.so` 可加载，沙箱 JVM 下未执行。
- 音频：AAC 路径为真实实现；ALAC（macOS 系统音频 ct=2）因 `libalac.so` 未编译，
  以日志标 TODO 并静默丢帧（AudioPlayer 侧原本就有 runCatching 保护，
  AudioStreamServer 侧本轮加了保护）。

历史（v0.1）已知限制：
- 视频链路（TCP 信令 + RTP/UDP + MediaCodec H.264 解码 + Surface 渲染）
  为真实骨架实现，`LoopbackTest` 可在真机上做编码→解码回环自测（沙箱无 Android 运行时，
  无法执行）。
- CastLink 自有协议的 PIN 输错锁定、30 秒降亮、媒体页文件投放仍为 TODO 桩
 （AirPlay 侧的 PIN 输错锁定已做实）。
