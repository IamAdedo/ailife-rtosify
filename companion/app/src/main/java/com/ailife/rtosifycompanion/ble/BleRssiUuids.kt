package com.ailife.rtosifycompanion.ble

import java.util.UUID

/**
 * UUID definitions for BLE services in RTOSify.
 *
 * Uses custom UUIDs in the 0000FDxx range to avoid conflicts with standard services.
 */
object BleRssiUuids {
    // ========== RSSI Monitoring Service (FindDevice) ==========
    
    /**
     * Primary service UUID for RTOSify Find Device feature.
     * Advertised by both phone and watch when FindDevice mode is active.
     */
    val FIND_DEVICE_SERVICE_UUID: UUID =
        UUID.fromString("0000FD00-0000-1000-8000-00805F9B34FB")

    /**
     * Characteristic UUID for device identification.
     * Minimal characteristic - RSSI reading doesn't require data transfer.
     */
    val RSSI_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("0000FD01-0000-1000-8000-00805F9B34FB")
    
    // ========== Data Transport Service (BLE Messaging) ==========
    
    /**
     * Primary service UUID for BLE data transport.
     * Used for bidirectional communication between phone and watch.
     */
    val DATA_TRANSPORT_SERVICE_UUID: UUID =
        UUID.fromString("0000FD10-0000-1000-8000-00805F9B34FB")

    /**
     * TX Characteristic (Server writes, Client receives via notifications).
     * The server (watch/companion) writes data here, and the client (phone) subscribes.
     */
    val DATA_TX_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("0000FD11-0000-1000-8000-00805F9B34FB")

    /**
     * RX Characteristic (Server receives writes from Client).
     * The client (phone) writes data here, and the server (watch/companion) reads it.
     */
    val DATA_RX_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("0000FD12-0000-1000-8000-00805F9B34FB")
    
    /**
     * Client Characteristic Configuration Descriptor UUID (standard).
     * Used to enable/disable notifications on TX characteristic.
     */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
