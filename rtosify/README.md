# RTOSify — Phone App

The **phone/smartphone** side of the RTOSify Android watch-tethering system.

RTOSify gives any Android smartwatch the full feature set of a premium smartwatch (Apple Watch Ultra 3, Pixel Watch 3, Samsung Galaxy Watch 7 Ultra, Huawei Band 9) by offloading computation and connectivity to the paired phone.

---

## Package

| Field | Value |
|---|---|
| Package name | `com.iamadedo.phoneapp` |
| Min SDK | Android 8.0 (API 26) |
| Target SDK | Android 15 (API 36) |
| Version | 1.0 |

---

## Architecture

The phone app runs a persistent foreground `BluetoothService` that communicates with the watch companion app over a **pluggable multi-transport stack**:

```
BluetoothService
    └── TransportManager
            ├── BluetoothTransport    (Classic RFCOMM — primary)
            ├── BleTransport          (BLE GATT — proximity/find)
            ├── WifiIntranetTransport (TCP over LAN — high bandwidth)
            └── WebRtcTransport       (Internet via signalling server)
```

All messages are JSON with `{version, type, timestamp, data}` — see `Protocol.kt`.

---

## Features

### 📡 Connectivity
- Multi-transport auto-switching (BT → WiFi → Internet) with seamless failover
- mDNS device discovery on local network
- WebRTC signalling for internet connectivity across networks
- QR-code pairing
- Encrypted transport via `EncryptionManager`
- Bluetooth PAN internet tethering to watch

### 🔔 Notifications
- Full notification mirroring from all installed apps (configurable per-app)
- Inline reply forwarding back from watch
- Notification dismissal sync
- Dynamic Island-style floating notification UI on watch
- **AI Quick Replies**: Claude AI generates 3 contextual reply suggestions per incoming message for one-tap reply (requires Anthropic API key in Settings → AI Features)

### 🏥 Health & Medical
- Health data dashboard: Heart Rate, SpO2, Steps, Sleep, Temperature, HRV, Stress
- ECG readings streamed from watch to phone for display
- **Sleep reports**: score, deep/REM/light/awake breakdown, breathing quality, snoring detection
- **Daily Readiness Score** (Pixel Watch-style): sleep + resting HR + HRV composite 0–100, PUSH/MODERATE/RECOVER recommendation
- **Energy Score** (Samsung-style): 5-pillar composite — sleep, activity, HRV, SpO2, skin temperature
- **Cardio Load / Target Load**: ATL/CTL exponential moving average model with acute:chronic ratio and overtraining risk
- **VO₂Max estimates** and fitness level classification (Poor → Superior)
- **Running Ability Index**: pace projections for 5K and 10K
- **HR Recovery**: 1-minute post-exercise drop classification
- **Blood pressure trend** (PTT-based proxy, requires initial cuff calibration)
- **Body composition**: BMI, body fat %, muscle mass, BMR via Deurenberg formula
- **Menstrual cycle tracking** with wrist-temperature-based ovulation prediction
- **Stress level monitoring** via HRV SDNN throughout the day
- **Premature beat detection** alerts with OCCASIONAL/FREQUENT/BIGEMINY classification
- **Respiratory rate** measurement from watch accelerometer

### 🏃 Fitness & Workouts
- Live workout companion: receives real-time HR zone, pace, distance, cadence, calories from watch
- Workout history and post-workout evaluation score (0–100)
- **Personal Record tracking**: detects new PRs across all sport types (running pace, longest ride, HIIT calories, etc.) and triggers celebration on watch
- **Triathlon mode**: SWIM → T1 → BIKE → T2 → RUN with per-segment time, distance, avg HR
- **Running Coach**: real-time cadence and overstriding cues relayed from watch
- Custom workout push: define interval sessions on phone, execute on watch
- Training load history, TRIMP calculation, recovery hour recommendation
- Workout PR storage with per-sport best values

### 🛡️ Safety
- **Emergency SOS**: triggered from watch → phone auto-dials 112 + SMS emergency contacts with GPS location
- **Fall detection**: high-priority phone notification when watch detects a fall; escalates to SOS if user unresponsive
- **Crash detection**: vehicle crash detection via multi-sensor pattern matching
- **Loss of Pulse**: if watch detects absent pulse for 30s → phone dials emergency services automatically
- **Health alerts**: irregular rhythm, low SpO2, high/low HR surface on phone as notifications

### 📍 Anti-Lost (bidirectional)
- **Phone alerts**: when the watch (and its wearer) moves beyond Bluetooth range — ideal when phone is left on a desk
- **Watch alerts**: phone notifies watch when IT detects the phone going out of range — ideal when phone is forgotten somewhere
- RSSI-based with configurable sensitivity threshold (default −75 dBm)
- 3-consecutive-reading confirmation to avoid false positives
- Repeating vibration + persistent dismissible notification on both devices
- Auto-clears when devices come back in range

