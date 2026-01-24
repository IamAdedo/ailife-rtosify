package com.ailife.rtosify.communication

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.content.SharedPreferences
import android.util.Log
import com.ailife.rtosify.DevicePrefManager
import com.ailife.rtosify.ProtocolMessage
import com.ailife.rtosify.security.EncryptionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.UUID

/**
 * Manages Bluetooth and WiFi transports for the Phone app.
 * Handles auto-reconnection and Network Settings rules.
 */
class TransportManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val encryptionManager: EncryptionManager,
    private val mdnsDiscovery: MdnsDiscovery,
    private val devicePrefManager: DevicePrefManager
) {


    private var bluetoothTransport: BluetoothTransport? = null
    private var bleTransport: BleTransport? = null
    private var wifiTransport: WifiIntranetTransport? = null
    private var internetTransport: WebRtcTransport? = null

    // Jobs
    private var btReconnectJob: Job? = null
    private var wifiMonitorJob: Job? = null
    private var internetMonitorJob: Job? = null
    private var connectionHealthMonitor: Job? = null
    
    @Volatile private var isConnectingWifi = false
    @Volatile private var isConnectingInternet = false
    
    @Volatile private var wifiTemporarilyDisabled = false
    @Volatile private var wifiForceForPairing = false
    @Volatile private var isMirroring = false
    
    private var discoveredLocalMac: String? = null
    
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

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Waiting : ConnectionState()
        data class Connected(val type: String, val deviceName: String?, val deviceMac: String? = null) : ConnectionState()
    }

    companion object {
        private const val TAG = "TransportManager"
        private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        // LAN Rules (matching BluetoothService/NetworkSettingsActivity)
        const val WIFI_RULE_BT_FALLBACK = 1
        const val WIFI_RULE_MAINACTIVITY = 2
        const val WIFI_RULE_ALWAYS = 4
        const val WIFI_RULE_BT_OR_APP = 8  // Enable when BT disconnected OR app open

        // Internet Rules
        const val INTERNET_RULE_BT_LAN_FALLBACK = 1  // Enable when both BT and LAN disconnected
        const val INTERNET_RULE_MAINACTIVITY = 2
        const val INTERNET_RULE_ALWAYS = 4
    }

    /**
     * Returns the current connection status string (e.g., "Connected via BT+LAN")
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
            return "Disconnected"
        }

        val types = mutableListOf<String>()
        if (btConnected) types.add("BT")
        if (bleConnected) types.add("BLE")
        if (wifiConnected) types.add("LAN")
        if (internetConnected) types.add("Internet")

        return "Connected via ${types.joinToString("+")}"
    }

    fun isBtConnected(): Boolean = bluetoothTransport?.isConnected() == true
    fun isBleConnected(): Boolean = bleTransport?.isConnected() == true
    fun isLanConnected(): Boolean = wifiTransport?.isConnected() == true
    fun isInternetConnected(): Boolean = internetTransport?.isConnected() == true
    fun isAnyTransportConnected(): Boolean = isBtConnected() || isBleConnected() || isLanConnected() || isInternetConnected()

    /**
     * Starts the client logic: Auto-reconnect Bluetooth and WiFi monitoring.
     */
    fun startClient(targetDevice: BluetoothDevice) {
        // Load discovered MAC if it exists
        discoveredLocalMac = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("discovered_local_mac", null)
        
        startBluetoothReconnect(targetDevice)
        startWifiMonitoring(targetDevice.address)
        startInternetMonitoring(targetDevice.address)
        startConnectionHealthMonitor(targetDevice.address)
    }

    fun setDiscoveredLocalMac(mac: String) {
        discoveredLocalMac = mac
    }

    private fun startBluetoothReconnect(device: BluetoothDevice) {
        if (btReconnectJob?.isActive == true) return
        btReconnectJob?.cancel()

        btReconnectJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting Bluetooth reconnect loop for ${device.name}")
            _connectionState.value = ConnectionState.Connecting

            var consecutiveFailures = 0

            while (isActive) {
                // Check Bluetooth adapter state
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter?.isEnabled != true) {
                    Log.w(TAG, "Bluetooth disabled, waiting...")
                    delay(5000)
                    continue
                }

                // Check transport mode preference
                val devicePrefs = devicePrefManager.getDevicePrefs(device.address)
                val transportMode = devicePrefs.getString("bluetooth_transport_mode", "classic")
                
                if (transportMode == "ble") {
                    // Use BLE transport
                    Log.d(TAG, "Using BLE transport mode")
                    val start = System.currentTimeMillis()
                    Log.v(TAG, "Attempting BLE connection...")
                    if (!connectBle(device)) {
                        Log.v(TAG, "BLE connection attempt completed (failed) in ${System.currentTimeMillis() - start}ms")
                        consecutiveFailures++
                        delay(if(consecutiveFailures > 5) 3000 else 1000)
                        continue
                    }
                    Log.v(TAG, "BLE session ended in ${System.currentTimeMillis() - start}ms")
                    consecutiveFailures = 0
                } else {
                    // Use Bluetooth Classic RFCOMM
                    Log.d(TAG, "Using Bluetooth Classic transport mode")
                    var socket: BluetoothSocket? = null
                    try {
                        socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                        socket.connect()
                        consecutiveFailures = 0
                    } catch (e: IOException) {
                        Log.w(TAG, "BT Classic connect failed: ${e.message}")
                        consecutiveFailures++
                        socket = null
                        delay(if(consecutiveFailures > 5) 3000 else 1000)
                        continue
                    }

                    if (socket != null) {
                        handleBluetoothConnection(socket)
                    }
                }
            }
        }
    }

    private suspend fun connectBle(device: BluetoothDevice): Boolean {
        try {
            Log.v(TAG, "Creating BleTransport...")
            
            // Try to find the stored name for this device as the MAC might be unreliable/rotating
            val deviceName = devicePrefManager.getPairedDevices().find { it.mac == device.address }?.name
            
            val transport = BleTransport(
                context = context,
                bluetoothAdapter = BluetoothAdapter.getDefaultAdapter(),
                isServer = false,
                targetDevice = device,
                targetDeviceName = deviceName
            )

            Log.v(TAG, "Calling transport.connect()...")
            if (transport.connect()) {
                Log.v(TAG, "transport.connect() succeeded")
                bleTransport = transport
                updateConnectionState()
                
                // Run receive loop in current coroutine (blocking the reconnect loop)
                try {
                    transport.receive().collect { msg ->
                        _incomingMessages.send(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BLE receive error", e)
                } finally {
                    Log.d(TAG, "BLE disconnected")
                    bleTransport = null
                    updateConnectionState()
                    transport.disconnect()
                }
                return true
            } else {
                transport.disconnect()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE connect failed: ${e.message}", e)
            return false
        }
    }

    private suspend fun handleBluetoothConnection(socket: BluetoothSocket) {
        val deviceName = socket.remoteDevice?.name ?: "Unknown"
        Log.d(TAG, "Bluetooth Classic connected: $deviceName")

        val transport = BluetoothTransport(socket)
        if (transport.connect()) {
            bluetoothTransport = transport
            updateConnectionState()
            
            try {
                transport.receive().collect { msg ->
                    _incomingMessages.send(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth receive error", e)
            } finally {
                Log.d(TAG, "Bluetooth Classic disconnected")
                bluetoothTransport = null
                updateConnectionState()
            }
        } else {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun triggerWifiReevaluation() {
        bluetoothTransport?.getRemoteAddress()?.let { mac ->
            // Cancel current job to force immediate re-evaluation in next loop
            // Or just call the check logic.
            // A simple way is to cancel and let it restart, but we need to ensure it DOES restart.
            scope.launch {
                wifiMonitorJob?.cancel()
                startWifiMonitoring(mac)
                internetMonitorJob?.cancel()
                startInternetMonitoring(mac)
            }
        }
    }

    private fun startWifiMonitoring(deviceMac: String) {
        if (wifiMonitorJob?.isActive == true) return
        wifiMonitorJob?.cancel()
        
        wifiMonitorJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting WiFi monitoring for MAC: $deviceMac")
            
            while (isActive) {
                if (shouldWifiBeEnabled()) {
                    // Try to connect if not connected
                    if (wifiTransport == null || !wifiTransport!!.isConnected()) {
                        val connected = connectWifi(deviceMac)
                        updateConnectionState() // Update state after connection attempt
                        if (!connected) {
                            // Wait after failed attempt before retrying with jitter
                            delay(3000 + (Math.random() * 2000).toLong())
                            continue
                        }
                    }
                } else {
                    // Reduce activity if rule says no
                    if (wifiTransport != null) {
                       wifiTransport?.disconnect()
                       wifiTransport = null
                       updateConnectionState()
                    }
                }
                delay(2000) // Check rules/status periodically
            }
        }
    }

    private suspend fun connectWifi(mac: String): Boolean {
        if (isConnectingWifi) return false
        
        val devicePrefs = devicePrefManager.getDevicePrefs(mac)
        val useFixedIp = devicePrefs.getBoolean("wifi_fixed_ip_enabled", false)
        val fixedIp = if (useFixedIp) devicePrefs.getString("wifi_fixed_ip_address", null) else null
        
        Log.d(TAG, "connectWifi: mac=$mac, useFixedIp=$useFixedIp, fixedIp=$fixedIp")
        
        val transport = WifiIntranetTransport(
            remoteMac = mac,  // Companion's Bluetooth MAC
            localMac = mac,   // Also use companion's MAC for encryption consistency
            deviceName = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.name ?: Build.MODEL,
            encryptionManager = encryptionManager,
            mdnsDiscovery = mdnsDiscovery,
            isServer = false, // Phone is client
            fixedIp = fixedIp,
            fixedPort = 8881 // DEFAULT_PORT
        )

        try {
            isConnectingWifi = true
            updateConnectionState()
            // Ensure encryption is initialized for this device
            val hasKey = encryptionManager.hasKey(mac)
            Log.d(TAG, "Connecting WiFi to $mac, hasKey=$hasKey")
            encryptionManager.setActiveDevice(mac)
            
            if (transport.connect()) {
                wifiTransport = transport
                updateConnectionState()
                
                // Launch collector
                scope.launch {
                    try {
                        transport.receive().collect { msg ->
                             _incomingMessages.send(msg)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "WiFi receive error", e)
                    } finally {
                        wifiTransport = null
                        updateConnectionState()
                        transport.disconnect() // Disconnect the transport when its receive loop ends
                    }
                }
                return true
            } else {
                transport.disconnect()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi connect failed: ${e.message}")
            transport.disconnect()
            return false
        } finally {
            isConnectingWifi = false
        }
    }
    
    private fun startInternetMonitoring(deviceMac: String) {
        if (internetMonitorJob?.isActive == true) return
        internetMonitorJob?.cancel()
        
        internetMonitorJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting Internet monitoring for MAC: $deviceMac")
            
            while (isActive) {
                if (shouldInternetBeEnabled()) {
                    if (internetTransport == null || !internetTransport!!.isConnected()) {
                        Log.d(TAG, "Internet transport disconnected or null - reconnecting")
                        internetTransport?.disconnect()
                        internetTransport = null
                        
                        val connected = connectInternet(deviceMac)
                        updateConnectionState()
                        if (!connected) {
                            // Longer jittered delay for internet retry to avoid sync issues
                            // 2s retry as requested (aggressive)
                            delay(2000)
                            continue
                        }
                    }
                } else {
                    if (internetTransport != null) {
                        internetTransport?.disconnect()
                        internetTransport = null
                        updateConnectionState()
                    }
                }
                delay(2000)
            }
        }
    }
    
    private suspend fun connectInternet(mac: String): Boolean {
        if (isConnectingInternet) return false
        
        val devicePrefs = devicePrefManager.getDevicePrefs(mac)
        val signalingUrl = devicePrefs.getString("internet_signaling_url", "ws://192.168.1.10:8080") ?: "ws://192.168.1.10:8080"
        val stunUrl = devicePrefs.getString("internet_stun_url", "stun:stun.cloudflare.com:3478")
        val turnUrl = devicePrefs.getString("internet_turn_url", "")
        val turnUsername = devicePrefs.getString("internet_turn_username", "")
        val turnPassword = devicePrefs.getString("internet_turn_password", "")
        
        Log.d(TAG, "connectInternet: mac=$mac, signalingUrl=$signalingUrl, stunUrl=$stunUrl, turnUrl=$turnUrl")
        
        val transport = WebRtcTransport(
            context = context,
            remoteMac = mac,
            localMac = discoveredLocalMac ?: (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.address ?: "02:00:00:00:00:00",
            deviceName = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.name ?: Build.MODEL,
            encryptionManager = encryptionManager,
            signalingUrl = signalingUrl,
            isInitiator = true,
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
                        Log.e(TAG, "Internet receive error", e)
                    } finally {
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

    private fun shouldWifiBeEnabled(): Boolean {
        // Force LAN on during pairing
        if (wifiForceForPairing) return true

        // Don't connect if temporarily disabled (e.g., during re-pairing)
        if (wifiTemporarilyDisabled) return false

        val prefs = devicePrefManager.getActiveDevicePrefs()
        val rule = prefs.getInt("wifi_activation_rule", 0) // Default disabled

        // Always rule takes precedence
        if ((rule and WIFI_RULE_ALWAYS) != 0) return true

        val btConnected = bluetoothTransport?.isConnected() == true
        val appActive = isAppInForeground || isMirroring

        // Combined rule: BT disconnected OR app open
        if ((rule and WIFI_RULE_BT_OR_APP) != 0) {
            if (!btConnected || appActive) return true
        }

        var shouldEnable = false

        // BT Fallback rule
        if ((rule and WIFI_RULE_BT_FALLBACK) != 0) {
            if (!btConnected) shouldEnable = true
        }

        // App foreground rule (when any app UI is visible)
        if ((rule and WIFI_RULE_MAINACTIVITY) != 0) {
            if (appActive) shouldEnable = true
        }

        return shouldEnable
    }
    
    private fun shouldInternetBeEnabled(): Boolean {
        val prefs = devicePrefManager.getActiveDevicePrefs()
        val rule = prefs.getInt("internet_activation_rule", 0) // Default disabled

        if ((rule and INTERNET_RULE_ALWAYS) != 0) return true

        val btConnected = bluetoothTransport?.isConnected() == true
        val lanConnected = wifiTransport?.isConnected() == true

        var shouldEnable = false

        // BT and LAN fallback: enable only when BOTH are disconnected
        if ((rule and INTERNET_RULE_BT_LAN_FALLBACK) != 0) {
            if (!btConnected && !lanConnected) shouldEnable = true
        }

        if ((rule and INTERNET_RULE_MAINACTIVITY) != 0) {
            if (isAppInForeground || isMirroring) shouldEnable = true
        }

        return shouldEnable
    }
    
    var isAppInForeground: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                triggerWifiReevaluation()
            }
        }

    fun updateMirroringState(active: Boolean) {
        if (isMirroring != active) {
            isMirroring = active
            Log.d(TAG, "Mirroring state changed: $active")
            triggerWifiReevaluation()
        }
    }

    suspend fun send(message: ProtocolMessage): Boolean {
        // Priority 1: WiFi (Fastest, Local)
        val wifi = wifiTransport
        if (wifi != null && wifi.isConnected()) {
            if (wifi.send(message)) return true
        }

        // Priority 2: Bluetooth Classic/BLE (Reliable, Local)
        val bt = bluetoothTransport
        if (bt != null && bt.isConnected()) {
            if (bt.send(message)) return true
        }
        
        val ble = bleTransport
        if (ble != null && ble.isConnected()) {
            if (ble.send(message)) return true
        }
        
        // Priority 3: Internet (Remote, slower, data usage)
        val internet = internetTransport
        if (internet != null && internet.isConnected()) {
            if (internet.send(message)) return true
        }
        Log.w(TAG, "No active transport to send message")
        return false
    }

    fun stopAll() {
        btReconnectJob?.cancel()
        wifiMonitorJob?.cancel()
        internetMonitorJob?.cancel()
        
        scope.launch {
            withContext(NonCancellable) {
                bluetoothTransport?.disconnect()
                bleTransport?.disconnect()
                wifiTransport?.disconnect()
                internetTransport?.disconnect()
                bluetoothTransport = null
                bleTransport = null
                wifiTransport = null
                internetTransport = null
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }
    
    private fun updateConnectionState() {
        val wifi = wifiTransport
        val bt = bluetoothTransport
        val ble = bleTransport
        val internet = internetTransport
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

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
            } else if (btReconnectJob?.isActive == true && bluetoothAdapter?.isEnabled == true) {
                ConnectionState.Waiting
            } else {
                ConnectionState.Disconnected
            }
        }

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
        
        Log.i(TAG, "Status Update: Connected=$isAnyConnected ($typeString), State=${newState.javaClass.simpleName}")
    }
    

    fun isWifiConnected(): Boolean = wifiTransport?.isConnected() == true
    
    fun temporarilyDisableWifi() {
        wifiTemporarilyDisabled = true
        scope.launch {
            wifiTransport?.disconnect()
            wifiTransport = null
            updateConnectionState()
        }
    }
    
    fun reenableWifi() {
        wifiTemporarilyDisabled = false
        // WiFi monitoring loop will pick it up automatically
    }
    
    fun forceWifiForPairing(mac: String) {
        wifiForceForPairing = true
        Log.d(TAG, "Forcing WiFi for pairing: $mac")
        // WiFi monitoring loop will pick it up automatically
    }
    
    fun stopForcedWifiPairing() {
        wifiForceForPairing = false
        Log.d(TAG, "Stopped forcing WiFi for pairing")
    }
    
    /**
     * Connection Health Monitor - ensures monitoring loops stay running
     */
    fun startConnectionHealthMonitor(deviceMac: String) {
        if (connectionHealthMonitor?.isActive == true) return
        connectionHealthMonitor?.cancel()
        
        connectionHealthMonitor = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting connection health monitor")
            while (isActive) {
                try {
                    val btRunning = btReconnectJob?.isActive == true
                    val wifiRunning = wifiMonitorJob?.isActive == true
                    val internetRunning = internetMonitorJob?.isActive == true
                    val btEnabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
                    
                    // Restart BT monitoring if it stopped
                    if (btEnabled && !btRunning) {
                        Log.w(TAG, "Health monitor: BT reconnect stopped - restarting")
                        val adapter = BluetoothAdapter.getDefaultAdapter()
                        val device = adapter?.getRemoteDevice(deviceMac)
                        device?.let { startBluetoothReconnect(it) }
                    }
                    
                    // Restart WiFi monitoring if it stopped (respects WiFi rules)
                    // WiFi monitoring has its own shouldConnectWifi() check
                    if (!wifiRunning) {
                        Log.w(TAG, "Health monitor: WiFi monitoring stopped - restarting")
                        startWifiMonitoring(deviceMac)
                    }
                    
                    if (!internetRunning) {
                        Log.w(TAG, "Health monitor: Internet monitoring stopped - restarting")
                        startInternetMonitoring(deviceMac)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection health monitor error (non-fatal): ${e.message}", e)
                    // Continue running despite errors
                }
                
                delay(10000) // Check every 10 seconds
            }
        }
    }
}
