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
    private var wifiServerMonitorJob: Job? = null
    private var wifiServerWatchdog: Job? = null
    private var btServerWatchdog: Job? = null
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

                val serverSocket = try {
                    bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to create BT server socket: ${e.message}")
                    consecutiveFailures++
                    delay(3000)
                    if (consecutiveFailures >= maxConsecutiveFailures) break
                    continue
                }
                
                consecutiveFailures = 0
                
                try {
                    while (isActive) {
                        val socket = try {
                            serverSocket.accept()
                        } catch (e: IOException) {
                            Log.w(TAG, "BT accept failed: ${e.message}")
                            break
                        }

                        socket?.let {
                            handleBluetoothConnection(it)
                        }
                    }
                } finally {
                    try {
                        serverSocket.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error closing BT server socket", e)
                    }
                }
            }
        }
    }

    private suspend fun handleBluetoothConnection(socket: BluetoothSocket) {
        val transport = BluetoothTransport(socket)
        
        try {
            if (transport.connect()) {
                bluetoothTransport = transport
                updateConnectionState()
                
                try {
                    lastMessageTime = System.currentTimeMillis()
                    startHeartbeatLoop()
                    transport.receive().collect { msg ->
                        lastMessageTime = System.currentTimeMillis()
                        _incomingMessages.send(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BT receive error", e)
                } finally {
                    bluetoothTransport = null
                    checkHeartbeatStatus()
                    updateConnectionState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BT connection error", e)
        } finally {
            transport.disconnect()
        }
    }

    /**
     * Starts the WiFi server logic.
     */
    fun startWifiServer(deviceName: String, localMac: String, remoteMac: String) {
        if (wifiServerJob?.isActive == true) return
        wifiServerJob?.cancel()

        wifiServerJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting WiFi server for local=$localMac, remote=$remoteMac")
            
            while (isActive) {
                val transport = WifiIntranetTransport(
                    remoteMac = remoteMac,
                    localMac = localMac,
                    deviceName = deviceName,
                    encryptionManager = encryptionManager,
                    mdnsDiscovery = mdnsDiscovery,
                    isServer = true
                )
                
                try {
                    // Pre-emptively set active device for encryption
                    val hasKey = encryptionManager.hasKey(remoteMac)
                    Log.d(TAG, "WiFi server accepting connection from $remoteMac, hasKey=$hasKey")
                    encryptionManager.setActiveDevice(remoteMac)
                    
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
                            wifiTransport = null
                            checkHeartbeatStatus() // Only stop if no transports left
                            updateConnectionState()
                        }
                    } else {
                        delay(2000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WiFi connection error", e)
                } finally {
                    transport.disconnect()
                }
            }
        }
    }
    
    /**
     * WiFi Server Watchdog - monitors and auto-restarts WiFi server if it stops
     */
    fun startWifiServerWatchdog(context: Context, deviceName: String, localMac: String, remoteMac: String) {
        if (wifiServerWatchdog?.isActive == true) return
        wifiServerWatchdog?.cancel()
        
        wifiServerWatchdog = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting WiFi server watchdog")
            while (isActive) {
                try {
                    val hasKey = encryptionManager.hasKey(remoteMac)
                    val serverRunning = wifiServerJob?.isActive == true
                    
                    // Check WiFi activation rule (companion should always accept connections)
                    // We don't enforce rules on server side, but we check if keys exist
                    if (hasKey && !serverRunning) {
                        Log.w(TAG, "WiFi server watchdog: server stopped but keys exist - restarting")
                        startWifiServer(deviceName, localMac, remoteMac)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WiFi server watchdog error (non-fatal): ${e.message}", e)
                    // Continue running despite errors
                }
                
                delay(5000) // Check every 5 seconds
            }
        }
    }
    
    /**
     * Bluetooth Server Watchdog - monitors and auto-restarts Bluetooth server if it stops
     */
    fun startBluetoothServerWatchdog(bluetoothAdapter: BluetoothAdapter?) {
        if (btServerWatchdog?.isActive == true) return
        btServerWatchdog?.cancel()
        
        btServerWatchdog = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting Bluetooth server watchdog")
            while (isActive) {
                try {
                    val serverRunning = bluetoothServerJob?.isActive == true
                    val btEnabled = bluetoothAdapter?.isEnabled == true
                    
                    if (btEnabled && !serverRunning) {
                        Log.w(TAG, "BT server watchdog: server stopped but BT enabled - restarting")
                        startBluetoothServer(bluetoothAdapter)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BT server watchdog error (non-fatal): ${e.message}", e)
                    // Continue running despite errors
                }
                
                delay(5000) // Check every 5 seconds
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
        Log.d(TAG, "Stopping heartbeat loop")
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun checkHeartbeatStatus() {
        if (bluetoothTransport == null && wifiTransport == null) {
            stopHeartbeatLoop()
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
        wifiTransport?.let {
            if (it.isConnected()) {
                return it.send(message)
            }
        }
        
        // Priority 2: Bluetooth
        bluetoothTransport?.let {
            if (it.isConnected()) {
                return it.send(message)
            }
        }
        
        Log.w(TAG, "No active transport to send message")
        return false
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
                } else {
                    ConnectionState.Waiting
                }
            }
        }
        
        _connectionState.value = newState
    }

    fun stopAll() {
        bluetoothServerJob?.cancel()
        wifiServerJob?.cancel()
        wifiServerWatchdog?.cancel()
        btServerWatchdog?.cancel()
        scope.launch {
            bluetoothTransport?.disconnect()
            wifiTransport?.disconnect()
            bluetoothTransport = null
            wifiTransport = null
            updateConnectionState()
        }
    }
}
