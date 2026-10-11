# CastLink

[中文文档](README.zh-CN.md)

CastLink is an Android LAN screen-casting app: **sender and receiver in one APK**, plus an **iOS AirPlay receiver**. Cast your phone screen to a TV/box, mirror an iPhone via AirPlay, or push any file to another device — all on your local network, no cloud, no account.

## Usage

### Receiving (TV / box / spare phone)

Install CastLink and open it. The standby screen shows:

- A **QR code** — scan it with any phone to open the file-upload page
- A **4-digit PIN** — needed for every cast or mirroring connection
- The device's **Wi-Fi name and IP address**

Leave the app on this screen. That's it — it's now discoverable.

### Casting from iPhone (AirPlay)

1. Make sure the iPhone and the receiver are on the **same Wi-Fi**.
2. Open **Control Center → Screen Mirroring**.
3. Pick the CastLink device name (shown on the standby screen).
4. Enter the 4-digit PIN shown on the TV.

The TV plays your iPhone screen fullscreen. Disconnect from Control Center when done.

> If the iPhone asks for a PIN every few seconds and never connects, check that CastLink is still in the foreground on the receiver and both devices are on the same network.

### Casting from Android

1. Open CastLink → **Cast this screen**.
2. Pick the receiver from the discovered devices list (or tap **Enter IP manually**).
3. Enter the receiver's 4-digit PIN.
4. Allow screen capture when the system dialog appears.

Your screen is mirrored at 720p/30fps. Tap **Stop** in the notification to end.

### Pushing files

On the receiver's standby screen:

1. Scan the QR code with any phone, or open `http://<receiver-ip>:8756/` in a browser.
2. Choose any file to upload — video, audio, APK, documents, anything.
3. The receiver handles it automatically:
   - **Video/audio** → opens in the built-in player
   - **APK** → asks to install (needs "install unknown apps" permission)
   - **Other files** → opens with the system's default viewer

Received files are listed in the app's **Media** tab, tap to open again.

### Updating

CastLink checks for updates automatically (once a day, toggle in Settings). When a new version is found, it shows what's new — tap **Update now** to download and install.

You can also check manually: **Settings → Online update → Check for updates**.

### Settings

- **Device name**: the name shown to other devices (AirPlay list, discovery)
- **PIN**: view or regenerate the 4-digit PIN
- **Language / 语言**: Chinese or English, switches instantly
- **Download mirror**: if updates download slowly, switch the mirror source

## Features

- Android sender + Android receiver + AirPlay receiver, one APK
- AirPlay: PIN pairing (SRP), FairPlay handshake, H.264 + AAC/ALAC
- File cast over HTTP: any file type, auto-routed on the receiver
- OTA updates with China-friendly mirrors
- NEBULA UI: phone portrait/landscape, TV and car head-unit layouts
- Chinese/English, instant switch
- Built-in crash reporter and diagnostics

## Limitations

- DRM content (Netflix, Disney+, Apple TV…) can never be cast — platform restriction, not a bug.
- Android sender is 720p/30fps video-only for now.

## Third-party

See [THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES). AirPlay crypto derived from [PhairPlay](https://github.com/mazer666/PhairPlay) (Apache-2.0). No GPL code.

## License

Apache-2.0.
