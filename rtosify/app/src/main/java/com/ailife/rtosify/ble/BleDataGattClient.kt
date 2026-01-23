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
import android.os.Handler
import android.os.Looper

/**
 * BLE GATT Client for RTOSify data transport.
 *
 * Connects to remote device's GATT server for bidirectional communication.
 * Client (phone) writes to RX characteristic and receives via TX characteristic notifications.
 */
class BleDataGattClient(
    private val context: Context,
    private val onDataReceived: (ByteArray) -> Unit,

    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onTransportActive: () -> Unit
) {

    companion object {
        private const val TAG = "BleDataGattClient"
        private const val MAX_MTU = 517
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val MAX_RETRIES = 100
        private const val STUCK_WRITE_TIMEOUT_MS = 5000L
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var currentMtu = 23 // Default BLE MTU
    private var isConnected = false
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    
    // Queues for outgoing data
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private val highPriorityQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isWriting = false
    private val lock = Any()
    private var serviceDiscoveryRetryCount = 0
    private var retryCount = 0
    private var lastWriteTime = 0L
    private var isRetryScheduled = false

    // ... (rest of class) ...

    /**
     * Send data to server via RX characteristic write.
     */
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun sendData(data: ByteArray, priority: Boolean = false): Boolean {
        if (!isConnected || rxCharacteristic == null) {
            Log.w(TAG, "Not connected, cannot send data")
            return false
        }

        // Calculate max payload size (MTU - 3 bytes ATT header overhead)
        val maxPayload = (currentMtu - 3).coerceAtLeast(20).coerceAtMost(512)

        if (data.size <= maxPayload) {
            if (priority) {
                highPriorityQueue.offer(data)
            } else {
                writeQueue.offer(data)
            }
        } else {
             var offset = 0
             while (offset < data.size) {
                 val end = minOf(offset + maxPayload, data.size)
                 val chunk = data.copyOfRange(offset, end)
                 if (priority) {
                     highPriorityQueue.offer(chunk)
                 } else {
                     writeQueue.offer(chunk)
                 }
                 offset = end
             }
        }
        
        processWriteQueue()
        return true
    }

    private fun processWriteQueue() {
        synchronized(lock) {
            if (!isConnected || isRetryScheduled) return

            if (isWriting) {
                // Check for stuck write
                val elapsed = System.currentTimeMillis() - lastWriteTime
                if (elapsed > STUCK_WRITE_TIMEOUT_MS) {
                    Log.w(TAG, "Write seems stuck ($elapsed ms), force-resetting isWriting and continuing")
                    isWriting = false
                } else {
                    return
                }
            }

            // Check high priority first
            var data = highPriorityQueue.peek()
            var isHighPriority = true
            
            if (data == null) {
                data = writeQueue.peek()
                isHighPriority = false
            }
            
            if (data == null) return

            val rx = rxCharacteristic ?: return

            if (!checkPermission()) {
                Log.e(TAG, "Missing permission to write characteristic")
                return
            }

            try {
                // Switch to NO_RESPONSE for better throughput
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                
                val success: Boolean
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val code = bluetoothGatt?.writeCharacteristic(
                        rx,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ) ?: BluetoothGatt.GATT_FAILURE
                    success = (code == BluetoothGatt.GATT_SUCCESS)
                } else {
                    @Suppress("DEPRECATION")
                    rx.value = data
                    @Suppress("DEPRECATION")
                    success = bluetoothGatt?.writeCharacteristic(rx) ?: false
                }
                
                if (success) {
                    // Remove from appropriate queue
                    if (isHighPriority) {
                        highPriorityQueue.poll()
                    } else {
                        writeQueue.poll()
                    }
                    isWriting = true
                    lastWriteTime = System.currentTimeMillis()
                    retryCount = 0
                    val priorityStr = if (isHighPriority) "High" else "Normal"
                    Log.d(TAG, "Write initiated ($priorityStr): ${data.size} bytes")
                    

                } else {
                    retryCount++
                    val priorityStr = if (isHighPriority) "High" else "Normal"
                    
                    // Persistent Retry: Do not disconnect. Just delay more and log.
                    if (retryCount <= MAX_RETRIES) {
                        if (!isRetryScheduled) {
                            isRetryScheduled = true
                            Log.w(TAG, "Write initiation failed ($priorityStr) - retry $retryCount/$MAX_RETRIES")
                            retryHandler.postDelayed({ 
                                synchronized(lock) { isRetryScheduled = false }
                                processWriteQueue() 
                            }, 50)
                        }
                    } else {
                        // Beyond MAX_RETRIES, we still don't disconnect, but we slow down significantly 
                        // to let the link clear up.
                        if (!isRetryScheduled) {
                            isRetryScheduled = true
                            Log.e(TAG, "Congestion critical ($priorityStr) - persistent retry active")
                            retryHandler.postDelayed({ 
                                synchronized(lock) { isRetryScheduled = false }
                                processWriteQueue() 
                            }, 500)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing characteristic: ${e.message}", e)
                disconnect()
            }
        }
    }
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server: ${gatt.device.address}")
                    isConnected = true
                    serviceDiscoveryRetryCount = 0
                    
                    // Request MTU increase for better throughput with a small delay
                    // to avoid issues with some stacks rejecting immediate requests
                    if (checkPermission()) {
                        retryHandler.postDelayed({
                            if (isConnected && bluetoothGatt != null) {
                                Log.d(TAG, "Requesting Connection Priority HIGH and MTU increase to $MAX_MTU")
                                bluetoothGatt?.requestConnectionPriority(android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                                bluetoothGatt?.requestMtu(MAX_MTU)
                            }
                        }, 300)
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
                
                // Discover services after MTU negotiation with delay
                if (checkPermission()) {
                    retryHandler.postDelayed({
                         if (isConnected && bluetoothGatt != null) {
                             Log.d(TAG, "Discovering services...")
                             gatt.discoverServices()
                         }
                    }, 500)
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
                    if (serviceDiscoveryRetryCount < 3) {
                        serviceDiscoveryRetryCount++
                        Log.w(TAG, "Data transport service not found, retrying discovery ($serviceDiscoveryRetryCount/3)...")
                        retryHandler.postDelayed({ 
                            if (isConnected && bluetoothGatt != null) {
                                gatt.discoverServices() 
                            }
                        }, 500)
                    } else {
                        Log.e(TAG, "Data transport service not found after retries")
                        disconnect()
                    }
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
            synchronized(lock) {
                isWriting = false
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write successful")
                onTransportActive()
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
     * Disconnect from GATT server.
     */
    fun disconnect() {
        // Always update state first to stop upstream callers
        if (isConnected) {
            isConnected = false
            onConnectionStateChanged(false)
        }
        
        txCharacteristic = null
        rxCharacteristic = null
        writeQueue.clear()

        if (!checkPermission()) {
            return
        }

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
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
