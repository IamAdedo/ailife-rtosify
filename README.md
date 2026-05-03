# RTOSify — Android Watch Tethering System

> Transform any Android smartwatch into a premium wearable. Mirror your phone's world to your wrist — notifications, health, safety, fitness, media, calls, navigation, and more — over Bluetooth, WiFi, or the internet.

---

## What is RTOSify?

RTOSify is a two-app Android system that pairs your **Android phone** with an **Android smartwatch** and delivers the full combined feature set of:

- 🍎 **Apple Watch Ultra 3** — Emergency SOS, fall detection, ECG, sleep tracking, workout metrics
- 🔴 **Pixel Watch 3** — Daily readiness, cardio load, loss-of-pulse detection, morning briefing
- 🟡 **Samsung Galaxy Watch 7 Ultra** — Energy score, body composition, snoring detection, emergency siren, triathlon mode
- 🟠 **Huawei Band 9** — Sleep breathing, VO₂Max, activity rings, sedentary reminders, white noise

…on **any Android watch** running Android 8.0+.

---

## Repository Structure

This repo contains **two independent Android Studio projects**. Open each in a separate Android Studio window.

```
ailife-rtosify/
├── rtosify/       ← Phone App   (com.iamadedo.phoneapp)
├── companion/     ← Watch App   (com.iamadedo.watchapp)
└── .github/
    └── workflows/
        └── android.yml   ← CI: builds both apps in parallel
```

> ⚠️ Do **not** open the root `ailife-rtosify/` directory as a project. Open `rtosify/` or `companion/` individually.

---

## Getting Started

### 1. Install both apps

| Device | App |
|--------|-----|
| Android phone (Android 8.0+) | `rtosify/` → build and install |
| Android smartwatch (Android 8.0+) | `companion/` → build and install |

### 2. Pair

1. Open **RTOSify** on your phone → tap **Smartphone mode**
2. Open **RTOSify Companion** on your watch → tap **Smartwatch mode**
3. The watch shows a QR code — scan it from the phone, or let Bluetooth auto-discover
4. Grant permissions on both devices when prompted

---

## Communication Architecture

Both apps communicate over a pluggable multi-transport stack that auto-selects the best available link:

```
BluetoothService (foreground service, both devices)
    └── TransportManager
            ├── BluetoothTransport    Classic RFCOMM  ← primary
            ├── BleTransport          BLE GATT        ← proximity & low-power
            ├── WifiIntranetTransport TCP/LAN         ← high bandwidth (screen mirror)
            └── WebRtcTransport       Internet P2P    ← when on different networks
```

All messages use a shared JSON protocol defined in `Protocol.kt` (130+ message types, kept identical between both apps).

---

## Feature Summary

### 🔔 Notifications
- Mirror all phone notifications to watch with inline reply and dismiss sync
- Dynamic Island floating overlay style
- Per-app enable/disable, custom vibration patterns, sounds
- **AI Quick Replies** via Claude API — 3 contextual suggestions per message, one-tap send

### 🏥 Health & Medical
| Feature | Source inspiration |
|---|---|
| Heart rate, SpO2, steps, temperature | Core |
| ECG (30s single-lead + AFib classification) | Apple Watch |
| Sleep tracking: onset, stages, score, apnea screening | Apple Watch / Huawei |
| Smart Alarm (wake in lightest sleep) | Apple Watch / Huawei |
| Sleep snoring detection (mic) | Samsung |
| White noise / soundscapes (on-device synthesis) | Huawei |
| Daily Readiness Score (sleep + RHR + HRV) | Pixel Watch / Fitbit |
| Energy Score (5-pillar: sleep, activity, HRV, SpO2, temp) | Samsung |
| Cardio Load + Target Load (ATL/CTL model) | Pixel Watch / Garmin |
| VO₂Max estimate + Running Ability Index | Apple Watch / Garmin |
| HR Recovery classification | Apple Watch |
| Blood pressure trend (PTT-based proxy) | Samsung |
| Body composition (BMI, body fat %, muscle, BMR) | Samsung |
| Menstrual cycle tracking + temperature ovulation prediction | Apple Watch |
| Stress / HRV monitoring | Samsung / Garmin |
| Premature beat detection | Samsung |
| Respiratory rate (accelerometer) | Huawei |
| Noise monitoring (85 dB alert) | Apple Watch |
| Handwash detection (accel + mic, 20s timer) | Apple Watch |

