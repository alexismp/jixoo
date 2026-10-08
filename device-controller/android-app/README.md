# Pixoo 64 Slideshow Controller — Android App

An Android controller application (Jetpack Compose + Material 3) designed for unattended 24/7 operation of one or two Divoom Pixoo 64 LED displays, synced with Google Cloud Storage (`gs://`) or local folders.

---

## Required Network Setup

Because the Pixoo 64 communicates over local unencrypted HTTP (`POST http://<pixoo-ip>:80/post`) and uses an ESP32 Wi-Fi microcontroller, your network environment **must** satisfy the following requirements:

1. **2.4 GHz Wi-Fi Band Required**:
   - The Pixoo 64 hardware only supports **2.4 GHz Wi-Fi (`802.11 b/g/n`)**. If using a mobile hotspot or dedicated router, ensure 2.4 GHz / Compatibility Mode is enabled.
2. **Same IPv4 `/24` Subnet & Client Isolation Disabled**:
   - The Android controller phone and all target Pixoo 64 displays **must be connected to the same Wi-Fi LAN / IPv4 `/24` subnet** (e.g., `10.16.245.x/24` or `192.168.1.x/24`).
   - **AP / Client Isolation MUST be off**: Many hotel or public conference guest Wi-Fi networks isolate wireless clients from talking to each other on port `80`. If `Check Ping` fails between devices on the same SSID, connect the phone and Pixoo(s) to a dedicated portable router or hotspot.
3. **Switching Wi-Fi Networks (`Discover & Replace IP`)**:
   - Whenever you move the setup to a new Wi-Fi network or hotspot, DHCP assigns new IPs to the Pixoo display(s).
   - Expand **Target Pixoo Devices** and tap **`Discover & Replace IP`**. This scans the active Wi-Fi `/24` subnet (`64` parallel HTTP probes + UDP discovery in `< 2s`), **ignores and overwrites any stale IP from previous networks**, saves the newly discovered IP(s), and verifies reachability (`ONLINE`).
4. **Outbound Internet Access (for Google Cloud Storage sync)**:
   - Required to list and download updated visuals from `gs://conference-pics/...`. Downloaded images and GIFs are cached persistently on the phone, so local Pixoo playback continues uninterrupted if internet connectivity drops temporarily.

---

## Architecture & Reliability Features

- **Autonomous Hardware Playback on the Pixoo (`PicNum = N` in ESP32 SRAM)**:
  - Phone screen preview rendering is disabled (`previewPixels64 = null`) so the Android device expends minimal CPU/GPU/battery.
  - For animated GIFs, a single animation cycle (up to `40` frames to fit safely inside the ESP32's `520 KB` internal SRAM) is burst-uploaded to the Pixoo's hardware buffer at full wire speed (`0ms` artificial inter-frame sleep). The Pixoo ESP32 then loops the animation autonomously using its native hardware timer for jitter-free framerate.
  - `Draw/ResetHttpGifId` is issued **only once at slideshow startup** (and before `uint16` overflow at `60000`) rather than between slides, avoiding unnecessary screen clears.
- **Strict Hardware Backpressure & Secondary Device Circuit Breaker**:
  - Per-device `Mutex` serialization ensures a frame or command is never sent until the Pixoo confirms completion (`{"error_code": 0}`).
  - If a configured Secondary Pixoo goes offline, a **per-device circuit breaker** isolates it into a `15s` backoff window (`0ms` per-frame penalty) so an unreachable secondary screen never stalls or degrades the primary display.
- **Live Per-Device RTT & FPS Telemetry**:
  - Logs actual upload duration, average Wi-Fi round-trip time (`RTT ms`), Secondary status (`ONLINE` vs `OFFLINE (isolated)`), and native playback FPS in the live console log.

---

## UI Controls & Configuration Reference

| Feature / Setting | Default | Description |
|---|---|---|
| **Primary Pixoo IP** | Auto-discover | Target IPv4 address (`✕` button clears field; **`Discover & Replace IP`** scans current subnet and replaces stale IPs automatically) |
| **Second Pixoo Sync (`--device2`)** | Disabled | Streams slides in lockstep to a second Pixoo 64 display with automatic offline circuit-breaker isolation |
| **Port (`-p`)** | `80` | Pixoo HTTP API port |
| **GCS Bucket Preset Selector** | `gs://conference-pics/gravidots/visuals` | Dropdown selector for **Best visuals** (`.../visuals-best`), **All approved visuals** (`.../visuals`), **Backup visuals** (`.../visuals-backup`), or **Custom URL...**, with one-tap **Save & Refresh Cache** |
| **Slide Interval (`-i`)** | `10s` (`1s–60s`) | Duration each slide plays autonomously on the Pixoo screen before uploading the next visual *(tip: `30s–60s` minimizes multi-frame upload transitions)* |
| **GCS Cache Refresh (`-r`)** | `2m` | How often the phone checks Google Cloud Storage for newly added or removed visuals (`30s`, `1m`, `2m`, `5m`, `10m`) |
| **Google Cloud Auth (`-a`)** | Auto-resolved | Resolves access automatically via device Google account (`AccountManager`) or optional manual OAuth2 Bearer Token |

---

## Building & Deploying via ADB

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk

# Build and run unit tests
./gradlew testDebugUnitTest assembleDebug

# Install & launch on connected Android phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.glaforge.jixoo.controller/.MainActivity
```
