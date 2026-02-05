package com.ailife.rtosify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ailife.rtosify.widget.HealthWidget
import com.ailife.rtosify.widget.StatusWidget
import android.appwidget.AppWidgetManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import com.ailife.rtosify.media.VideoCompressor
import com.ailife.rtosify.utils.DeviceActionManager

class BluetoothService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var connectionJob: Job? = null
    // REMOVED: serverJob - phone app no longer acts as server
    private var statusUpdateJob: Job? = null

    // REMOVED: bluetoothAdapter, bluetoothSocket, APP_UUID, APP_NAME - handled by TransportManager

    private lateinit var prefs: SharedPreferences
    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()
    private val PREF_NAME = "AppPrefs"

    private var lastValidWifiSsid: String = ""
    private var currentConnectionJob: Job? = null

    // Transport Manager
    lateinit var transportManager: com.ailife.rtosify.communication.TransportManager
    
    private var transportSwitchJob: Job? = null
    private var mdnsDiscovery: com.ailife.rtosify.communication.MdnsDiscovery? = null
    private lateinit var encryptionManager: com.ailife.rtosify.security.EncryptionManager

    // Clipboard monitoring
    private var clipboardManager: android.content.ClipboardManager? = null
    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? =
            null
    private var lastClipboardText: String? = null
    private val clipboardPollingHandler = Handler(Looper.getMainLooper())
    private var clipboardPollingRunnable: Runnable? = null

    // Shizuku UserService for Android 10+ clipboard access
    private var userServiceConnection: Shizuku.UserServiceArgs? = null
    var userService: IUserService? = null
    private val shizukuLock = Any()

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
                        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
                )
                .daemon(false)
                .processNameSuffix("user_service")
                .debuggable(BuildConfig.DEBUG)
                .version(1)
    }

    private val userServiceConn =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null && binder.pingBinder()) {
                        userService = IUserService.Stub.asInterface(binder)
                        deviceActionManager.updateUserService(userService)
                        Log.i(TAG, "UserService connected successfully")
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    userService = null
                    deviceActionManager.updateUserService(null)
                    Log.w(TAG, "UserService disconnected")
                }
            }
            
    private fun shizukuAvailable(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    // Media Session Listener
    private var mediaSessionListener: MediaSessionListener? = null
    
    // File Observer Manager
    private var fileObserverManager: FileObserverManager? = null

    // Device Action Manager
    private lateinit var deviceActionManager: DeviceActionManager

    @Volatile private var lastMessageTime: Long = 0L

    @Volatile private var isTransferring: Boolean = false
    @Volatile private var isTransferCancelled: Boolean = false

    // File Reception Variables
    private var receiveFile: File? = null
    private var receiveFileOutputStream: FileOutputStream? = null
    private var receiveTotalSize: Long = 0
    private var receiveBytesRead: Long = 0

// REMOVED: HEARTBEAT_INTERVAL, CONNECTION_TIMEOUT - defined in TransportManager



    // REFACTORING: More robust notification control
    @Volatile private var currentNotificationStatus: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Bluetooth enforcement
    private val btEnforcementHandler = Handler(Looper.getMainLooper())
    private val btEnforcementRunnable = object : Runnable {
        override fun run() {
            if (activePrefs.getBoolean("force_bt_enabled", false)) {
                serviceScope.launch {
                    try {
                        enforceBluetooth()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in Bluetooth enforcement: ${e.message}")
                    }
                }
            }
            btEnforcementHandler.postDelayed(this, 10000) // Check every 10 seconds
        }
    }

    private suspend fun enforceBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter != null && !adapter.isEnabled) {
            Log.i(TAG, "Force Bluetooth Enabled is active. Triggering enforcement...")
            deviceActionManager.setBluetoothEnabled(true)
        }
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    @Volatile private var isStopping = false

    interface ServiceCallback {
        fun onStatusChanged(status: String)
        fun onDeviceConnected(deviceName: String)
        fun onDeviceDisconnected()
        fun onTransportStatusChanged(status: com.ailife.rtosify.communication.TransportManager.TransportStatus) {}
        fun onError(message: String)
        fun onScanResult(devices: List<BluetoothDevice>)
        fun onAppListReceived(appsJson: String)
        fun onUploadProgress(progress: Int)
        fun onDownloadProgress(progress: Int, file: java.io.File? = null)
        fun onFileListReceived(path: String, filesJson: String)
        fun onWatchStatusUpdated(
                batteryLevel: Int,
                isCharging: Boolean,
                wifiSsid: String,
                wifiEnabled: Boolean,
                dndEnabled: Boolean,
                ipAddress: String? = null,
                wifiState: String? = null
        ) {}
        fun onHealthDataUpdated(healthData: HealthDataUpdate) {}
        fun onHealthHistoryReceived(historyData: HealthHistoryResponse) {}
        fun onHealthSettingsReceived(settings: HealthSettingsUpdate) {}
        fun onPreviewReceived(path: String, imageBase64: String?, textContent: String?) {}
        fun onWifiScanResultsReceived(results: List<WifiScanResultData>) {}
        fun onBatteryDetailReceived(data: BatteryDetailData) {}
        fun onDeviceInfoReceived(info: DeviceInfoData) {}
        fun onShellCommandResponse(response: ShellCommandResponse) {}
        fun onPermissionInfoReceived(info: PermissionInfoData) {}
        fun onWifiKeyAck(success: Boolean) {}
        fun onWifiTestAck(success: Boolean) {}
        fun onWifiTestReceived(message: String) {}
        fun onTransferCancelled() {}
    }

    interface AlarmCallback {
        fun onAlarmsReceived(alarms: List<AlarmData>)
    }

    var callback: ServiceCallback? = null
    var alarmCallback: AlarmCallback? = null

    @Volatile var currentStatus: String = "" // Will be initialized in onCreate
    @Volatile var currentDeviceName: String? = null
    val isConnected: Boolean get() = if (::transportManager.isInitialized) transportManager.isAnyTransportConnected() else false
    
    fun isWifiTransportActive(): Boolean {
        return transportManager.isWifiConnected()
    }

    fun isWifiConnected(): Boolean {
        return transportManager.isWifiConnected()
    }

    fun isInternetConnected(): Boolean {
        val state = transportManager.connectionState.value
        return state is com.ailife.rtosify.communication.TransportManager.ConnectionState.Connected &&
               state.type.contains("Internet")
    }

    fun isPairedWithCurrentDevice(): Boolean {
        val mac = getConnectedDeviceMac() ?: return false
        return encryptionManager.hasKey(mac)
    }

    fun unpairWifi() {
        val mac = getConnectedDeviceMac() ?: return
        encryptionManager.removeDeviceKeys(mac)
        Log.d(TAG, "Unpaired WiFi for $mac")
    }

    fun getConnectedDeviceMac(): String? {
        val lastMac = devicePrefManager.getSelectedDeviceMac()
        // We'll trust the preference if connected, or return null if not or unknown
        return if (transportManager.connectionState.value is com.ailife.rtosify.communication.TransportManager.ConnectionState.Connected) {
            lastMac
        } else null
    }

    fun cancelTransfer() {
        isTransferCancelled = true
        
        // Always notify UI to ensure it doesn't get stuck
        serviceScope.launch(Dispatchers.Main) { 
            callback?.onTransferCancelled() 
        }
        
        if (isTransferring || receivingFileStream != null) {
            // Active Cleanup for Receiving: Don't wait for next chunk, kill it now.
            if (receivingFileStream != null) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        // Notify sender to stop
                        sendMessage(ProtocolHelper.createFileTransferEnd(success = false, error = "Cancelled by user"))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending cancel message: ${e.message}")
                    }
                    
                    cleanupFileTransfer()
                }
            }
        }
    }

    fun getEncryptionKeyForCurrentDevice(): String? {
        val mac = getConnectedDeviceMac() ?: return null
        return encryptionManager.exportKey(mac)
    }

    fun sendWifiKeyExchange(key: String) {
        val watchMac = getConnectedDeviceMac() ?: return
        // Use discovered_local_mac (real MAC from watch) if available, otherwise fallback to adapter address
        val phoneMac = prefs.getString("discovered_local_mac", null)
            ?: BluetoothAdapter.getDefaultAdapter()?.address ?: ""
        sendMessage(ProtocolHelper.createWifiKeyExchange(phoneMac, watchMac, key))
    }

    fun startMdnsDiscovery(callback: (String) -> Unit) {
        mdnsDiscovery?.clearCache()
        mdnsDiscovery?.startDiscovery()
        serviceScope.launch {
            mdnsDiscovery?.getDiscoveredServices()?.collect {
                callback(it.host)
            }
        }
    }

    fun stopMdnsDiscovery() {
        mdnsDiscovery?.stop()
    }

    fun forceWifiForPairing(mac: String) {
        // Force WiFi on during pairing, regardless of rules
        transportManager.forceWifiForPairing(mac)
        Log.d(TAG, "Forcing WiFi connection for pairing: $mac")
    }

    fun stopForcedWifiPairing() {
        // Stop forcing WiFi after pairing completes
        transportManager.stopForcedWifiPairing()
        Log.d(TAG, "Stopped forcing WiFi for pairing")
    }

    fun sendWifiTestMessage(content: String) {
        sendMessage(ProtocolHelper.createWifiTestEncrypt(content))
    }

    fun triggerWifiConnectionForMainActivity() {
        transportManager.isAppInForeground = true
        // Sync state to watch
        if (isConnected) {
            sendMessage(ProtocolHelper.createSyncPhoneState(true))
        }
    }

    fun syncWifiRuleToCompanion(rule: Int) {
        sendMessage(ProtocolHelper.createUpdateWifiRule(rule))
    }

    fun notifyWifiRuleChanged() {
        transportManager.triggerWifiReevaluation()
    }

    private var wifiTemporarilyDisabled = false

    fun temporarilyDisableWifi() {
        wifiTemporarilyDisabled = true
        transportManager.temporarilyDisableWifi()
        Log.d(TAG, "WiFi temporarily disabled for re-pairing")
    }

    fun reenableWifi() {
        wifiTemporarilyDisabled = false
        transportManager.reenableWifi()
        Log.d(TAG, "WiFi re-enabled after re-pairing")
    }
    
    fun sendDynamicIslandBackground(base64: String, opacity: Int) {
        if (isConnected) {
            sendMessage(ProtocolHelper.createSetDynamicIslandBackground(base64, opacity))
        }
    }

    private val phoneStateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                        Log.d(TAG, "Phone State Changed: $state, Number: $number")

                        when (state) {
                            TelephonyManager.EXTRA_STATE_RINGING -> {
                                if (number != null && activePrefs.getBoolean("phone_call_forwarding_enabled", true)) {
                                    val callerName = getContactName(number)
                                    sendMessage(
                                            ProtocolHelper.createIncomingCall(number, callerName)
                                    )
                                }
                            }
                            TelephonyManager.EXTRA_STATE_IDLE -> {
                                sendMessage(ProtocolHelper.createCallStateChanged("IDLE"))
                            }
                            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                                sendMessage(ProtocolHelper.createCallStateChanged("OFFHOOK"))
                            }
                        }
                    }
                }
            }

    private fun getContactName(phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val uri =
                Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(phoneNumber)
                )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // JSON Protocol - message types defined in Protocol.kt

    companion object {
        const val ACTION_SEND_NOTIF_TO_WATCH = "com.ailife.rtosify.ACTION_SEND_NOTIF"
        const val ACTION_SEND_REMOVE_TO_WATCH = "com.ailife.rtosify.ACTION_SEND_REMOVE"
        const val ACTION_WATCH_DISMISSED_LOCAL = "com.ailife.rtosify.WATCH_DISMISSED_LOCAL"
        const val ACTION_APPS_RECEIVED = "com.ailife.rtosify.APPS_RECEIVED"
        const val ACTION_CMD_DISMISS_ON_PHONE = "com.ailife.rtosify.CMD_DISMISS_PHONE"
        const val ACTION_CMD_EXECUTE_ACTION = "com.ailife.rtosify.CMD_EXECUTE_ACTION"

        const val ACTION_CMD_SEND_REPLY = "com.ailife.rtosify.CMD_SEND_REPLY"
        const val ACTION_SEND_NAVIGATION_INFO = "com.ailife.rtosify.ACTION_SEND_NAVIGATION_INFO"

        const val EXTRA_NOTIF_JSON = "extra_notif_json"
        const val EXTRA_NOTIF_KEY = "extra_notif_key"
        const val EXTRA_NOTIF_CACHE_KEY = "extra_notif_cache_key"
        const val EXTRA_ACTION_KEY = "extra_action_key"
        const val EXTRA_REPLY_TEXT = "extra_reply_text"

        const val ACTION_UPDATE_SETTINGS = "com.ailife.rtosify.ACTION_UPDATE_SETTINGS"
        const val ACTION_RELOAD_FILE_RULES = "com.ailife.rtosify.ACTION_RELOAD_FILE_RULES"

        // DISTINCT IDs to ensure correct cleanup when changing states

        // DISTINCT IDs to ensure correct cleanup when changing states
        const val NOTIFICATION_ID_WAITING = 10
        const val NOTIFICATION_ID_CONNECTED = 11
        const val NOTIFICATION_ID_DISCONNECTED = 12

        const val INSTALL_NOTIFICATION_ID = 2


        const val CHANNEL_ID_WAITING = "channel_status_waiting"
        const val CHANNEL_ID_CONNECTED = "channel_status_connected"
        const val CHANNEL_ID_DISCONNECTED = "channel_status_disconnected"

        const val INSTALL_CHANNEL_ID = "install_channel"
        
        // LAN Activation Rules
        const val WIFI_RULE_BT_FALLBACK = 1      // Enable when BT disconnected
        const val WIFI_RULE_MAINACTIVITY = 2     // Enable when app is open
        const val WIFI_RULE_ALWAYS = 4           // Enable all the time
        const val WIFI_RULE_BT_OR_APP = 8        // Enable when BT disconnected OR app open


        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"

        const val ACTION_SCREEN_DATA_AVAILABLE = "com.ailife.rtosify.SCREEN_DATA_AVAILABLE"
        const val ACTION_SEND_REMOTE_INPUT = "com.ailife.rtosify.SEND_REMOTE_INPUT"
        const val ACTION_SCREEN_DATA_RECEIVED = "com.ailife.rtosify.SCREEN_DATA_RECEIVED"

        private const val TAG = "BluetoothService"
        private const val DEBUG_NOTIFICATIONS = false // Ative para debug
    }

    private val internalReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (!isConnected) return
                    when (intent?.action) {
                        ACTION_SEND_NOTIF_TO_WATCH -> {
                            var notifData: NotificationData? = null
                            val cacheKey = intent.getStringExtra(EXTRA_NOTIF_CACHE_KEY)

                            if (cacheKey != null) {
                                notifData = NotificationCache.remove(cacheKey)
                            }

                            if (notifData == null) {
                                val jsonString = intent.getStringExtra(EXTRA_NOTIF_JSON)
                                if (jsonString != null) {
                                    try {
                                        val gson = com.google.gson.Gson()
                                        notifData =
                                                gson.fromJson(jsonString, NotificationData::class.java)
                                    } catch (_: Exception) {}
                                }
                            }

                            if (notifData != null) {
                                sendMessage(ProtocolHelper.createNotificationPosted(notifData))
                            }
                        }
                        ACTION_SEND_REMOVE_TO_WATCH -> {
                            val key = intent.getStringExtra(EXTRA_NOTIF_KEY)
                            if (key != null) {
                                sendMessage(ProtocolHelper.createNotificationRemoved(key))
                            }
                        }
                        ACTION_SEND_NAVIGATION_INFO -> {
                            val image = intent.getStringExtra("image")
                            val title = intent.getStringExtra("title") ?: ""
                            val content = intent.getStringExtra("content") ?: ""
                            val keepScreenOn = intent.getBooleanExtra("keepScreenOn", true)
                            val packageName = intent.getStringExtra("packageName") ?: ""
                            
                            val navInfo = NavigationInfoData(
                                image = image,
                                title = title,
                                content = content,
                                keepScreenOn = keepScreenOn,
                                packageName = packageName
                            )
                            sendMessage(ProtocolHelper.createNavigationInfo(navInfo))
                        }
                        ACTION_UPDATE_SETTINGS -> {
                            Log.d(TAG, "Received ACTION_UPDATE_SETTINGS broadcast")
                            // updateHealthSettings() - Removed as per user request (irrelevant for transport refactor)
                            // syncSettingsToWatch() - Replaced by specific updates or implemented above if needed
                            // For now, assuming health settings are the primary settings to sync
                            
                            // SYNC ALL AUTOMATION AND DI SETTINGS
                            if (isConnected) {
                                sendAutomationSettings()
                                
                                if (activePrefs.getBoolean("clipboard_sync_enabled", false)) {
                                    startClipboardMonitoring()
                                } else {
                                    stopClipboardMonitoring()
                                }
                            }
                        }
                        StatusWidget.ACTION_TOGGLE_DND -> {
                             handleDndToggle()
                        }
                        StatusWidget.ACTION_TOGGLE_MIRROR -> {
                             handleMirrorToggle()
                        }
                        ACTION_RELOAD_FILE_RULES -> {
                            Log.d(TAG, "Reloading File Observer rules")
                            fileObserverManager?.reload()
                        }
                    }
                }
            }

    private val watchDismissReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_WATCH_DISMISSED_LOCAL) {
                        val key = intent.getStringExtra(EXTRA_NOTIF_KEY)
                        if (key != null && isConnected) {
                            sendMessage(ProtocolHelper.createDismissNotification(key))
                        }
                    }
                }
            }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        devicePrefManager = DevicePrefManager(this)
        prefs = devicePrefManager.getGlobalPrefs()
        deviceActionManager = DeviceActionManager(this, userService) {
            bindUserServiceIfNeeded()
        }
        createNotificationChannel()
        
        // Initialize Transport Manager
        encryptionManager = com.ailife.rtosify.security.EncryptionManager(this)
        mdnsDiscovery = com.ailife.rtosify.communication.MdnsDiscovery(this)

        transportManager = com.ailife.rtosify.communication.TransportManager(
            this,
            serviceScope,
            encryptionManager,
            mdnsDiscovery!!,
            devicePrefManager
        )
        
        serviceScope.launch {
            transportManager.incomingMessages.collect { message ->
                lastMessageTime = System.currentTimeMillis()
                processReceivedMessage(message)
            }
        }
        
        serviceScope.launch {
            transportManager.status.collect { status ->
                when(val state = status.state) {
                    is com.ailife.rtosify.communication.TransportManager.ConnectionState.Connected -> {
                         // Prefer stored name if available to avoid stale Bluetooth name overwriting protocol-synced name
                         val storedName = status.deviceMac?.let { mac ->
                             devicePrefManager.getPairedDevices().find { it.mac == mac }?.name
                         }
                         currentDeviceName = storedName ?: status.deviceName ?: getString(R.string.device_name_default)
                         
                         // Update selected device MAC in preferences
                         if (status.deviceMac != null) {
                             devicePrefManager.setSelectedDeviceMac(status.deviceMac)
                             // DO NOT update device name here from Bluetooth device name - it's often stale
                             // Device name will be updated from protocol in handleDeviceInfoUpdate which has the correct name
                         }
                         
                         // Show transport type in status - handle combined types
                         val statusText = buildConnectionStatusText(status.typeString, currentDeviceName ?: "")
                         updateStatus(statusText)
                         Log.d(TAG, "Device connected via ${status.typeString}: $currentDeviceName")
                         
                         // Trigger activity callback
                         mainHandler.post {
                             callback?.onDeviceConnected(currentDeviceName ?: "")
                             callback?.onTransportStatusChanged(status)
                         }
                         
                         // Start periodic status updates when connected
                         if (statusUpdateJob?.isActive != true) {
                             startPeriodicUpdates()
                         }
                         // Sync foreground state immediately on connection
                         val isForeground = transportManager.isAppInForeground
                         Log.d(TAG, "Connection status updated (${status.typeString}), syncing foreground state: $isForeground")
                         sendMessage(ProtocolHelper.createSyncPhoneState(isForeground))
                    }
                    is com.ailife.rtosify.communication.TransportManager.ConnectionState.Disconnected -> {
                         currentDeviceName = null
                         Log.d(TAG, "Device disconnected")
                         updateStatus(getString(R.string.status_disconnected))
                         
                         // Trigger activity callback
                         mainHandler.post {
                             callback?.onDeviceDisconnected()
                             callback?.onTransportStatusChanged(status)
                         }
                         
                         statusUpdateJob?.cancel()
                    }
                    is com.ailife.rtosify.communication.TransportManager.ConnectionState.Connecting -> {
                         currentDeviceName = null
                         updateStatus(getString(R.string.status_starting))
                         mainHandler.post { callback?.onTransportStatusChanged(status) }
                    }
                    is com.ailife.rtosify.communication.TransportManager.ConnectionState.Waiting -> {
                         currentDeviceName = null
                         updateStatus(getString(R.string.status_stopped))
                         mainHandler.post { callback?.onTransportStatusChanged(status) }
                    }
                }
            }
        }

        val filterInternal =
                IntentFilter().apply {
                    addAction(ACTION_SEND_NOTIF_TO_WATCH)
                    addAction(ACTION_SEND_REMOVE_TO_WATCH)
                    addAction(ACTION_SEND_NAVIGATION_INFO)
                    addAction(ACTION_UPDATE_SETTINGS)
                    addAction(StatusWidget.ACTION_TOGGLE_DND)
                    addAction(StatusWidget.ACTION_TOGGLE_MIRROR)
                    addAction(ACTION_RELOAD_FILE_RULES)
                }
        val filterWatch = IntentFilter(ACTION_WATCH_DISMISSED_LOCAL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                    this,
                    internalReceiver,
                    filterInternal,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                    this,
                    watchDismissReceiver,
                    filterWatch,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(internalReceiver, filterInternal)
            registerReceiver(watchDismissReceiver, filterWatch)
        }

        val filterPhone = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                    this,
                    phoneStateReceiver,
                    filterPhone,
                    ContextCompat.RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(phoneStateReceiver, filterPhone)
        }

        val filterMirror =
                IntentFilter().apply {
                    addAction(ACTION_SCREEN_DATA_AVAILABLE)
                    addAction(ACTION_SEND_REMOTE_INPUT)
                    addAction(MirroringService.ACTION_MIRROR_STATE_CHANGED)
                    addAction("com.ailife.rtosify.SEND_MIRROR_STOP")
                    addAction("com.ailife.rtosify.UPDATE_REMOTE_RESOLUTION")
                }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                    this,
                    mirroringReceiver,
                    filterMirror,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(mirroringReceiver, filterMirror)
        }

        // Bind to Shizuku UserService if available (for Android 10+ clipboard)
        bindUserServiceIfNeeded()
        
        // Register app lifecycle callbacks to track foreground/background state
        // This enables the WiFi rule "when app open" to work correctly
        // Register process lifecycle observer to track app foreground/background state
        // This correctly handles the entire app process, not individual activities
        registerProcessLifecycleObserver()

        if (DEBUG_NOTIFICATIONS) Log.d(TAG, "onCreate: Service created")
        
        if (activePrefs.getBoolean("force_bt_enabled", false)) {
            startBluetoothEnforcement()
        }
        
        // Initialize MediaSessionListener
        mediaSessionListener = MediaSessionListener(this, object : MediaSessionListener.MediaStateCallback {
            override fun onMediaStateChanged(mediaState: MediaStateData) {
               if (isConnected) {
                    sendMediaState(mediaState)
                }
            }
        })
        mediaSessionListener?.start()
        
        // Initialize File Observer
        fileObserverManager = FileObserverManager(this, {
             try {
                if (userService == null && shizukuAvailable()) {
                     ensureUserServiceBound()
                     // Wait briefly for bind
                     kotlinx.coroutines.delay(500)
                }
                userService
             } catch(e: Exception) {
                 null
             }
        }) { data ->
            if (isConnected) {
                // Send detected file info to watch
                serviceScope.launch {
                    sendMessage(ProtocolHelper.createFileDetected(data))
                }
            }
        }
        
        // Initialize mirroring state
        transportManager.updateMirroringState(MirroringService.isRunning)
        
        // Register direct frame callback
        MirroringService.frameCallback = { data, isKeyFrame ->
            if (isConnected) {
                // Use runBlocking to enforce backpressure.
                // This blocks the encoding thread until the frame is sent or dropped.
                // Since this runs on a background thread (drainAndSend), it is safe.
                try {
                    kotlinx.coroutines.runBlocking {
                        transportManager.send(ProtocolHelper.createMirrorData(data, isKeyFrame))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending mirror frame", e)
                }
            }
        }
    }
    
    private fun registerProcessLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // App process came to foreground
                    Log.d(TAG, "App process entered foreground")
                    transportManager.isAppInForeground = true
                    transportManager.triggerWifiReevaluation()
                    
                    // Sync state to watch
                    if (isConnected) {
                        sendMessage(ProtocolHelper.createSyncPhoneState(true))
                    }
                }
                
                override fun onStop(owner: LifecycleOwner) {
                    // App process went to background
                    Log.d(TAG, "App process entered background")
                    transportManager.isAppInForeground = false
                    transportManager.triggerWifiReevaluation()
                    
                    // Sync state to watch
                    if (isConnected) {
                        // Keep "open" state if mirroring is running
                        val effectiveForeground = MirroringService.isRunning
                        sendMessage(ProtocolHelper.createSyncPhoneState(effectiveForeground))
                    }
                }
            }
        )
    }

    private fun bindUserServiceIfNeeded() {
        try {
            if (Shizuku.pingBinder() &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "Binding to Shizuku UserService")
                Shizuku.bindUserService(userServiceArgs, userServiceConn)
                userServiceConnection = userServiceArgs
            } else {
                Log.w(TAG, "❌ Shizuku not available or permission not granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to bind UserService: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun ensureUserServiceBound() {
        if (userService == null) {
            synchronized(shizukuLock) {
                if (userService == null) {
                    Log.d(TAG, "UserService is null, attempting to bind...")

                    try {
                        if (!Shizuku.pingBinder()) {
                            Log.w(TAG, "Shizuku binder not available")
                            return
                        }
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Log.w(TAG, "Shizuku permission not granted")
                            return
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking Shizuku availability: ${e.message}")
                        return
                    }

                    bindUserServiceIfNeeded()
                }
            }

            val maxWaitTime = 3000L
            val startTime = System.currentTimeMillis()
            while (userService == null && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                delay(100)
            }

            if (userService != null) {
                Log.i(
                        TAG,
                        "✅ UserService bound successfully after ${System.currentTimeMillis() - startTime}ms"
                )
            } else {
                Log.w(TAG, "❌ UserService binding timed out after ${maxWaitTime}ms")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // CRITICAL: Call startForeground immediately to fulfill the promise made in the
        // BootReceiver.
        updateForegroundNotification()

        if (intent?.action == "STOP_ALARM") {
            stopFindPhoneAlarm()
            return START_NOT_STICKY
        }

        // Handle notification action execution
        if (intent?.action == "EXECUTE_ACTION_ON_PHONE") {
            val notifKey = intent.getStringExtra(EXTRA_NOTIF_KEY)
            val actionKey = intent.getStringExtra(EXTRA_ACTION_KEY)
            if (notifKey != null && actionKey != null) {
                sendMessage(ProtocolHelper.createExecuteNotificationAction(notifKey, actionKey))
            }
            return START_NOT_STICKY
        }

        // Handle notification reply
        if (intent?.action == "SEND_REPLY_TO_PHONE") {
            val notifKey = intent.getStringExtra(EXTRA_NOTIF_KEY)
            val actionKey = intent.getStringExtra(EXTRA_ACTION_KEY)
            val replyText = intent.getStringExtra(EXTRA_REPLY_TEXT)
            if (notifKey != null && actionKey != null && replyText != null) {
                sendMessage(
                        ProtocolHelper.createSendNotificationReply(notifKey, actionKey, replyText)
                )
            }
            return START_NOT_STICKY
        }

        // Initialize logic if not connected
        if (!isConnected) {
            initializeLogicFromPrefs()
        }

        // Handle widget actions
    if (intent?.action != null && intent.action!!.startsWith("com.ailife.rtosify.widget.ACTION_TOGGLE")) {
        Log.d(TAG, "onStartCommand: Received widget action: ${intent.action}")
        when (intent.action) {
            StatusWidget.ACTION_TOGGLE_DND -> handleDndToggle()
            StatusWidget.ACTION_TOGGLE_MIRROR -> handleMirrorToggle()
        }
    }

    if (DEBUG_NOTIFICATIONS)
            Log.d(TAG, "onStartCommand: Service started, isConnected=$isConnected")

        isStopping = false // Reset on start
        return START_STICKY
    }

    private fun initializeLogicFromPrefs() {
        if (!prefs.getBoolean("service_enabled", true)) return
        
        // Initialize DND state from system
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        dndEnabled = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        
        // RTOSify app is phone-only, always start smartphone logic
        startSmartphoneLogic()
    }

    // REMOVED: checkAndEnableBluetooth() - This method was blocking service operation when BT disabled
    // Bluetooth checks should only be done in BluetoothTransport, not at service level

    @SuppressLint("MissingPermission")
    fun startSmartphoneLogic() {
        val lastMac = devicePrefManager.getSelectedDeviceMac()
        if (lastMac != null) {
            val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter
            val device = adapter?.getRemoteDevice(lastMac)
            if (device != null) {
                transportManager.startClient(device)
            } else {
                 Log.e(TAG, "Cannot get Bluetooth device for MAC: $lastMac")
                 callback?.onError(getString(R.string.error_invalid_mac))
            }
        } else {
            Log.e(TAG, "No device MAC configured. User must pair device in WelcomeActivity first.")
            updateStatus(getString(R.string.status_no_watch_found))
            callback?.onError(getString(R.string.status_open_app_watch))
        }
    }

    fun reconnect() {
        transportManager.stopAll()
        startSmartphoneLogic()
    }

    // DELETED: startScanForDevices() - Phone should NOT scan for devices
    // Device MAC is already known from WelcomeActivity pairing
    // Scanning all paired devices (headphones, cars, etc.) wastes battery and creates security risk

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        devicePrefManager.setSelectedDeviceMac(device.address)
        devicePrefManager.addPairedDevice(device.name ?: "Watch", device.address)
    }

    @SuppressLint("MissingPermission")
    // Old handlers removed - replaced by TransportManager


    private suspend fun processReceivedMessage(message: ProtocolMessage) {
        when (message.type) {
            MessageType.HEARTBEAT -> {
                /* IGNORE */
            }
// REMOVED: MessageType.REQUEST_APPS -> handleRequestApps()
            MessageType.RESPONSE_APPS -> handleResponseApps(message)
// REMOVED: MessageType.NOTIFICATION_POSTED -> showMirroredNotification(message)
// REMOVED: MessageType.NOTIFICATION_REMOVED -> dismissLocalNotification(message)
            MessageType.DISMISS_NOTIFICATION -> requestDismissOnPhone(message)
            MessageType.EXECUTE_NOTIFICATION_ACTION ->
                    handleExecuteNotificationAction(message)
            MessageType.SEND_NOTIFICATION_REPLY -> handleSendNotificationReply(message)
            MessageType.FILE_TRANSFER_START -> handleFileTransferStart(message)
            MessageType.FILE_CHUNK -> handleFileChunk(message)
            MessageType.FILE_TRANSFER_END -> handleFileTransferEnd(message)
            MessageType.SHUTDOWN -> handleShutdownCommand()
            MessageType.REBOOT -> handleRebootCommand()
            MessageType.SET_WIFI -> handleSetWifiCommand(message)
            MessageType.UNINSTALL_APP -> handleUninstallApp(message)
            MessageType.LOCK_DEVICE -> handleLockDeviceCommand()
            MessageType.STATUS_UPDATE -> handleStatusUpdateReceived(message)
            MessageType.SET_DND -> handleSetDndCommand(message)
            MessageType.FIND_PHONE -> handleFindPhoneCommand(message)
            MessageType.FIND_DEVICE -> handleFindDeviceCommand(message)
            MessageType.FIND_DEVICE_LOCATION_REQUEST -> handleFindDeviceLocationRequest(message)
            MessageType.FIND_DEVICE_LOCATION_UPDATE -> handleFindDeviceLocationUpdate(message)
            MessageType.MEDIA_CONTROL -> handleMediaControl(message)
            MessageType.REQUEST_MEDIA_STATE -> handleRequestMediaState()
            MessageType.CAMERA_START -> handleCameraStart()
            MessageType.CAMERA_STOP -> handleCameraStop()
            MessageType.CAMERA_SHUTTER -> handleCameraShutter()
            MessageType.CAMERA_RECORD_START -> handleCameraRecordStart()
            MessageType.CAMERA_RECORD_STOP -> handleCameraRecordStop()
            MessageType.RESPONSE_FILE_LIST -> handleResponseFileList(message)
            MessageType.HEALTH_DATA_UPDATE -> handleHealthDataReceived(message)
            MessageType.RESPONSE_HEALTH_HISTORY -> handleHealthHistoryReceived(message)
            MessageType.RESPONSE_HEALTH_SETTINGS -> handleHealthSettingsReceived(message)
            MessageType.RESPONSE_PREVIEW -> handlePreviewReceived(message)
            MessageType.MAKE_CALL -> handleMakeCallCommand(message)
            MessageType.REJECT_CALL -> handleRejectCallCommand()
            MessageType.ANSWER_CALL -> handleAnswerCallCommand()
            MessageType.WIFI_SCAN_RESULTS -> handleWifiScanResults(message)
            MessageType.CLIPBOARD_SYNC -> handleClipboardReceived(message)
            MessageType.SHARE_SYNC -> handleShareReceived(message)
            MessageType.BATTERY_DETAIL_UPDATE -> handleBatteryDetailUpdate(message)
            MessageType.DEVICE_INFO_UPDATE -> handleDeviceInfoUpdate(message)
            MessageType.BATTERY_ALERT -> handleBatteryAlert(message)
            MessageType.RESPONSE_ALARMS -> handleResponseAlarms(message)
            MessageType.SCREEN_MIRROR_START -> handleMirrorStart(message)
            MessageType.SCREEN_MIRROR_STOP -> handleMirrorStop()
            MessageType.SCREEN_MIRROR_DATA -> handleMirrorData(message)
            MessageType.REMOTE_INPUT -> handleRemoteInput(message)
            MessageType.UPDATE_RESOLUTION -> handleUpdateResolution(message)
            MessageType.MIRROR_RES_CHANGE -> handleMirrorResChange(message)
            MessageType.REQUEST_PHONE_BATTERY -> handleRequestPhoneBattery()
            MessageType.SHELL_COMMAND_RESPONSE -> handleShellCommandResponse(message)
            MessageType.PERMISSION_INFO_RESPONSE -> handlePermissionInfoResponse(message)
            MessageType.WIFI_KEY_ACK -> handleWifiKeyAck(message)
            MessageType.WIFI_TEST_ACK -> handleWifiTestAck(message)
            MessageType.WIFI_TEST_ENCRYPT -> handleWifiTestReceived(message)
            MessageType.SYNC_MAC -> handleSyncMac(message)
            MessageType.NOTIFICATION_LITE -> Log.d(TAG, "Ignored NOTIFICATION_LITE on RTOSify")
            MessageType.SET_LITE_MODE -> Log.d(TAG, "Ignored SET_LITE_MODE on RTOSify")
            MessageType.REQUEST_FILE_DOWNLOAD -> handleRequestFileDownload(message)
            else -> Log.d(TAG, "Unknown message type: ${message.type}")
        }
    }

    // DELETED: readMessage() - Dead code, never called
    // Message reading is now handled by transport layer

// REMOVED: heartbeatLoop - now handled by TransportManager

    private fun forceDisconnect() {
        Log.d(TAG, "forceDisconnect: Ensuring mirroring stopped via TransportManager delegation.")
        if (MirroringService.isRunning) {
            Log.d(TAG, "forceDisconnect: Mirroring is active, stopping it.")
            val stopIntent = Intent(this, MirroringService::class.java)
            stopService(stopIntent)
        }

        transportManager.stopAll()
        statusUpdateJob?.cancel()
    }

    // Leftover WiFi management methods removed - logic moved to TransportManager

    
    /**
     * Start WiFi transport.
     */
    // Removed WifiIntranetTransport logic
        
    fun sendMessage(message: ProtocolMessage) {
        serviceScope.launch(Dispatchers.IO) {
            if (!transportManager.send(message)) {
                Log.w(TAG, "sendMessage: Failed to send message type=${message.type}")
            }
        }
    }

    // ========================================================================
    // STATUS DO WATCH (BATERIA, WIFI, DND)
    // ========================================================================

    private fun startPeriodicUpdates() {
        statusUpdateJob?.cancel()
        statusUpdateJob =
                serviceScope.launch(Dispatchers.IO) {
                    while (isActive && isConnected) {
                        try {
                            val status = collectWatchStatus()
                            sendMessage(ProtocolHelper.createStatusUpdate(status))
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao enviar status: ${e.message}")
                        }
                        delay(20000) // Increased delay to 20s for status updates
                    }
                }
    }

    @SuppressLint("MissingPermission")
    private fun collectWatchStatus(): StatusUpdateData {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // Use ACTION_BATTERY_CHANGED intent for reliable charging detection (same as DynamicIslandService)
        val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        Log.d("CHARGING_DEBUG", "PHONE collectWatchStatus: battery=$batteryLevel, isCharging=$isCharging, status=$status")

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val dndEnabled = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        var wifiSsid: String? = null
        var wifiState: String = "UNKNOWN"
        var displayWifi: String // Legacy/Display field

        try {
            if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
            ) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

                if (wm.isWifiEnabled) {
                    val info = wm.connectionInfo

                    if (info != null && info.supplicantState == SupplicantState.COMPLETED) {
                        val rawSsid = info.ssid

                        if (rawSsid == "<unknown ssid>" || rawSsid.isEmpty()) {
                            wifiState = "CONNECTED"
                            wifiSsid = lastValidWifiSsid.ifEmpty { null }
                            displayWifi = wifiSsid ?: getString(R.string.status_connected)
                        } else {
                            val cleanSsid = rawSsid.replace("\"", "")
                            if (cleanSsid != "<unknown ssid>") {
                                lastValidWifiSsid = cleanSsid
                                wifiSsid = cleanSsid
                                wifiState = "CONNECTED"
                                displayWifi = cleanSsid
                            } else if (lastValidWifiSsid.isNotEmpty()) {
                                wifiSsid = lastValidWifiSsid
                                wifiState = "CONNECTED"
                                displayWifi = lastValidWifiSsid
                            } else {
                                wifiState = "CONNECTED"
                                displayWifi = getString(R.string.status_connected)
                            }
                        }
                    } else {
                        lastValidWifiSsid = ""
                        wifiState = "DISCONNECTED"
                        displayWifi = getString(R.string.status_disconnected)
                    }
                } else {
                    lastValidWifiSsid = ""
                    wifiState = "DISABLED"
                    displayWifi = getString(R.string.status_wifi_off)
                }
            } else {
                wifiState = "NO_PERMISSION"
                displayWifi = getString(R.string.status_no_permission)
            }
        } catch (_: Exception) {
            wifiState = "ERROR"
            displayWifi = getString(R.string.status_wifi_error)
        }

        return StatusUpdateData(
                battery = batteryLevel,
                charging = isCharging,
                dnd = dndEnabled,
                ipAddress = getIpAddress(),
                wifiSsid = wifiSsid,
                wifiState = wifiState
        )
    }

    private fun getIpAddress(): String? {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null else {
                String.format(
                        "%d.%d.%d.%d",
                        (ip and 0xff),
                        (ip shr 8 and 0xff),
                        (ip shr 16 and 0xff),
                        (ip shr 24 and 0xff)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun handleStatusUpdateReceived(message: ProtocolMessage) {
        try {
            val status = ProtocolHelper.extractData<StatusUpdateData>(message)
            Log.d("CHARGING_DEBUG", "PHONE received STATUS_UPDATE: battery=${status.battery}, charging=${status.charging}")

            lastBatteryLevel = status.battery
            dndEnabled = status.dnd
            val state = status.wifiState ?: "UNKNOWN"
            val displayWifi = when (state) {
                "CONNECTED" -> status.wifiSsid ?: getString(R.string.status_connected)
                "DISCONNECTED" -> status.wifiSsid ?: getString(R.string.wifi_status_disconnected)
                "DISABLED", "OFF" -> status.wifiSsid ?: getString(R.string.wifi_status_disabled)
                "NO_PERMISSION" -> status.wifiSsid ?: getString(R.string.wifi_status_no_permission)
                else -> status.wifiSsid ?: state
            }
            
            lastWifiSsid = displayWifi // Keeping this as display string for now if used elsewhere
            updateWidgets()

            val isWifiEnabled = state != "DISABLED" && state != "OFF"

            withContext(Dispatchers.Main) {
                callback?.onWatchStatusUpdated(
                        status.battery,
                        status.charging,
                        displayWifi,
                        isWifiEnabled,
                        status.dnd,
                        status.ipAddress,
                        status.wifiState // Pass state too if callback updated
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro parser status: ${e.message}")
        }
    }

    private suspend fun handleHealthDataReceived(message: ProtocolMessage) {
        try {
            val healthData = ProtocolHelper.extractData<HealthDataUpdate>(message)
            
            lastSteps = healthData.steps
            lastHr = healthData.heartRate ?: 0
            lastSpo2 = healthData.bloodOxygen ?: 0
            updateWidgets()
            
            withContext(Dispatchers.Main) { callback?.onHealthDataUpdated(healthData) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing health data: ${e.message}")
        }
    }

    private suspend fun handleHealthHistoryReceived(message: ProtocolMessage) {
        try {
            val historyData = ProtocolHelper.extractData<HealthHistoryResponse>(message)
            withContext(Dispatchers.Main) { callback?.onHealthHistoryReceived(historyData) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing health history: ${e.message}")
        }
    }

    private suspend fun handleHealthSettingsReceived(message: ProtocolMessage) {
        try {
            val settings = ProtocolHelper.extractData<HealthSettingsUpdate>(message)
            withContext(Dispatchers.Main) { callback?.onHealthSettingsReceived(settings) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing health settings: ${e.message}")
        }
    }

    private suspend fun handleWifiScanResults(message: ProtocolMessage) {
        try {
            val resultsJson = message.data.getAsJsonArray("results")
            val listType = object : TypeToken<List<WifiScanResultData>>() {}.type
            val results: List<WifiScanResultData> =
                    ProtocolHelper.gson.fromJson(resultsJson, listType)
            withContext(Dispatchers.Main) { callback?.onWifiScanResultsReceived(results) }
        } catch (e: Exception) {
            Log.e(TAG, "Erro parser wifi scan results: ${e.message}")
        }
    }

    fun requestHealthData(type: String? = null) {
        sendMessage(ProtocolHelper.createRequestHealthData(type))
    }

    fun requestHealthHistory(type: String, startTime: Long, endTime: Long) {
        val request = HealthHistoryRequest(type, startTime, endTime)
        sendMessage(ProtocolHelper.createRequestHealthHistory(request))
    }

    fun startLiveMeasurement(type: String) {
        val request = LiveMeasurementRequest(type)
        sendMessage(ProtocolHelper.createStartLiveMeasurement(request))
    }

    fun stopLiveMeasurement() {
        sendMessage(ProtocolHelper.createStopLiveMeasurement())
    }

    fun updateHealthSettings(settings: HealthSettingsUpdate) {
        sendMessage(ProtocolHelper.createUpdateHealthSettings(settings))
    }

    fun requestHealthSettings() {
        sendMessage(ProtocolHelper.createRequestHealthSettings())
    }

    fun requestWifiScan() {
        sendMessage(ProtocolHelper.createRequestWifiScan())
    }

    fun connectToWifi(ssid: String, password: String?) {
        sendMessage(ProtocolHelper.createConnectWifi(ssid, password))
    }

    fun sendDndCommand(enable: Boolean) {
        sendMessage(ProtocolHelper.createSetDnd(enable))
    }

    fun sendWifiCommand(enable: Boolean) {
        sendMessage(ProtocolHelper.createSetWifi(enable))
    }

    private fun handleRequestPhoneBattery() {
        Log.d(TAG, "Handling dedicated phone battery request from Watch")
        serviceScope.launch(Dispatchers.IO) {
            try {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                // Use ACTION_BATTERY_CHANGED intent for reliable charging detection
                val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL
                Log.d("CHARGING_DEBUG", "PHONE handleRequestPhoneBattery: battery=$batteryLevel, isCharging=$isCharging, status=$status")

                sendMessage(ProtocolHelper.createPhoneBatteryUpdate(batteryLevel, isCharging))
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting phone battery data: ${e.message}")
            }
        }
    }

    // Clipboard sync functions
    fun sendShareSync(shareData: ShareData) {
        if (!activePrefs.getBoolean("sharing_sync_enabled", false)) return
        sendMessage(ProtocolHelper.createShareSync(shareData))
    }

    private fun startClipboardMonitoring() {
        serviceScope.launch {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                // Android 9-: Listener only (no polling needed)
                withContext(Dispatchers.Main) { startStandardClipboardListener() }
                Log.d(TAG, "Clipboard monitoring started (Android 9- mode - listener only)")
            } else {
                // Android 10+: Shizuku UserService polling (3s)
                ensureUserServiceBound()

                if (userService != null) {
                    withContext(Dispatchers.Main) { startClipboardPolling(3000) }
                    Log.d(TAG, "Clipboard monitoring started (Shizuku mode)")
                } else {
                    Log.w(
                            TAG,
                            "Shizuku UserService not available after waiting, clipboard sync disabled on Android 10+"
                    )
                }
            }
        }
    }

    private fun stopClipboardMonitoring() {
        Log.d(TAG, "Stopping clipboard monitoring")

        // Stop listener (Android 9-)
        clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        clipboardListener = null

        // Stop polling
        clipboardPollingRunnable?.let { clipboardPollingHandler.removeCallbacks(it) }
        clipboardPollingRunnable = null
    }

    private fun startStandardClipboardListener() {
        if (clipboardManager == null) {
            clipboardManager =
                    getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        }

        clipboardListener =
                android.content.ClipboardManager.OnPrimaryClipChangedListener {
                    val clip = clipboardManager?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text?.toString()
                        if (text != null && text != lastClipboardText) {
                            lastClipboardText = text
                            Log.d(TAG, "Clipboard changed (listener): $text")
                            if (isConnected) {
                                sendMessage(ProtocolHelper.createClipboardSync(text))
                            }
                        }
                    }
                }
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
    }

    private fun startClipboardPolling(intervalMs: Long) {
        clipboardPollingRunnable =
                object : Runnable {
                    override fun run() {
                        checkClipboard()
                        clipboardPollingHandler.postDelayed(this, intervalMs)
                    }
                }
        clipboardPollingHandler.postDelayed(clipboardPollingRunnable!!, intervalMs)
    }

    private fun checkClipboard() {
        val text =
                try {
                    userService?.primaryClipText
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading clipboard via Shizuku: ${e.message}")
                    null
                }

        if (text != null && text != lastClipboardText) {
            lastClipboardText = text
            Log.d(TAG, "Clipboard changed (polling): $text")
            if (isConnected) {
                sendMessage(ProtocolHelper.createClipboardSync(text))
            }
        }
    }

    fun requestWatchApps() {
        if (isConnected) {
            sendMessage(ProtocolHelper.createRequestApps())
        }
    }

    private suspend fun handleShareReceived(message: ProtocolMessage) {
        if (!activePrefs.getBoolean("sharing_sync_enabled", false)) return
        try {
            val shareData = ProtocolHelper.extractData<ShareData>(message)
            withContext(Dispatchers.Main) {
                showShareChooser(shareData.title ?: "Share", shareData.text, shareData.type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SHARE_SYNC: ${e.message}")
        }
    }

    private suspend fun handleClipboardReceived(message: ProtocolMessage) {
        val text = ProtocolHelper.extractStringField(message, "text") ?: return

        if (text == lastClipboardText) return
        lastClipboardText = text

        withContext(Dispatchers.Main) {
            Log.d(TAG, "Setting clipboard text: $text")

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                // Android 9-: Use standard ClipboardManager
                val clip = android.content.ClipData.newPlainText("RTOSify", text)
                clipboardManager?.setPrimaryClip(clip)
            } else {
                // Android 10+: Use Shizuku UserService
                try {
                    userService?.setPrimaryClipText(text)
                    Log.d(TAG, "Clipboard set via Shizuku")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting clipboard via Shizuku: ${e.message}")
                }
            }
        }
    }

    // Connection state automation
    private fun onConnectionEstablished() {
        serviceScope.launch(Dispatchers.IO) {
            delay(1000) // Debounce

            // Check clipboard pref
            if (activePrefs.getBoolean("clipboard_sync_enabled", false)) {
                startClipboardMonitoring()
            }

            // Send automation settings to watch (watch will handle the logic)
            sendAutomationSettings()
            
            // Sync foreground state twice (immediate and after debounce)
            sendMessage(ProtocolHelper.createSyncPhoneState(transportManager.isAppInForeground))
        }
        // Auto BT Tether: Enable phone BT tethering + watch internet
        if (activePrefs.getBoolean("auto_bt_tether_enabled", false)) {
            deviceActionManager.setBluetoothTetheringEnabled(true)
            sendMessage(ProtocolHelper.createEnableBluetoothInternet(true))
        }
    }

    private fun handleDeviceInfoUpdate(message: ProtocolMessage) {
        val info = ProtocolHelper.extractData<DeviceInfoData>(message)
        
        // Update device name if present
    if (info.deviceName != null && info.deviceName != "Unknown") {
        val mac = transportManager.status.value.deviceMac
        android.util.Log.d(TAG, "handleDeviceInfoUpdate: received name='${info.deviceName}' for MAC='$mac'")
        if (mac != null) {
            devicePrefManager.updateDeviceName(mac, info.deviceName)
            currentDeviceName = info.deviceName
            
            // Refresh status UI
            val statusText = buildConnectionStatusText(transportManager.status.value.typeString, info.deviceName)
            updateStatus(statusText)
            updateWidgets()
        } else {
            android.util.Log.w(TAG, "handleDeviceInfoUpdate: Cannot update name because current MAC is null")
        }
    }
        
        serviceScope.launch(Dispatchers.Main) { callback?.onDeviceInfoReceived(info) }
    }

    private fun onConnectionLost() {
        Log.d(
                TAG,
                "onConnectionLost: Cleaning up. Mirroring running: ${MirroringService.isRunning}"
        )

        if (MirroringService.isRunning) {
            Log.d(TAG, "onConnectionLost: Stopping MirroringService")
            val stopIntent = Intent(this, MirroringService::class.java)
            stopService(stopIntent)
        }

        serviceScope.launch(Dispatchers.IO) {
            stopClipboardMonitoring()

            // Auto BT Tether: Disable phone side (notify to disable manually)
            if (activePrefs.getBoolean("auto_bt_tether_enabled", false)) {
                // Watch will handle its own disconnection
            }

            // Note: WiFi/Data automation runs on watch side
            // Watch detects disconnection and handles WiFi/Data automatically
        }
    }

    private fun sendAutomationSettings() {
        val notifyVal = activePrefs.getBoolean("notify_on_disconnect", false)
        Log.d(TAG, "sendAutomationSettings: notify_on_disconnect = $notifyVal")
        
        val settings =
                SettingsUpdateData(
                        notifyOnDisconnect = notifyVal,
                        clipboardSyncEnabled =
                                activePrefs.getBoolean("clipboard_sync_enabled", false),
                        autoWifiEnabled = activePrefs.getBoolean("auto_wifi_enabled", false),
                        autoDataEnabled = activePrefs.getBoolean("auto_data_enabled", false),
                        autoBtTetherEnabled =
                                activePrefs.getBoolean("auto_bt_tether_enabled", false),
                        forceBtEnabled = activePrefs.getBoolean("force_bt_enabled", false),
                        shareSyncEnabled = activePrefs.getBoolean("sharing_sync_enabled", false),
                        internetActivationRule = activePrefs.getInt("internet_activation_rule", 0),
                        internetSignalingUrl = activePrefs.getString("internet_signaling_url", ""),
                        hqLanEnabled = activePrefs.getBoolean("hq_lan_enabled", false),
                aggressiveKeepaliveEnabled =
                        activePrefs.getBoolean("aggressive_keepalive_enabled", false),
                wakeScreenEnabled = activePrefs.getBoolean("wake_screen_enabled", false),
                wakeScreenDndEnabled = activePrefs.getBoolean("wake_screen_dnd_enabled", false),
                vibrateEnabled = activePrefs.getBoolean("vibrate_enabled", false),
                vibrateInSilentEnabled = activePrefs.getBoolean("vibrate_silent_enabled", false),
                vibrationStrength = activePrefs.getInt("vibration_strength", 2),
                vibrationPattern = activePrefs.getInt("vibration_pattern", 0)
                )
        sendMessage(ProtocolHelper.createUpdateSettings(settings))
        
        // Sync Dynamic Island settings separately
        syncDynamicIslandSettings()
        
        // Local enforcement update
        if (activePrefs.getBoolean("force_bt_enabled", false)) {
            startBluetoothEnforcement()
        } else {
            stopBluetoothEnforcement()
        }
    }

    fun syncDynamicIslandSettings() {
        Log.d(TAG, "syncDynamicIslandSettings: Sending all DI settings to watch")
        
        val settings = SettingsUpdateData(
                // Notification style
                notificationStyle = activePrefs.getString("notification_style", "android"),
                // Display settings
                dynamicIslandTimeout = activePrefs.getInt("dynamic_island_timeout", 5),
                dynamicIslandY = activePrefs.getInt("dynamic_island_y", 8),
                dynamicIslandWidth = activePrefs.getInt("dynamic_island_width", 150),
                dynamicIslandHeight = activePrefs.getInt("dynamic_island_height", 40),
                // Auto-hide settings
                dynamicIslandAutoHideMode = activePrefs.getInt("di_auto_hide_mode", 0),
                dynamicIslandBlacklistApps = activePrefs.getStringSet("di_blacklist_apps", emptySet())?.toList(),
                dynamicIslandHideWithActiveNotifs = activePrefs.getBoolean("di_hide_with_active_notifs", false),
                // Text settings
                dynamicIslandTextMultiplier = activePrefs.getFloat("dynamic_island_text_multiplier", 1.0f),
                dynamicIslandLimitMessageLength = activePrefs.getBoolean("dynamic_island_limit_message_length", true),
                // Feature toggles
                diShowPhoneCalls = activePrefs.getBoolean("di_show_phone_calls", true),
                diShowAlarms = activePrefs.getBoolean("di_show_alarms", true),
                diShowDisconnect = activePrefs.getBoolean("di_show_disconnect", true),
                diShowMedia = activePrefs.getBoolean("di_show_media", true),
                // New settings
                dynamicIslandFollowDnd = activePrefs.getBoolean("di_follow_dnd", false),
                dynamicIslandBlacklistHidePeak = activePrefs.getBoolean("di_blacklist_hide_peak", false),
                inAppReplyDialog = activePrefs.getBoolean("in_app_reply_dialog", false)
        )
        Log.d(TAG, "syncDynamicIslandSettings: inAppReplyDialog = ${settings.inAppReplyDialog}")
        sendMessage(ProtocolHelper.createUpdateSettings(settings))
    }

    private fun startBluetoothEnforcement() {
        Log.d(TAG, "Starting Bluetooth enforcement")
        btEnforcementHandler.removeCallbacks(btEnforcementRunnable)
        btEnforcementHandler.post(btEnforcementRunnable)
    }

    private fun stopBluetoothEnforcement() {
        Log.d(TAG, "Stopping Bluetooth enforcement")
        btEnforcementHandler.removeCallbacks(btEnforcementRunnable)
    }

    private fun wakeScreenForBluetooth() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isInteractive) {
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "rtosify:BtEnforceWakeLock"
                )
                wakeLock.acquire(3000)
                Log.d(TAG, "Screen woken up for Bluetooth enforcement")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen: ${e.message}")
        }
    }

    private fun triggerBluetoothEnableRequest() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ACTION_REQUEST_ENABLE: ${e.message}")
            // Fallback: Show toast
            mainHandler.post {
                Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            }
        }

    }

    private fun handleSetDndCommand(message: ProtocolMessage) {
        val enable = ProtocolHelper.extractBooleanField(message, "enabled")
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            if (enable) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            serviceScope.launch {
                delay(500)
                val status = collectWatchStatus()
                sendMessage(ProtocolHelper.createStatusUpdate(status))
            }
        }
    }

    private var findPhoneRingtone: Ringtone? = null

    private fun handleFindPhoneCommand(message: ProtocolMessage) {
        val enabled = ProtocolHelper.extractBooleanField(message, "enabled")
        if (enabled) {
            startFindPhoneAlarm()
        } else {
            stopFindPhoneAlarm()
        }
    }

    private fun handleFindDeviceCommand(message: ProtocolMessage) {
        val enabled = ProtocolHelper.extractBooleanField(message, "enabled")
        if (!enabled) {
            // Watch stopped the alarm, update phone UI
            val intent = Intent("com.ailife.rtosify.STOP_RINGING")
            sendBroadcast(intent)
            Log.i(TAG, "Watch stopped alarm, updating phone UI")
        }
    }

    private var findDeviceLocationListener: LocationListener? = null
    private var findDeviceRssiReceiver: BroadcastReceiver? = null
    private var bleRssiMonitor: com.ailife.rtosify.ble.BleRssiMonitor? = null
    @Volatile private var currentFindDeviceRssi: Int = 0

    private fun handleFindDeviceLocationRequest(message: ProtocolMessage) {
        val enabled = ProtocolHelper.extractBooleanField(message, "enabled")
        Log.i(TAG, "Location request received from watch: enabled=$enabled")
        
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        
        if (enabled) {
            // Start BLE RSSI Monitoring
            if (bleRssiMonitor == null) {
                currentFindDeviceRssi = 0
                bleRssiMonitor = com.ailife.rtosify.ble.BleRssiMonitor(this) { rssi ->
                    currentFindDeviceRssi = rssi
                    Log.d(TAG, "BLE RSSI updated: $rssi dBm")
                }

                val targetMac = getConnectedDeviceMac()
                val bleStarted = bleRssiMonitor?.startMonitoring(
                    btAdapter!!,
                    targetMac,
                    fallbackToClassic = {
                        // Fallback to Classic Bluetooth discovery if BLE fails
                        Log.w(TAG, "BLE monitoring failed, falling back to Classic Bluetooth discovery")
                        startClassicBluetoothDiscovery(btAdapter!!)
                    }
                ) ?: false

                if (!bleStarted) {
                    Log.w(TAG, "BLE monitoring failed to start, using Classic Bluetooth discovery")
                    startClassicBluetoothDiscovery(btAdapter!!)
                }
            }

            if (findDeviceLocationListener == null) {
                findDeviceLocationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        sendFindDeviceLocationUpdate(
                            location.latitude,
                            location.longitude,
                            location.accuracy,
                            currentFindDeviceRssi // Use actual scanned RSSI
                        )
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
                }
                
                try {
                        // 1. Send last known location immediately
                        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                            sendFindDeviceLocationUpdate(it.latitude, it.longitude, it.accuracy, currentFindDeviceRssi)
                        }

                        // 2. Request updates from GPS
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            1000L,
                            0f,
                            findDeviceLocationListener!!,
                            Looper.getMainLooper()
                        )
                        Log.i(TAG, "Started GPS updates for watch find request")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start location updates for watch request: ${e.message}")
                }
            }
        } else {
            // Stop BLE RSSI Monitoring
            bleRssiMonitor?.stopMonitoring()
            bleRssiMonitor = null

            // Stop Classic Bluetooth Discovery (if fallback was used)
            findDeviceRssiReceiver?.let {
                try { unregisterReceiver(it) } catch(e: Exception) {}
                findDeviceRssiReceiver = null
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                     btAdapter?.cancelDiscovery()
                }
            }

            findDeviceLocationListener?.let {
                locationManager.removeUpdates(it)
                findDeviceLocationListener = null
                Log.i(TAG, "Stopped GPS updates for watch find request")
            }
        }
    }

    // Fallback method for Classic Bluetooth discovery when BLE fails
    private fun startClassicBluetoothDiscovery(btAdapter: BluetoothAdapter) {
        if (findDeviceRssiReceiver == null) {
            findDeviceRssiReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        Log.d(TAG, "Classic Discovery found device: ${device?.name ?: "Unknown"} (${device?.address}) RSSI=$rssi")

                        val targetMac = getConnectedDeviceMac()
                        if (device != null && (device.address.equals(targetMac, ignoreCase = true) || (targetMac == null && device.bondState == BluetoothDevice.BOND_BONDED))) {
                            currentFindDeviceRssi = rssi
                            Log.d(TAG, "Target device found (${device.address})! RSSI=$rssi")
                        }
                    } else if (intent?.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                        if (findDeviceRssiReceiver != null) {
                            serviceScope.launch {
                                delay(2000)
                                if (findDeviceRssiReceiver != null && btAdapter.isEnabled) {
                                    if (ActivityCompat.checkSelfPermission(this@BluetoothService, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                                        btAdapter.startDiscovery()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            registerReceiver(findDeviceRssiReceiver, filter)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                btAdapter.startDiscovery()
            }
        }
    }

    private fun handleMediaControl(message: ProtocolMessage) {
        try {
            val controlData = ProtocolHelper.extractData<MediaControlData>(message)
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            when (controlData.command) {
                MediaControlData.CMD_PLAY,
                MediaControlData.CMD_PAUSE,
                MediaControlData.CMD_PLAY_PAUSE,
                MediaControlData.CMD_NEXT,
                MediaControlData.CMD_PREVIOUS -> {
                    // Try to use MediaSessionListener for direct control first
                    if (mediaSessionListener?.sendCommand(controlData.command) == true) {
                        Log.d(TAG, "Media command handled via MediaSessionListener: ${controlData.command}")
                        return
                    }

                    val keyCode =
                            when (controlData.command) {
                                MediaControlData.CMD_PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                                MediaControlData.CMD_PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                                MediaControlData.CMD_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                                MediaControlData.CMD_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                                MediaControlData.CMD_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                                else -> 0
                            }
                    if (keyCode != 0) {
                        try {
                            audioManager.dispatchMediaKeyEvent(
                                    KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                            )
                            audioManager.dispatchMediaKeyEvent(
                                    KeyEvent(KeyEvent.ACTION_UP, keyCode)
                            )
                        } catch (e: Exception) {
                            // Fallback to broadcast if dispatchMediaKeyEvent fails
                            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                            intent.putExtra(
                                    Intent.EXTRA_KEY_EVENT,
                                    KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                            )
                            sendOrderedBroadcast(intent, null)

                            val intentUp = Intent(Intent.ACTION_MEDIA_BUTTON)
                            intentUp.putExtra(
                                    Intent.EXTRA_KEY_EVENT,
                                    KeyEvent(KeyEvent.ACTION_UP, keyCode)
                            )
                            sendOrderedBroadcast(intentUp, null)
                        }
                    }
                }
                MediaControlData.CMD_VOL_UP -> {
                    audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                    )
                }
                MediaControlData.CMD_VOL_DOWN -> {
                    audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling media control: ${e.message}")
        }
    }

    private fun handleRequestMediaState() {
        try {
            val currentState = mediaSessionListener?.getCurrentState() ?: MediaStateData(
                isPlaying = false,
                title = null,
                artist = null,
                album = null,
                duration = 0L,
                position = 0L,
                volume = 0,
                albumArtBase64 = null
            )
            sendMediaState(currentState)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request media state: ${e.message}")
        }
    }
    
    private fun sendMediaState(mediaState: MediaStateData) {
        val message = ProtocolMessage(
            type = MessageType.MEDIA_STATE_UPDATE,
            data = com.google.gson.Gson().toJsonTree(mediaState).asJsonObject
        )
        sendMessage(message)
    }

    private fun handleCameraStart() {
        val intent = Intent(this, CameraActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun handleCameraStop() {
        // We can't easily finish an activity from service unless we broadcast to it.
        // But users can just swipe back.
        // Or we can send a broadcast to finish it.
        // For now, let's assume user manually closes or we implement a termination broadcast.
    }

    private fun handleMakeCallCommand(message: ProtocolMessage) {
        val phoneNumber = ProtocolHelper.extractStringField(message, "phoneNumber")
        if (phoneNumber != null) {
            initiateCall(phoneNumber)
        }
    }

    private fun initiateCall(phoneNumber: String) {
        val intent =
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                                PackageManager.PERMISSION_GRANTED
                ) {
                    Intent(Intent.ACTION_CALL).apply { data = Uri.parse("tel:$phoneNumber") }
                } else {
                    Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:$phoneNumber") }
                }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun handleCameraShutter() {
        val intent = Intent(CameraActivity.ACTION_TAKE_PICTURE)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun handleCameraRecordStart() {
        val intent = Intent(CameraActivity.ACTION_START_VIDEO)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun handleCameraRecordStop() {
        val intent = Intent(CameraActivity.ACTION_STOP_VIDEO)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    fun sendCameraFrame(base64: String) {
        // Use runBlocking to enforce backpressure.
        // This blocks the camera analysis thread (cameraExecutor) until the frame is sent.
        // CameraX Strategy KEEP_ONLY_LATEST ensures we drop frames while blocked.
        try {
            kotlinx.coroutines.runBlocking {
                transportManager.send(ProtocolHelper.createCameraFrame(base64))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending camera frame", e)
        }
    }

    private fun startFindPhoneAlarm() {
        // Start FindDeviceAlarmActivity which handles the loud sound and UI
        val intent = Intent(this, FindDeviceAlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        Log.i(TAG, "Launched FindDeviceAlarmActivity")
    }

    private fun stopFindPhoneAlarm() {
        // Send broadcast to finish FindDeviceAlarmActivity if it's running
        val intent = Intent("com.ailife.rtosify.STOP_ALARM")
        sendBroadcast(intent)
        Log.i(TAG, "Stopped phone alarm via broadcast")
    }

    // File transfer state (receiving)
    private var receivingFile: File? = null
    private var receivingFileStream: java.io.RandomAccessFile? = null
    private var expectedFileSize: Long = 0
    private var receivedFileSize: Long = 0
    private var expectedChecksum: String = ""
    private var receivingFileType: String = "REGULAR"
    private val receivedChunks = mutableListOf<ByteArray>()

    // File transfer state (sending - waiting for ack)
    private var waitingForFileAck = false
    private var fileAckDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    private suspend fun handleFileTransferStart(message: ProtocolMessage) {
        try {
            val fileData = ProtocolHelper.extractData<FileTransferData>(message)
            android.util.Log.d(
                    TAG,
                    "File transfer starting: ${fileData.name}, ${fileData.size} bytes"
            )

            // Prepare to receive file
            // If it's a regular file (not APK), save to Downloads
            val targetFile =
                    if (fileData.type == "REGULAR" || fileData.type == "SHARE") {
                        val downloadsDir =
                                android.os.Environment.getExternalStoragePublicDirectory(
                                        android.os.Environment.DIRECTORY_DOWNLOADS
                                )
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        File(downloadsDir, fileData.name)
                    } else {
                        File(cacheDir, fileData.name)
                    }

            isTransferCancelled = false
            try {
                // Avoid overwrite by renaming if exists
                var finalFile = targetFile
                var counter = 1
                while (finalFile.exists()) {
                    val name = fileData.name
                    val dotIndex = name.lastIndexOf('.')
                    val newName = if (dotIndex != -1) {
                         "${name.substring(0, dotIndex)} ($counter)${name.substring(dotIndex)}"
                    } else {
                         "$name ($counter)"
                    }
                    finalFile = File(targetFile.parentFile, newName)
                    counter++
                }
                
                receivingFile = finalFile
                receivingFileStream = java.io.RandomAccessFile(receivingFile!!, "rw")
                android.util.Log.d(TAG, "Saving to: ${finalFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to open primary target ${targetFile.absolutePath}, trying fallback: ${e.message}")
                val fallbackFile = File(cacheDir, fileData.name)
                receivingFile = fallbackFile
                receivingFileStream = java.io.RandomAccessFile(fallbackFile, "rw")

            }

            receivingFileStream?.setLength(fileData.size) // Pre-allocate file size
            expectedFileSize = fileData.size
            receivedFileSize = 0
            expectedChecksum = fileData.checksum
            receivingFileType = fileData.type
            receivedChunks.clear()
            
            withContext(Dispatchers.Main) { callback?.onDownloadProgress(0) }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error starting file transfer: ${e.message}")
            sendMessage(
                    ProtocolHelper.createFileTransferEnd(
                            success = false,
                            error = e.message ?: "Failed to open file"
                    )
            )
            cleanupFileTransfer()
            withContext(Dispatchers.Main) { callback?.onDownloadProgress(-1) }
        }
    }

    private suspend fun handleFileChunk(message: ProtocolMessage) {
        try {
            if (isTransferCancelled) {
                cleanupFileTransfer()
                // Notify sender to stop
                sendMessage(ProtocolHelper.createFileTransferEnd(success = false, error = "Cancelled by user"))
                withContext(Dispatchers.Main) { callback?.onTransferCancelled() }
                return
            }
            val chunkData = ProtocolHelper.extractData<FileChunkData>(message)

            val raf = receivingFileStream
            if (raf == null) {
                android.util.Log.e(TAG, "Received chunk ${chunkData.chunkNumber} but no file stream open")
                return
            }

            // Decode chunk
            val chunkBytes = android.util.Base64.decode(chunkData.data, android.util.Base64.DEFAULT)

            // Write to file at the correct offset (handles out-of-order chunks)
            raf.seek(chunkData.offset)
            raf.write(chunkBytes)
            receivedFileSize += chunkBytes.size

            android.util.Log.d(
                    TAG,
                    "Received chunk ${chunkData.chunkNumber}/${chunkData.totalChunks} at offset ${chunkData.offset}"
            )

            // Report progress
            if (expectedFileSize > 0) {
                val progress = (receivedFileSize * 100 / expectedFileSize).toInt().coerceIn(0, 99)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    callback?.onDownloadProgress(progress, null)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error handling chunk: ${e.message}")
        }
    }

    private suspend fun handleFileTransferEnd(message: ProtocolMessage) {
        try {
            val success = ProtocolHelper.extractBooleanField(message, "success")

            // Check if this is an acknowledgment for a file we sent
            if (waitingForFileAck) {
                android.util.Log.d(TAG, "Received file transfer acknowledgment: success=$success")
                waitingForFileAck = false
                fileAckDeferred?.complete(success)
                fileAckDeferred = null
                return
            }

            // Otherwise, this is the end of a file we're receiving
            if (!success) {
                val error = ProtocolHelper.extractStringField(message, "error")
                android.util.Log.e(TAG, "File transfer failed: $error")
                cleanupFileTransfer()
                
                // If we are sending, this signals us to stop
                if (isTransferring) {
                    isTransferCancelled = true // Abort sender loop
                }
                
                withContext(Dispatchers.Main) { callback?.onDownloadProgress(-1) }
                return
            }

            // Close file stream
            receivingFileStream?.close()
            receivingFileStream = null

            val file = receivingFile
            if (file == null) {
                android.util.Log.e(TAG, "No file to finalize")
                return
            }

            android.util.Log.d(TAG, "File transfer complete: ${file.name}, ${file.length()} bytes")

            // Verify checksum if provided
            if (expectedChecksum.isNotEmpty()) {
                val actualChecksum = calculateMd5(file)
                if (actualChecksum != expectedChecksum) {
                    android.util.Log.e(
                            TAG,
                            "Checksum mismatch! Expected: $expectedChecksum, Got: $actualChecksum"
                    )
                    file.delete()
                    cleanupFileTransfer()
                    // Send failure acknowledgment
                    sendMessage(
                            ProtocolHelper.createFileTransferEnd(
                                    success = false,
                                    error = "Checksum mismatch"
                            )
                    )
                    withContext(Dispatchers.Main) { callback?.onDownloadProgress(-1) }
                    return
                }
            }

            // Report completion
            withContext(Dispatchers.Main) { callback?.onDownloadProgress(100, file) }

            // Send success acknowledgment to sender
            sendMessage(ProtocolHelper.createFileTransferEnd(success = true))
            android.util.Log.d(TAG, "Sent file transfer acknowledgment")

            // Show install dialog ONLY if type is "APK" (sent from app install section, not file
            // manager download)
            // Show install dialog or silent install if type is "APK"
            if (receivingFileType == "APK") {
                withContext(kotlinx.coroutines.Dispatchers.Main) { showInstallApkDialog(file) }
            } else if (receivingFileType == "SHARE") {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val authority = "${packageName}.fileprovider"
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@BluetoothService, authority, file)
                    showShareChooser("Received File: ${file.name}", null, contentResolver.getType(uri) ?: "*/*", uri)
                    showFileReceivedNotification(file)
                    Toast.makeText(
                                    this@BluetoothService,
                                    getString(R.string.upload_complete_title) + ": ${file.name}",
                                    Toast.LENGTH_LONG
                            )
                            .show()
                }
            } else {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(
                                    this@BluetoothService,
                                    getString(R.string.upload_complete_title) + ": ${file.name}",
                                    Toast.LENGTH_LONG
                            )
                            .show()
                }
            }

            cleanupFileTransfer()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error finalizing file transfer: ${e.message}")
            cleanupFileTransfer()
            // Send failure acknowledgment
            sendMessage(
                    ProtocolHelper.createFileTransferEnd(
                            success = false,
                            error = e.message ?: "Unknown error"
                    )
            )
        }
    }

    private fun cleanupFileTransfer() {
        try {
            receivingFileStream?.close()
        } catch (_: Exception) {}
        receivingFileStream = null
        receivingFile = null
        expectedFileSize = 0
        receivedFileSize = 0
        expectedChecksum = ""
        receivingFileType = "REGULAR"
        receivedChunks.clear()
    }

    private fun calculateMd5(file: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun showInstallApkDialog(tempFile: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            showPermissionNotification()
            return
        }
        showInstallNotification(tempFile)
    }

    private fun handleShutdownCommand() {
        Log.i(TAG, "Shutdown command received")
        mainHandler.post {
            Toast.makeText(this, getString(R.string.toast_shutdown_received), Toast.LENGTH_SHORT).show()
        }
        deviceActionManager.shutdown()
    }

    private fun handleRebootCommand() {
        Log.i(TAG, "Reboot command received")
        mainHandler.post {
            Toast.makeText(this, getString(R.string.toast_reboot_received), Toast.LENGTH_SHORT).show()
        }
        deviceActionManager.reboot()
    }

    private fun handleSetWifiCommand(message: ProtocolMessage) {
        val enabled = message.data.get("enabled")?.asBoolean ?: return
        Log.i(TAG, "Set WiFi command received: $enabled")
        deviceActionManager.setWifiEnabled(enabled)
    }

    private suspend fun handleUninstallApp(message: ProtocolMessage) {
        val packageName = message.data.get("package")?.asString ?: return
        Log.i(TAG, "Uninstall app command received: $packageName")
        deviceActionManager.uninstallApk(packageName)
    }

    private fun handleLockDeviceCommand() {
        Log.i(TAG, "Lock device command received")
        deviceActionManager.lockDevice()
    }

    fun sendShutdownCommand() {
        sendMessage(ProtocolHelper.createShutdown())
    }

    fun sendRebootCommand() {
        sendMessage(ProtocolHelper.createReboot())
    }

    fun sendLockDeviceCommand() {
        sendMessage(ProtocolHelper.createLockDevice())
    }

    fun sendFindDeviceCommand(enabled: Boolean) {
        sendMessage(ProtocolHelper.createFindDevice(enabled))
    }

    fun sendFindPhoneCommand(enabled: Boolean) {
        sendMessage(ProtocolHelper.createFindPhone(enabled))
    }

    fun sendFindDeviceLocationUpdate(latitude: Double, longitude: Double, accuracy: Float, rssi: Int) {
        val locationData = FindDeviceLocationData(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            rssi = rssi,
            timestamp = System.currentTimeMillis()
        )
        sendMessage(ProtocolHelper.createFindDeviceLocationUpdate(locationData))
    }

    /**
     * Start local BLE RSSI monitoring on this device.
     * Called by FindDeviceActivity when it opens to monitor the remote device's RSSI.
     */
    fun startLocalFindDeviceMonitoring() {
        // Trigger local BLE monitoring by simulating a location request
        val enableMessage = ProtocolHelper.createFindDeviceLocationRequest(true)
        handleFindDeviceLocationRequest(enableMessage)
        Log.i(TAG, "Started local BLE monitoring for FindDevice")
    }

    /**
     * Stop local BLE RSSI monitoring on this device.
     * Called by FindDeviceActivity when it closes.
     */
    fun stopLocalFindDeviceMonitoring() {
        val disableMessage = ProtocolHelper.createFindDeviceLocationRequest(false)
        handleFindDeviceLocationRequest(disableMessage)
        Log.i(TAG, "Stopped local BLE monitoring for FindDevice")
    }

    private fun handleFindDeviceLocationUpdate(message: ProtocolMessage) {
        try {
            val locationData = ProtocolHelper.extractData<FindDeviceLocationData>(message)
            
            // Broadcast location update to FindDeviceActivity
            val intent = Intent(FindDeviceActivity.ACTION_LOCATION_UPDATE).apply {
                putExtra("latitude", locationData.latitude)
                putExtra("longitude", locationData.longitude)
                putExtra("accuracy", locationData.accuracy)
                putExtra("rssi", locationData.rssi)
                putExtra("timestamp", locationData.timestamp)
            }
            sendBroadcast(intent)
            
            Log.d(TAG, "📍 Received location update from watch: lat=${locationData.latitude}, lon=${locationData.longitude}, rssi=${locationData.rssi}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling find device location update: ${e.message}")
        }
    }

    fun syncCalendar() {
        if (!isConnected) {
            mainHandler.post {
                Toast.makeText(
                                this,
                                getString(R.string.toast_watch_not_connected),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            mainHandler.post {
                Toast.makeText(this, getString(R.string.toast_perm_calendar_not_granted), Toast.LENGTH_SHORT)
                        .show()
            }
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val events = mutableListOf<CalendarEvent>()
            val projection =
                    arrayOf(
                            CalendarContract.Events.TITLE,
                            CalendarContract.Events.DTSTART,
                            CalendarContract.Events.DTEND,
                            CalendarContract.Events.EVENT_LOCATION,
                            CalendarContract.Events.DESCRIPTION
                    )

            val now = System.currentTimeMillis()
            val weekFromNow = now + (7 * 24 * 60 * 60 * 1000L)
            val selection =
                    "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?)"
            val selectionArgs = arrayOf(now.toString(), weekFromNow.toString())

            try {
                contentResolver.query(
                                CalendarContract.Events.CONTENT_URI,
                                projection,
                                selection,
                                selectionArgs,
                                "${CalendarContract.Events.DTSTART} ASC"
                        )
                        ?.use { cursor ->
                            val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                            val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                            val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                            val locIdx =
                                    cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                            val descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)

                            while (cursor.moveToNext()) {
                                events.add(
                                        CalendarEvent(
                                                title = cursor.getString(titleIdx) ?: "No Title",
                                                startTime = cursor.getLong(startIdx),
                                                endTime = cursor.getLong(endIdx),
                                                location = cursor.getString(locIdx),
                                                description = cursor.getString(descIdx)
                                        )
                                )
                            }
                        }

                if (events.isNotEmpty()) {
                    sendMessage(ProtocolHelper.createSyncCalendar(events))
                    mainHandler.post {
                        Toast.makeText(
                                        this@BluetoothService,
                                        getString(R.string.toast_sync_success, "Calendar"),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                } else {
                    mainHandler.post {
                        Toast.makeText(
                                        this@BluetoothService,
                                        getString(R.string.toast_no_calendar_events),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                }
            } catch (e: Exception) {
                Log.e("BluetoothService", "Error syncing calendar: ${e.message}")
                mainHandler.post {
                    Toast.makeText(
                                    this@BluetoothService,
                                    getString(R.string.toast_sync_failed, "Calendar"),
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            }
        }
    }

    fun updateDndSettings(settings: DndSettingsData) {
        sendMessage(ProtocolHelper.createUpdateDndSettings(settings))
    }

    fun syncContacts() {
        if (!isConnected) {
            mainHandler.post {
                Toast.makeText(
                                this,
                                getString(R.string.toast_watch_not_connected),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            mainHandler.post {
                Toast.makeText(this, R.string.toast_perm_contacts_not_granted, Toast.LENGTH_SHORT)
                        .show()
            }
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val contactsList = mutableListOf<Contact>()

            val projection =
                    arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    )

            try {
                contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                projection,
                                null,
                                null,
                                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                        )
                        ?.use { cursor ->
                            val nameIdx =
                                    cursor.getColumnIndex(
                                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                                    )
                            val numberIdx =
                                    cursor.getColumnIndex(
                                            ContactsContract.CommonDataKinds.Phone.NUMBER
                                    )

                            val contactMap = mutableMapOf<String, MutableList<String>>()

                            while (cursor.moveToNext()) {
                                val name = cursor.getString(nameIdx) ?: "Unknown"
                                val number = cursor.getString(numberIdx) ?: ""
                                if (number.isNotEmpty()) {
                                    contactMap.getOrPut(name) { mutableListOf() }.add(number)
                                }
                            }

                            contactMap.forEach { (name, numbers) ->
                                contactsList.add(Contact(name = name, phoneNumbers = numbers))
                            }
                        }

                if (contactsList.isNotEmpty()) {
                    val limitedList = contactsList.take(100)
                    sendMessage(ProtocolHelper.createSyncContacts(limitedList))
                    mainHandler.post {
                        Toast.makeText(
                                        this@BluetoothService,
                                        getString(R.string.toast_sync_success, "Contacts"),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                } else {
                    mainHandler.post {
                        Toast.makeText(
                                        this@BluetoothService,
                                        getString(R.string.toast_no_contacts_found),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                }
            } catch (e: Exception) {
                Log.e("BluetoothService", "Error syncing contacts: ${e.message}")
                mainHandler.post {
                    Toast.makeText(
                                    this@BluetoothService,
                                    getString(R.string.toast_sync_failed, "Contacts"),
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            }
        }
    }

    fun sendApkFile(uri: Uri) {
        sendUriFile(uri, "APK")
    }

    fun sendUriFile(uri: Uri, type: String = "SHARE") {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileNameFromUri(uri) ?: "shared_file"
                val tempFile = File(cacheDir, "transfer_$fileName")
                
                android.util.Log.d(TAG, "sendUriFile: uri=$uri, type=$type, tempFile=${tempFile.absolutePath}")

                // If it's already a file URI and it's not the same as our target, we can copy it.
                // If it IS the same as our target (unlikely with "transfer_" prefix), skip copy.
                // But better: if it's a file URI, we might be able to send it directly if accessible.
                
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    tempFile.outputStream().use { outputStream -> 
                        val copied = inputStream.copyTo(outputStream)
                        android.util.Log.d(TAG, "sendUriFile: staged $copied bytes to ${tempFile.absolutePath}")
                    }
                }
                
                if (tempFile.exists() && tempFile.length() > 0) {
                    sendFile(tempFile, type)
                    // Note: sendFile might need to delete this temp file after completion if it's internal
                } else {
                    throw Exception("Failed to stage file or file is empty")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending URI file: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback?.onError("${getString(R.string.toast_upload_failed)}: ${e.message}")
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun handleRequestFileDownload(message: ProtocolMessage) {
        val data = message.data
        val path = data.get("path")?.asString ?: return
        val prepareVideo = data.get("prepare_video")?.asBoolean ?: false
        
        serviceScope.launch(Dispatchers.IO) {
            var fileToDownload = File(path)
            var tempSourceFile: File? = null
            
            // Check if file is accessible directly
            if (!fileToDownload.exists() || !fileToDownload.canRead()) {
                Log.d(TAG, "File restricted or not found directly, trying copy via UserService: $path")
                val cacheDir = externalCacheDir ?: cacheDir
                val uniqueName = "stream_${System.currentTimeMillis()}_${File(path).name}"
                tempSourceFile = File(cacheDir, uniqueName)
                
                var copied = false
                userService?.let { service ->
                    try {
                        copied = service.copyFile(path, tempSourceFile!!.absolutePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy restricted file: ${e.message}")
                    }
                }
                
                if (copied && tempSourceFile!!.exists() && tempSourceFile!!.length() > 0) {
                     fileToDownload = tempSourceFile!!
                     Log.d(TAG, "Restricted file copied to cache: ${fileToDownload.absolutePath}")
                } else {
                     Log.e(TAG, "Failed to access file even via UserService: $path")
                     // Fallback: try one last check if it appeared magically (e.g. slight delay)
                }
            }

            if (prepareVideo && fileToDownload.exists()) {
                Log.d(TAG, "Preparing video for watch: ${fileToDownload.absolutePath}")
                try {
                    val cacheDir = externalCacheDir ?: cacheDir
                    val compressedFile = File(cacheDir, "cmp_${System.currentTimeMillis()}.mp4")
                    
                    val success = VideoCompressor.compressVideo(
                        fileToDownload.absolutePath, 
                        compressedFile.absolutePath
                    )
                    
                    if (success && compressedFile.exists()) {
                         Log.i(TAG, "Video compressed successfully: ${compressedFile.absolutePath} (${compressedFile.length()} bytes)")
                         fileToDownload = compressedFile
                    } else {
                         Log.e(TAG, "Video compression failed, using original")
                    }
                } catch (e: Exception) {
                     Log.e(TAG, "Error during video preparation: ${e.message}")
                }
            }
            
            if (fileToDownload.exists() && fileToDownload.canRead()) {
                sendFile(fileToDownload)
                // Cleanup temp files lazily or we could add a deletion callback to sendFile if we modify it
            } else {
                Log.e(TAG, "Requested file does not exist: ${fileToDownload.absolutePath}")
                // Clean up temp source if we failed
                tempSourceFile?.delete()
            }
        }
    }

    fun sendFile(file: File, type: String = "REGULAR", remotePath: String? = null) {
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!isConnected) {
                withContext(Dispatchers.Main) { callback?.onError(getString(R.string.status_disconnected)) }
                return@launch
            }
            isTransferring = true

            try {
                isTransferring = true
                isTransferCancelled = false
                
                // Calculate MD5 using stream to avoid OOM
                val md5 = java.security.MessageDigest.getInstance("MD5")
                file.inputStream().use { driver ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (driver.read(buffer).also { bytesRead = it } != -1) {
                        md5.update(buffer, 0, bytesRead)
                    }
                }
                val checksum = md5.digest().joinToString("") { "%02x".format(it) }
                val fileSize = file.length()

                val fileData =
                        FileTransferData(
                                name = file.name,
                                size = fileSize,
                                checksum = checksum,
                                type = type,
                                path = remotePath
                        )
                if (!transportManager.send(ProtocolHelper.createFileTransferStart(fileData))) {
                     Log.w(TAG, "Failed to send file start")
                     withContext(Dispatchers.Main) { callback?.onError("Failed to start transfer") }
                     return@launch
                }
                // sendMessage(ProtocolHelper.createFileTransferStart(fileData))
                delay(100)

                val chunkSize = 32 * 1024
                val totalChunks = (fileSize + chunkSize - 1) / chunkSize
                var chunkNumber = 0
                
                file.inputStream().use { input ->
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    var offset: Long = 0
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isTransferCancelled) {
                             Log.i(TAG, "File transfer cancelled by user")
                             sendMessage(ProtocolHelper.createFileTransferEnd(false, "Cancelled by user"))
                             withContext(Dispatchers.Main) { 
                                 callback?.onTransferCancelled()
                                 // onUploadProgress(-1) called by caller logic usually, but here we break
                             }
                             return@launch
                        }

                        // If read less than buffer size (last chunk), truncate
                        val chunkBuffer = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                        
                        val base64Chunk =
                                android.util.Base64.encodeToString(chunkBuffer, android.util.Base64.NO_WRAP)

                        val chunkData =
                                FileChunkData(
                                        offset = offset,
                                        data = base64Chunk,
                                        chunkNumber = chunkNumber,
                                        totalChunks = totalChunks.toInt()
                                )
                        val chunkMsg = ProtocolHelper.createFileChunk(chunkData)
                        if (!transportManager.send(chunkMsg)) {
                             withContext(Dispatchers.Main) { callback?.onError("Failed to send chunk") }
                             throw java.io.IOException("Failed to send chunk $chunkNumber")
                        }
                        // sendMessage(ProtocolHelper.createFileChunk(chunkData))

                        val progress = ((chunkNumber + 1) * 95 / totalChunks.coerceAtLeast(1))
                        withContext(Dispatchers.Main) { callback?.onUploadProgress(progress.toInt()) }

                        offset += bytesRead
                        chunkNumber++
                        //delay(10)
                    }
                }

                fileAckDeferred = kotlinx.coroutines.CompletableDeferred()
                waitingForFileAck = true
                if (!transportManager.send(ProtocolHelper.createFileTransferEnd(success = true))) {
                     Log.w(TAG, "Failed to send transfer end")
                     withContext(Dispatchers.Main) { callback?.onError("Failed to send transfer end") }
                     return@launch
                }
                // transportManager.send(ProtocolHelper.createFileTransferEnd(success = true))

                val ackReceived =
                        try {
                            kotlinx.coroutines.withTimeout(15000) {
                                fileAckDeferred?.await() ?: false
                            }
                        } catch (_: Exception) {
                            false
                        }

                withContext(Dispatchers.Main) {
                    if (ackReceived) {
                        callback?.onUploadProgress(100)
                    } else {
                        callback?.onUploadProgress(-1)
                        callback?.onError(getString(R.string.applist_error_processing))
                    }
                }
                
                // Cleanup staged file if it was created by sendUriFile
                if (file.name.startsWith("transfer_") && file.parentFile == cacheDir) {
                    android.util.Log.d(TAG, "Deleting staged file: ${file.absolutePath}")
                    file.delete()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending file: ${e.message}", e)
                sendMessage(
                        ProtocolHelper.createFileTransferEnd(
                                success = false,
                                error = e.message ?: "Unknown error"
                        )
                )
                withContext(Dispatchers.Main) {
                    callback?.onUploadProgress(-1)
                    callback?.onError(getString(R.string.toast_upload_failed) + ": ${e.message}")
                }
                
                // Cleanup staged file even on error
                if (file.name.startsWith("transfer_") && file.parentFile == cacheDir) {
                    file.delete()
                }
            } finally {
                isTransferring = false
                waitingForFileAck = false
                fileAckDeferred = null
            }
        }
    }

    fun requestFileList(path: String = "/") {
        sendMessage(ProtocolHelper.createRequestFileList(path))
    }

    fun requestFileDownload(path: String) {
        sendMessage(ProtocolHelper.createRequestFileDownload(path))
    }

    fun deleteRemoteFile(path: String) {
        sendMessage(ProtocolHelper.createDeleteFiles(listOf(path)))
    }

    fun renameRemoteFile(oldPath: String, newPath: String) {
        sendMessage(ProtocolHelper.createRenameFile(oldPath, newPath))
    }

    fun deleteRemoteFiles(paths: List<String>) {
        sendMessage(ProtocolHelper.createDeleteFiles(paths))
    }

    fun moveRemoteFiles(srcPaths: List<String>, dstPath: String) {
        sendMessage(ProtocolHelper.createMoveFiles(srcPaths, dstPath))
    }

    fun copyRemoteFiles(srcPaths: List<String>, dstPath: String) {
        sendMessage(ProtocolHelper.createCopyFiles(srcPaths, dstPath))
    }
    
    fun requestPreview(path: String) {
        sendMessage(ProtocolHelper.createRequestPreview(path))
    }

    fun requestWatchStatus() {
        sendMessage(ProtocolHelper.createRequestWatchStatus())
    }

    fun requestDeviceInfoUpdate() {
        sendMessage(ProtocolHelper.createRequestDeviceInfoUpdate())
    }

    private fun showShareChooser(title: String, text: String?, type: String?, streamUri: Uri? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type ?: (if (streamUri != null) "*/*" else "text/plain")
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
            if (streamUri != null) {
                putExtra(Intent.EXTRA_STREAM, streamUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, title)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(chooser)
        Log.d(TAG, "Opened share chooser: $title")
    }

    private fun showFileReceivedNotification(file: File) {
        val authority = "${packageName}.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(this, authority, file)

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, "service_channel")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("File Shared Successfully")
                .setContentText("Received ${file.name}")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(file.hashCode(), notification)
    }

    private suspend fun handleResponseFileList(message: ProtocolMessage) {
        val path = ProtocolHelper.extractStringField(message, "path") ?: "/"
        val filesJson = message.data.get("files").toString()
        withContext(Dispatchers.Main) { callback?.onFileListReceived(path, filesJson) }
    }


    private fun showInstallNotification(tempFile: File) {
        val authority = "${packageName}.fileprovider"
        val installUri = FileProvider.getUriForFile(this, authority, tempFile)
        val installIntent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(installUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        installIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
        val builder =
                NotificationCompat.Builder(this, INSTALL_CHANNEL_ID)
                        .setContentTitle(getString(R.string.notification_apk_received_title))
                        .setContentText(getString(R.string.notification_apk_received_text))
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
        notificationManager.notify(INSTALL_NOTIFICATION_ID, builder.build())
    }

    private fun showPermissionNotification() {
        val intent =
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = "package:$packageName".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
        val builder =
                NotificationCompat.Builder(this, INSTALL_CHANNEL_ID)
                        .setContentTitle(getString(R.string.notification_config_required_title))
                        .setContentText(getString(R.string.notification_config_required_text))
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
        notificationManager.notify(INSTALL_NOTIFICATION_ID, builder.build())
    }

    private fun showErrorNotification(msg: String) {
        val builder =
                NotificationCompat.Builder(this, CHANNEL_ID_DISCONNECTED)
                        .setContentTitle(getString(R.string.notification_error_title))
                        .setContentText(msg)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
        notificationManager.notify(999, builder.build())
    }

// REMOVED: handleRequestApps()

    private fun handleResponseApps(message: ProtocolMessage) {
        try {
            val appsJsonArray = message.data.getAsJsonArray("apps")
            val gson = com.google.gson.Gson()
            val apps = gson.fromJson(appsJsonArray, Array<AppInfo>::class.java).toList()

            // Convert back to JSON string for callback (temporary until callback is updated)
            val jsonArray = org.json.JSONArray()
            for (app in apps) {
                val obj = org.json.JSONObject()
                obj.put("name", app.name)
                obj.put("package", app.packageName)
                obj.put("icon", app.icon)
                obj.put("isSystemApp", app.isSystemApp)
                jsonArray.put(obj)
            }

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                callback?.onAppListReceived(jsonArray.toString())
                
                // Also broadcast for other activities
                val broadcastIntent = Intent(ACTION_APPS_RECEIVED)
                broadcastIntent.putExtra("apps_json", jsonArray.toString())
                sendBroadcast(broadcastIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error parsing app list: ${e.message}")
        }
    }

    // REMOVED: getInstalledAppsWithIcons body
// REMOVED: showMirroredNotification

// REMOVED: dismissLocalNotification()

    private fun requestDismissOnPhone(message: ProtocolMessage) {
        val key = ProtocolHelper.extractStringField(message, "key") ?: return
        val intent =
                Intent(ACTION_CMD_DISMISS_ON_PHONE).apply {
                    putExtra(EXTRA_NOTIF_KEY, key)
                    setPackage(packageName)
                }
        sendBroadcast(intent)
    }

    private fun handleExecuteNotificationAction(message: ProtocolMessage) {
        val notifKey = ProtocolHelper.extractStringField(message, "notifKey") ?: return
        val actionKey = ProtocolHelper.extractStringField(message, "actionKey") ?: return

        Log.d(TAG, "Executing notification action: $actionKey for notification: $notifKey")

        val intent =
                Intent(ACTION_CMD_EXECUTE_ACTION).apply {
                    putExtra(EXTRA_NOTIF_KEY, notifKey)
                    putExtra(EXTRA_ACTION_KEY, actionKey)
                    setPackage(packageName)
                }
        sendBroadcast(intent)
    }

    private fun handleSendNotificationReply(message: ProtocolMessage) {
        val notifKey = ProtocolHelper.extractStringField(message, "notifKey") ?: return
        val actionKey = ProtocolHelper.extractStringField(message, "actionKey") ?: return
        val replyText = ProtocolHelper.extractStringField(message, "replyText") ?: return

        Log.d(TAG, "Sending notification reply: '$replyText' for notification: $notifKey")

        val intent =
                Intent(ACTION_CMD_SEND_REPLY).apply {
                    putExtra(EXTRA_NOTIF_KEY, notifKey)
                    putExtra(EXTRA_ACTION_KEY, actionKey)
                    putExtra(EXTRA_REPLY_TEXT, replyText)
                    setPackage(packageName)
                }
        sendBroadcast(intent)
    }

    fun requestRemoteAppList() {
        sendMessage(ProtocolHelper.createRequestApps())
    }

    fun sendUninstallCommand(packageName: String) {
        android.util.Log.d(TAG, "Sending uninstall command for: $packageName")
        mainHandler.post {
            Toast.makeText(this, getString(R.string.toast_uninstall_sending, packageName), Toast.LENGTH_SHORT).show()
        }
        sendMessage(ProtocolHelper.createUninstallApp(packageName))
    }

    fun resetApp() {
        activePrefs.edit().clear().apply()
        prefs.edit().clear().apply()
        stopConnectionLoopOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isActive(): Boolean {
        return connectionJob?.isActive == true
    }

    fun stopConnectionLoopOnly() {
        transportManager.stopAll()
        statusUpdateJob?.cancel()

        val wasConnected = isConnected

        forceDisconnect()
        // isConnected is now a getter
        currentDeviceName = null

        if (wasConnected || currentNotificationStatus != getString(R.string.status_stopped)) {
            updateStatus(getString(R.string.status_stopped))
        }
    }

    fun stopServiceCompletely() {
        isStopping = true
        stopConnectionLoopOnly()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancelAll() // Ensure all are gone
        } catch (_: Exception) {}
        stopSelf()
    }
    private fun buildConnectionStatusText(type: String, deviceName: String): String {
        val hasBluetooth = type.contains("Bluetooth") || type.contains("BT")
        val hasBle = type.contains("BLE")
        val hasLan = type.contains("WiFi") || type.contains("LAN")
        val hasInternet = type.contains("Internet")

        if (!hasBluetooth && !hasBle && !hasLan && !hasInternet) return getString(R.string.status_connected)

        val sb = StringBuilder()
        sb.append(getString(R.string.status_connected))
        sb.append(" (")

        val transports = mutableListOf<String>()
        if (hasBluetooth) transports.add("BT")
        if (hasBle) transports.add("BLE")
        if (hasLan) transports.add("LAN")
        if (hasInternet) transports.add("Internet")

        sb.append(transports.joinToString("+"))
        sb.append(")")

        return sb.toString()
    }

    private fun updateStatus(text: String) {
        if (isStopping) return // Prevent updates if stopping

        currentStatus = text
        currentNotificationStatus = text
        
        Log.d(TAG, "updateStatus: text='$text', callback=${callback != null}")

        mainHandler.post {
            try {
                updateForegroundNotification()
            } catch (e: Exception) {
                if (DEBUG_NOTIFICATIONS) Log.e(TAG, "updateStatus erro: ${e.message}")
            }
            Log.d(TAG, "updateStatus mainHandler: calling callback?.onStatusChanged")
            updateWidgets()
            callback?.onStatusChanged(text)
        }
    }

    /**
     * CORREÇÃO DEFINITIVA PARA CANAIS BLOQUEADOS: Ao invés de usar um único ID, usamos um ID
     * diferente para cada estado (Waiting, Connected, etc).
     * * Lógica:
     * 1. Determinamos o Estado Atual -> Novo ID e Novo Canal.
     * 2. Chamamos startForeground com o NOVO ID. Isso "segura" o serviço e previne crashes.
     * 3. Se o usuário bloqueou esse canal, o Android não mostra nada (correto).
     * 4. CRUCIAL: Chamamos .cancel() nos IDs dos estados antigos. Isso garante que a notificação
     * "Waiting" suma visualmente se mudamos para "Connected", mesmo que "Connected" esteja oculto.
     */
    private fun updateForegroundNotification() {
        try {
            val (targetId, targetChannel, targetText) = determineNotificationState()

            val intent = Intent(this, MainActivity::class.java)
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            val notification =
                    NotificationCompat.Builder(this, targetChannel)
                            .setContentTitle(getString(R.string.notification_service_title))
                            .setContentText(targetText)
                            .setSmallIcon(R.drawable.ic_smartwatch_notification)
                            .setContentIntent(pending)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .build()

            // Inicia/Atualiza o Foreground no ID correto do estado atual
            if (Build.VERSION.SDK_INT >= 34) {
                val hasLocation =
                        ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                val type =
                        if (hasLocation)
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                startForeground(targetId, notification, type)
            } else if (Build.VERSION.SDK_INT >= 29) {
                var type = 0
                if (Build.VERSION.SDK_INT >= 31) {
                    type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(targetId, notification, type)
            } else {
                startForeground(targetId, notification)
            }

            // LIMPEZA: Remove visualmente as notificações dos outros estados.
            // Isso corrige o bug onde "Aguardando" persistia se "Conectado" estivesse bloqueado.
            if (targetId != NOTIFICATION_ID_WAITING)
                    notificationManager.cancel(NOTIFICATION_ID_WAITING)
            if (targetId != NOTIFICATION_ID_CONNECTED)
                    notificationManager.cancel(NOTIFICATION_ID_CONNECTED)
            if (targetId != NOTIFICATION_ID_DISCONNECTED)
                    notificationManager.cancel(NOTIFICATION_ID_DISCONNECTED)
        } catch (e: Exception) {
            if (DEBUG_NOTIFICATIONS)
                    Log.e(TAG, "updateForegroundNotification: Erro: ${e.message}", e)
        }
    }

    // Format transport type for notification display
    private fun formatTransportTypeForNotification(type: String): String {
        val parts = mutableListOf<String>()
        if (type.contains("BT") || type.contains("Bluetooth")) parts.add("BT")
        if (type.contains("BLE")) parts.add("BLE")
        if (type.contains("LAN") || type.contains("WiFi")) parts.add("WiFi")
        if (type.contains("Internet")) parts.add("Net")
        return if (parts.isNotEmpty()) parts.joinToString("+") else type
    }

    // Retorna Triple(ID, Channel, Text)
    private fun determineNotificationState(): Triple<Int, String, String> {
        return when {
            isConnected -> {
                val state = transportManager.connectionState.value
                val statusText = if (state is com.ailife.rtosify.communication.TransportManager.ConnectionState.Connected) {
                    val transportDisplay = formatTransportTypeForNotification(state.type)
                    if (currentDeviceName != null) {
                        "Connected to $currentDeviceName via $transportDisplay"
                    } else {
                        "Connected via $transportDisplay"
                    }
                } else {
                    if (currentDeviceName != null) {
                        "Connected to $currentDeviceName"
                    } else {
                        "Connected"
                    }
                }
                Triple(NOTIFICATION_ID_CONNECTED, CHANNEL_ID_CONNECTED, statusText)
            }
            currentNotificationStatus.contains("Aguardando", ignoreCase = true) ||
                    currentNotificationStatus.contains("Waiting", ignoreCase = true) ||
                    currentNotificationStatus.contains("Escaneando", ignoreCase = true) ||
                    currentNotificationStatus.contains("Searching", ignoreCase = true) ||
                    currentNotificationStatus.contains("Iniciado", ignoreCase = true) ||
                    currentNotificationStatus.contains("Started", ignoreCase = true) ||
                    currentNotificationStatus.isEmpty() ->
                    Triple(
                            NOTIFICATION_ID_WAITING,
                            CHANNEL_ID_WAITING,
                            currentNotificationStatus.ifEmpty { getString(R.string.status_waiting) }
                    )
            else ->
                    Triple(
                            NOTIFICATION_ID_DISCONNECTED,
                            CHANNEL_ID_DISCONNECTED,
                            currentNotificationStatus
                    )
        }
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
                NotificationChannel(
                        CHANNEL_ID_WAITING,
                        getString(R.string.notif_waiting_channel),
                        NotificationManager.IMPORTANCE_LOW
                )
        )

        notificationManager.createNotificationChannel(
                NotificationChannel(
                        CHANNEL_ID_CONNECTED,
                        getString(R.string.notif_connected_channel),
                        NotificationManager.IMPORTANCE_LOW
                )
        )

        notificationManager.createNotificationChannel(
                NotificationChannel(
                        CHANNEL_ID_DISCONNECTED,
                        getString(R.string.status_disconnected),
                        NotificationManager.IMPORTANCE_LOW
                )
        )

        notificationManager.createNotificationChannel(
                NotificationChannel(INSTALL_CHANNEL_ID, "APKs", NotificationManager.IMPORTANCE_HIGH)
        )

    }

    private fun savePreference(key: String, value: String) {
        activePrefs.edit().putString(key, value).apply()
    }

    private fun handleRejectCallCommand() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
                            PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    telecomManager.endCall()
                    Log.d(TAG, "Call rejected via TelecomManager")
                } catch (e: Exception) {
                    Log.e(TAG, "Error rejecting call: ${e.message}")
                }
            }
        }
    }

    private fun handleAnswerCallCommand() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
                            PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    telecomManager.acceptRingingCall()
                    Log.d(TAG, "Call answered via TelecomManager")
                } catch (e: Exception) {
                    Log.e(TAG, "Error answering call: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isStopping = true

        Log.d(TAG, "BluetoothService destroying...")

        stopBluetoothEnforcement()
        stopClipboardMonitoring()
        stopFindPhoneAlarm()
        
        // Stop media session listener
        mediaSessionListener?.stop()
        mediaSessionListener = null

        serviceScope.launch {
            try {
                // Stop mDNS discovery explicitly if needed, though TransportManager now handles transport disconnects
                 mdnsDiscovery?.stop()
            } catch (e: Exception) {
                 Log.e(TAG, "Error stopping mDNS in onDestroy: ${e.message}")
            }
        }
        // Unbind Shizuku UserService
        try {
            if (userServiceConnection != null) {
                Shizuku.unbindUserService(userServiceArgs, userServiceConn, true)
                userServiceConnection = null
            }
            userService = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding UserService: ${e.message}")
        }

        try {
            unregisterReceiver(internalReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(watchDismissReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(phoneStateReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(mirroringReceiver)
        } catch (_: Exception) {}

        if (MirroringService.isRunning) {
            Log.d(TAG, "onDestroy: Mirroring is active, stopping it.")
            val stopIntent = Intent(this, MirroringService::class.java)
            stopService(stopIntent)
        }

        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        forceDisconnect()
    }

    private suspend fun handlePreviewReceived(message: ProtocolMessage) {
        val path = ProtocolHelper.extractStringField(message, "path")
        val imageBase64 = ProtocolHelper.extractStringField(message, "imageBase64")
        val textContent = ProtocolHelper.extractStringField(message, "textContent")
        if (path != null) {
            withContext(Dispatchers.Main) { callback?.onPreviewReceived(path, imageBase64, textContent) }
        }
    }

    private suspend fun handleBatteryDetailUpdate(message: ProtocolMessage) {
        try {
            val data = ProtocolHelper.extractData<BatteryDetailData>(message)
            withContext(Dispatchers.Main) { callback?.onBatteryDetailReceived(data) }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling battery detail update: ${e.message}")
        }
    }

    private fun handleBatteryAlert(message: ProtocolMessage) {
        try {
            val alertType = message.data.get("alertType").asString
            val level = message.data.get("level").asInt

            postBatteryNotification(alertType, level)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling battery alert: ${e.message}")
        }
    }

    private fun postBatteryNotification(alertType: String, level: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "battery_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                    NotificationChannel(
                            channelId,
                            "Battery Alerts",
                            NotificationManager.IMPORTANCE_HIGH
                    )
            )
        }

        val title =
                if (alertType == "FULL") getString(R.string.notif_battery_full_title)
                else getString(R.string.notif_battery_low_title)
        val text =
                if (alertType == "FULL") getString(R.string.notif_battery_full_text)
                else getString(R.string.notif_battery_low_text, level)

        val intent =
                Intent(this, MainActivity::class.java).apply {
                    putExtra("open_battery_detail", true)
                }
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        1234,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        val builder =
                NotificationCompat.Builder(this, channelId)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)

        nm.notify(123456, builder.build())
    }

    // Alarm management methods
    private fun handleResponseAlarms(message: ProtocolMessage) {
        serviceScope.launch {
            try {
                val jsonArray = message.data.getAsJsonArray("alarms")
                val alarms =
                        ProtocolHelper.gson.fromJson<List<AlarmData>>(
                                jsonArray,
                                object : TypeToken<List<AlarmData>>() {}.type
                        )
                withContext(Dispatchers.Main) { alarmCallback?.onAlarmsReceived(alarms) }
                Log.d(TAG, "Received ${alarms.size} alarms from watch")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling RESPONSE_ALARMS: ${e.message}")
            }
        }
    }

    fun requestAlarms() {
        sendMessage(ProtocolHelper.createRequestAlarms())
        Log.d(TAG, "Requested alarms from watch")
    }

    fun addAlarm(alarm: AlarmData) {
        sendMessage(ProtocolHelper.createAddAlarm(alarm))
        Log.d(TAG, "Sent ADD_ALARM: ${alarm.hour}:${alarm.minute}")
    }

    fun updateAlarm(alarm: AlarmData) {
        sendMessage(ProtocolHelper.createUpdateAlarm(alarm))
        Log.d(TAG, "Sent UPDATE_ALARM: ${alarm.id}")
    }

    fun deleteAlarm(alarmId: String) {
        sendMessage(ProtocolHelper.createDeleteAlarm(alarmId))
        Log.d(TAG, "Sent DELETE_ALARM: $alarmId")
    }

    private val mirroringReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(TAG, "mirroringReceiver triggered: action=${intent?.action}")
                    when (intent?.action) {
                        ACTION_SCREEN_DATA_AVAILABLE -> {
                            val base64Data = intent.getStringExtra("data") ?: return
                            val isKeyFrame = intent.getBooleanExtra("isKeyFrame", false)
                            Log.d(
                                    TAG,
                                    "Forwarding frame: size=${base64Data.length}B, keyframe=$isKeyFrame"
                            )
                            this@BluetoothService.sendMessage(ProtocolHelper.createMirrorData(base64Data, isKeyFrame))
                        }
                        ACTION_SEND_REMOTE_INPUT -> {
                            val action = intent.getIntExtra("action", 0)
                            val x = intent.getFloatExtra("x", 0f)
                            val y = intent.getFloatExtra("y", 0f)
                            this@BluetoothService.sendMessage(ProtocolHelper.createRemoteInput(action, x, y))
                        }
                        MirroringService.ACTION_MIRROR_STATE_CHANGED -> {
                            val isRunning = MirroringService.isRunning
                            Log.d(TAG, "Mirroring state changed: isRunning=$isRunning")
                            transportManager.updateMirroringState(isRunning)
                            
                            // Update remote device about our "open" state
                            val isForeground = transportManager.isAppInForeground
                            sendMessage(ProtocolHelper.createSyncPhoneState(isForeground || isRunning))
                        }
                        "com.ailife.rtosify.SEND_MIRROR_STOP" -> {
                            Log.d(TAG, "Sending mirror stop command to companion")
                            sendMessage(
                                    ProtocolMessage(
                                            type = MessageType.SCREEN_MIRROR_STOP,
                                            data = com.google.gson.JsonObject()
                                    )
                            )
                        }
                        "com.ailife.rtosify.UPDATE_REMOTE_RESOLUTION" -> {
                            val width = intent.getIntExtra("width", 0)
                            val height = intent.getIntExtra("height", 0)
                            val density = intent.getIntExtra("density", 0)
                            val reset = intent.getBooleanExtra("reset", false)
                            val mode = intent.getIntExtra("mode", ResolutionData.MODE_RESOLUTION)
                            this@BluetoothService.sendMessage(
                                    ProtocolHelper.createUpdateResolution(
                                            width,
                                            height,
                                            density,
                                            reset,
                                            mode
                                    )
                            )
                        }
                    }
                }
            }

    private fun handleMirrorStart(message: ProtocolMessage) {
        val data = ProtocolHelper.extractData<MirrorStartData>(message)
        Log.d(
                TAG,
                "handleMirrorStart: isRequest=${data.isRequest}, width=${data.width}, height=${data.height}, mode=${data.mode}"
        )

        if (data.isRequest) {
            // Other device wants to view US.
            Log.d(TAG, "Mirror request received: ${data.width}x${data.height} mode=${data.mode}")

            // Apply resolution first if matching is requested
            if (data.width > 0 && data.height > 0) {
                // Construct temporary resolution message
                val resData = JsonObject()
                resData.addProperty("width", data.width)
                resData.addProperty("height", data.height)
                resData.addProperty("density", data.dpi)
                resData.addProperty("reset", false)
                resData.addProperty("mode", data.mode)

                val resMessage =
                        ProtocolMessage(type = MessageType.UPDATE_RESOLUTION, data = resData)
                handleUpdateResolution(resMessage)
            }

            val intent =
                    Intent(this, MirrorSettingsActivity::class.java).apply {
                        putExtra("request_mirror", true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
            startActivity(intent)
        } else {
            // Other device is streaming TO us. Open MirrorActivity.
            Log.d(TAG, "Incoming mirror stream: ${data.width}x${data.height}")
            val intent =
                    Intent(this, MirrorActivity::class.java).apply {
                        putExtra("width", data.width)
                        putExtra("height", data.height)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            startActivity(intent)
        }
    }

    private fun handleMirrorStop() {
        val intent = Intent("com.ailife.rtosify.STOP_MIRROR")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun handleMirrorData(message: ProtocolMessage) {
        val data = ProtocolHelper.extractData<MirrorData>(message)
        val intent = Intent(ACTION_SCREEN_DATA_RECEIVED)
        intent.setPackage(packageName) // Make it explicit for Android 14+
        intent.putExtra("data", data.data)
        intent.putExtra("isKeyFrame", data.isKeyFrame)
        sendBroadcast(intent)
    }

    private fun handleRemoteInput(message: ProtocolMessage) {
        val data = ProtocolHelper.extractData<RemoteInputData>(message)
        Log.d(TAG, "Received touch event: action=${data.action}, x=${data.x}, y=${data.y}")

        // Send to accessibility service via broadcast (IPC)
        val intent = Intent("com.ailife.rtosify.DISPATCH_REMOTE_INPUT")
        intent.setPackage(packageName) // Make it explicit for Android 14+
        intent.putExtra("action", data.action)
        intent.putExtra("x", data.x)
        intent.putExtra("y", data.y)
        sendBroadcast(intent)
    }

    private fun handleUpdateResolution(message: ProtocolMessage) {
        val data = ProtocolHelper.extractData<ResolutionData>(message)
        serviceScope.launch {
            try {
                if (data.reset) {
                    Log.d(TAG, "Resetting resolution to default")
                    userService?.executeCommand("wm size reset")
                    userService?.executeCommand("wm density reset")
                } else {
                    var targetW = data.width
                    var targetH = data.height
                    var targetD = data.density

                    if (data.mode == ResolutionData.MODE_ASPECT) {
                        // Calculate aspect ratio matching (Phone mode: decrease long dimension)
                        val metrics = android.util.DisplayMetrics()
                        val windowManager =
                                getSystemService(Context.WINDOW_SERVICE) as
                                        android.view.WindowManager
                        windowManager.defaultDisplay.getRealMetrics(metrics)

                        val physW = metrics.widthPixels
                        val physH = metrics.heightPixels
                        val targetAspect = data.width.toFloat() / data.height.toFloat()

                        Log.d(
                                TAG,
                                "Aspect Matching: Physical=${physW}x${physH}, TargetAspect=$targetAspect"
                        )

                        if (physW.toFloat() / physH.toFloat() > targetAspect) {
                            // Source is wider than target, decrease width
                            targetW = (physH * targetAspect).toInt()
                            targetH = physH
                        } else {
                            // Source is taller than target, decrease height
                            targetW = physW
                            targetH = (physW / targetAspect).toInt()
                        }
                        targetD = metrics.densityDpi // Keep original density
                    }

                    if (targetW > 0 && targetH > 0) {
                        Log.d(
                                TAG,
                                "Setting resolution to ${targetW}x${targetH} (mode=${data.mode})"
                        )
                        userService?.executeCommand("wm size ${targetW}x${targetH}")
                        if (targetD > 0) {
                            userService?.executeCommand("wm density $targetD")
                        }
                    }
                }

                // If mirroring is running, we MUST refresh it to apply new resolution
                if (MirroringService.isRunning) {
                    Log.d(TAG, "Mirroring is running, sending refresh signal")
                    val refreshIntent = Intent("com.ailife.rtosify.REFRESH_MIRROR")
                    refreshIntent.setPackage(packageName)
                    sendBroadcast(refreshIntent)
                }

                // Notify the remote device (viewer) about actual resolution change
                if (!data.reset) {
                    val metrics = resources.displayMetrics
                    sendMessage(
                            ProtocolHelper.createMirrorResChange(
                                    metrics.widthPixels,
                                    metrics.heightPixels,
                                    metrics.densityDpi
                            )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update resolution: ${e.message}")
            }
        }
    }

    private fun handleMirrorResChange(message: ProtocolMessage) {
        val data = ProtocolHelper.extractData<ResolutionData>(message)
        Log.d(TAG, "Received remote resolution change: ${data.width}x${data.height}")

        val intent = Intent("com.ailife.rtosify.MIRROR_RES_CHANGE")
        intent.setPackage(packageName)
        intent.putExtra("width", data.width)
        intent.putExtra("height", data.height)
        intent.putExtra("density", data.density)
        sendBroadcast(intent)
    }

    private fun handleDndToggle() {
        val newState = !dndEnabled
        
        // 1. Toggle Local DND (if permitted)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            try {
                nm.setInterruptionFilter(if (newState) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set local DND: ${e.message}")
            }
        }
        
        // 2. Sync to Watch
        sendDndCommand(newState)
        
        // 3. Update local state and refresh widgets
        dndEnabled = newState
        updateWidgets()
    }

    private var dndEnabled = false
    private var lastBatteryLevel = -1
    private var lastWifiSsid = "Not Connected"
    private var lastHr = -1
    private var lastSteps = -1
    private var lastSpo2 = -1

    private fun handleMirrorToggle() {
        if (MirroringService.isRunning) {
             val stopIntent = Intent(this, MirroringService::class.java)
             stopService(stopIntent)
             // Widget will be updated via service connection/status update, 
             // but let's do an immediate refresh if possible.
             updateWidgets()
        } else {
             val intent = Intent(this, MirrorSettingsActivity::class.java).apply {
                 putExtra("request_mirror", true)
                 addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
             }
             startActivity(intent)
             // Mirroring starting is handled by the activity.
        }
    }

    fun updateWidgets() {
        // Update Status Widget
        val statusIntent = Intent(this, StatusWidget::class.java).apply {
            action = StatusWidget.ACTION_WIDGET_UPDATE
            putExtra(StatusWidget.EXTRA_DEVICE_NAME, currentDeviceName ?: "Watch")
            putExtra(StatusWidget.EXTRA_CONNECTION_STATUS, currentStatus)
            putExtra(StatusWidget.EXTRA_BATTERY_LEVEL, lastBatteryLevel)
            putExtra(StatusWidget.EXTRA_WIFI_SSID, lastWifiSsid)
            putExtra(StatusWidget.EXTRA_WIDGET_DND_STATE, dndEnabled)
        }
        sendBroadcast(statusIntent)

        // Update Health Widget
        val healthIntent = Intent(this, HealthWidget::class.java).apply {
            action = HealthWidget.ACTION_WIDGET_HEALTH_UPDATE
            putExtra(HealthWidget.EXTRA_STEPS, lastSteps)
            putExtra(HealthWidget.EXTRA_HEART_RATE, lastHr)
            putExtra(HealthWidget.EXTRA_SPO2, lastSpo2)
            putExtra(HealthWidget.EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        sendBroadcast(healthIntent)
    }

    // Terminal / Shell Command Response Handlers
    private fun handleShellCommandResponse(message: ProtocolMessage) {
        try {
            val response = ProtocolHelper.extractData<ShellCommandResponse>(message)
            Log.d(TAG, "Received shell command response: exitCode=${response.exitCode}, uid=${response.uid}")
            callback?.onShellCommandResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling shell command response: ${e.message}", e)
        }
    }

    private fun handlePermissionInfoResponse(message: ProtocolMessage) {
        try {
            val info = ProtocolHelper.extractData<PermissionInfoData>(message)
            Log.d(TAG, "Received permission info: level=${info.level}, uid=${info.uid}")
            callback?.onPermissionInfoReceived(info)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling permission info response: ${e.message}", e)
        }
    }

    private fun handleWifiKeyAck(message: ProtocolMessage) {
        val success = ProtocolHelper.extractBooleanField(message, "success")
        Log.i(TAG, "Received WiFi key ACK: success=$success")
        callback?.onWifiKeyAck(success)
    }

    private fun handleWifiTestAck(message: ProtocolMessage) {
        val success = ProtocolHelper.extractBooleanField(message, "success")
        Log.i(TAG, "Received WiFi test ACK: success=$success")
        callback?.onWifiTestAck(success)
    }

    private fun handleWifiTestReceived(message: ProtocolMessage) {
        val testContent = ProtocolHelper.extractStringField(message, "message") ?: ""
        Log.i(TAG, "Received WiFi encryption test message: $testContent")
        callback?.onWifiTestReceived(testContent)
    }

    fun notifyInternetSettingsChanged() {
        Log.d(TAG, "Internet settings changed, notifying watch")
        val rule = activePrefs.getInt("internet_activation_rule", 0)
        val url = activePrefs.getString("internet_signaling_url", "") ?: ""
        val stunUrl = activePrefs.getString("internet_stun_url", "stun:stun.cloudflare.com:3478") ?: ""
        val turnUrl = activePrefs.getString("internet_turn_url", "") ?: ""
        val turnUsername = activePrefs.getString("internet_turn_username", "") ?: ""
        val turnPassword = activePrefs.getString("internet_turn_password", "") ?: ""
        
        sendMessage(ProtocolHelper.createUpdateInternetSettings(
            rule, url, stunUrl, turnUrl, turnUsername, turnPassword
        ))
    }

    private fun handleSyncMac(message: ProtocolMessage) {
        val mac = ProtocolHelper.extractStringField(message, "mac") ?: return
        Log.i(TAG, "Received discovered local MAC from watch: $mac")
        
        // Safety check: Ensure the watch isn't telling us we are the watch (happens if watch logic is loops)
        val currentWatchMac = devicePrefManager.getSelectedDeviceMac()
        if (mac == currentWatchMac) {
            Log.w(TAG, "Watch sent invalid SYNC_MAC: $mac is the Watch's own MAC. Ignoring.")
            return
        }
        
        val oldMac = prefs.getString("discovered_local_mac", null)
        if (mac != oldMac) {
            prefs.edit().putString("discovered_local_mac", mac).apply()
            Log.i(TAG, "Local MAC updated. Discovered: $mac (was: $oldMac).")
            transportManager.setDiscoveredLocalMac(mac)
            
            // Re-trigger internet connection logic if already connected via WebRTC
            serviceScope.launch {
                val lastMac = devicePrefManager.getSelectedDeviceMac()
                if (lastMac != null) {
                    Log.i(TAG, "Re-triggering internet monitoring with updated MAC.")
                }
            }
        } else {
            transportManager.setDiscoveredLocalMac(mac)
        }
    }

}