### 🗺️ Navigation & Maps
- Turn-by-turn instruction mirroring to watch (Google Maps / Waze compatible)
- **Offline map tile sync**: downloads OpenStreetMap 2 km radius tiles via Overpass API — no API key, works fully offline on watch

### 🌤️ Weather & Astronomy
- **Weather**: current + 5-day forecast via Open-Meteo API (free, no key required)
- **Sunrise / sunset + golden hour** computed locally (NOAA solar algorithm)
- **Moon phase, moonrise, moonset** computed locally (lunar cycle algorithm, no network)
- **Tides**: 7-day tide table via WorldTides API + sunrise/sunset overlay

### ⏰ Alarms & Reminders
- Full alarm sync to/from watch
- **Smart Alarm**: wakes user in lightest sleep stage within configurable window
- **Morning Briefing**: daily push at configurable time (default 07:00) — readiness, sleep summary, weather, target workout type
- **Medication reminders**: add/edit/delete schedules, exact AlarmManager delivery, adherence tracking, 7-day adherence rate

### 🔊 Media & Camera
- Full media session sync (track, artist, album art, playback controls)
- Camera remote: viewfinder on watch, shutter/zoom/timer/flip from watch
- Screen mirroring with touch relay

### 👨‍👩‍👧 Family Health Community
- Share health snapshots (HR, SpO2, steps, sleep score) with named family members
- Purely local over Bluetooth/WiFi — no cloud involved
- 6-hour TTL on snapshots, on-demand pull from watch

### 🏠 Smart Home
- Google Home / Matter device control relayed from watch (ON/OFF/DIM/LOCK)

### 🎙️ Live Transcript
- Starts phone microphone on demand from watch
- Streams speech-to-text results to watch in real time via Android SpeechRecognizer
- Useful in meetings, noisy environments, accessibility

### ⚙️ Device Control (requires Shizuku or root)
- Remote shutdown / reboot / lock from watch
- APK push and silent install
- File browser bridge (phone filesystem browsable from watch)
- Shell command execution on phone from watch
- WiFi enable/disable, mobile data toggle, clipboard sync

---

## Setup

1. Install this APK on your **phone**
2. Install **RTOSify Companion** on your Android smartwatch
3. Grant all permissions when prompted
4. Open the app → select **Smartphone mode**
5. Tap **Pair watch** and follow the QR code flow
6. Configure per-app notification mirroring in **Settings → Notifications**

---

## Build

```bash
cd rtosify
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # release APK (unsigned)
./gradlew test              # unit tests
./gradlew connectedAndroidTest  # instrumented tests (device needed)
```

Requires **JDK 17**. AGP 8.13.1, Gradle 8.13.

---

## Key Files

| File | Purpose |
|---|---|
| `BluetoothService.kt` | Top-level coordinator, 130+ message dispatch |
| `Protocol.kt` | All message types, data classes, ProtocolHelper |
| `MyNotificationListener.kt` | Intercepts and mirrors all device notifications |
| `WeatherSyncManager.kt` | Open-Meteo + NOAA astronomy push to watch |
| `MorningBriefingManager.kt` | Daily readiness briefing scheduler |
| `AntiLostPhoneHandler.kt` | Phone-side RSSI proximity monitoring + alerts |
| `AntiLostDismissReceiver.kt` | Handles dismiss action from notification |
| `SuggestedRepliesGenerator.kt` | Claude API quick-reply generation |
| `OfflineMapsSyncManager.kt` | OSM Overpass tile download and push |
| `TranscriptManager.kt` | Live speech-to-text streamed to watch |
| `MedicationManager.kt` | Medication schedule, alarms, adherence |
| `WorkoutPersonalRecordTracker.kt` | PR detection per sport type |
| `FamilyHealthManager.kt` | Family health snapshot store (local) |
| `MorningBriefingManager.kt` | Morning summary push to watch |
| `communication/TransportManager.kt` | Multi-transport orchestration |
| `security/EncryptionManager.kt` | In-transit message encryption |

---

## CI / CD

GitHub Actions builds both apps on every push to `master`:

```
.github/workflows/android.yml
  ├── build-phone  →  tests + debug APK + release APK
  ├── build-watch  →  tests + debug APK + release APK
  └── build-summary (gate job for branch protection)
```

APK artifacts are available in the **Actions** tab after each successful build.

---

## Related

- **RTOSify Companion** (`companion/`) — install on your Android smartwatch
- **CLAUDE.md** — architecture reference for AI-assisted development
