package com.ailife.rtosifycompanion.communication

import android.util.Log
import com.ailife.rtosifycompanion.ProtocolMessage
import com.ailife.rtosifycompanion.security.EncryptionManager
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
        private const val KEEPALIVE_INTERVAL = 5000L  // 5 seconds
        private const val KEEPALIVE_TIMEOUT = 15000L  // 15 seconds
    }

    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    
    private val messageChannel = Channel<ProtocolMessage>(Channel.BUFFERED)
    private var receiveJob: Job? = null
    private var keepaliveJob: Job? = null
    private var connected = false
    private var lastReceiveTime = 0L

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
        // Use 0 for dynamic port allocation
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
                lastReceiveTime = System.currentTimeMillis()
                startReceiving()
                startKeepalive()
                Log.d(TAG, "Client connected from ${it.inetAddress}")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server accept error", e)
        }
        // Keep mDNS service advertised for reconnections
        // Only stop in disconnect() method
        return false
    }

    private suspend fun connectAsClient(): Boolean {
        Log.d(TAG, "Attempting to discover service for MAC: $remoteMac")
        
        mdnsDiscovery.startDiscovery()
        
        return withTimeoutOrNull(20000L) {
            val success = mdnsDiscovery.getDiscoveredServices()
                .filter { it.deviceMac.equals(remoteMac, ignoreCase = true) }
                .map { discoveryResult ->
                    Log.d(TAG, "Testing discovered service: ${discoveryResult.host}:${discoveryResult.port}")
                    try {
                        val s = Socket()
                        s.connect(java.net.InetSocketAddress(discoveryResult.host, discoveryResult.port), 5000)
                        socket = s
                        setupStreams(s)
                        connected = true
                        lastReceiveTime = System.currentTimeMillis()
                        startReceiving()
                        startKeepalive()
                        Log.d(TAG, "Successfully connected to ${discoveryResult.host}:${discoveryResult.port}")
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "Connection to ${discoveryResult.host}:${discoveryResult.port} failed: ${e.message}")
                        false
                    }
                }
                .firstOrNull { it }
            success == true
        } ?: false
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

                    // Read encrypted data
                    val encryptedBytes = ByteArray(length)
                    input.readFully(encryptedBytes)
                    
                    // Update receive time for keepalive
                    lastReceiveTime = System.currentTimeMillis()

                    // Decrypt
                    // Decrypt using the remote device's MAC
                    val decryptedBytes = encryptionManager.decryptForDevice(remoteMac, encryptedBytes)
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

    private fun startKeepalive() {
        keepaliveJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && connected) {
                delay(KEEPALIVE_INTERVAL)
                
                val elapsed = System.currentTimeMillis() - lastReceiveTime
                if (elapsed > KEEPALIVE_TIMEOUT) {
                    Log.w(TAG, "WiFi keepalive timeout: ${elapsed}ms since last receive")
                    disconnect()
                    break
                }
                
                // Send keepalive ping (-1 message length)
                try {
                    val output = outputStream ?: break
                    synchronized(output) {
                        output.writeInt(-1)
                        output.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WiFi keepalive send failed", e)
                    disconnect()
                    break
                }
            }
        }
    }

    /**
     * Disconnect and clean up resources.
     */
    override suspend fun disconnect() {
        connected = false
        receiveJob?.cancel()
        keepaliveJob?.cancel()
        
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
        
        Log.d(TAG, "WiFi transport disconnected")
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
            val encryptedBytes = encryptionManager.encryptForDevice(remoteMac, jsonBytes)
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
