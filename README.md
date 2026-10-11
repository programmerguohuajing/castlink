# CastLink

[中文文档](README.zh-CN.md)

CastLink is an Android LAN screen-casting app: **sender and receiver in one APK**, plus an **iOS AirPlay receiver**. Cast your phone screen to a TV/box, mirror an iPhone via AirPlay, or push any file to another device — all on your local network, no cloud, no account.

## Features

- **All-in-one**: Android sender + Android receiver + AirPlay receiver in a single APK (`com.castlink.receiver`)
- **AirPlay receiver** (Android 8+): iPhone/iPad Screen Mirroring with PIN pairing (SRP), FairPlay handshake, H.264 video + AAC/ALAC audio
- **Android sender**: MediaProjection → H.264 720p/30fps → RTP → receiver, with PIN verification
- **Native receiver pipeline**: auto fullscreen playback when a cast stream arrives
- **File cast**: push *any* file (APK, video, audio, documents…) over HTTP; receiver auto-routes to player / installer / system viewer
- **OTA updates**: in-app update check, APK download via China-friendly mirrors (ghproxy.net, gh-proxy.com…), one-tap install
- **NEBULA UI**: deep-space tech theme, responsive layouts — phone portrait/landscape, large screens (TV / car head unit)
- **i18n**: Chinese / English, follows system by default, instant switch
- **Diagnostics**: built-in crash reporter (no adb needed), loopback self-test, AirPlay handshake logs

## Quick start

1. Install the APK on the **receiver** (TV/box): open the app → it shows a standby page with a QR code and a 4-digit PIN.
2. **From iPhone**: Control Center → Screen Mirroring → pick the device → enter the PIN.
3. **From Android**: open CastLink → *Cast this screen* → pick the receiver → enter the PIN → allow screen capture.
4. **File push**: on the receiver standby page, scan the QR (or open `http://<receiver-ip>:8756/`) → upload any file.

> The QR encodes an `http://` landing page (iOS doesn't recognize custom schemes).

## Project layout

```
app/src/main/
  java/com/castlink/receiver/
    airplay/        # AirPlay receiver (RTSP, pairing, FairPlay, RTP, ALAC…)
    sender/         # Android sender (MediaProjection, H.264 encoder, signaling)
    protocol/       # Native protocol (signaling server/client, RTP, file push)
    player/         # VideoPipeline, loopback test
    ui/             # Activities (Main, Standby, Sender, Settings, StreamPlayer…)
    update/         # OTA update manager
    util/           # FileAction, LocaleHelper, NetUtil, CrashReporter…
    handshake/      # FairPlay / ALAC JNI bridge
  jniLibs/          # libplayfair.so + libalac.so (4 ABIs, Apache-2.0)
  res/              # NEBULA theme, layouts (incl. -land / -sw600dp-land)
tools/
  build_manual.sh      # Reproducible manual build (aapt2 + kotlinc + d8)
  build_libplayfair.sh # Rebuild libplayfair.so with NDK (adds -lm)
  native-src/          # playfair C sources (PhairPlay, Apache-2.0)
```

## Build

Standard Gradle build works on a normal dev machine:

```bash
./gradlew assembleDebug
```

The sandbox CI here can't run the Gradle daemon (loopback TCP interception), so `tools/build_manual.sh` does the same steps by hand (aapt2 → kotlinc → d8 → zipalign → apksigner). See [BUILD-INSTRUCTIONS.md](BUILD-INSTRUCTIONS.md).

Native libs: `tools/build_libplayfair.sh` (NDK r28c). **Important**: link with `-lm` — `modified_md5.c` uses `sin()`, otherwise `dlopen` fails on arm64 (`cannot locate symbol "sin"`).

## OTA release flow

1. Upload the APK to a GitHub Release.
2. Update `version.json` at the repo root (`versionCode`, `versionName`, `apkUrl`, `changelog`) and push.
3. In the app: Settings → fill in GitHub owner/repo → Check for updates. The app fetches `version.json` via jsDelivr and downloads the APK through a configurable China mirror.

## Limitations

- DRM content (Netflix, Disney+, Apple TV…) can never be cast — platform restriction, not a bug.
- Android sender is 720p/30fps video-only for now (4K/120fps is on the roadmap).
- No real-device lab here: protocol logic is unit-tested, but end-to-end (real iPhone, encoders) relies on device testing.

## Third-party

See [THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES). AirPlay crypto derived from [PhairPlay](https://github.com/mazer666/PhairPlay) (Apache-2.0). No GPL code.

## License

Apache-2.0 — see [LICENSE](LICENSE) (to be added).
