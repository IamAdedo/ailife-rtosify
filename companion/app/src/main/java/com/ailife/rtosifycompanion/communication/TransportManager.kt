package com.ailife.rtosifycompanion.communication

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
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

        // LAN Activation Rules (Bitmask)
        const val WIFI_RULE_BT_FALLBACK = 1
        const val WIFI_RULE_MAINACTIVITY = 2
        const val WIFI_RULE_ALWAYS = 4
        const val WIFI_RULE_BT_OR_APP = 8  // Enable when BT disconnected OR app open

        // Internet Activation Rules (Bitmask)
        const val INTERNET_RULE_BT_LAN_FALLBACK = 1  // Enable when both BT and LAN disconnected
        const val INTERNET_RULE_MAINACTIVITY = 2
        const val INTERNET_RULE_ALWAYS = 4
    }

    /**
     * Returns the current connection status string (e.g., "Connected via BLE+LAN")
     */
    fun getConnectionStatusString(): String {
        val bt = bluetoothTransport
        val ble = bleTransport
        val wifi = wifiTransport
        val internet = internetTransport

        val btConnected = bt != null && bt.isConnected()
        val bleConnected = ble != null && ble.isConnected()
        val wifiConnected = wifi != null && wifi.isConnected()
        val internetConnected = internet != null && internet.isConnected()

        if (!btConnected && !bleConnected && !wifiConnected && !internetConnected) {
            return context.getString(com.ailife.rtosifycompanion.R.string.status_disconnected)
        }

        val types = mutableListOf<String>()
        if (btConnected) types.add(context.getString(com.ailife.rtosifycompanion.R.string.transport_bt))
        if (bleConnected) types.add(context.getString(com.ailife.rtosifycompanion.R.string.transport_ble))
        if (wifiConnected) types.add(context.getString(com.ailife.rtosifycompanion.R.string.transport_lan))
        if (internetConnected) types.add(context.getString(com.ailife.rtosifycompanion.R.string.transport_internet))

        return context.getString(com.ailife.rtosifycompanion.R.string.status_connected_via, types.joinToString("+"))
    }

    fun isBtConnected(): Boolean = bluetoothTransport?.isConnected() == true
    fun isBleConnected(): Boolean = bleTransport?.isConnected() == true
    fun isLanConnected(): Boolean = wifiTransport?.isConnected() == true
    fun isInternetConnected(): Boolean = internetTransport?.isConnected() == true
    fun isAnyTransportConnected(): Boolean = isBtConnected() || isBleConnected() || isLanConnected() || isInternetConnected()

    private var bluetoothTransport: BluetoothTransport? = null
    private var bleTransport: BleTransport? = null
    private var wifiTransport: WifiIntranetTransport? = null
    private var internetTransport: WebRtcTransport? = null

    // Jobs for server processes
    private var bluetoothServerJob: Job? = null
    private var bleServerJob: Job? = null
    private var wifiServerJob: Job? = null
    private var internetMonitorJob: Job? = null
    
    // Message handling
    private val _incomingMessages = Channel<ProtocolMessage>(Channel.BUFFERED)
    val incomingMessages: Flow<ProtocolMessage> = _incomingMessages.receiveAsFlow()

    data class TransportStatus(
        val isConnected: Boolean = false,
        val isBtConnected: Boolean = false,
        val isBleConnected: Boolean = false,
        val isLanConnected: Boolean = false,
        val isInternetConnected: Boolean = false,
        val deviceName: String? = null,
        val deviceMac: String? = null,
        val typeString: String = "",
        val state: ConnectionState = ConnectionState.Disconnected
    )

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow(TransportStatus())
    val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private var btMonitorJob: Job? = null
    private var wifiMonitorJob: Job? = null
    private var wifiServerMonitorJob: Job? = null
    private var wifiServerWatchdog: Job? = null
    private var btServerWatchdog: Job? = null
    private var bleServerWatchdog: Job? = null
    private var internetMonitorWatchdog: Job? = null
    @Volatile private var attemptingWifiTransport: WifiIntranetTransport? = null
    
    @Volatile private var isConnectingWifi = false
    @Volatile private var isConnectingInternet = false
    @Volatile private var currentWifiRule: Int = 0 // Default disabled
    @Volatile private var currentInternetRule: Int = 0 // Default disabled
    @Volatile private var signalingUrl: String = "http://signaling.rtosify.ai-life.xyz:8080"
    @Volatile private var stunUrl: String = "stun:stun.cloudflare.com:3478"
    @Volatile private var turnUrl: String = ""
    @Volatile private var turnUsername: String = ""
    @Volatile private var turnPassword: String = ""
    @Volatile private var wifiPairingMode = false  // Force WiFi server during pairing
    @Volatile private var isPhoneAppOpen = false   // Tracks if phone app is in foreground

    fun updatePhoneForegroundState(isOpen: Boolean) {
        isPhoneAppOpen = isOpen
        // Trigger immediate check in watchdogs
        triggerWatchdogsReevaluation()
    }

    fun updateWifiRule(rule: Int) {
        currentWifiRule = rule
        triggerWatchdogsReevaluation()
    }

    fun updateInternetSettings(
        rule: Int, 
        url: String,
        stun: String = "stun:stun.cloudflare.com:3478",
        turn: String = "",
        user: String = "",
        pass: String = ""
    ) {
        currentInternetRule = rule
        signalingUrl = url
        stunUrl = stun
        turnUrl = turn
        turnUsername = user
        turnPassword = pass
        triggerWatchdogsReevaluation()
    }

    private fun triggerWatchdogsReevaluation() {
        val name = lastDeviceName
        val local = lastLocalMac
        val remote = lastRemoteMac
        if (name != null && local != null && remote != null) {
            // Don't cancel/restart watchdogs - they run every 3s and will pick up changes
            // Just ensure they're running if not already
            scope.launch {
                if (wifiServerWatchdog?.isActive != true) {
                    startWifiServerWatchdog(null, name, local, remote)
                }
                if (internetMonitorWatchdog?.isActive != true) {
                    startInternetMonitorWatchdog(name, local, remote)
                }
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


    private var bluetoothServerSocket: BluetoothServerSocket? = null

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

                try {
                    bluetoothServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
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
                            bluetoothServerSocket?.accept()
                        } catch (e: IOException) {
                            Log.w(TAG, "BT accept failed: ${e.message}")
                            break
                        }

                        // Launch handler in separate coroutine to accept next connection immediately if needed
                        socket?.let { newSocket ->
                            Log.i(TAG, "BT connection accepted, launching handler...")
                            // Ensure any existing transport is cleaned up
                            val oldTransport = bluetoothTransport
                            if (oldTransport != null) {
                                Log.w(TAG, "Disconnecting old BT transport for new connection")
                                oldTransport.disconnect()
                            }
                            
                            scope.launch(Dispatchers.IO) {
                                handleBluetoothConnection(newSocket)
                            }
                        }
                    }
                } finally {
                    try {
                        bluetoothServerSocket?.close()
                        bluetoothServerSocket = null
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
                
                // Update watchdog info from BT connection
                val deviceName = transport.getRemoteDeviceName() ?: "Phone"
                val deviceMac = transport.getRemoteAddress() ?: "02:00:00:00:00:00"
                
                lastDeviceName = deviceName
                lastRemoteMac = deviceMac
                // Use a default for localMac if unknown, or wait for WiFi key exchange to set it properly
                if (lastLocalMac == null) lastLocalMac = "02:00:00:00:00:00"
                
                updateConnectionState()
                triggerWatchdogsReevaluation()
                
                try {
                    transport.receive().collect { msg ->
                        _incomingMessages.send(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BT receive error: ${e.message}")
                } finally {
                    Log.w(TAG, "Cleaning up BluetoothTransport - connection ended.")
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
     * Starts the BLE server to accept incoming BLE connections.
     */
    fun startBleServer(bluetoothAdapter: BluetoothAdapter?) {
        if (bleServerJob?.isActive == true) return
        bleServerJob?.cancel()

        bleServerJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting BLE server...")
            
            if (bluetoothAdapter?.isEnabled != true) {
                Log.w(TAG, "Bluetooth disabled, cannot start BLE server")
                return@launch
            }

            val transport = BleTransport(
                context = context,
                bluetoothAdapter = bluetoothAdapter,
                isServer = true,
                targetDevice = null,
                onStateChanged = { connected ->
                    Log.d(TAG, "BleTransport state changed: connected=$connected")
                    updateConnectionState()
                    triggerWatchdogsReevaluation()
                }
            )

            try {
                handleBleConnection(transport)
            } finally {
                transport.disconnect()
            }
        }
    }


private suspend fun handleBleConnection(transport: BleTransport) {
    if (transport.connect()) {
        bleTransport = transport
        
        // Update watchdog info from BLE connection
        val deviceName = transport.getRemoteDeviceName() ?: "Phone (BLE)"
        val deviceMac = transport.getRemoteAddress() ?: "02:00:00:00:00:00"
        
        lastDeviceName = deviceName
        lastRemoteMac = deviceMac
        if (lastLocalMac == null) lastLocalMac = "02:00:00:00:00:00"
        
        updateConnectionState()
        triggerWatchdogsReevaluation()
        
        try {
            transport.receive().collect { msg ->
                _incomingMessages.send(msg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE receive error: ${e.message}")
        } finally {
            Log.w(TAG, "Cleaning up BleTransport - connection ended.")
            bleTransport = null
            updateConnectionState()
        }
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
                    isServer = true,
                    fixedPort = 8881 // Use fixed port for server
                )
                attemptingWifiTransport = transport
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
                            Log.e(TAG, "WiFi receive error: ${e.message}")
                        } finally {
                            Log.w(TAG, "Cleaning up WifiTransport - connection ended.")
                            wifiTransport = null
                            updateConnectionState()
                        }
                    } else {
                        delay(2000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WiFi connection error", e)
                } finally {
                    attemptingWifiTransport = null
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
            Log.d(TAG, "Starting LAN server watchdog")
            while (isActive) {
                try {
                    val hasKey = encryptionManager.hasKey(remoteMac)
                    val serverRunning = wifiServerJob?.isActive == true
                    val btConnected = bluetoothTransport?.isConnected() == true

                    val internetEnabled = shouldInternetBeEnabled(btConnected)

                    val activeWifiMac = wifiTransport?.getRemoteAddress()
                    val macMismatch = activeWifiMac != null && !activeWifiMac.equals(remoteMac, ignoreCase = true)

                    // Logic based on rules (OR logic: if any active rule matches, enable LAN)
                    // CRITICAL FIX: If Internet is enabled, we MUST enable LAN server too, 
                    // because LAN is strictly better/faster/cheaper than Internet.
                    // This prevents the "Connected via Internet" issue when devices are on the same WiFi.
                    val shouldBeRunning = (wifiPairingMode ||
                        internetEnabled || 
                        ((currentWifiRule and WIFI_RULE_ALWAYS) != 0) ||
                        (((currentWifiRule and WIFI_RULE_BT_OR_APP) != 0) && (!btConnected || isPhoneAppOpen)) ||
                        (((currentWifiRule and WIFI_RULE_MAINACTIVITY) != 0) && isPhoneAppOpen) ||
                        (((currentWifiRule and WIFI_RULE_BT_FALLBACK) != 0) && !btConnected)) && !macMismatch

                    if (hasKey) {
                        if (macMismatch) {
                            Log.w(TAG, "LAN server watchdog: MAC mismatch detected (Active: $activeWifiMac, Target: $remoteMac). Stopping server.")
                            stopWifiServer()
                        } else if (shouldBeRunning && !serverRunning) {
                            Log.w(TAG, "LAN server watchdog: starting (Rules matched [Rule=$currentWifiRule, BT=$btConnected, PhoneApp=$isPhoneAppOpen] or Internet enabled)")
                            startWifiServer(deviceName, localMac, remoteMac)
                        } else if (!shouldBeRunning && serverRunning) {
                            Log.d(TAG, "LAN server watchdog: stopping (No rules matched [Rule=$currentWifiRule, BT=$btConnected, PhoneApp=$isPhoneAppOpen])")
                            stopWifiServer()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "LAN server watchdog error (non-fatal): ${e.message}", e)
                    // Continue running despite errors
                }

                delay(3000) // Check every 3 seconds (improved from 5s for faster response)
            }
        }
    }
    
    /**
     * Bluetooth Server Watchdog - monitors and auto-restarts Bluetooth server if it stops
     * Enforces Exclusive Mode: Stops BT server if BLE is already connected.
     */
    fun startBluetoothServerWatchdog(bluetoothAdapter: BluetoothAdapter?) {
        if (btServerWatchdog?.isActive == true) return
        btServerWatchdog?.cancel()
        
        btServerWatchdog = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting Bluetooth server watchdog")
            while (isActive) {
                try {
                    val bleConnected = bleTransport?.isConnected() == true
                    
                    if (bleConnected) {
                        // Exclusive Mode: If BLE is connected, ensure BT server is STOPPED
                        if (bluetoothServerJob?.isActive == true) {
                            Log.i(TAG, "BT server watchdog: BLE connected, stopping BT server (Exclusive Mode)")
                            stopBluetoothServer()
                        }
                    } else {
                        // Normal Mode: Keep BT server running
                        val serverRunning = bluetoothServerJob?.isActive == true
                        val btEnabled = bluetoothAdapter?.isEnabled == true
                        
                        if (btEnabled && !serverRunning) {
                            Log.w(TAG, "BT server watchdog: server stopped but BT enabled - restarting")
                            startBluetoothServer(bluetoothAdapter)
                        }
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
     * BLE Server Watchdog - monitors and auto-restarts BLE server if it stops
     * Enforces Exclusive Mode: Stops BLE server if BT Classic is already connected.
     */
    fun startBleServerWatchdog(bluetoothAdapter: BluetoothAdapter?) {
        if (bleServerWatchdog?.isActive == true) return
        bleServerWatchdog?.cancel()
        
        bleServerWatchdog = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting BLE server watchdog")
            while (isActive) {
                try {
                    val btConnected = bluetoothTransport?.isConnected() == true
                    
                    if (btConnected) {
                         // Exclusive Mode: If BT is connected, ensure BLE server is STOPPED
                         if (bleServerJob?.isActive == true) {
                             Log.i(TAG, "BLE server watchdog: BT connected, stopping BLE server (Exclusive Mode)")
                             stopBleServer()
                         }
                    } else {
                        // Normal Mode: Keep BLE server running
                        val serverRunning = bleServerJob?.isActive == true
                        val btEnabled = bluetoothAdapter?.isEnabled == true
                        
                        if (btEnabled && !serverRunning) {
                            // Check if BLE peripheral mode is supported to avoid log loops
                            if (bluetoothAdapter.bluetoothLeAdvertiser == null) {
                                Log.w(TAG, "BLE Peripheral mode (Advertising) not supported on this hardware. BLE Server will not be started.")
                                delay(30000) // Much longer delay if hardware doesn't support it
                                continue
                            }
                            Log.w(TAG, "BLE server watchdog: server stopped but BT enabled - restarting")
                            startBleServer(bluetoothAdapter)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BLE server watchdog error (non-fatal): ${e.message}", e)
                    // Continue running despite errors
                }
                
                delay(5000) // Check every 5 seconds
            }
        }
    }

    /**
     * Starts the Internet monitoring watchdog.
     */
    fun startInternetMonitorWatchdog(deviceName: String, localMac: String, remoteMac: String) {
        lastDeviceName = deviceName
        lastLocalMac = localMac
        lastRemoteMac = remoteMac

        if (internetMonitorWatchdog?.isActive == true) return
        internetMonitorWatchdog?.cancel()

        internetMonitorWatchdog = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting Internet monitoring watchdog")
            while (isActive) {
                try {
                    val hasKey = encryptionManager.hasKey(remoteMac)
                    val internetRunning = internetMonitorJob?.isActive == true
                    val btConnected = bluetoothTransport?.isConnected() == true

                    val activeInternetMac = internetTransport?.getRemoteAddress()
                    val macMismatch = activeInternetMac != null && !activeInternetMac.equals(remoteMac, ignoreCase = true)

                    val shouldBeEnabled = shouldInternetBeEnabled(btConnected) && !macMismatch

                    if (hasKey) {
                        if (macMismatch) {
                            Log.w(TAG, "Internet watchdog: MAC mismatch detected (Active: $activeInternetMac, Target: $remoteMac). Stopping monitoring.")
                            stopInternetMonitoring()
                        } else if (shouldBeEnabled && !internetRunning) {
                            Log.w(TAG, "Internet watchdog: should be running but stopped - starting")
                            startInternetMonitoring(remoteMac)
                        } else if (!shouldBeEnabled && internetRunning) {
                            Log.d(TAG, "Internet watchdog: should NOT be running but is - stopping")
                            stopInternetMonitoring()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Internet watchdog error: ${e.message}")
                }
                delay(3000) // Check every 3 seconds (improved from 5s for faster response)
            }
        }
    }

    private fun startInternetMonitoring(deviceMac: String) {
        if (internetMonitorJob?.isActive == true) return
        internetMonitorJob?.cancel()

        internetMonitorJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting Internet monitoring loop for MAC: $deviceMac")
            while (isActive) {
                if (internetTransport == null || !internetTransport!!.isConnected()) {
                    Log.d(TAG, "Internet transport disconnected or null - reconnecting")
                    internetTransport?.disconnect()
                    internetTransport = null
                    connectInternet(deviceMac)
                }
                // 10s retry as requested
                delay(10000)
            }
        }
    }

    private fun stopInternetMonitoring() {
        internetMonitorJob?.cancel()
        internetMonitorJob = null
        
        val transportToDisconnect = internetTransport
        if (transportToDisconnect == null) return

        scope.launch {
            transportToDisconnect.disconnect()
            // Only nullify if it hasn't changed
            if (internetTransport === transportToDisconnect) {
                internetTransport = null
                updateConnectionState()
            }
        }
    }

    private suspend fun connectInternet(mac: String): Boolean {
        if (isConnectingInternet) return false

        Log.d(TAG, "connectInternet: mac=$mac, signalingUrl=$signalingUrl")

        val transport = WebRtcTransport(
            context = context,
            remoteMac = mac,
            localMac = lastLocalMac ?: "02:00:00:00:00:00",
            deviceName = lastDeviceName ?: Build.MODEL,
            encryptionManager = encryptionManager,
            signalingUrl = signalingUrl,
            isInitiator = false, // Companion is usually receiver (answerer)
            stunUrl = stunUrl,
            turnUrl = turnUrl,
            turnUsername = turnUsername,
            turnPassword = turnPassword
        )

        try {
            isConnectingInternet = true
            updateConnectionState()

            // Ensure encryption is initialized
            if (!encryptionManager.setActiveDevice(mac)) {
                Log.e(TAG, "Cannot connect Internet: Failed to set active device for encryption")
                return false
            }

            if (transport.connect()) {
                internetTransport = transport
                updateConnectionState()

                scope.launch {
                    try {
                        transport.receive().collect { msg ->
                            _incomingMessages.send(msg)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Internet receive error: ${e.message}")
                    } finally {
                        Log.w(TAG, "Cleaning up InternetTransport - connection ended.")
                        internetTransport = null
                        updateConnectionState()
                        transport.disconnect()
                    }
                }
                return true
            } else {
                transport.disconnect()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Internet connect failed: ${e.message}")
            transport.disconnect()
            return false
        } finally {
            isConnectingInternet = false
        }
    }

    private fun shouldInternetBeEnabled(btConnected: Boolean): Boolean {
        if ((currentInternetRule and INTERNET_RULE_ALWAYS) != 0) return true

        val lanConnected = wifiTransport?.isConnected() == true
        val appOpen = isPhoneAppOpen

        // Rule: Enable if BT or LAN are disconnected (Fallback)
        if ((currentInternetRule and INTERNET_RULE_BT_LAN_FALLBACK) != 0) {
            if (!btConnected && !lanConnected) return true
        }

        // Rule: Enable when Phonne App is in foreground
        if ((currentInternetRule and INTERNET_RULE_MAINACTIVITY) != 0) {
            if (appOpen) return true
        }

        return false
    }

    /**
     * Stops the WiFi server.
     */
    fun stopWifiServer() {
        wifiServerJob?.cancel()
        
        val transportToDisconnect = wifiTransport
        val attemptingToDisconnect = attemptingWifiTransport
        
        scope.launch {
            attemptingToDisconnect?.disconnect()
            transportToDisconnect?.disconnect()
            
            // Only nullify if they haven't changed (prevent race with new connection)
            if (attemptingWifiTransport === attemptingToDisconnect) {
                attemptingWifiTransport = null
            }
            if (wifiTransport === transportToDisconnect) {
                wifiTransport = null
                updateConnectionState()
            }
        }
    }

    private fun stopBluetoothServer() {
        bluetoothServerJob?.cancel()
        // Do NOT disconnect the transport here if it is already connected?
        // Wait, if we stop the server, we just stop LISTENING. Existing connections might persist?
        // But for Exclusive Mode, we only stop the server if the OTHER transport is active.
        // If we are connecting via BLE, we stop BT server. This assumes we are NOT connected via BT.
        // If we are connected via BT, we keep BT server (implied by "btConnected" check in watchdog).
        
        // However, if we are in waiting state, we might want to close the socket.
    }
    
    fun stopBleServer() {
        bleServerJob?.cancel()
        scope.launch {
            // If the server object has a stop method to stop advertising
             // BleTransport doesn't expose stop() directly but disconnecting it helps
             // But BleTransport in SERVER mode:
             // We need to access the transport instance if created?
             // Actually startBleServer creates a local 'transport' variable but assigns it to 'bleTransport' ONLY if connected.
             // If it's just listening/advertising, 'bleTransport' might be null.
             // But we need to stop the *Advertising*.
             // The BleTransport class has a stop() method if we can access it.
             // Since BleTransport instance is lost if not assigned to bleTransport, 
             // we rely on the cancelling of bleServerJob.
             // But wait, BleTransport logic is inside the job...
             // In startBleServer: "val transport = BleTransport(...) ... finally { transport.disconnect() }"
             // So cancelling the job triggers the finally block which calls disconnect(), which calls gattServer.stop().
             // This functions correctly.
        }
    }

    /**
     * Sends a message using the best available transport.
     */
    suspend fun send(message: ProtocolMessage): Boolean {
        // Priority 1: WiFi
        wifiTransport?.let {
            if (it.isConnected()) {
                if (it.send(message)) return true
            }
        }
        
        // Priority 2: Bluetooth (Classic or BLE, whichever is active)
        bluetoothTransport?.let {
            if (it.isConnected()) {
                if (it.send(message)) return true
            }
        }
        
        bleTransport?.let {
            if (it.isConnected()) {
                if (it.send(message)) return true
            }
        }

        // Priority 3: Internet
        internetTransport?.let {
            if (it.isConnected()) {
                if (it.send(message)) return true
            }
        }
        
        Log.w(TAG, "No active transport to send message")
        return false
    }

    private fun updateConnectionState() {
        val wifi = wifiTransport
        val bt = bluetoothTransport
        val ble = bleTransport
        val internet = internetTransport

        // Build connection type string showing all active transports
        val btConnected = bt != null && bt.isConnected()
        val bleConnected = ble != null && ble.isConnected()
        val wifiConnected = wifi != null && wifi.isConnected()
        val internetConnected = internet != null && internet.isConnected()

        val types = mutableListOf<String>()
        if (btConnected) types.add("BT")
        if (bleConnected) types.add("BLE")
        if (wifiConnected) types.add("LAN")
        if (internetConnected) types.add("Internet")
        val typeString = types.joinToString("+")

        val deviceName = bt?.getRemoteDeviceName() ?: ble?.getRemoteDeviceName() ?: wifi?.getRemoteDeviceName() ?: internet?.getRemoteDeviceName()
        val deviceMac = bt?.getRemoteAddress() ?: ble?.getRemoteAddress() ?: wifi?.getRemoteAddress() ?: internet?.getRemoteAddress()

        val isAnyConnected = btConnected || bleConnected || wifiConnected || internetConnected

        val newState = if (isAnyConnected) {
            ConnectionState.Connected(typeString, deviceName, deviceMac)
        } else {
            if (isConnectingWifi || isConnectingInternet) {
                ConnectionState.Connecting
            } else {
                ConnectionState.Waiting
            }
        }

        Log.i(TAG, "Status Update: Connected=$isAnyConnected ($typeString), State=${newState.javaClass.simpleName}")
        _connectionState.value = newState
        _status.value = TransportStatus(
            isConnected = isAnyConnected,
            isBtConnected = btConnected,
            isBleConnected = bleConnected,
            isLanConnected = wifiConnected,
            isInternetConnected = internetConnected,
            deviceName = deviceName,
            deviceMac = deviceMac,
            typeString = typeString,
            state = newState
        )
    }

    fun forceDisconnect() {
        Log.d(TAG, "Force disconnect requested for all transports")
        scope.launch {
            bluetoothTransport?.disconnect()
            bluetoothTransport = null
            bleTransport?.disconnect()
            bleTransport = null
            wifiTransport?.disconnect()
            wifiTransport = null
            internetTransport?.disconnect()
            internetTransport = null
            updateConnectionState()
        }
    }

    fun forceDisconnectBluetooth() {
        Log.d(TAG, "Force disconnect requested for Bluetooth transport")
        scope.launch {
            bluetoothTransport?.disconnect()
            bluetoothTransport = null
            updateConnectionState()
        }
    }

    /**
     * Get the connected Bluetooth device (for ANCS connection)
     */
    fun getConnectedBluetoothDevice(): android.bluetooth.BluetoothDevice? {
        return bleTransport?.getConnectedDevice()
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
        bleServerJob?.cancel()
        wifiServerJob?.cancel()
        internetMonitorJob?.cancel()
        wifiServerWatchdog?.cancel()
        wifiServerWatchdog?.cancel()
        btServerWatchdog?.cancel()
        bleServerWatchdog?.cancel()
        internetMonitorWatchdog?.cancel()
        // Use a detached scope for cleanup to ensure it runs even if the service scope is cancelled
        CoroutineScope(Dispatchers.IO).launch {
                try {
                    bluetoothServerSocket?.close() // Interrupt blocking accept()
                    bluetoothServerSocket = null
                    bluetoothTransport?.disconnect()
                    bleTransport?.disconnect()
                    wifiTransport?.disconnect()
                    attemptingWifiTransport?.disconnect()
                    internetTransport?.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking disconnect during stopAll: ${e.message}")
                }
                bluetoothTransport = null
                bleTransport = null
                wifiTransport = null
                attemptingWifiTransport = null
                internetTransport = null
                updateConnectionState()
            }
    }

}
