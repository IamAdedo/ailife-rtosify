# RTOSify — Phone & Watch Companion

> Mirror your phone's world to your wrist. Notifications, files, health, media, calls, and more — all in sync, over any connection.

![banner](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/banner.png)

---

## Table of Contents

- [What is RTOSify?](#what-is-rtosify)
- [Requirements](#requirements)
- [Getting Started](#getting-started)
- [Features](#features)
  - [Notification Mirroring](#notification-mirroring)
  - [Dynamic Island](#dynamic-island)
  - [Screen Mirroring](#screen-mirroring)
  - [File Manager](#file-manager)
  - [Health Monitoring](#health-monitoring)
  - [Battery Monitoring](#battery-monitoring)
  - [Media Control](#media-control)
  - [Call Handling](#call-handling)
  - [Alarm Sync](#alarm-sync)
  - [Find Device](#find-device)
  - [Camera Remote](#camera-remote)
  - [App Management](#app-management)
  - [Watch Face Management](#watch-face-management)
  - [Terminal / Shell](#terminal--shell)
  - [Home Screen Widgets](#home-screen-widgets)
  - [Automation](#automation)
  - [Connection & Network Settings](#connection--network-settings)
- [Permissions Explained](#permissions-explained)
- [FAQ](#faq)
- [Developer Notes](#developer-notes)


---

## What is RTOSify?

RTOSify is a two-app Android system that bridges your Android phone and an Android-based smartwatch. The **Phone App** runs on your phone and captures everything — notifications, health data, media, calls, and files. The **Watch Companion App** runs on your smartwatch and displays or controls it all.

The two apps communicate over **Bluetooth, BLE, WiFi (LAN), or the internet (WebRTC)** — automatically choosing the best available link.


## Requirements

| Item | Minimum |
|------|---------|
| Phone Android | 8.0+ |
| Watch Android | 6.0+ |
| Bluetooth | Classic BT 2.0+ or BLE 4.0+ |
| Optional | Shizuku or root for advanced features |

Both devices must have the apps installed. The phone uses the **RTOSify** app; the watch uses the **RTOSify Companion** app.

---

## Getting Started

### 1. Install both apps
- Install **RTOSify** on your Android phone.
- Install **RTOSify Companion** on your Android smartwatch.

[GitLab](https://gitlab.com/ailife8881/rtosify/-/releases)

[ApkPure](https://apkpure.com/p/com.ailife.rtosify)

[UpToDown](https://rtosify.en.uptodown.com/android)

### 2. Pair the devices

**On your phone:**
1. Open RTOSify → tap **Get Started**.
2. Grant the requested permissions (Bluetooth, Location, Notifications).
3. The app will scan for nearby devices. Select your watch, or tap **Scan QR Code** to scan the QR code shown on the watch.

**On your watch:**
1. Open RTOSify Companion → tap **Get Started**.
2. Grant permissions.
3. The watch displays a **QR code** — scan it from the phone to pair instantly.

Once paired, the main dashboard shows a green connected indicator with your device name. You're ready.

![Welcome Screen](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/welcome-phone.jpg?ref_type=heads)

---

## Features

### Notification Mirroring

The core feature of RTOSify. Every notification that arrives on your phone is forwarded to your watch in real time.

**What you can configure:**
- **Enable/disable per app** — choose exactly which apps send notifications to your watch via an app selection list.
- **Wake on notification** — turn on the watch screen when a notification arrives.
- **Wake during Do Not Disturb** — allow notifications to bypass DND for important apps.
- **Notification style** — multiple display styles to match your watch UI.
- **Vibration** — enable or disable, adjust strength, and set custom vibration patterns. Works even in silent mode if enabled.
- **Sound** — pick a notification sound played on the watch.
- **Notification actions** — reply inline from the watch or dismiss a notification and have it dismissed on the phone simultaneously.

| Notification Settings | App Selection List | Per-App Config |
|:---:|:---:|:---:|
| ![Notification 1](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/notification-1.jpg?ref_type=heads) | ![App List](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/notification-appllist.jpg?ref_type=heads) | ![Per App](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/notification-perapp.jpg?ref_type=heads) |

| Navigation | Rules | File Observer |
|:---:|:---:|:---:|
| ![Navigation](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/notification-navigation.jpg?ref_type=heads) | ![Rules](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/notification-rules.jpg?ref_type=heads) | ![File Observer](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/notification-fileobserver.jpg?ref_type=heads) |

| Notification 2 | Notification 3 |
|:---:|:---:|
| ![Notification 2](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/notification-2.jpg?ref_type=heads) | ![Notification 3](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/notification-3.jpg?ref_type=heads) |

---

### Dynamic Island

A floating notification bubble that appears at the top of the watch screen for incoming notifications — without interrupting what you are doing.

**Configurable options:**
- Enable or disable the Dynamic Island overlay.
- Customize bubble color with a built-in color picker.
- Blacklist specific apps so they never appear in the bubble.
- Control auto-dismiss timing.

| Dynamic Island 1 | Dynamic Island 2 | Dynamic Island 3 |
|:---:|:---:|:---:|
| ![DI 1](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/dynamicisland-1.jpg?ref_type=heads) | ![DI 2](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/dynamicisland-2.jpg?ref_type=heads) | ![DI 3](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/dynamicisland-3.jpg?ref_type=heads) |

![Dynamic Island Notification](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/dynamicisland-notification.png?ref_type=heads)

---

### Screen Mirroring

See your phone's screen live on your watch and control it remotely.

- **Live video feed** encoded in H.264 and transmitted over the active connection.
- **Touch input**: tap and swipe on the watch display to control the phone.
- **Navigation buttons**: send Back, Home, and Recent Apps commands remotely.
- **Resolution control**: adjust quality vs. bandwidth to suit your connection.
- Works best over **WiFi (LAN)** for smooth, high-quality mirroring.

| Phone View | Watch View |
|:---:|:---:|
| ![Mirror Phone](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/mirroring-phone.jpg?ref_type=heads) | ![Mirror Watch](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/morroring-watch.png?ref_type=heads) |

---

### File Manager

A full remote file manager to browse, transfer, and manage files between your phone and watch.

- **Browse**: navigate the watch filesystem from your phone.
- **Upload / Download**: transfer any file in both directions.
- **Rename, copy, move, delete**: full file operations on remote files.
- **Transfer queue**: multiple transfers handled simultaneously with real-time progress.
- **Share to Watch**: share any file from any app using the standard Android share sheet — it goes straight to the watch.
- **Share to Phone**: send files from the watch back to the phone.

| File Browser | File Preview |
|:---:|:---:|
| ![File Manager](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/filemanager.jpg?ref_type=heads) | ![File Preview](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/filemanager-preview.jpg?ref_type=heads) |

---

### Health Monitoring

RTOSify reads health sensor data from your watch and displays it on your phone with interactive charts.

**Tracked metrics:**
- **Steps** — daily, weekly, and monthly counts with a goal progress bar.
- **Heart rate** — current reading and historical trend graph.
- **Blood oxygen (SpO2)** — current and historical chart.
- **Measure now** — request an immediate sensor reading from the phone at any time.

All data is stored locally and browsable by date.

| Steps Tracking | Heart Rate |
|:---:|:---:|
| ![Steps](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/health-steps.jpg?ref_type=heads) | ![Heart Rate](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/health-heartrate.jpg?ref_type=heads) |

---

### Battery Monitoring

Deep battery statistics for your watch, visible and tracked from your phone.

- **Real-time metrics**: percentage, charging status, voltage, temperature, current draw (mA), and instant power (mW).
- **Estimated remaining time** based on the current drain rate.
- **Historical charts**: battery level and current draw plotted over time.
- **Alerts**: notify when fully charged, or set a custom low-battery threshold with a slider.
- **Custom capacity**: enter your watch's actual mAh for accurate time-remaining estimates.

| Battery Overview | Battery Charts |
|:---:|:---:|
| ![Battery 1](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/battery-1.jpg?ref_type=heads) | ![Battery 2](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/battery-2.jpg?ref_type=heads) |

---

### Media Control

Control media playing on your phone directly from your watch.

- See the **track title, artist, and album art** on your watch.
- **Play / pause**, skip forward/back, and seek through tracks.
- **Volume indicator** showing the current level.
- Real-time updates — changes on the phone reflect on the watch instantly.
- A **Media Widget** is also available for your watch home screen for one-tap control.

| Media Control | Volume |
|:---:|:---:|
| ![Media](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/media.png?ref_type=heads) | ![Volume](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/volume.png?ref_type=heads) |

---

### Call Handling

When your phone rings, your watch knows about it.

- **Incoming call display**: caller name (from contacts) or number shown on the watch with Accept and Reject buttons.
- **Answer or reject** calls from the watch.
- **Dialer**: a full numeric keypad on the watch to place outgoing calls through your phone.
- **Contact search**: browse and search synced phone contacts directly from the watch dialer.

| Dialer | Contacts |
|:---:|:---:|
| ![Dialer](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/dialer.png?ref_type=heads) | ![Contacts](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/dialer-contacts.png?ref_type=heads) |

---

### Alarm Sync

Manage alarms on your watch from the phone, or create them on the watch itself.

- **Create, edit, and delete alarms** from either device.
- Set **repeat days** (Mon–Sun), custom labels, and times.
- Changes sync instantly over the connection.
- When an alarm fires, the watch shows a **full-screen alarm UI** with snooze (10 minutes) and dismiss options.

| Alarm List (Phone) | Alarm Edit (Phone) | Alarm Ringing (Watch) |
|:---:|:---:|:---:|
| ![Alarm 1](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/alarm-1.jpg?ref_type=heads) | ![Alarm 2](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/alarm-2.jpg?ref_type=heads) | ![Alarm Watch](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/alarm-watch.png?ref_type=heads) |

---

### Find Device

Lost your watch somewhere?

- Tap **Find Device** on the phone to make the watch ring audibly.
- The phone shows a **proximity indicator** (Excellent / Good / Fair / Weak / Very Weak) based on Bluetooth signal strength.
- GPS distance is shown if location data is available.
- Tap **Stop** to cancel the alarm remotely.
- The watch can ring the phone back using **Find Phone** from the watch app.

![Find Device](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/finddevice.jpg?ref_type=heads)

---

### Camera Remote

Turn your watch into a remote shutter for your phone's camera.

- **Live viewfinder**: see the phone camera feed on the watch screen.
- **Capture photos** by tapping the shutter button on the watch.
- **Record video** with a live elapsed-time counter displayed on the watch.

![Camera Remote](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/camera.jpg?ref_type=heads)

---

### App Management

Manage the apps installed on your watch remotely from your phone.

- **View all installed apps** on the watch, with version numbers.
- **Uninstall apps** remotely without touching the watch.
- **Install APKs**: send an APK from your phone to the watch — it transfers in chunks with checksum verification and installs automatically (requires Shizuku or root on the watch).

| App List | Sending APK |
|:---:|:---:|
| ![App List](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/applist.jpg?ref_type=heads) | ![Sending](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/applist-sending.jpg?ref_type=heads) |

---

### Watch Face Management

Browse and install custom watch faces from a built-in store.

- **Watch Face Store**: browse available watch faces with preview images.
- **Download and install** watch faces directly from the store to the watch.
- **File browser**: manage locally stored watch faces and swap between them.

![Watch Face Store](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/watchfacestore%20.jpg?ref_type=heads)

---

### Terminal / Shell

For advanced users — execute shell commands on the watch directly from the phone.

- **Color-coded output** distinguishes input, output, and errors.
- **Command history**: scroll through previously used commands.
- **Working directory tracking**: the terminal tracks your current directory as you `cd` around.
- **Permission levels**:
  - *Limited* — standard Android APIs only.
  - *Shizuku* — shell (UID 2000) level with broader access.
  - *Root* — full root access (UID 0), requires a rooted device.

![Terminal](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/terminal.jpg?ref_type=heads)

---

### Home Screen Widgets

Both the phone and watch support home screen widgets for at-a-glance information without opening the app.

**Phone widgets:**
- **Status Widget** — watch connection status, battery level, and key info at a glance.
- **Health Widget** — today's steps and last heart rate reading on your home screen.

**Watch widgets:**
- **Dashboard Widget** — connection status and quick stats.
- **Notification Log Widget** — scrollable list of recent notifications received from the phone.
- **Media Widget** — now-playing info with a play/pause button.

![Phone Widgets](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/widget-phone.jpg?ref_type=heads)

---

### Automation

Set up automatic behaviors that run without any user interaction.

| Automation | What it does |
|------------|-------------|
| **Auto-start on boot** | RTOSify starts automatically when the device boots |
| **Clipboard sync** | Copy on one device — paste on the other |
| **Auto WiFi** | Automatically connects to known WiFi networks |
| **Auto mobile data** | Toggles mobile data based on rules |
| **Bluetooth tether (PAN)** | Shares phone internet to the watch over Bluetooth |
| **Force Bluetooth on** | Keeps Bluetooth enabled regardless of other settings |
| **File observer** | Watches filesystem paths and triggers actions on file changes |
| **Starred contacts sync** | Syncs the phone's starred contacts to the watch dialer |

| Automation 1 | Automation 2 |
|:---:|:---:|
| ![Automation 1](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/automation-1.jpg?ref_type=heads) | ![Automation 2](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/automation-2.jpg?ref_type=heads) |

---

### Connection & Network Settings

RTOSify supports four communication transports, selected automatically based on the rules you configure.

| Transport | Best for |
|-----------|---------|
| **Bluetooth Classic** | Everyday use — reliable, always available |
| **BLE (Bluetooth Low Energy)** | Battery-efficient background connection |
| **WiFi / LAN** | High bandwidth — screen mirroring, large file transfers |
| **WebRTC (Internet)** | When phone and watch are on different networks |

**Configurable rules:**
- **LAN mode**: Disabled / BT Fallback / App Open / BT-or-App / Always
- **Internet mode**: Disabled / BT-or-LAN Fallback / App Open / Always
- **Fixed IP**: manually set the watch's IP to skip discovery.
- **WebRTC servers**: configure your own STUN/TURN servers.
- **Test connection**: built-in internet connectivity tester to verify WebRTC works.

| Network Settings | Transport Status | Connection Test |
|:---:|:---:|:---:|
| ![Comms 1](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/comms-1.jpg?ref_type=heads) | ![Comms 2](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/comms-2.jpg?ref_type=heads) | ![Comms Test](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/comms-test.jpg?ref_type=heads) |

![Comms 3](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/comms-3.jpg?ref_type=heads)

---

## Permissions Explained

RTOSify requests several permissions. Here is what each one is actually used for:

| Permission | Why it is needed |
|-----------|----------------|
| **Notification Access** | Read phone notifications to mirror them to the watch |
| **Bluetooth** | Core communication between phone and watch |
| **Location** | Required by Android for Bluetooth and WiFi device scanning |
| **Do Not Disturb Access** | Respect or bypass DND when forwarding notifications |
| **Battery Optimization Exemption** | Keep the background service alive for a persistent connection |
| **Overlay / System Alert Window** | Draw the Dynamic Island bubble over other apps |
| **Contacts & Calendar** | Sync starred contacts and calendar events to the watch |
| **Camera** | Camera Remote feature |
| **Phone State** | Detect incoming calls to forward them to the watch |
| **Accessibility Service** | Required for some automation features |
| **Shizuku / Root** *(optional)* | APK install, advanced file ops, WiFi/data control, shell |

![Fullscreen Notification](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/fullscreen-notification.png?ref_type=heads)

![Notification Log](https://gitlab.com/ailife8881/rtosify/-/raw/master/images/notification-log.png?ref_type=heads)

---

## FAQ

**Q: Do both devices need to be on the same WiFi?**
No. The apps use Bluetooth when nearby. You can configure them to fall back to internet (WebRTC) when apart. See [Connection & Network Settings](#connection--network-settings).

**Q: Can I use RTOSify without Shizuku or root?**
Yes. Most features work without elevated privileges: notifications, health, media, calls, screen mirroring, find device, and more. Shizuku/root unlocks APK installation on the watch, WiFi/data control, and the shell terminal.

**Q: Does screen mirroring drain battery?**
Screen mirroring is intensive. Disable it when not needed and use it over WiFi for best performance. All other features are designed to be lightweight, especially in Lite Mode on the watch.

**Q: Can I pair multiple watches?**
Yes. The phone app supports multiple paired devices. Switch between them from the main dashboard menu.

**Q: My notifications stopped arriving on the watch.**
1. Check that Notification Access is still granted — Android can revoke it after OS updates.
2. Check the specific app is enabled in the App Selection list.
3. Verify the connection is active (green indicator on the dashboard).

**Q: The connection keeps dropping.**
Enable **Battery Optimization Exemption** for both apps and turn on **Aggressive Keep-Alive** in Automation settings. If using Bluetooth Classic, try switching to BLE or enabling the Force Bluetooth toggle.

---

## Developer Notes

### Repository Structure

This repository contains **two independent Android Studio projects**. Do not open the root directory as a project in Android Studio.

```
rtosify/
├── rtosify/      ← Open this for the Phone App
├── companion/    ← Open this (in a separate window) for the Watch App
└── signaling-server/  ← Node.js WebRTC signaling server
```

Package names:
- Phone: `com.ailife.rtosify`
- Watch: `com.ailife.rtosifycompanion`

### Build Commands

```bash
# Phone app
cd rtosify
./gradlew assembleDebug
./gradlew installDebug

# Watch app
cd companion
./gradlew assembleDebug
./gradlew installDebug

# Run unit tests (from either directory)
./gradlew test
```

### Architecture Overview

- **`Protocol.kt`** — shared JSON message protocol (130+ message types). Must be kept **identical** in both apps.
- **`BluetoothService.kt`** — foreground service and top-level coordinator. Receives from `TransportManager` and dispatches via `handleReceivedMessage()`.
- **`TransportManager.kt`** — pluggable multi-transport layer. Auto-selects transport based on configured rules and applies encryption before sending.
- **`EncryptionManager`** (`security/`) — encrypts all messages in transit.
- **`IUserService.aidl`** — AIDL interface for Shizuku/root operations. Must match exactly between both apps.

### Communication Transports

| Class | Transport |
|-------|-----------|
| `BluetoothTransport` | Classic BT RFCOMM (UUID `00001101-0000-1000-8000-00805f9b34fb`) |
| `BleTransport` | BLE GATT with chunked data transfer |
| `WifiIntranetTransport` | TCP socket, mDNS/NSD discovery |
| `WebRtcTransport` | WebRTC P2P with `SignalingClient` |

### Adding a New Message Type

1. Add a constant to `MessageType` in `Protocol.kt` — **in both apps**.
2. Add a data class if the message carries a payload.
3. Add a factory method in `ProtocolHelper`.
4. Handle the new type in `BluetoothService.handleReceivedMessage()` — **in both apps**.

### Adding a New Transport

1. Implement the `CommunicationTransport` interface (`Transport.kt`).
2. Register the new transport in `TransportManager`.

### Adding Shizuku / Root Features

1. Add the method signature to `IUserService.aidl` — **in both apps** (must match exactly).
2. Implement it in `UserService.kt`.
3. Rebuild to regenerate AIDL stubs.
4. Call via the Shizuku binder from app code.

### Key Dependencies

| Library | Purpose |
|---------|---------|
| Kotlin Coroutines | Async operations throughout |
| Gson | JSON serialization of protocol messages |
| Shizuku API | Elevated privilege (shell/root) operations |
| libsu | Root shell access |
| ZXing | QR code generation and scanning for pairing |
| CameraX | Camera Remote feature |
| MPAndroidChart | Health and battery charts (phone app) |
| Jsoup | Watch face store HTML parsing (phone app) |

### Internationalization

User-facing strings are in `res/values/strings.xml` (English) with Chinese translations in `res/values-zh/strings.xml`. All code comments and log messages are in English.

### Related READMEs

- [Phone App README](rtosify/README.md)
- [Watch App README](companion/README.md)
- [Signaling Server README](signaling-server/README.md)