### 🏃 Fitness & Workouts
| Feature | Source inspiration |
|---|---|
| Multi-sport workout tracker (RUN/CYCLE/SWIM/HIKE/HIIT/ROW) | Apple Watch |
| Auto workout detection (RUNNING/WALKING/CYCLING/ROWING) | Huawei |
| Triathlon mode: SWIM→T1→BIKE→T2→RUN | Samsung Galaxy Watch Ultra |
| Running Coach: cadence + form cues | Samsung |
| Personal Record detection + celebration | Pixel Watch / Fitbit |
| Pace target haptic cues | Pixel Watch |
| Activity Rings: Move / Exercise / Stand | Apple Watch |
| Sedentary reminder (50 min idle → vibrate + notify) | Huawei / Apple |
| Stand reminder (50 min past → hourly nudge) | Apple Watch |
| Custom interval workouts (built on phone, executed on watch) | Apple Watch |
| Training load + recovery hours (TRIMP) | Garmin |
| Workout evaluation score | Samsung |
| Post-workout VO₂Max estimate | Garmin / Samsung |

### 🛡️ Safety
| Feature | Source inspiration |
|---|---|
| Emergency SOS (long-press → countdown → dial 112 + SMS) | Apple Watch |
| Fall detection (freefall + impact, 30s confirm) | Apple Watch |
| Crash detection (vehicle impact pattern) | Apple Watch |
| Loss of Pulse detection → auto-dial 112 | Pixel Watch 3 |
| Emergency Siren (880/1320 Hz audible alarm) | Galaxy Watch Ultra |
| Health alerts (AFib, low SpO2, high/low HR) | Apple Watch |

### 📍 Anti-Lost (bidirectional, new)
- **Watch alerts you** when your phone is left behind (RSSI drops below threshold)
- **Phone alerts you** when your watch/wrist leaves the phone's range
- Configurable sensitivity, 3-reading confirmation, auto-clears on return
- Repeating haptic + persistent dismissible notification on both devices
- Instant alert on full BT disconnect (no GPS or internet required)

### 🧭 Navigation & Adventure
- Turn-by-turn instruction mirroring (Google Maps / Waze)
- Offline OSM map tiles pushed from phone (2 km radius, no API key)
- Compass (magnetometer + gravity fusion)
- Barometric altimeter (elevation gain, storm warning)
- GPS route tracking + waypoints
- Backtrack (breadcrumb trail cached before disconnect)
- Dive computer — Bühlmann ZHL-16C NDL (experimental, needs pressure hardware)
- Tides (7-day table)

### 🌤️ Weather & Astronomy
- Current conditions + 5-day forecast (Open-Meteo, free, no key)
- Sunrise / sunset + golden hour (NOAA algorithm, offline)
- Moon phase, moonrise, moonset (offline)

### ⏰ Alarms & Reminders
- Full alarm sync (create, edit, delete, snooze)
- Smart Alarm (lightest sleep stage wake)
- Morning Briefing (daily push: readiness + sleep + weather + target workout)
- Medication reminders with adherence tracking
- Bedtime Mode (DND + dim + scheduled)

### 🔊 Media & Camera
- Full media session sync (track, artist, album art, controls)
- Camera remote viewfinder (shutter, zoom, timer, front/rear)
- Screen mirroring with touch relay

