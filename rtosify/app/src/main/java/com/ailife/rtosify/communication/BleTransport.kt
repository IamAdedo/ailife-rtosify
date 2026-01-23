package com.ailife.rtosify.communication

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.ailife.rtosify.ProtocolMessage
import com.ailife.rtosify.ble.BleDataGattClient
import com.ailife.rtosify.ble.BleDataGattServer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.ByteArrayOutputStream

/**
 * BLE implementation of CommunicationTransport.
 * 
 * Provides bidirectional communication over Bluetooth Low Energy using GATT characteristics.
 * Can operate as either server (watch/companion) or client (phone).
 */
class BleTransport(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val isServer: Boolean,
    private val targetDevice: BluetoothDevice? = null
) : CommunicationTransport {

    companion object {
        private const val TAG = "BleTransport"
        private const val KEEPALIVE_INTERVAL = 5000L  // 5 seconds
        private const val KEEPALIVE_TIMEOUT = 15000L  // 15 seconds
        private const val MESSAGE_DELIMITER = 0xFF.toByte()
        private const val MESSAGE_START_MARKER = 0xFE.toByte()
    }

    private var gattServer: BleDataGattServer? = null
    private var gattClient: BleDataGattClient? = null
    
    private val messageChannel = Channel<ProtocolMessage>(Channel.BUFFERED)
    private var keepaliveJob: Job? = null
    @Volatile private var connected = false
    @Volatile private var lastReceiveTime = 0L
    
    private var connectedDevice: BluetoothDevice? = null
    private val receivedChunks = ByteArrayOutputStream()

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isServer) {
                connectAsServer()
            } else {
                connectAsClient()
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE connection failed", e)
            false
        }
    }

    private suspend fun connectAsServer(): Boolean {
        Log.d(TAG, "Starting BLE server...")
        
        gattServer = BleDataGattServer(
            context = context,
            onDataReceived = { data -> handleReceivedData(data) },
            onClientConnected = { device ->
                connectedDevice = device
                connected = true
                lastReceiveTime = System.currentTimeMillis()
                startKeepalive()
                Log.i(TAG, "BLE server: Client connected ${device.address}")
            },
            onClientDisconnected = { device ->
                if (connectedDevice?.address == device.address) {
                    connected = false
                    connectedDevice = null
                    stopKeepalive()
                    Log.i(TAG, "BLE server: Client disconnected ${device.address}")
                }
            }
        )
        
        val started = gattServer?.start(bluetoothAdapter) ?: false
        if (started) {
            Log.i(TAG, "BLE server started successfully, waiting for client...")
        }
        return started
    }

    private suspend fun connectAsClient(): Boolean {
        if (targetDevice == null) {
            Log.e(TAG, "Cannot connect as client: no target device specified")
            return false
        }
        
        Log.d(TAG, "Connecting to BLE server: ${targetDevice.address}")
        
        var connectionEstablished = false
        
        gattClient = BleDataGattClient(
            context = context,
            onDataReceived = { data -> handleReceivedData(data) },
            onConnectionStateChanged = { isConnected ->
                connected = isConnected
                if (isConnected) {
                    connectedDevice = targetDevice
                    lastReceiveTime = System.currentTimeMillis()
                    startKeepalive()
                    connectionEstablished = true
                    Log.i(TAG, "BLE client: Connected to server")
                } else {
                    connectedDevice = null
                    stopKeepalive()
                    Log.i(TAG, "BLE client: Disconnected from server")
                }
            }
        )
        
        val connectInitiated = gattClient?.connect(targetDevice) ?: false
        if (!connectInitiated) {
            return false
        }
        
        // Wait for connection with timeout
        val startTime = System.currentTimeMillis()
        while (!connectionEstablished && System.currentTimeMillis() - startTime < 10000) {
            delay(100)
        }
        
        return connectionEstablished
    }

    private fun handleReceivedData(data: ByteArray) {
        lastReceiveTime = System.currentTimeMillis()
        
        // Check for keepalive marker
        if (data.size == 1 && data[0] == MESSAGE_DELIMITER) {
            Log.d(TAG, "Keepalive received")
            return
        }
        
        try {
            // Accumulate chunks
            receivedChunks.write(data)
            
            // Process buffer
            val currentData = receivedChunks.toByteArray()
            var startIndex = 0
            
            for (i in currentData.indices) {
                if (currentData[i] == MESSAGE_DELIMITER) {
                    // Found a complete message
                    if (i > startIndex) {
                        val messageData = currentData.copyOfRange(startIndex, i)
                        val json = String(messageData, Charsets.UTF_8)
                        
                        Log.d(TAG, "Received complete message: ${messageData.size} bytes")
                        
                        try {
                            val message = ProtocolMessage.fromJson(json)
                            CoroutineScope(Dispatchers.IO).launch {
                                messageChannel.send(message)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse message JSON", e)
                        }
                    }
                    startIndex = i + 1
                }
            }
            
            // Reset buffer and keep remaining incomplete data
            receivedChunks.reset()
            if (startIndex < currentData.size) {
                val remaining = currentData.copyOfRange(startIndex, currentData.size)
                receivedChunks.write(remaining)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing received data", e)
            receivedChunks.reset()
        }
    }

    private fun startKeepalive() {
        stopKeepalive()
        
        // Monitor timeout
        CoroutineScope(Dispatchers.IO).launch {
            while (isActive && connected) {
                delay(1000)
                val elapsed = System.currentTimeMillis() - lastReceiveTime
                if (elapsed > KEEPALIVE_TIMEOUT) {
                    Log.w(TAG, "BLE keepalive timeout: ${elapsed}ms since last receive - disconnecting")
                    disconnect()
                    break
                }
            }
        }

        // Send keepalive pings
        keepaliveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && connected) {
                delay(KEEPALIVE_INTERVAL)
                
                try {
                    // Send keepalive marker
                    val keepaliveData = byteArrayOf(MESSAGE_DELIMITER)
                    val sent = if (isServer) {
                        gattServer?.sendData(keepaliveData, priority = true) ?: false
                    } else {
                        gattClient?.sendData(keepaliveData, priority = true) ?: false
                    }
                    
                    if (!sent) {
                        Log.w(TAG, "Failed to send keepalive")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Keepalive send failed: ${e.message}")
                    disconnect()
                    break
                }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    override suspend fun disconnect() {
        connected = false
        stopKeepalive()
        
        try {
            messageChannel.close()
            gattServer?.stop()
            gattClient?.disconnect()
            gattServer = null
            gattClient = null
            connectedDevice = null
            receivedChunks.reset()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
        
        Log.d(TAG, "BLE disconnected")
    }

    override suspend fun send(message: ProtocolMessage): Boolean = withContext(Dispatchers.IO) {
        if (!connected) {
            Log.e(TAG, "Cannot send: not connected")
            return@withContext false
        }

        try {
            // Serialize message and add delimiter
            val jsonBytes = message.toBytes()
            val dataWithDelimiter = jsonBytes + MESSAGE_DELIMITER
            
            Log.d(TAG, "Sending message: ${dataWithDelimiter.size} bytes")
            
            // Send via appropriate GATT role
            val success = if (isServer) {
                gattServer?.sendData(dataWithDelimiter) ?: false
            } else {
                gattClient?.sendData(dataWithDelimiter) ?: false
            }
            
            if (!success) {
                Log.e(TAG, "Failed to send message")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            false
        }
    }

    override fun receive(): Flow<ProtocolMessage> = messageChannel.receiveAsFlow()

    override fun isConnected(): Boolean {
        if (!connected) return false
        
        // Also check underlying GATT connection
        val gattConnected = if (isServer) {
            gattServer?.isConnected() ?: false
        } else {
            gattClient?.isConnected() ?: false
        }
        
        // Check keepalive timeout
        val elapsed = System.currentTimeMillis() - lastReceiveTime
        return gattConnected && elapsed <= KEEPALIVE_TIMEOUT
    }

    override fun getTransportType(): String = "BLE"

    override fun getRemoteDeviceName(): String? = try {
        connectedDevice?.name ?: connectedDevice?.address
    } catch (e: Exception) {
        null
    }

    override fun getRemoteAddress(): String? = try {
        connectedDevice?.address
    } catch (e: Exception) {
        null
    }
}
