# Pixoo 64 Slideshow Streamer — Android App

An Android application (Jetpack Compose + Material 3) that replicates all capabilities and options of [`simple-control.sh`](../simple-control.sh) with interactive UI widgets, a real-time 64×64 LED matrix preview, local network device discovery, and a live streamer console log.

## Mapping from `simple-control.sh` Options to UI Widgets

| `simple-control.sh` Option / Env Var | Default | Android App Widget |
|---|---|---|
| `PIXOO_IP` / `PIXOO_HOST` | Auto-discover (fallback `192.168.1.49`) | **Primary Pixoo IP** text field (also supports `IP1,IP2` comma-separated splitting), **Discover Devices** Wi-Fi scanner button, and **Check Device(s)** ping badge |
| `-d, --device2 <ip>` / `PIXOO_IP2` | Disabled | **Second Pixoo Lockstep Sync** toggle switch + **Second Pixoo IP** text field & quick-assign chip from discovered devices |
| `-p, --port <port>` / `PIXOO_PORT` | `80` | **Port (-p)** numeric input field |
| `-s, --source <src>` / `IMAGE_SOURCE` | `gs://conference-pics/gravidots/visuals` | **Image Source** segmented filter chips (`GCS Bucket (gs://)` vs `Local Directory`), GCS bucket URL field with default reset button, and Android SAF folder picker |
| `-i, --interval <sec>` / `INTERVAL` | `3` | **Slide Interval** slider (`1s–60s`), numeric input field, and preset chips (`3s, 5s, 10s, 15s, 30s, 60s`). Also acts as `min_dur` for full-cycle animated GIF playback |
| `-r, --refresh <duration>` / `REFRESH_INTERVAL` | `1m` (`60s`) | **GCS Cache Refresh** duration input (supports `1m`, `60s`, `90s`, etc.), preset chips (`30s, 1m, 2m, 5m, 10m`), and **Sync Now** button |
| `-a, --account <email>` / `GCS_ACCOUNT` | Auto-resolved | **Google Cloud Account** input field, **Pick Account** button (`AccountManager`), **Resolve Access** button (falls back across public access and device Google accounts on 403), and optional **OAuth2 Bearer Token** override |

## Building the APK

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
