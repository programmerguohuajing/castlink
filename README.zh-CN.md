# CastLink 投屏

[English](README.md)

CastLink 是一款 Android 局域网投屏应用：**发送端 + 接收端 + iOS AirPlay 接收，三合一，装一个 APK 就够**。手机投电视、iPhone 隔空播放镜像、任意文件推送——全走局域网，不经过云端，无需账号。

## 功能

- **三合一**：Android 发送端 + Android 接收端 + AirPlay 接收端，单个 APK（`com.castlink.receiver`）
- **AirPlay 接收**（Android 8+）：iPhone/iPad 屏幕镜像，PIN 配对（SRP）、FairPlay 握手、H.264 视频 + AAC/ALAC 音频
- **Android 发送端**：录屏 → H.264 720p/30fps → RTP → 接收端，带 PIN 校验
- **原生接收管线**：收到投屏流自动弹全屏播放
- **文件投放**：HTTP 推送**任意文件**（APK、视频、音频、文档…），接收端自动分发到播放器 / 安装器 / 系统查看器
- **在线更新**：应用内检查更新，经国内镜像（ghproxy.net、gh-proxy.com…）下载 APK，一键安装
- **NEBULA UI**：深空科技风，响应式布局——手机竖屏/横屏、大屏（电视/车机）三套适配
- **中英双语**：默认跟随系统，可即时切换
- **诊断工具**：内置崩溃上报（无需 adb）、本地回环自测、AirPlay 握手日志

## 上手

1. 在**接收端**（电视/盒子）安装 APK：打开即待机页，显示二维码 + 4 位 PIN。
2. **iPhone**：控制中心 → 屏幕镜像 → 选设备 → 输 PIN。
3. **Android**：打开 CastLink → *把本机屏幕投出去* → 选接收端 → 输 PIN → 允许录屏。
4. **文件投放**：扫待机页二维码（或访问 `http://<接收端IP>:8756/`）→ 上传任意文件。

> 二维码是 `http://` 落地页（iOS 不识别自定义 scheme）。

## 工程结构

```
app/src/main/
  java/com/castlink/receiver/
    airplay/        # AirPlay 接收（RTSP、配对、FairPlay、RTP、ALAC…）
    sender/         # Android 发送端（录屏、H.264 编码、信令）
    protocol/       # 自有协议（信令服务/客户端、RTP、文件推送）
    player/         # VideoPipeline、回环自测
    ui/             # 各页面（主页、待机、发送端、设置、播放页…）
    update/         # 在线更新
    util/           # FileAction、LocaleHelper、NetUtil、CrashReporter…
    handshake/      # FairPlay / ALAC JNI 桥接
  jniLibs/          # libplayfair.so + libalac.so（4 ABI，Apache-2.0）
  res/              # NEBULA 主题、布局（含 -land / -sw600dp-land）
tools/
  build_manual.sh      # 可复现手工构建（aapt2 + kotlinc + d8）
  build_libplayfair.sh # NDK 重编 libplayfair.so（记得加 -lm）
  native-src/          # playfair C 源码（PhairPlay，Apache-2.0）
```

## 构建

普通开发机直接 Gradle：

```bash
./gradlew assembleDebug
```

本沙箱跑不起 Gradle daemon（loopback 被拦截），所以用 `tools/build_manual.sh` 手工走同样流程（aapt2 → kotlinc → d8 → zipalign → apksigner）。详见 [BUILD-INSTRUCTIONS.md](BUILD-INSTRUCTIONS.md)。

Native 库：`tools/build_libplayfair.sh`（NDK r28c）。**注意**：必须加 `-lm`——`modified_md5.c` 用了 `sin()`，不链接 arm64 真机会 `dlopen` 失败（`cannot locate symbol "sin"`）。

## 发版（在线更新）流程

1. 把 APK 传到 GitHub Release。
2. 更新仓库根 `version.json`（`versionCode` / `versionName` / `apkUrl` / `changelog`）并 push。
3. App 内：设置 → 填 GitHub 用户名/仓库名 → 检查更新。App 经 jsDelivr 取 `version.json`，经可配置的国内镜像下载 APK。

## 已知限制

- DRM 内容（Netflix、Disney+、Apple TV…）永远投不了——系统限制，不是 bug。
- Android 发送端目前 720p/30fps 纯视频（4K/120fps 在路线图上）。
- 没有真机实验室：协议逻辑有单测，但端到端（真 iPhone、编码器行为）依赖真机验证。

## 第三方

见 [THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES)。AirPlay 相关来自 [PhairPlay](https://github.com/mazer666/PhairPlay)（Apache-2.0）。无 GPL 代码。

## 许可证

Apache-2.0（LICENSE 待补）。
