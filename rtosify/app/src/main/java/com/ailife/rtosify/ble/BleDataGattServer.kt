package com.ailife.rtosify.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE GATT Server for RTOSify data transport.
 *
 * Handles bidirectional communication via BLE GATT characteristics.
 * Server (watch/companion) receives data via RX characteristic writes 
 * and sends data via TX characteristic notifications.
 */
class BleDataGattServer(
    private val context: Context,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onClientConnected: (BluetoothDevice) -> Unit,
    private val onClientDisconnected: (BluetoothDevice) -> Unit
) {

    companion object {
        private const val TAG = "BleDataGattServer"
        private const val MAX_MTU = 512
    }

    private var bluetoothGattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private var connectedDevice: BluetoothDevice? = null
    private var currentMtu = 23 // Default BLE MTU
    
    // Queue for outgoing data
    private val txQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isSendingNotification = false

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: BluetoothDevice?,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Client connected: ${device?.address}")
                    connectedDevice = device
                    device?.let { onClientConnected(it) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Client disconnected: ${device?.address}")
                    if (connectedDevice?.address == device?.address) {
                        connectedDevice = null
                        currentMtu = 23
                    }
                    device?.let { onClientDisconnected(it) }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.d(TAG, "MTU changed to: $mtu for device ${device?.address}")
            currentMtu = mtu
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic?.uuid == BleRssiUuids.DATA_TX_CHARACTERISTIC_UUID) {
                // Send empty response for read requests (data is sent via notifications)
                val response = ByteArray(0)
                if (checkPermission()) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        response
                    )
                }
            } else {
                if (checkPermission()) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null
                    )
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic?.uuid == BleRssiUuids.DATA_RX_CHARACTERISTIC_UUID) {
                value?.let {
                    Log.d(TAG, "Received data: ${it.size} bytes from ${device?.address}")
                    onDataReceived(it)
                }
                
                if (responseNeeded && checkPermission()) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                }
            } else {
                if (responseNeeded && checkPermission()) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null
                    )
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor?.uuid == BleRssiUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                val notificationsEnabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                Log.d(TAG, "Notifications ${if (notificationsEnabled) "enabled" else "disabled"} for ${device?.address}")
                
                if (responseNeeded && checkPermission()) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value
                    )
                }
                
                // If notifications enabled and we have queued data, start sending
                if (notificationsEnabled) {
                    processTxQueue()
                }
            } else {
                if (responseNeeded && checkPermission()) {
                    bluetoothGattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null
                    )
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            isSendingNotification = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Continue sending if more data in queue
                processTxQueue()
            } else {
                Log.e(TAG, "Notification send failed with status: $status")
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
     */
    fun start(bluetoothAdapter: BluetoothAdapter): Boolean {
        if (!checkPermission()) {
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

            // Create data transport service
            val service = BluetoothGattService(
                BleRssiUuids.DATA_TRANSPORT_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // TX Characteristic (server sends via notifications)
            val txCharacteristic = BluetoothGattCharacteristic(
                BleRssiUuids.DATA_TX_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            
            // Add CCCD descriptor for notifications
            val txDescriptor = BluetoothGattDescriptor(
                BleRssiUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            txCharacteristic.addDescriptor(txDescriptor)

            // RX Characteristic (server receives via writes)
            val rxCharacteristic = BluetoothGattCharacteristic(
                BleRssiUuids.DATA_RX_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            service.addCharacteristic(txCharacteristic)
            service.addCharacteristic(rxCharacteristic)
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
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(BleRssiUuids.DATA_TRANSPORT_SERVICE_UUID))
                .build()

            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)

            Log.i(TAG, "BLE GATT data server started")
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
     * Send data to connected client via TX characteristic notifications.
     */
    fun sendData(data: ByteArray): Boolean {
        if (connectedDevice == null) {
            Log.w(TAG, "No client connected, cannot send data")
            return false
        }

        // Add to queue
        txQueue.offer(data)
        processTxQueue()
        return true
    }

    private fun processTxQueue() {
        if (isSendingNotification || connectedDevice == null) {
            return
        }

        val data = txQueue.poll() ?: return

        if (!checkPermission()) {
            Log.e(TAG, "Missing permission to send notification")
            return
        }

        val service = bluetoothGattServer?.getService(BleRssiUuids.DATA_TRANSPORT_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(BleRssiUuids.DATA_TX_CHARACTERISTIC_UUID)

        if (characteristic == null) {
            Log.e(TAG, "TX characteristic not found")
            return
        }

        try {
            // Calculate max payload size (MTU - 3 bytes ATT header overhead)
            val maxPayload = (currentMtu - 3).coerceAtLeast(20) // Min 20 bytes even if MTU negotiation fails
            
            if (data.size <= maxPayload) {
                // Data fits in single packet - send directly
                characteristic.value = data
                isSendingNotification = true
                val success = bluetoothGattServer?.notifyCharacteristicChanged(
                    connectedDevice,
                    characteristic,
                    false
                ) ?: false
                
                if (!success) {
                    isSendingNotification = false
                    Log.e(TAG, "Failed to send notification")
                } else {
                    Log.d(TAG, "Sent ${data.size} bytes")
                }
            } else {
                // Data exceeds MTU - chunk it properly
                Log.d(TAG, "Chunking ${data.size} bytes with MTU $currentMtu (max payload: $maxPayload)")
                
                var offset = 0
                var chunkCount = 0
                
                while (offset < data.size) {
                    val end = minOf(offset + maxPayload, data.size)
                    val chunk = data.copyOfRange(offset, end)
                    txQueue.offer(chunk)
                    offset = end
                    chunkCount++
                }
                
                Log.d(TAG, "Split into $chunkCount chunks")
                
                // Process first chunk now
                processTxQueue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending notification: ${e.message}", e)
            isSendingNotification = false
        }
    }

    /**
     * Stop GATT server and advertising.
     */
    fun stop() {
        try {
            if (isAdvertising && bleAdvertiser != null && checkPermission()) {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                Log.i(TAG, "BLE advertising stopped")
            }

            if (checkPermission()) {
                connectedDevice?.let { device ->
                    bluetoothGattServer?.cancelConnection(device)
                }
                bluetoothGattServer?.close()
                bluetoothGattServer = null
                Log.i(TAG, "BLE GATT server closed")
            }

            connectedDevice = null
            txQueue.clear()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE server: ${e.message}")
        }
    }

    fun isConnected(): Boolean = connectedDevice != null

    private fun checkPermission(): Boolean {
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
