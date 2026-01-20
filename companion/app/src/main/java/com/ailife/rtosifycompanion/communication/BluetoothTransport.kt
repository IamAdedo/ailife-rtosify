package com.ailife.rtosifycompanion.communication

import android.bluetooth.BluetoothSocket
import android.util.Log
import com.ailife.rtosifycompanion.ProtocolMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Bluetooth implementation of CommunicationTransport.
 * Wraps a BluetoothSocket and provides Flow-based message receiving.
 */
class BluetoothTransport(
    private val socket: BluetoothSocket
) : CommunicationTransport {

    companion object {
        private const val TAG = "BluetoothTransport"
        private const val KEEPALIVE_INTERVAL = 5000L  // 5 seconds (matches phone app)
        private const val KEEPALIVE_TIMEOUT = 15000L  // 15 seconds (matches phone app)
    }

    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    
    private val messageChannel = Channel<ProtocolMessage>(Channel.BUFFERED)
    private var receiveJob: Job? = null
    private var keepaliveJob: Job? = null
    @Volatile private var connected = false
    @Volatile private var lastReceiveTime = 0L

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!socket.isConnected) {
                socket.connect()
            }
            setupStreams()
            connected = true
            lastReceiveTime = System.currentTimeMillis()
            startReceiving()
            startKeepalive()
            Log.d(TAG, "Bluetooth connected")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth connection failed", e)
            false
        }
    }

    private fun setupStreams() {
        inputStream = DataInputStream(socket.inputStream)
        outputStream = DataOutputStream(socket.outputStream)
    }

    private fun startReceiving() {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val input = inputStream ?: return@launch
                
                while (isActive && connected) {
                    // Read message length
                    val length = try {
                        input.readInt()
                    } catch (e: Exception) {
                        Log.d(TAG, "Socket closed or error reading length: ${e.message}")
                        break
                    }

                    if (length == -1) {
                        // Keepalive received
                        lastReceiveTime = System.currentTimeMillis()
                        continue
                    }
                    
                    if (length !in 0..10_000_000) {
                        Log.e(TAG, "Invalid message size: $length")
                        break
                    }

                    // Read data
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    
                    // Update receive time for keepalive
                    lastReceiveTime = System.currentTimeMillis()

                    // Parse ProtocolMessage
                    val json = String(bytes, Charsets.UTF_8)
                    val message = ProtocolMessage.fromJson(json)
                    messageChannel.send(message)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Receive error", e)
            } finally {
                disconnect()
            }
        }
    }
    
    private fun startKeepalive() {
        // Monitor timeout in a separate job that doesn't do I/O
        CoroutineScope(Dispatchers.IO).launch {
            while (isActive && connected) {
                delay(1000) // Check every second
                val elapsed = System.currentTimeMillis() - lastReceiveTime
                if (elapsed > KEEPALIVE_TIMEOUT) {
                    Log.w(TAG, "BT keepalive timeout: ${elapsed}ms since last receive - forcing disconnect")
                    disconnect()
                    break
                }
            }
        }

        // Send keepalive pings in another job
        keepaliveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && connected) {
                delay(KEEPALIVE_INTERVAL)
                
                // Send keepalive ping (-1 message length)
                try {
                    val output = outputStream ?: break
                    synchronized(output) {
                        output.writeInt(-1)
                        output.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Keepalive send failed, link likely dead: ${e.message}")
                    disconnect()
                    break
                }
            }
        }
    }

    override suspend fun disconnect() {
        connected = false
        receiveJob?.cancel()
        keepaliveJob?.cancel()
        
        try {
            messageChannel.close()
            inputStream?.close()
            outputStream?.close()
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
        
        inputStream = null
        outputStream = null
        
        Log.d(TAG, "Bluetooth disconnected")
    }

    override suspend fun send(message: ProtocolMessage): Boolean = withContext(Dispatchers.IO) {
        if (!connected) {
            Log.e(TAG, "Cannot send: not connected")
            return@withContext false
        }

        val output = outputStream ?: return@withContext false

        try {
            // Serialize message
            val jsonBytes = message.toBytes()
            
            // Send: length + data
            synchronized(output) {
                output.writeInt(jsonBytes.size)
                output.write(jsonBytes)
                output.flush()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            disconnect()
            false
        }
    }

    override fun receive(): Flow<ProtocolMessage> = messageChannel.receiveAsFlow()

    override fun isConnected(): Boolean {
        if (!connected || !socket.isConnected) return false
        // Proactive check: if we haven't received anything in KEEPALIVE_TIMEOUT, consider it dead
        val elapsed = System.currentTimeMillis() - lastReceiveTime
        return elapsed <= KEEPALIVE_TIMEOUT
    }

    override fun getTransportType(): String = "Bluetooth"

    override fun getRemoteDeviceName(): String? = try {
        socket.remoteDevice?.name ?: socket.remoteDevice?.address
    } catch (e: Exception) {
        null
    }

    override fun getRemoteAddress(): String? = try {
        socket.remoteDevice?.address
    } catch (e: Exception) {
        null
    }
}
