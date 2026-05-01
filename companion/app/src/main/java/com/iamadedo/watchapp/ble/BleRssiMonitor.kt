package com.iamadedo.watchapp.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Coordinator for BLE RSSI monitoring in FindDevice feature.
 *
 * Manages both GATT server (for advertising) and GATT client (for RSSI reading).
 * Runs bidirectionally on both phone and watch.
 */
class BleRssiMonitor(
    private val context: Context,
    private val onRssiUpdate: (Int) -> Unit
) {

    companion object {
        private const val TAG = "BleRssiMonitor"
        private const val SCAN_TIMEOUT = 10000L // 10 seconds
    }

    private val gattServer = BleGattServer(context)
    private var gattClient: BleGattClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var onFallbackToClassic: (() -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                Log.i(TAG, "Found device advertising FindDevice service: ${device.address}")
                stopBleScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error ($errorCode)"
            }
            Log.e(TAG, "BLE scan failed: $reason")
            isScanning = false
            onFallbackToClassic?.invoke()
        }
    }

    private val scanTimeoutRunnable = Runnable {
        Log.w(TAG, "BLE scan timeout - remote device not found")
        stopBleScan()
        onFallbackToClassic?.invoke()
    }

    /**
     * Start BLE RSSI monitoring.
     *
     * @param bluetoothAdapter Bluetooth adapter instance
     * @param targetDeviceMac MAC address of remote device (from existing RFCOMM connection)
     * @param fallbackToClassic Callback invoked if BLE fails (to use Classic discovery)
     * @return true if started successfully, false otherwise
     */
    fun startMonitoring(
        bluetoothAdapter: BluetoothAdapter,
        targetDeviceMac: String?,
        fallbackToClassic: (() -> Unit)? = null
    ): Boolean {
        this.bluetoothAdapter = bluetoothAdapter
        this.onFallbackToClassic = fallbackToClassic

        // Start GATT server (advertise our presence)
        val serverStarted = gattServer.start(bluetoothAdapter)
        if (!serverStarted) {
            Log.e(TAG, "Failed to start GATT server")
            fallbackToClassic?.invoke()
            return false
        }

        // Start GATT client (connect to remote device)
        if (targetDeviceMac != null) {
            // Direct connect using known MAC address
            try {
                val device = bluetoothAdapter.getRemoteDevice(targetDeviceMac)
                connectToDevice(device)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get remote device by MAC: ${e.message}")
                // Fall through to BLE scan
            }
        }

        // Fallback: Scan for device advertising FindDevice service
        return startBleScan(bluetoothAdapter)
    }

    /**
     * Stop BLE RSSI monitoring and cleanup resources.
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping BLE RSSI monitoring")

        stopBleScan()
        gattClient?.disconnect()
        gattClient = null
        gattServer.stop()

        handler.removeCallbacks(scanTimeoutRunnable)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (gattClient != null) {
            Log.w(TAG, "GATT client already connected, disconnecting first")
            gattClient?.disconnect()
        }

        gattClient = BleGattClient(
            context,
            onRssiUpdate = onRssiUpdate,
            onConnectionFailed = {
                Log.e(TAG, "GATT connection failed")
                onFallbackToClassic?.invoke()
            }
        )

        gattClient?.connect(device)
    }

    private fun startBleScan(bluetoothAdapter: BluetoothAdapter): Boolean {
        if (!checkPermissions()) {
            Log.e(TAG, "Missing BLE scan permissions")
            onFallbackToClassic?.invoke()
            return false
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner not available")
            onFallbackToClassic?.invoke()
            return false
        }

        try {
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleRssiUuids.FIND_DEVICE_SERVICE_UUID))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true

            // Set scan timeout
            handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT)

            Log.i(TAG, "BLE scan started")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting BLE scan: ${e.message}")
            onFallbackToClassic?.invoke()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan: ${e.message}")
            onFallbackToClassic?.invoke()
            return false
        }
    }

    private fun stopBleScan() {
        if (!isScanning) return

        try {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                isScanning = false
                handler.removeCallbacks(scanTimeoutRunnable)
                Log.i(TAG, "BLE scan stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan: ${e.message}")
        }
    }

    private fun checkPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            val hasScan = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            val hasAdvertise = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED

            return hasConnect && hasScan && hasAdvertise
        }
        return true
    }
}
