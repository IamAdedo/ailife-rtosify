# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RTOSify is a multi-transport notification mirroring system split into two independent Android applications:

- **Phone App** (`rtosify/`): Captures notifications from an Android phone and sends them to a smartwatch
- **Watch Companion App** (`companion/`): Receives and displays notifications on a smartwatch from the phone

Both apps use identical communication protocol and can switch roles (phone/watch mode), but are typically deployed as separate applications on different devices.

## Repository Structure

```
rtosify/
├── rtosify/           # Phone app (separate Android Studio project)
│   └── app/
│       └── src/main/java/com/ailife/rtosify/
├── companion/         # Watch app (separate Android Studio project)
│   └── app/
│       └── src/main/java/com/ailife/rtosifycompanion/
```

**Important**: These are TWO SEPARATE Android Studio projects in a single git repository. When opening in Android Studio:
- Open `rtosify/` for the phone app
- Open `companion/` for the watch app (in a separate window)

Do NOT open the root `rtosify/` directory as a project.

## Build Commands

Each app is built independently using its own Gradle wrapper.

### Phone App (rtosify/)

```bash
cd rtosify
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK
./gradlew installDebug         # Build and install debug APK to connected device
./gradlew clean                # Clean build artifacts
```

### Watch Companion App (companion/)

```bash
cd companion
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK
./gradlew installDebug         # Build and install debug APK to connected device
./gradlew clean                # Clean build artifacts
```

### Testing

```bash
# Run unit tests (in either rtosify/ or companion/)
./gradlew test

# Run instrumented tests on connected device
./gradlew connectedAndroidTest
```

## Core Architecture

### Communication Protocol

The heart of RTOSify is `Protocol.kt` (identical in both apps), which defines a JSON-based message protocol for all communication between phone and watch.

- **Message Structure**: All messages are JSON with `{version, type, timestamp, data}` format
- **Message Types**: Defined in `MessageType` object (130+ message types)
- **Data Classes**: Strongly-typed data structures for each message type (e.g., `NotificationData`, `StatusUpdateData`, `FileTransferData`)
- **Helper Functions**: `ProtocolHelper` provides factory methods to create and parse messages

**Key Message Categories**:
- Notifications: `NOTIFICATION_POSTED`, `NOTIFICATION_REMOVED`, `DISMISS_NOTIFICATION`
- File Transfer: `FILE_TRANSFER_START`, `FILE_CHUNK`, `FILE_TRANSFER_END`
- Device Control: `SHUTDOWN`, `REBOOT`, `LOCK_DEVICE`, `FIND_DEVICE`
- Status Sync: `STATUS_UPDATE`, `BATTERY_DETAIL_UPDATE`, `DEVICE_INFO_UPDATE`
- Screen Mirroring: `SCREEN_MIRROR_START`, `SCREEN_MIRROR_DATA`, `REMOTE_INPUT`
- Health Data: `HEALTH_DATA_UPDATE`, `REQUEST_HEALTH_HISTORY`
- Shell Commands: `EXECUTE_SHELL_COMMAND`, `SHELL_COMMAND_RESPONSE`

### Communication Architecture

Both apps have a `communication/` package implementing a pluggable multi-transport system:

- **`CommunicationTransport`** (`Transport.kt`): Interface implemented by all transports (`connect`, `send`, `receive`, `disconnect`)
- **`TransportManager`**: Orchestrates all transports with auto-reconnection, selects active transport based on availability and Network Settings rules
- **`BluetoothTransport`**: Classic Bluetooth RFCOMM — UUID `00001101-0000-1000-8000-00805f9b34fb`, primary transport
- **`BleTransport`**: BLE-based data transport using GATT
- **`WifiIntranetTransport`**: TCP socket over local WiFi network, preferred for high-bandwidth operations like screen mirroring
- **`WebRtcTransport`**: Internet transport via WebRTC with `SignalingClient` for peer negotiation
- **`MdnsDiscovery`**: Discovers devices on the local network via mDNS/NSD

`BluetoothService.kt` (foreground service in both apps) is the top-level coordinator: it uses `TransportManager` to send/receive protocol messages and calls `handleReceivedMessage()` for dispatch.

**BLE stack** (`ble/` package): `BleGattServer`/`BleGattClient` for GATT connections, `BleDataGattServer`/`BleDataGattClient` for chunked data transfer, `BleRssiMonitor` for proximity detection.

**Encryption**: `security/EncryptionManager` encrypts messages in transit; `TransportManager` holds a reference and applies it before sending.

### Notification System

**Phone App**:
- `MyNotificationListener.kt`: `NotificationListenerService` that intercepts all device notifications
- Filters notifications based on user-selected apps
- Extracts icons, text, actions (reply, dismiss) from notifications
- Sends to watch via `BluetoothService`
- Supports notification actions and inline replies

**Watch App**:
- Receives notification data via `BluetoothService`
- Creates Android notifications from received data
- Supports dismissing notifications (syncs back to phone)
- Optional "Dynamic Island" style notification UI (`DynamicIslandView.kt`, `DynamicIslandService.kt`)

### Privileged Operations (Shizuku/Root)

Both apps support elevated permissions via Shizuku or root for advanced features.

**AIDL Interface** (`IUserService.aidl`):
- File operations (list, delete, rename, move, copy)
- App installation/uninstallation
- WiFi control (enable/disable, connect, scan)
- Mobile data toggle
- Clipboard sync
- Shell command execution

**UserService.kt**:
- Implements `IUserService.Stub()` for AIDL
- Runs with shell (UID 2000) or root (UID 0) privileges when launched via Shizuku
- Fallback to standard Android APIs when elevated privileges unavailable

