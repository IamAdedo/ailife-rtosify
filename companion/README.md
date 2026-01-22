# RTOSify Companion - Watch App

This is the **smartwatch/companion** application for the RTOSify notification system.

## Description

RTOSify Companion is the watch app that receives and displays notifications from your Android phone running the main RTOSify app.

## Features

- **Multi-Transport Server**: Accepts connections via Bluetooth, WiFi, or Internet (Signaling Server)
- **Dynamic Island**: Persistent overlay for media controls, timers, and notifications
- **Navigation Overlay**: Full-screen turn-by-turn directions overlay (Google Maps/Waze)
- **Notification Log**: Persistent history of received notifications with search
- **Screen Mirroring**: Mirror phone screen or cast watch screen
- **Find Device**: Trigger loud alarm on phone to locate it
- **Media Control**: Full storage-less media controller for phone playback
- **Camera Remote**: View phone camera feed and take photos
- **Notification Display**: Shows notifications received from the phone
- **Interactive Notifications**: Dismiss notifications directly from the watch
- **Status Sync**: Sends watch battery, WiFi, Health data, and DND status to the phone
- **APK Installation**: Can receive and install APKs (requires Shizuku/Root)

## Requirements

- Android smartwatch or device running Android 8.0 (API 26) or higher
- Bluetooth capability
- Root access recommended for APK installation features

## Setup

1. Install this app on your **smartwatch**
2. Grant all required permissions when prompted:
   - Bluetooth permissions
   - Location (for WiFi SSID reading)
   - Notification permissions
   - Battery optimization exemption
3. Open the app and select **Watch** mode
4. The watch will wait for a connection from your phone
5. On your phone, open **RTOSify** and connect to this watch

## Package Information

- **Package Name**: `com.ailife.rtosifycompanion`
- **App Name**: RTOSify Companion
- **Version**: 1.0

## Related Projects

- **RTOSify**: The phone app that sends notifications (install on your smartphone)

## How It Works

1. The app starts a server (Bluetooth RFCOMM and/or WebSocket)
2. When the phone app connects, it syncs state (Privacy Policy, Settings)
3. "Dynamic Island" service runs in background to handle overlays
4. Notification Listener ensures navigation apps trigger the Overlay view
5. Persistent logs are saved to storage for review in the Log Activity
6. The watch periodically sends status updates (battery, WiFi, DND, Health) to the phone

## Build Instructions

1. Open this project in Android Studio
2. Sync Gradle dependencies
3. Build and run on your smartwatch

```bash
./gradlew assembleRelease
```

## Permissions

The app requires the following permissions:
- `BLUETOOTH` / `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` - For Bluetooth connectivity
- `ACCESS_FINE_LOCATION` - To read WiFi SSID
- `ACCESS_BACKGROUND_LOCATION` - To read WiFi SSID in background
- `ACCESS_WIFI_STATE` - To monitor WiFi connection
- `POST_NOTIFICATIONS` - To display mirrored notifications
- `RECEIVE_BOOT_COMPLETED` - To auto-start after device reboot
- `FOREGROUND_SERVICE` - To maintain persistent Bluetooth connection
- `ACCESS_NOTIFICATION_POLICY` - To manage Do Not Disturb settings

## Watch-Specific Features

- **Dynamic Island**: Apple-style overlay for active status and quick actions
- **Navigation Overlay**: Detects navigation instructions and shows large, readable turn indicators
- **Battery Status**: Reports watch battery level and charging state to phone
- **WiFi Status**: Reports connected WiFi network to phone
- **DND Control**: Can toggle Do Not Disturb mode via phone commands
- **Root/Shizuku Features**: Required for APK installation, File management, and other privileged actions
