# RTOSify - Phone App

This is the **phone/smartphone** application for the RTOSify notification system.

## Description

RTOSify is the main phone app that captures notifications, media, and navigation instructions from your Android phone and sends them to a companion smartwatch device via Bluetooth, WiFi, or Internet.

## Features

- **Notification Listener**: Monitors notifications on your phone
- **Multi-Transport Connectivity**: Seamlessly switches between Bluetooth, different Subnets (LAN), and Internet via signaling server
- **Watchface Store**: Browse, scrape, and install watchfaces directly to the watch
- **Navigation Mirroring**: Detects Google Maps/Waze instructions and sends them to the watch overlay
- **Media Controller**: Syncs playback status, volume, and metadata for all media apps
- **Privacy Policy**: Integrated privacy agreement handling for data collection
- **Find Device**: Locate your watch by playing a sound
- **Health Dashboard**: View health data (Heart Rate, SpO2, Steps) synced from the watch
- **Camera Remote**: Viewfinder for controlling the phone camera from the watch
- **Notification Filtering**: Select which apps' notifications to mirror
- **Automatic Sync**: Automatically sends new notifications to the watch
- **APK Transfer**: Can send APK files to the watch for installation (requires Shizuku on watch for silent install)

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
5. Pair with your smartwatch running the **RTOSify Companion** app

## Package Information

- **Package Name**: `com.ailife.rtosify`
- **App Name**: RTOSify
- **Version**: 1.0

## Related Projects

- **RTOSify Companion**: The watch app that receives and displays notifications (install on your smartwatch)

## How It Works

1. The app uses `NotificationListenerService` to capture notifications
2. It acts as a client connecting to the watch via the best available transport (Bluetooth > WiFi > Internet)
3. Notifications, media updates, and navigation instructions are serialized to JSON
4. The data is sent securely to the connected smartwatch
4. The watch displays the notification
5. When dismissed on the watch, the phone receives a command to dismiss it locally
6. Watchface scraper downloads new faces and transfers them as zip files
7. Privacy Policy agreement is required on first launch

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