**Usage**: Features like APK transfer, file management, WiFi control require Shizuku app to be installed and running on the device.

### Screen Mirroring

- `MirroringService.kt`: Captures screen using `MediaProjection` API
- Encodes frames and sends via Bluetooth
- `MirrorActivity.kt`: Displays mirrored screen and handles touch input
- Supports remote control (touch events, navigation buttons)
- Resolution adjustment via `ResolutionData`

### File Transfer System

Chunked file transfer protocol for sending APKs, watch faces, and other files:
1. Send `FILE_TRANSFER_START` with metadata (name, size, checksum, type)
2. Send file in chunks via `FILE_CHUNK` messages (Base64 encoded)
3. Send `FILE_TRANSFER_END` with success status
4. Receiver reassembles chunks and verifies checksum

Types: `TYPE_REGULAR`, `TYPE_APK`, `TYPE_WATCHFACE`

### Settings & Preferences

Both apps use `SharedPreferences` for settings:
- Per-device preferences managed by `DevicePrefManager` (in phone app)
- Settings sync via `UPDATE_SETTINGS` protocol message
- Key settings: notification mirroring, clipboard sync, auto WiFi/data, vibrate, Dynamic Island config

### Additional Features

**Health Data** (Watch → Phone):
- `HealthDataCollector.kt`: Collects steps, heart rate, SpO2 from watch sensors
- Syncs to phone for display and charting
- Historical data requests supported

**Alarms**:
- Alarm sync between devices
- `WatchAlarmManager.kt`: Manages alarms on watch
- CRUD operations via protocol messages

**Call Management**:
- Phone call state sync to watch
- Answer/reject calls from watch
- `CallActivity.kt`: Watch UI for incoming calls

**Camera Remote**:
- `CameraRemoteActivity.kt`: Phone camera viewfinder on watch
- Shutter control from watch
- Uses CameraX library

**Watch Faces**:
- `watchface/` package: Store, download, and install watch faces
- File browser for watch face management

**Companion-only features**:
- `AncsClient.kt`: Apple Notification Center Service client (for iOS device notifications)
- `NotificationLogManager` / `NotificationLogActivity`: Persistent log of all received notifications
- `BluetoothPanManager`: Bluetooth PAN tethering for sharing phone internet to watch
- `MediaControlActivity`: Watch-side media playback controls
- Widgets: `DashboardWidget`, `MediaWidget`, `NotificationLogWidget`

**Phone-only features**:
- `FileObserverManager`: Watches filesystem paths and triggers rules on file changes
- `MediaSessionListener`: Tracks active media sessions for sync to watch
- `NotificationCache`: Caches active notifications for reconnection sync
- `utils/OtaManager.kt`: OTA update delivery to watch
- `widget/StatusWidget`, `widget/HealthWidget`: Home screen widgets

## Language & Internationalization

The codebase supports multiple languages:
- Strings defined in `res/values/strings.xml` (English)
- Chinese translations in `res/values-zh/strings.xml`
- All logs and comments are in English (recent migration from Chinese)

## Package Names

- Phone App: `com.ailife.rtosify`
- Watch Companion: `com.ailife.rtosifycompanion`

## Dependencies

Key libraries used:
- **Kotlin Coroutines**: Async operations
- **Gson**: JSON serialization/deserialization
- **Shizuku API**: Elevated privilege operations
- **libsu**: Root access support
- **ZXing**: QR code scanning/generation (for device pairing)
- **CameraX**: Camera functionality
- **MPAndroidChart**: Health data visualization (phone app)
- **Jsoup**: HTML parsing (phone app, for watch face store)

## Common Development Tasks

### Adding a New Protocol Message Type

1. Add constant to `MessageType` in `Protocol.kt` (both apps)
2. Create data class if needed (e.g., `data class MyData(...)`)
3. Add helper function in `ProtocolHelper` (e.g., `createMyMessage()`)
4. Handle message in `BluetoothService.handleReceivedMessage()` (both apps)

### Modifying Communication

- **Transport selection logic**: Edit `TransportManager.kt` in the `communication/` package
- **Adding a new transport**: Implement `CommunicationTransport` interface, register in `TransportManager`
- **Message handling**: `BluetoothService.handleReceivedMessage()` in both apps — `BluetoothService` receives from `TransportManager.incomingMessages` flow
- **BLE proximity**: Edit `ble/BleRssiMonitor.kt`

### Adding Shizuku/Root Features

1. Add method signature to `IUserService.aidl` (both apps must match)
2. Implement method in `UserService.kt`
3. Rebuild project to regenerate AIDL stubs
4. Call via Shizuku binder in app code

### String Extraction

All user-facing strings should be in `strings.xml`:
```xml
<string name="my_feature">My Feature</string>
```

Reference in code:
```kotlin
getString(R.string.my_feature)
```

## Important Notes

- **Separate Projects**: Always remember these are TWO separate Android projects. Changes to shared code (like `Protocol.kt`) must be made in BOTH apps.
- **Protocol Sync**: `Protocol.kt` must be kept identical between phone and watch apps for compatibility.
- **AIDL Matching**: `IUserService.aidl` must match exactly between apps to ensure compatible AIDL interfaces.
- **Bluetooth UUID**: Both apps use the same UUID for discovery and connection.
- **Foreground Services**: Both apps run Bluetooth services in foreground to maintain persistent connections.
- **Permissions**: Apps require extensive permissions (Bluetooth, Location, Notification Access, etc.) - see README files for details.
