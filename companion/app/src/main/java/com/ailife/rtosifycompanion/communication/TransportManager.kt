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

        // WiFi Activation Rules (Bitmask)
        const val WIFI_RULE_BT_FALLBACK = 1
        const val WIFI_RULE_MAINACTIVITY = 2
        const val WIFI_RULE_ALWAYS = 4
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

    private var btMonitorJob: Job? = null
    private var wifiMonitorJob: Job? = null
    private var wifiServerMonitorJob: Job? = null
    private var wifiServerWatchdog: Job? = null
    private var btServerWatchdog: Job? = null
    
    @Volatile private var isConnectingWifi = false
    @Volatile private var currentWifiRule: Int = WIFI_RULE_BT_FALLBACK
    @Volatile private var wifiPairingMode = false  // Force WiFi server during pairing
    @Volatile private var isPhoneAppOpen = false   // Tracks if phone app is in foreground

    fun updatePhoneForegroundState(isOpen: Boolean) {
        isPhoneAppOpen = isOpen
        // Trigger immediate check in watchdog
        val name = lastDeviceName
        val local = lastLocalMac
        val remote = lastRemoteMac
        if (name != null && local != null && remote != null) {
            scope.launch {
                wifiServerWatchdog?.cancel()
                startWifiServerWatchdog(null, name, local, remote)
            }
        }
    }

    fun updateWifiRule(rule: Int) {
        currentWifiRule = rule
        // Trigger immediate check in watchdog by restarting it with saved params
        val name = lastDeviceName
        val local = lastLocalMac
        val remote = lastRemoteMac
        if (name != null && local != null && remote != null) {
            scope.launch {
                wifiServerWatchdog?.cancel()
                startWifiServerWatchdog(null, name, local, remote)
            }
        }
    }
    
    // Save these for server restart
    private var lastDeviceName: String? = null
    private var lastLocalMac: String? = null
    private var lastRemoteMac: String? = null

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
                    transport.receive().collect { msg ->
                        _incomingMessages.send(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BT receive error", e)
                } finally {
                    bluetoothTransport = null
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
        lastDeviceName = deviceName
        lastLocalMac = localMac
        lastRemoteMac = remoteMac
        
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
                            transport.receive().collect { msg ->
                                _incomingMessages.send(msg)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "WiFi receive error", e)
                        } finally {
                            wifiTransport = null
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
    fun startWifiServerWatchdog(context: Context?, deviceName: String, localMac: String, remoteMac: String) {
        lastDeviceName = deviceName
        lastLocalMac = localMac
        lastRemoteMac = remoteMac
        
        if (wifiServerWatchdog?.isActive == true) return
        wifiServerWatchdog?.cancel()
        
        wifiServerWatchdog = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting WiFi server watchdog")
            while (isActive) {
                try {
                    val hasKey = encryptionManager.hasKey(remoteMac)
                    val serverRunning = wifiServerJob?.isActive == true
                    val btConnected = bluetoothTransport?.isConnected() == true
                    
                    // Logic based on rules (OR logic: if any active rule matches, enable WiFi)
                    val shouldBeRunning = wifiPairingMode || 
                        ((currentWifiRule and WIFI_RULE_ALWAYS) != 0) ||
                        (((currentWifiRule and WIFI_RULE_MAINACTIVITY) != 0) && isPhoneAppOpen) ||
                        (((currentWifiRule and WIFI_RULE_BT_FALLBACK) != 0) && !btConnected)

                    if (hasKey) {
                        if (shouldBeRunning && !serverRunning) {
                            Log.w(TAG, "WiFi server watchdog: should be running but stopped - starting")
                            startWifiServer(deviceName, localMac, remoteMac)
                        } else if (!shouldBeRunning && serverRunning) {
                            Log.d(TAG, "WiFi server watchdog: should NOT be running but is - stopping")
                            stopWifiServer()
                        }
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

    fun forceDisconnect() {
        Log.d(TAG, "Force disconnect requested")
        scope.launch {
            bluetoothTransport?.disconnect()
            bluetoothTransport = null
            wifiTransport?.disconnect()
            wifiTransport = null
            updateConnectionState()
        }
    }

    fun enableWifiPairingMode() {
        wifiPairingMode = true
        Log.d(TAG, "WiFi pairing mode enabled - server will stay running")
    }
    
    fun disableWifiPairingMode() {
        wifiPairingMode = false
        Log.d(TAG, "WiFi pairing mode disabled - normal rules apply")
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
