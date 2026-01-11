package com.ailife.rtosifycompanion.communication

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ailife.rtosifycompanion.ProtocolMessage
import com.ailife.rtosifycompanion.security.EncryptionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.UUID

/**
 * Manages Bluetooth and WiFi transports, handling connection logic and message routing.
 * Prioritizes WiFi for data transmission when available.
 */
class TransportManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val encryptionManager: EncryptionManager,
    private val mdnsDiscovery: MdnsDiscovery
) {

    companion object {
        private const val TAG = "TransportManager"
        private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val APP_NAME = "RTOSifyApp"
    }

    private var bluetoothTransport: BluetoothTransport? = null
    private var wifiTransport: WifiIntranetTransport? = null

    // Jobs for server processes
    private var bluetoothServerJob: Job? = null
    private var wifiServerJob: Job? = null
    
    // Message handling
    private val _incomingMessages = Channel<ProtocolMessage>(Channel.BUFFERED)
    val incomingMessages: Flow<ProtocolMessage> = _incomingMessages.receiveAsFlow()

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Transport monitoring jobs
    private var btMonitorJob: Job? = null
    private var wifiMonitorJob: Job? = null
    private var heartbeatJob: Job? = null
    
    @Volatile private var isConnectingWifi = false
    private var lastMessageTime = 0L
    private val HEARTBEAT_INTERVAL = 20000L
    private val CONNECTION_TIMEOUT = 60000L

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Waiting : ConnectionState()
        data class Connected(val type: String, val deviceName: String?, val deviceMac: String?) : ConnectionState()
    }

    /**
     * Starts the Bluetooth server to accept incoming connections.
     */
    fun startBluetoothServer(bluetoothAdapter: BluetoothAdapter?) {
        if (bluetoothServerJob?.isActive == true) return
        bluetoothServerJob?.cancel()

        bluetoothServerJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting Bluetooth server...")
            _connectionState.value = ConnectionState.Waiting

            var consecutiveFailures = 0
            val maxConsecutiveFailures = 10

            while (isActive) {
                if (bluetoothAdapter?.isEnabled != true) {
                    Log.w(TAG, "Bluetooth disabled, cannot start server")
                    delay(5000)
                    continue
                }

                var serverSocket: BluetoothServerSocket? = null
                try {
                    serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                    consecutiveFailures = 0
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to create BT server socket: ${e.message}")
                    consecutiveFailures++
                    delay(3000)
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        Log.e(TAG, "Too many BT server failures")
                        break
                    }
                    continue
                }

                var socket: BluetoothSocket? = null
                while (socket == null && isActive) {
                    try {
                        socket = serverSocket?.accept(5000)
                    } catch (_: IOException) {}
                }

                if (socket != null) {
                    try { serverSocket?.close() } catch (_: Exception) {}
                    handleBluetoothConnection(socket)
                }
            }
        }
    }

    private suspend fun handleBluetoothConnection(socket: BluetoothSocket) {
        val deviceName = socket.remoteDevice?.name ?: "Unknown"
        val deviceMac = socket.remoteDevice?.address ?: ""
        Log.d(TAG, "Bluetooth connected: $deviceName ($deviceMac)")
        
        val transport = BluetoothTransport(socket)
        if (transport.connect()) {
            bluetoothTransport = transport
            updateConnectionState()
            scope.launch {
                try {
                    lastMessageTime = System.currentTimeMillis()
                    startHeartbeatLoop()
                    transport.receive().collect { msg ->
                        lastMessageTime = System.currentTimeMillis()
                        _incomingMessages.send(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Bluetooth receive error", e)
                } finally {
                    stopHeartbeatLoop()
                    bluetoothTransport = null
                    updateConnectionState()
                }
            }
        } else {
            Log.e(TAG, "Failed to initialize Bluetooth transport")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Starts the WiFi server logic.
     */
    fun startWifiServer(deviceName: String, deviceMac: String) {
        if (wifiServerJob?.isActive == true) return
        wifiServerJob?.cancel()

        wifiServerJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting WiFi server...")
            
            while (isActive) {
                val transport = WifiIntranetTransport(
                    deviceMac = deviceMac,
                    deviceName = deviceName,
                    encryptionManager = encryptionManager,
                    mdnsDiscovery = mdnsDiscovery,
                    isServer = true
                )
                
                try {
                    if (transport.connect()) {
                        wifiTransport = transport
                        updateConnectionState()
                        
                        try {
                            lastMessageTime = System.currentTimeMillis()
                            startHeartbeatLoop()
                            transport.receive().collect { msg ->
                                lastMessageTime = System.currentTimeMillis()
                                _incomingMessages.send(msg)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "WiFi receive error", e)
                        } finally {
                            stopHeartbeatLoop()
                            wifiTransport = null
                            updateConnectionState()
                        }
                    } else {
                        delay(2000)
                    }
                } finally {
                    transport.disconnect()
                }
            }
        }
    }

    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                val connected = (bluetoothTransport?.isConnected() == true) || (wifiTransport?.isConnected() == true)
                if (!connected) continue
                
                val elapsed = System.currentTimeMillis() - lastMessageTime
                if (elapsed > CONNECTION_TIMEOUT) {
                    Log.w(TAG, "Connection timeout! $elapsed ms")
                    stopAll()
                    break
                }
                
                // Send heartbeat
                send(ProtocolMessage(type = "heartbeat"))
            }
        }
    }

    private fun stopHeartbeatLoop() {
        val stillConnected = (bluetoothTransport?.isConnected() == true) || (wifiTransport?.isConnected() == true)
        if (!stillConnected) {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
    }

    /**
     * Stops the WiFi server.
     */
    fun stopWifiServer() {
        wifiServerJob?.cancel()
        scope.launch {
            wifiTransport?.disconnect()
            wifiTransport = null
            updateConnectionState()
        }
    }

    /**
     * Sends a message using the best available transport.
     */
    suspend fun send(message: ProtocolMessage): Boolean {
        // Priority 1: WiFi
        val wifi = wifiTransport
        if (wifi != null && wifi.isConnected()) {
            if (wifi.send(message)) return true
        }

        // Priority 2: Bluetooth
        val bt = bluetoothTransport
        if (bt != null && bt.isConnected()) {
            if (bt.send(message)) return true
        }
        
        Log.w(TAG, "Send failed: No connected transport")
        return false
    }

    private fun startTransportMonitoring(transport: CommunicationTransport, type: String, name: String?) {
        updateConnectionState()
    }

    private fun updateConnectionState() {
        val wifi = wifiTransport
        val bt = bluetoothTransport
        
        val newState = when {
            wifi != null && wifi.isConnected() && bt != null && bt.isConnected() -> 
                ConnectionState.Connected("Dual", bt.getRemoteDeviceName(), bt.getRemoteAddress())
            wifi != null && wifi.isConnected() -> 
                ConnectionState.Connected("WiFi", wifi.getRemoteDeviceName(), wifi.getRemoteAddress())
            bt != null && bt.isConnected() -> 
                ConnectionState.Connected("Bluetooth", bt.getRemoteDeviceName(), bt.getRemoteAddress())
            else -> {
                if (isConnectingWifi) {
                    ConnectionState.Connecting
                } else if (bluetoothServerJob?.isActive == true && (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled == true) {
                    ConnectionState.Waiting
                } else {
                    ConnectionState.Disconnected
                }
            }
        }
        
        _connectionState.value = newState
    }
    
    fun getRemoteDeviceName(): String? {
        return wifiTransport?.getRemoteDeviceName() ?: bluetoothTransport?.getRemoteDeviceName()
    }

    fun stopAll() {
        bluetoothServerJob?.cancel()
        wifiServerJob?.cancel()
        heartbeatJob?.cancel()
        
        scope.launch(NonCancellable) {
            bluetoothTransport?.disconnect()
            wifiTransport?.disconnect()
            bluetoothTransport = null
            wifiTransport = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }
    
    fun isWifiConnected(): Boolean {
        return wifiTransport?.isConnected() == true
    }

    fun isConnected(): Boolean {
        return (bluetoothTransport?.isConnected() == true) || (wifiTransport?.isConnected() == true)
    }
}
