# RTOSify - Notification Mirroring System

A Bluetooth-based notification mirroring system split into two separate Android apps:
- **Phone App**: Sends notifications from your phone to your smartwatch
- **Watch App**: Receives and displays notifications on your smartwatch
- **Signaling Server**: NodeJS server for Internet-based communication between devices

## Project Structure

This repository contains two independent Android Studio projects:

### 📱 [rtosify/](rtosify/) - Phone App
The main smartphone application that captures and sends notifications to the watch.
- Package: `com.ailife.rtosify`
- [Read more →](rtosify/README.md)

### ⌚ [companion/](companion/) - Watch Companion App
The smartwatch application that receives and displays notifications from the phone.
- Package: `com.ailife.rtosifycompanion`
- [Read more →](companion/README.md)

### 🌐 [signaling-server/](signaling-server/) - WebRTC Signaling Server
Node.js server enabling the devices to communicate over the Internet when local Bluetooth/WiFi is unavailable.
- [Read more →](signaling-server/README.md)

## Quick Start

1. **Open Phone App in Android Studio**:
   - File → Open → Select `rtosify/rtosify/`
   - Build and install on your phone

2. **Open Watch App in Android Studio** (new window):
   - File → Open → Select `rtosify/companion/`
   - Build and install on your smartwatch

3. **Connect**:
   - Launch both apps
   - Grant required permissions
   - Pair devices via Bluetooth

## Documentation

- [📱 Phone App README](rtosify/README.md)
- [⌚ Watch App README](companion/README.md)
- [🌐 Signaling Server README](signaling-server/README.md)

## Features

- Real-time notification synchronization via Bluetooth, WiFi (LAN), and Internet
- Selective app notification mirroring
- Two-way notification dismissal
- Battery and WiFi status sync
- APK file transfer and installation
- Watchface Store and Scraper
- Navigation Turn-by-Turn Overlay
- Dynamic Island UI on Watch
- Find Device (Locate Phone/Watch)
- Auto-start on boot

## Requirements

- Phone: Android 8.0 (API 26) or higher
- Watch: Android 8.0 (API 26) or higher
- Bluetooth, WiFi, or Internet connectivity

## License

Notification mirroring system for Android devices.
