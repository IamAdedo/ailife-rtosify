package com.iamadedo.phoneapp.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * BLE GATT Client for RSSI monitoring.
 *
 * Connects to remote device's GATT server and periodically reads RSSI
 * for distance estimation in FindDevice feature.
 */
class BleGattClient(
    private val context: Context,
    private val onRssiUpdate: (Int) -> Unit,
    private val onConnectionFailed: () -> Unit
) {

    companion object {
        private const val TAG = "BleGattClient"
        private const val RSSI_READ_INTERVAL = 1000L // 1 second (1Hz)
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY = 2000L // 2 seconds
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var targetDevice: BluetoothDevice? = null
    private var isRunning = false

    private val rssiReadRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            try {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val success = bluetoothGatt?.readRemoteRssi() ?: false
                    if (success) {
                        handler.postDelayed(this, RSSI_READ_INTERVAL)
                    } else {
                        Log.w(TAG, "Failed to request RSSI read")
                        handler.postDelayed(this, RSSI_READ_INTERVAL)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading RSSI: ${e.message}")
                handler.postDelayed(this, RSSI_READ_INTERVAL)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server: ${gatt.device.address}")
                    retryCount = 0

                    // Start periodic RSSI reading
                    handler.post(rssiReadRunnable)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected from GATT server: ${gatt.device.address}")
                    handler.removeCallbacks(rssiReadRunnable)

                    if (isRunning && retryCount < MAX_RETRY_COUNT) {
                        // Attempt reconnection
                        retryCount++
                        Log.i(TAG, "Attempting reconnection ($retryCount/$MAX_RETRY_COUNT)")
                        handler.postDelayed({
                            reconnect()
                        }, RETRY_DELAY)
                    } else if (retryCount >= MAX_RETRY_COUNT) {
                        Log.e(TAG, "Max retry count reached, giving up")
                        onConnectionFailed()
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "RSSI updated: $rssi dBm")
                onRssiUpdate(rssi)
            } else {
                Log.w(TAG, "Failed to read RSSI, status: $status")
            }
        }
    }

    /**
     * Connect to remote device's GATT server.
     *
     * @param device Target Bluetooth device
     */
    fun connect(device: BluetoothDevice) {
        if (!checkPermissions()) {
            Log.e(TAG, "Missing required BLE permissions")
            onConnectionFailed()
            return
        }

        targetDevice = device
        isRunning = true
        retryCount = 0

        try {
            Log.i(TAG, "Connecting to device: ${device.address}")

            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                device.connectGatt(context, false, gattCallback)
            }

            if (bluetoothGatt == null) {
                Log.e(TAG, "Failed to connect to GATT server")
                onConnectionFailed()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception connecting to GATT: ${e.message}")
            onConnectionFailed()
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to GATT: ${e.message}")
            onConnectionFailed()
        }
    }

    /**
     * Disconnect from GATT server and stop RSSI monitoring.
     */
    fun disconnect() {
        isRunning = false
        handler.removeCallbacks(rssiReadRunnable)

        try {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
                Log.i(TAG, "Disconnected from GATT server")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from GATT: ${e.message}")
        }
    }

    private fun reconnect() {
        disconnect()
        targetDevice?.let { device ->
            handler.postDelayed({
                connect(device)
            }, 500)
        }
    }

    private fun checkPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            return hasConnect
        }
        return true
    }
}
