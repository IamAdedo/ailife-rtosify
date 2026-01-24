package com.ailife.rtosifycompanion.ble

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

    private val onClientDisconnected: (BluetoothDevice) -> Unit,
    private val onTransportActive: () -> Unit
) {

    companion object {
        private const val TAG = "BleDataGattServer"
        private const val MAX_MTU = 517
        private const val MAX_RETRIES = 100
        private const val STUCK_WRITE_TIMEOUT_MS = 5000L
    }

    private var bluetoothGattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private var connectedDevice: BluetoothDevice? = null
    private var currentMtu = 23 // Default BLE MTU
    
    // Queue for outgoing data
    private val txQueue = ConcurrentLinkedQueue<ByteArray>()
    private val highPriorityTxQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isSendingNotification = false
    private val lock = Any()
    private var retryCount = 0
    private var lastNotifyTime = 0L
    private var isRetryScheduled = false

    // ...

    /**
     * Send data to connected client via TX characteristic notifications.
     */
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun sendData(data: ByteArray, priority: Boolean = false): Boolean {
        if (connectedDevice == null) {
            Log.w(TAG, "No client connected, cannot send data")
            return false
        }

        // Calculate max payload size (MTU - 3 bytes ATT header overhead)
        // Calculate max payload size (MTU - 3 bytes ATT header overhead)
        // Clamp to 500 to be safe and avoid boundary fragmentation issues even if MTU is 512
        val maxPayload = (currentMtu - 3).coerceAtLeast(20).coerceAtMost(500)

        if (data.size <= maxPayload) {
            if (priority) {
                highPriorityTxQueue.offer(data)
            } else {
                txQueue.offer(data)
            }
        } else {
             var offset = 0
             while (offset < data.size) {
                 val end = minOf(offset + maxPayload, data.size)
                 val chunk = data.copyOfRange(offset, end)
                 if (priority) {
                     highPriorityTxQueue.offer(chunk)
                 } else {
                     txQueue.offer(chunk)
                 }
                 offset = end
             }
        }
        
        processTxQueue()
        return true
    }

    private fun processTxQueue() {
        synchronized(lock) {
            if (connectedDevice == null || isRetryScheduled) return

            if (isSendingNotification) {
                // Check for stuck notification
                val elapsed = System.currentTimeMillis() - lastNotifyTime
                if (elapsed > STUCK_WRITE_TIMEOUT_MS) {
                    Log.w(TAG, "Notification seems stuck ($elapsed ms), force-resetting isSendingNotification and continuing")
                    isSendingNotification = false
                } else {
                    return
                }
            }

            // Priority Check
            var data = highPriorityTxQueue.peek()
            var isHighPriority = true
            
            if (data == null) {
                data = txQueue.peek()
                isHighPriority = false
            }

            if (data == null) return

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
                characteristic.value = data
                isSendingNotification = true
                val success = bluetoothGattServer?.notifyCharacteristicChanged(
                    connectedDevice,
                    characteristic,
                    false
                ) ?: false
                
                if (success) {
                    lastNotifyTime = System.currentTimeMillis()
                    if (isHighPriority) {
                        highPriorityTxQueue.poll()
                    } else {
                        txQueue.poll()
                    }
                    retryCount = 0
                    val priorityStr = if (isHighPriority) "High" else "Normal"
                    Log.d(TAG, "Sent ($priorityStr): ${data.size} bytes")
                    

                } else {
                    retryCount++
                    if (retryCount <= MAX_RETRIES) {
                        if (!isRetryScheduled) {
                            isRetryScheduled = true
                            Log.w(TAG, "Failed to send notification - retry $retryCount/$MAX_RETRIES")
                            retryHandler.postDelayed({ 
                                synchronized(lock) { isRetryScheduled = false }
                                processTxQueue() 
                            }, 50)
                        }
                    } else {
                        // Persistent Retry: Keep trying at longer intervals instead of disconnecting
                        if (!isRetryScheduled) {
                            isRetryScheduled = true
                            Log.e(TAG, "Congestion critical - persistent retry active")
                            retryHandler.postDelayed({ 
                                synchronized(lock) { isRetryScheduled = false }
                                processTxQueue() 
                            }, 500)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending notification: ${e.message}", e)
                isSendingNotification = false
                disconnectAndReset()
            }
        }
    }

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
            synchronized(lock) {
                isSendingNotification = false
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Continue sending if more data in queue
                onTransportActive()
                processTxQueue()
            } else {
                Log.e(TAG, "Notification send failed with status: $status")
                disconnectAndReset()
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
            Log.w(TAG, "Device does not support multiple advertisements. Attempting to proceed with single advertisement if possible...")
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

    private fun disconnectAndReset() {
        val device = connectedDevice
        if (device != null) {
            try {
                bluetoothGattServer?.cancelConnection(device)
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling connection", e)
            }
            connectedDevice = null
            onClientDisconnected(device)
        }
        txQueue.clear()
        isSendingNotification = false
    }
}
