package com.iamadedo.phoneapp.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * BLE GATT Server for RTOSify FindDevice feature.
 *
 * Advertises a minimal GATT service to allow remote devices to connect
 * and read RSSI for distance monitoring.
 */
class BleGattServer(private val context: Context) {

    companion object {
        private const val TAG = "BleGattServer"
    }

    private var bluetoothGattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: android.bluetooth.BluetoothDevice?,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Remote device connected: ${device?.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Remote device disconnected: ${device?.address}")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            // Respond with minimal data (device is identifiable by MAC address)
            val response = ByteArray(1) { 0x01 }
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    response
                )
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature not supported"
                else -> "Unknown error ($errorCode)"
            }
            Log.e(TAG, "BLE advertising failed: $reason")
        }
    }

    /**
     * Start GATT server and BLE advertising.
     *
     * @param bluetoothAdapter Bluetooth adapter instance
     * @return true if started successfully, false otherwise
     */
    fun start(bluetoothAdapter: BluetoothAdapter): Boolean {
        if (!checkPermissions()) {
            Log.e(TAG, "Missing required BLE permissions")
            return false
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.e(TAG, "BLE advertising not supported on this device")
            return false
        }

        try {
            // Create GATT server
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)

            if (bluetoothGattServer == null) {
                Log.e(TAG, "Failed to open GATT server")
                return false
            }

            // Add minimal GATT service
            val service = BluetoothGattService(
                BleRssiUuids.FIND_DEVICE_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            val characteristic = BluetoothGattCharacteristic(
                BleRssiUuids.RSSI_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            service.addCharacteristic(characteristic)
            bluetoothGattServer?.addService(service)

            // Start BLE advertising
            bleAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
            if (bleAdvertiser == null) {
                Log.e(TAG, "BLE advertiser not available")
                bluetoothGattServer?.close()
                bluetoothGattServer = null
                return false
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(true)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(BleRssiUuids.FIND_DEVICE_SERVICE_UUID))
                .build()

            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)

            Log.i(TAG, "BLE GATT server started")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting BLE server: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE server: ${e.message}")
            return false
        }
    }

    /**
     * Stop GATT server and BLE advertising.
     */
    fun stop() {
        try {
            if (isAdvertising && bleAdvertiser != null) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bleAdvertiser?.stopAdvertising(advertiseCallback)
                    isAdvertising = false
                    Log.i(TAG, "BLE advertising stopped")
                }
            }

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothGattServer?.close()
                bluetoothGattServer = null
                Log.i(TAG, "BLE GATT server closed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE server: ${e.message}")
        }
    }

    private fun checkPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            val hasAdvertise = ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED

            return hasConnect && hasAdvertise
        }
        return true
    }
}
