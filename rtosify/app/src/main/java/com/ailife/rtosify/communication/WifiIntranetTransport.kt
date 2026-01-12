package com.ailife.rtosify.communication

import android.util.Log
import com.ailife.rtosify.ProtocolMessage
import com.ailife.rtosify.security.EncryptionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * WiFi Intranet transport using mDNS discovery and TCP sockets.
 * All messages are encrypted using EncryptionManager.
 */
class WifiIntranetTransport(
    private val remoteMac: String,
    private val localMac: String,
    private val deviceName: String,
    private val encryptionManager: EncryptionManager,
    private val mdnsDiscovery: MdnsDiscovery,
    private val isServer: Boolean = false
) : CommunicationTransport {

    companion object {
        private const val TAG = "WifiTransport"
    }

    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    
    private val messageChannel = Channel<ProtocolMessage>(Channel.BUFFERED)
    private var receiveJob: Job? = null
    private var connected = false

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isServer) {
                connectAsServer()
            } else {
                connectAsClient()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            false
        }
    }

    private suspend fun connectAsServer(): Boolean {
        Log.d(TAG, "Starting server on dynamic port...")
        serverSocket = ServerSocket(0)
        val localPort = serverSocket!!.localPort
        Log.d(TAG, "Server listening on port $localPort")

        // Register mDNS service with our own MAC
        mdnsDiscovery.registerService(localMac, deviceName, localPort)
        
        try {
            // Accept connection (blocking)
            socket = serverSocket?.accept()
            socket?.let {
                setupStreams(it)
                connected = true
                startReceiving()
                Log.d(TAG, "Client connected from ${it.inetAddress}")
                return true
            }
        } finally {
            mdnsDiscovery.stop()
        }
        return false
    }

    private suspend fun connectAsClient(): Boolean {
        Log.d(TAG, "Attempting to discover service for MAC: $remoteMac")
        
        // Start discovery just in case it's not running
        mdnsDiscovery.startDiscovery()
        
        val discoveryResult: MdnsDiscovery.ServiceInfo? = withTimeoutOrNull(20000L) { // Increased to 20s
            mdnsDiscovery.getDiscoveredServices()
                .filter { it.deviceMac.equals(remoteMac, ignoreCase = true) }
                .firstOrNull()
        }

        if (discoveryResult == null) {
            Log.w(TAG, "mDNS discovery timeout for $remoteMac")
            return false
        }

        val host = discoveryResult.host
        val port = discoveryResult.port

        Log.d(TAG, "Connecting to $host:$port")
        socket = Socket(host, port)
        setupStreams(socket!!)
        connected = true
        startReceiving()
        Log.d(TAG, "Connected to server")
        return true
    }

    private fun setupStreams(socket: Socket) {
        inputStream = DataInputStream(socket.getInputStream())
        outputStream = DataOutputStream(socket.getOutputStream())
    }

    private fun startReceiving() {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val input = inputStream ?: return@launch
                
                while (isActive && connected) {
                    // Read encrypted message length
                    val length = input.readInt()
                    if (length !in 0..10_000_000) {
                        Log.e(TAG, "Invalid message size: $length")
                        break
                    }

                    // Read encrypted data
                    val encryptedBytes = ByteArray(length)
                    input.readFully(encryptedBytes)

                    // Decrypt
                    // Decrypt using the remote device's MAC
                    val decryptedBytes = encryptionManager.decrypt(remoteMac, encryptedBytes)
                    if (decryptedBytes == null) {
                        Log.e(TAG, "Failed to decrypt message")
                        continue
                    }

                    // Parse ProtocolMessage
                    val json = String(decryptedBytes, Charsets.UTF_8)
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

    /**
     * Disconnect and clean up resources.
     */
    override suspend fun disconnect() {
        connected = false
        receiveJob?.cancel()
        
        try {
            mdnsDiscovery.stop()
            messageChannel.close()
            inputStream?.close()
            outputStream?.close()
            socket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
        
        inputStream = null
        outputStream = null
        socket = null
        serverSocket = null
        
        Log.d(TAG, "Disconnected")
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
            
            // Encrypt using the remote device's MAC
            val encryptedBytes = encryptionManager.encrypt(remoteMac, jsonBytes)
            if (encryptedBytes == null) {
                Log.e(TAG, "Failed to encrypt message")
                return@withContext false
            }

            // Send: length + encrypted data
            synchronized(output) {
                output.writeInt(encryptedBytes.size)
                output.write(encryptedBytes)
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

    override fun isConnected(): Boolean = connected

    override fun getTransportType(): String = "WiFi Intranet"

    override fun getRemoteDeviceName(): String? = deviceName

    override fun getRemoteAddress(): String? = remoteMac
}