### 🎙️ Utilities
- Live Transcript: phone mic → speech-to-text → watch display
- Torch (full-white screen)
- Stopwatch with lap timing
- Walkie-talkie (push-to-talk Opus audio)
- Find Device (ring phone from watch + RSSI proximity indicator)
- Clipboard sync
- Family Health Community (local, no cloud)
- Google Home / Matter device control relay
- Offline maps sync (OpenStreetMap)
- Mindfulness: guided breathing with HRV measurement

### ⚙️ Power & Device Control (Shizuku / root optional)
- Remote shutdown, reboot, lock
- APK push and silent install
- File browser bridge
- Shell command execution
- WiFi / mobile data toggle

---

## Build

Each app is built independently:

```bash
# Phone app
cd rtosify
chmod +x gradlew
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (unsigned)
./gradlew test               # unit tests

# Watch app
cd companion
chmod +x gradlew
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew test
```

**Requirements:** JDK 17, Android Studio Meerkat or later, AGP 8.13.1, Gradle 8.13.

---

## CI / CD

GitHub Actions builds and tests both apps on every push to `master` or `main`:

```
.github/workflows/android.yml
  ├── build-phone  →  unit tests + debug APK + release APK
  ├── build-watch  →  unit tests + debug APK + release APK
  └── build-summary (gate job for branch protection)
```

Download APK artifacts from the **Actions** tab after any successful run.

---

## Package Names

| App | Package |
|-----|---------|
| Phone App | `com.iamadedo.phoneapp` |
| Watch App | `com.iamadedo.watchapp` |

---

## Protocol

All inter-device communication uses a shared JSON protocol:

```json
{ "version": 1, "type": "heart_rate_update", "timestamp": 1234567890, "data": { ... } }
```

`Protocol.kt` defines 130+ message types and must be kept **identical** between both apps. To add a new message type:

1. Add a constant in `MessageType` (both apps)
2. Add a data class if needed
3. Add a factory method in `ProtocolHelper`
4. Handle in `BluetoothService.handleReceivedMessage()` (both apps)

---

## Permissions

| Permission | Used for |
|---|---|
| `BLUETOOTH` / `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | Core connectivity |
| `ACCESS_FINE_LOCATION` | Required for BT scanning by Android |
| `NOTIFICATION_LISTENER` | Mirror phone notifications |
| `BODY_SENSORS` | Heart rate, SpO2 on watch |
| `RECORD_AUDIO` | Snoring detection, noise monitor, walkie-talkie, transcript |
| `CALL_PHONE` | Emergency SOS auto-dial |
| `SEND_SMS` | Emergency contact SMS with GPS |
| `CAMERA` | Camera remote |
| `FOREGROUND_SERVICE` | Persistent connection |
| `WRITE_SETTINGS` | Brightness control from watch |
| `REQUEST_INSTALL_PACKAGES` | APK install push (optional) |

---

## Key Files

| File | Both apps? | Purpose |
|------|-----------|---------|
| `Protocol.kt` | ✅ identical | All message types, data classes, helpers |
| `BluetoothService.kt` | ✅ | Foreground coordinator, message dispatch |
| `communication/TransportManager.kt` | ✅ | Multi-transport orchestration |
| `security/EncryptionManager.kt` | ✅ | In-transit encryption |
| `IUserService.aidl` | ✅ identical | Shizuku/root AIDL interface |
| `ble/BleRssiMonitor.kt` | ✅ | BLE GATT RSSI proximity |
| `AntiLostManager.kt` | Watch only | Phone-left-behind detection |
| `AntiLostPhoneHandler.kt` | Phone only | Watch-out-of-range detection |
| `HealthDataCollector.kt` | Watch only | Sensor polling |
| `MyNotificationListener.kt` | Phone only | Notification interception |

---

## Detailed Documentation

- 📱 [Phone App README](rtosify/README.md)
- ⌚ [Watch App README](companion/README.md)
- 🏗️ [Architecture Reference](CLAUDE.md)
