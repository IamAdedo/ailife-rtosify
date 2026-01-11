package com.ailife.rtosify.communication

import android.bluetooth.BluetoothSocket
import android.util.Log
import com.ailife.rtosify.ProtocolMessage
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
    }

    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    
    private val messageChannel = Channel<ProtocolMessage>(Channel.BUFFERED)
    private var receiveJob: Job? = null
    private var connected = false

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!socket.isConnected) {
                socket.connect()
            }
            setupStreams()
            connected = true
            startReceiving()
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

                    if (length !in 0..10_000_000) {
                        Log.e(TAG, "Invalid message size: $length")
                        break
                    }

                    // Read data
                    val bytes = ByteArray(length)
                    input.readFully(bytes)

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

    override suspend fun disconnect() {
        connected = false
        receiveJob?.cancel()
        
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

    override fun isConnected(): Boolean = connected && socket.isConnected

    override fun getTransportType(): String = "Bluetooth"
}
