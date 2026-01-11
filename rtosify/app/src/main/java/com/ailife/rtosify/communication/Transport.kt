package com.ailife.rtosify.communication

import com.ailife.rtosify.ProtocolMessage
import kotlinx.coroutines.flow.Flow

/**
 * Transport layer abstraction for communication between devices.
 * Implementations: BluetoothTransport, WifiIntranetTransport, InternetTransport (future)
 */
interface CommunicationTransport {
    
    /**
     * Connect to the remote device.
     * @return true if connection successful
     */
    suspend fun connect(): Boolean
    
    /**
     * Disconnect from the remote device.
     */
    suspend fun disconnect()
    
    /**
     * Send a protocol message to the remote device.
     * @param message The message to send
     * @return true if send successful
     */
    suspend fun send(message: ProtocolMessage): Boolean
    
    /**
     * Receive messages from the remote device.
     * @return Flow of incoming messages
     */
    fun receive(): Flow<ProtocolMessage>
    
    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean
    
    /**
     * Get transport type name for logging/debugging.
     */
    fun getTransportType(): String

    /**
     * Get remote device name if available.
     */
    fun getRemoteDeviceName(): String?
}

/**
 * Transport types
 */
enum class TransportType {
    BLUETOOTH,
    WIFI_INTRANET,
    INTERNET  // Reserved for future use
}
