package com.ailife.rtosify.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE GATT Client for RTOSify data transport.
 *
 * Connects to remote device's GATT server for bidirectional communication.
 * Client (phone) writes to RX characteristic and receives via TX characteristic notifications.
 */
class BleDataGattClient(
    private val context: Context,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {

    companion object {
        private const val TAG = "BleDataGattClient"
        private const val MAX_MTU = 512
        private const val CONNECT_TIMEOUT_MS = 10000L
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var currentMtu = 23 // Default BLE MTU
    private var isConnected = false
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    
    // Queue for outgoing data
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isWriting = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server: ${gatt.device.address}")
                    isConnected = true
                    
                    // Request MTU increase for better throughput
                    if (checkPermission()) {
                        gatt.requestMtu(MAX_MTU)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected from GATT server: ${gatt.device.address}")
                    isConnected = false
                    txCharacteristic = null
                    rxCharacteristic = null
                    onConnectionStateChanged(false)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to: $mtu")
                currentMtu = mtu
                
                // Discover services after MTU negotiation
                if (checkPermission()) {
                    gatt.discoverServices()
                }
            } else {
                Log.w(TAG, "MTU change failed, using default MTU")
                if (checkPermission()) {
                    gatt.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BleRssiUuids.DATA_TRANSPORT_SERVICE_UUID)
                if (service != null) {
                    txCharacteristic = service.getCharacteristic(BleRssiUuids.DATA_TX_CHARACTERISTIC_UUID)
                    rxCharacteristic = service.getCharacteristic(BleRssiUuids.DATA_RX_CHARACTERISTIC_UUID)
                    
                    if (txCharacteristic != null && rxCharacteristic != null) {
                        Log.i(TAG, "Data transport service found, enabling notifications")
                        enableTxNotifications()
                    } else {
                        Log.e(TAG, "Required characteristics not found")
                        disconnect()
                    }
                } else {
                    Log.e(TAG, "Data transport service not found")
                    disconnect()
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                disconnect()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BleRssiUuids.DATA_TX_CHARACTERISTIC_UUID) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    Log.d(TAG, "Received data: ${data.size} bytes")
                    onDataReceived(data)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isWriting = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write successful: ${characteristic.value?.size ?: 0} bytes")
                // Process next write in queue
                processWriteQueue()
            } else {
                Log.e(TAG, "Write failed with status: $status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (descriptor.uuid == BleRssiUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                    Log.i(TAG, "TX notifications enabled successfully")
                    onConnectionStateChanged(true)
                }
            } else {
                Log.e(TAG, "Descriptor write failed with status: $status")
                disconnect()
            }
        }
    }

    /**
     * Connect to remote device's GATT server.
     */
    fun connect(device: BluetoothDevice): Boolean {
        if (!checkPermission()) {
            Log.e(TAG, "Missing required BLE permissions")
            return false
        }

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
                return false
            }

            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception connecting to GATT: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to GATT: ${e.message}")
            return false
        }
    }

    /**
     * Enable notifications on TX characteristic to receive data.
     */
    private fun enableTxNotifications() {
        val tx = txCharacteristic ?: return
        
        if (!checkPermission()) {
            Log.e(TAG, "Missing permission to enable notifications")
            return
        }

        try {
            // Enable local notifications
            bluetoothGatt?.setCharacteristicNotification(tx, true)

            // Write CCCD to enable remote notifications
            val descriptor = tx.getDescriptor(BleRssiUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt?.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    bluetoothGatt?.writeDescriptor(descriptor)
                }
            } else {
                Log.e(TAG, "CCCD descriptor not found")
                disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling notifications: ${e.message}")
            disconnect()
        }
    }

    /**
     * Send data to server via RX characteristic write.
     */
    fun sendData(data: ByteArray): Boolean {
        if (!isConnected || rxCharacteristic == null) {
            Log.w(TAG, "Not connected, cannot send data")
            return false
        }

        // Add to queue
        writeQueue.offer(data)
        processWriteQueue()
        return true
    }

    private fun processWriteQueue() {
        if (isWriting || !isConnected) {
            return
        }

        val data = writeQueue.poll() ?: return
        val rx = rxCharacteristic ?: return

        if (!checkPermission()) {
            Log.e(TAG, "Missing permission to write characteristic")
            return
        }

        try {
            // Calculate max payload size (MTU - 3 bytes ATT header overhead)
            val maxPayload = (currentMtu - 3).coerceAtLeast(20) // Min 20 bytes even if MTU negotiation fails
            
            if (data.size <= maxPayload) {
                // Data fits in single packet - write directly
                isWriting = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt?.writeCharacteristic(
                        rx,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                } else {
                    @Suppress("DEPRECATION")
                    rx.value = data
                    @Suppress("DEPRECATION")
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    bluetoothGatt?.writeCharacteristic(rx)
                }
                Log.d(TAG, "Writing ${data.size} bytes")
            } else {
                // Data exceeds MTU - chunk it properly
                Log.d(TAG, "Chunking ${data.size} bytes with MTU $currentMtu (max payload: $maxPayload)")
                
                var offset = 0
                var chunkCount = 0
                
                while (offset < data.size) {
                    val end = minOf(offset + maxPayload, data.size)
                    val chunk = data.copyOfRange(offset, end)
                    writeQueue.offer(chunk)
                    offset = end
                    chunkCount++
                }
                
                Log.d(TAG, "Split into $chunkCount chunks")
                
                // Process first chunk now
                processWriteQueue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing characteristic: ${e.message}", e)
            isWriting = false
        }
    }

    /**
     * Disconnect from GATT server.
     */
    fun disconnect() {
        if (!checkPermission()) {
            return
        }

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            isConnected = false
            txCharacteristic = null
            rxCharacteristic = null
            writeQueue.clear()
            Log.i(TAG, "Disconnected from GATT server")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from GATT: ${e.message}")
        }
    }

    fun isConnected(): Boolean = isConnected && bluetoothGatt != null

    private fun checkPermission(): Boolean {
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
