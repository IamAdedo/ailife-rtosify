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

    companion object {
        private const val TAG = "TransportManager"
        private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        // WiFi Rules (matching BluetoothService/NetworkSettingsActivity)
        const val WIFI_RULE_BT_FALLBACK = 1
        const val WIFI_RULE_MAINACTIVITY = 2
        const val WIFI_RULE_ALWAYS = 4
    }

    private var bluetoothTransport: BluetoothTransport? = null
    private var wifiTransport: WifiIntranetTransport? = null

    // Jobs
    private var btReconnectJob: Job? = null
    private var wifiMonitorJob: Job? = null
    private var connectionHealthMonitor: Job? = null
    
    @Volatile private var isConnectingWifi = false
    
    @Volatile private var wifiTemporarilyDisabled = false
    @Volatile private var wifiForceForPairing = false
    
    // Message handling
    private val _incomingMessages = Channel<ProtocolMessage>(Channel.BUFFERED)
    val incomingMessages: Flow<ProtocolMessage> = _incomingMessages.receiveAsFlow()

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Waiting : ConnectionState()
        data class Connected(val type: String, val deviceName: String?, val deviceMac: String? = null) : ConnectionState()
    }

    /**
     * Starts the client logic: Auto-reconnect Bluetooth and WiFi monitoring.
     */
    fun startClient(targetDevice: BluetoothDevice) {
        startBluetoothReconnect(targetDevice)
        startWifiMonitoring(targetDevice.address)
        startConnectionHealthMonitor(targetDevice.address)
    }

    private fun startBluetoothReconnect(device: BluetoothDevice) {
        if (btReconnectJob?.isActive == true) return
        btReconnectJob?.cancel()

        btReconnectJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting Bluetooth reconnect loop for ${device.name}")
            _connectionState.value = ConnectionState.Connecting

            var consecutiveFailures = 0
            val maxConsecutiveFailures = 10

            while (isActive) {
                // Determine if we should try to connect BT
                // Generally yes, unless disabled
                // We use BluetoothAdapter to check state
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter?.isEnabled != true) {
                    Log.w(TAG, "Bluetooth disabled, waiting...")
                    delay(5000)
                    continue
                }

                // Try to connect
                var socket: BluetoothSocket? = null
                try {
                    socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                    socket.connect()
                    consecutiveFailures = 0
                } catch (e: IOException) {
                    Log.w(TAG, "BT Connect failed: ${e.message}")
                    consecutiveFailures++
                    socket = null // Ensure null
                    
                    // Logic to stop retrying after too many failures?
                    // Previous code: update status to Disconnected but keeps checking?
                    // We'll mimic the retry with backoff.
                    delay(if(consecutiveFailures > 5) 3000 else 1000)
                    continue
                }

                if (socket != null) {
                    handleBluetoothConnection(socket)
                }
            }
        }
    }

    private suspend fun handleBluetoothConnection(socket: BluetoothSocket) {
        val deviceName = socket.remoteDevice?.name ?: "Unknown"
        Log.d(TAG, "Bluetooth connected: $deviceName")

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
                Log.d(TAG, "Bluetooth disconnected")
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
                            // Wait after failed attempt before retrying
                            delay(3000)
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

    private fun shouldWifiBeEnabled(): Boolean {
        // Force WiFi on during pairing
        if (wifiForceForPairing) return true
        
        // Don't connect if temporarily disabled (e.g., during re-pairing)
        if (wifiTemporarilyDisabled) return false
        
        val prefs = devicePrefManager.getActiveDevicePrefs()
        val rule = prefs.getInt("wifi_activation_rule", WIFI_RULE_BT_FALLBACK)
        
        // Always rule takes precedence
        if ((rule and WIFI_RULE_ALWAYS) != 0) return true
        
        var shouldEnable = false
        
        // BT Fallback rule
        if ((rule and WIFI_RULE_BT_FALLBACK) != 0) {
            val btConnected = bluetoothTransport?.isConnected() == true
            if (!btConnected) shouldEnable = true
        }
        
        // App foreground rule (when any app UI is visible)
        if ((rule and WIFI_RULE_MAINACTIVITY) != 0) {
            if (isAppInForeground) shouldEnable = true
        }
        
        return shouldEnable
    }
    
    var isAppInForeground: Boolean = false
        set(value) {
            field = value
            // Force re-eval needs to happen in trigger
        }

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
        
        return false
    }

    fun stopAll() {
        btReconnectJob?.cancel()
        wifiMonitorJob?.cancel()
        
        scope.launch {
            bluetoothTransport?.disconnect()
            wifiTransport?.disconnect()
            bluetoothTransport = null
            wifiTransport = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }
    
    private fun updateConnectionState() {
        val wifi = wifiTransport
        val bt = bluetoothTransport
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        
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
                } else if (btReconnectJob?.isActive == true && bluetoothAdapter?.isEnabled == true) {
                    ConnectionState.Waiting
                } else {
                    ConnectionState.Disconnected
                }
            }
        }
        
        _connectionState.value = newState
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
                } catch (e: Exception) {
                    Log.e(TAG, "Connection health monitor error (non-fatal): ${e.message}", e)
                    // Continue running despite errors
                }
                
                delay(10000) // Check every 10 seconds
            }
        }
    }
}
