package com.ailife.rtosifycompanion.ble

import java.util.UUID

/**
 * UUID definitions for BLE RSSI monitoring in FindDevice feature.
 *
 * Uses custom UUIDs in the 0000FDxx range to avoid conflicts with standard services.
 */
object BleRssiUuids {
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
}
