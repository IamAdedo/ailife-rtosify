# Rtosify - Phone App

This is the **phone/smartphone** application for the Rtosify notification system.

## Description

Rtosify is the main phone app that captures notifications from your Android phone and sends them to a companion smartwatch device via Bluetooth.

## Features

- **Notification Listener**: Monitors notifications on your phone
- **Bluetooth Client**: Connects to the smartwatch (companion app) via Bluetooth
- **Notification Filtering**: Select which apps' notifications to mirror
- **Automatic Sync**: Automatically sends new notifications to the watch
- **APK Transfer**: Can send APK files to the watch for installation

## Requirements

- Android device (phone/tablet) running Android 8.0 (API 26) or higher
- Bluetooth capability
- Notification access permission

## Setup

1. Install this app on your **phone**
2. Grant all required permissions when prompted:
   - Bluetooth permissions
   - Notification access
   - Location (for Bluetooth scanning)
3. Open the app and select **Smartphone** mode
4. Configure which apps' notifications you want to mirror
5. Pair with your smartwatch running the **Rtosify Companion** app

## Package Information

- **Package Name**: `com.ailife.rtosify`
- **App Name**: Rtosify
- **Version**: 1.0

## Related Projects

- **Rtosify Companion**: The watch app that receives and displays notifications (install on your smartwatch)

## How It Works

1. The app uses `NotificationListenerService` to capture notifications from other apps
2. When a notification is posted, it's serialized to JSON
3. The notification is sent via Bluetooth to the connected smartwatch
4. The watch displays the notification
5. When dismissed on the watch, the phone receives a command to dismiss it locally

## Build Instructions

1. Open this project in Android Studio
2. Sync Gradle dependencies
3. Build and run on your phone

```bash
./gradlew assembleRelease
```

## Permissions

The app requires the following permissions:
- `BLUETOOTH` / `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` - For Bluetooth connectivity
- `ACCESS_FINE_LOCATION` - Required for Bluetooth scanning on Android
- `POST_NOTIFICATIONS` - To show notification sync status
- `RECEIVE_BOOT_COMPLETED` - To auto-start the service after device reboot
- `FOREGROUND_SERVICE` - To maintain persistent Bluetooth connection
- Notification Listener permission - To read notifications from other apps
