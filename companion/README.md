# RTOSify Companion - Watch App

This is the **smartwatch/companion** application for the RTOSify notification system.

## Description

RTOSify Companion is the watch app that receives and displays notifications from your Android phone running the main RTOSify app.

## Features

- **Bluetooth Server**: Accepts Bluetooth connections from the phone app
- **Notification Display**: Shows notifications received from the phone
- **Interactive Notifications**: Dismiss notifications directly from the watch
- **Status Sync**: Sends watch battery, WiFi, and DND status to the phone
- **APK Installation**: Can receive and install APK files sent from the phone

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

1. The app starts a Bluetooth server and waits for connections
2. When the phone app connects, it begins receiving notifications
3. Notifications are displayed as Android notifications on the watch
4. The watch periodically sends status updates (battery, WiFi, DND) to the phone
5. When you dismiss a notification on the watch, it sends a command to dismiss it on the phone too

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

- **Battery Status**: Reports watch battery level and charging state to phone
- **WiFi Status**: Reports connected WiFi network to phone
- **DND Control**: Can toggle Do Not Disturb mode via phone commands
- **Root Features**: If rooted, can install APKs sent from the phone
