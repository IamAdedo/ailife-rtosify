# RTOSify Signaling Server

A simple Node.js WebSocket server that facilitates WebRTC signaling and message relay between the Phone and Watch apps over the Internet.

## Purpose

Enables the RTOSify ecosystem to function even when devices are not in Bluetooth range or on the same local WiFi network. It relays JSON messages between devices identified by their MAC addresses.

## Requirements

- Node.js (v14 or higher)
- npm

## Usage

1. **Install Dependencies**:
   ```bash
   npm install
   ```

2. **Start the Server**:
   ```bash
   npm start
   ```
   The server listens on port **8080** by default.

## Protocol

- **Join**: Clients send `{"type": "join", "mac": "DEVICE_MAC_ADDRESS"}` to register.
- **Signal**: Clients send `{"type": "signal", "target": "TARGET_MAC", "payload": {...}}` to send data.
- **Relay**: The server forwards the `payload` to the connected client with `TARGET_MAC`.

## Deployment

Can be deployed to any Node.js hosting service (Heroku, AWS, DigitalOcean, etc.). Ensure the URL is reachable by both the Phone and Watch apps.
