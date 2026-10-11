# CastLink 系统性测试报告（v0.3.0 → v0.3.1）

- 测试日期：2026-10-11
- 测试对象：`~/workspace/castlink/`
- 最终交付：**CastLink-v0.3.1-debug.apk**（3.7MB，versionCode 4，versionName 0.3.1）
  - QA 过程中修了 5 个小问题，按规范 versionCode 3→4、版本名 v0.3.0→v0.3.1
- 目标：用户一次安装成功、所有功能可用

## 一、6 大项结果总览

| # | 测试项 | 结果 |
|---|---|---|
| 1 | 可复现构建（清零重跑 build_manual.sh） | **PASS**（8/8 步全绿，产物与原包 badging/文件清单一致） |
| 2 | 安装包结构审计 | **PASS**（0 安装失败级问题；3 WARN → 已修 2，1 为说明项） |
| 3 | 国际化完整性审计 | **PASS**（中英 106 key 全对齐，参数一致；3 处硬编码 → 已修） |
| 4 | 全量回归测试 | **PASS**（254/254，0 失败） |
| 5 | 真机安装风险清单 | 见 §四（静态可查项全部列出） |
| 6 | 模拟器快速验证 | **SKIP**（沙箱无 /dev/kvm、无 emulator、无 CPU 虚拟化标志，不死磕） |

## 二、发现的问题与修复（共 5 项，均已修复并重验）

| # | 问题 | 严重度 | 修复 |
|---|---|---|---|
| 1 | `AirPlayService.kt` 前台通知 3 处硬编码文案（渠道名/标题/正文），英文环境仍显示中文 | 低（i18n 不完整） | 收进 `strings.xml`（`airplay_notif_channel/title/text`），中英各一份 |
| 2 | `ACCESS_NETWORK_STATE` 权限声明但代码无对应 usage | 低（多余声明） | 从 manifest 删除 |
| 3 | launcher icon 缺失：manifest 未设 `android:icon`，res 无 mipmap，桌面显示系统默认图标 | 低（视觉） | 新增 adaptive icon（`mipmap-anydpi-v26`，深蓝底+白色投屏 glyph）+ mdpi~xxxhdpi PNG fallback；manifest 设 `android:icon`/`roundIcon` |
| 4 | 控制页 `panel_framerate` 标签英文 "Frame rate" 较长，窄屏有截断风险 | 低 | 加 `maxLines=1` + `ellipsize=end`（`standby_waiting` 为 wrap_content 无需改） |
| 5 | `FairPlay.kt` KDoc 算术笔误："bytes [144,164) = 32 bytes"（该区间实为 20 字节；12+20=32 总长是对的） | 极低（注释） | 修正注释为 "12-byte fixed header + 20 bytes [144,164) = 32 bytes total" |

说明项（非问题）：v1 签名 scheme 未启用——minSdk 26 ≥ 24，v2/v3 已覆盖安装校验，无影响。

## 三、回归测试明细（修复后重跑，254/254）

| 套件 | 结果 |
|---|---|
| PinLockout | 21/21 |
| FilePushServer | 15/15 |
| FilePushEdge（新增：超长文件名/重名覆盖/空文件/404） | 17/17 |
| AlacDecoder JVM | 12/12 |
| SenderProtocol（RTP/FU-A 重组字节一致） | 95/95 |
| SignalLockoutE2E（新增：client+server 内存对接，3 次错锁→PIN_LOCKED） | 10/10 |
| AirPlayProtocol（重建：FairPlay/Plist/SDP/Base64/RSA/SRP） | 84/84 |

注：v0.2.0 宣称的 "AirPlay 50 项"无存档文件，本次已重建为 84 项可复现套件（`tools/run_airplay_tests.sh`）。
待机降亮逻辑与 Activity 耦合无法单测——属设计问题非 bug，未重构（需用户授权另起一轮）。

## 四、真机安装风险清单（静态可查）

1. **未知来源安装**：Android 8+ 首次用浏览器/文件管理器打开 APK，会跳系统授权页"允许安装未知应用"——按提示打开即可，属正常流程（见 §五第 1 步）。
2. **Play Protect 警告**：debug 签名包可能被提示"未知开发者"，选"仍然安装"即可；不影响功能。
3. **debuggable=false**：本包 aapt2 link 未加 --debug，`application-debuggable` 未置位——无调试模式副作用，用户侧与 release 包行为一致（仅签名为 debug 证书）。
4. **权限**：全部为安装时（normal）权限，无需运行时申请。`FOREGROUND_SERVICE_MEDIA_PROJECTION` 在 API 26–28 上系统直接忽略该未知权限名，安全。
5. **MediaProjection 授权**：发送端首次点"把本机屏幕投出去"会弹**系统级**授权框（"立即开始"），必须点允许；投屏期间不要划掉前台服务通知，否则可能被系统杀掉断流。
6. **网络前提**：两台设备必须连**同一 Wi-Fi 同一网段**；访客网络、AP 隔离、2.4G/5G 隔离会导致发现不了设备（非 bug，先查网络）。
7. **硬限制**：Netflix / Disney+ / Apple TV+ 等 DRM 内容一定黑屏，永远做不了，不用报 bug。发送端当前视频-only，无音频是已知的。

## 五、仍需用户真机验证的清单（沙箱无真机，端到端未实测）

按价值排序，跑完 P0 两条 v0.3.1 才算立住（详细步骤见 `TEST-CHECKLIST-v0.3.0.md`）：

- **P0-1** 安卓→安卓投屏：两台 Android 8+ 同 Wi-Fi，A 机"把本机屏幕投出去"→发现→输 PIN→MediaProjection 授权→B 机看画面（预期 720p/30fps）
- **P0-2** iPhone 屏幕镜像：控制中心→屏幕镜像→选 CastLink→待机页四格 PIN 自动切换显示 iPhone 侧配对码→输入；再播一段视频验证音画
- **P1** 自有协议 PIN 输错 3 次→锁定 60 秒倒计时→重启 App 仍锁定→输对清零
- **P1** 文件投放：`curl -T video.mp4 http://<接收端ip>:8756/push/video.mp4`
- **P2** 待机 30 秒无操作降亮→触摸恢复

回报格式：`机型+系统版本 / 测的哪条 / 哪一步出的问题 / 现象 / 复现频率`，深挖抓 `adb logcat | grep -i castlink` 前后 20 行。

## 六、用户安装指引（3 步）

1. 把 `CastLink-v0.3.1-debug.apk` 传到手机/盒子 → 点击安装 → 遇到"允许安装未知应用"按系统提示打开开关 → 如 Play Protect 提示选"仍然安装"
2. 两台设备连**同一个 Wi-Fi**（接收端停在待机页记下 PIN；iPhone 用控制中心"屏幕镜像"）
3. 发送端点"把本机屏幕投出去"→ 选设备 → 输 PIN → 系统授权框点"立即开始"

## 七、构建复现说明

`bash tools/build_manual.sh`（约 3 分钟，8 步全绿，退出码 0）。已知良性警告：d8 的 156 条 Kotlin-metadata rewriting 警告（Kotlin 2.0.21 vs R8 配对问题，不影响 dex）、7 条 Java API deprecation 警告。Gradle daemon 在沙箱无解，仍走手工链；`app/build.gradle.kts` 已同步 versionCode 4 / 0.3.1。
