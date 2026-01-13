package com.ailife.rtosifycompanion

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentValues
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
import android.net.wifi.WifiNetworkSuggestion
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.topjohnwu.superuser.Shell
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

class BluetoothService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    private var serverJob: Job? = null
    private var statusUpdateJob: Job? = null
    private lateinit var healthDataCollector: HealthDataCollector
    private var liveMeasurementJob: Job? = null
    private var batteryHistoryJob: Job? = null

    // Shell command tracking
    private val runningShellProcesses = mutableMapOf<String, Process>()
    private val shellCommandJobs = mutableMapOf<String, Job>()
    private val shellCommandSequences = mutableMapOf<String, Int>() // Track sequence per session
    private var deviceInfoUpdateJob: Job? = null
    private lateinit var deviceInfoManager: DeviceInfoManager
    private lateinit var watchAlarmManager: WatchAlarmManager


    private val shizukuLock = Any()

    private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val APP_NAME = "RTOSifyApp"

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "AppPrefs"

    // Transport Manager
    private lateinit var transportManager: com.ailife.rtosifycompanion.communication.TransportManager
    private lateinit var encryptionManager: com.ailife.rtosifycompanion.security.EncryptionManager
    private var mdnsDiscovery: com.ailife.rtosifycompanion.communication.MdnsDiscovery? = null

    // Clipboard monitoring
    private var clipboardManager: android.content.ClipboardManager? = null
    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? =
            null
    private var lastClipboardText: String? = null
    private val clipboardPollingHandler = Handler(Looper.getMainLooper())
    private var clipboardPollingRunnable: Runnable? = null

    @Volatile private var lastMessageTime: Long = 0L

    @Volatile private var isTransferring: Boolean = false

    // File Reception Variables
    private var receiveFile: File? = null
    private var receiveFileOutputStream: FileOutputStream? = null
    private var receiveTotalSize: Long = 0
    private var receiveBytesRead: Long = 0

    private val HEARTBEAT_INTERVAL = 20000L
    private val CONNECTION_TIMEOUT = 60000L

    private val notificationMap = ConcurrentHashMap<String, Int>()
    private val notificationDataMap = ConcurrentHashMap<String, NotificationData>()

    // REFACTORING: More robust notification control
    @Volatile private var currentNotificationStatus: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Bluetooth enforcement
    private val btEnforcementHandler = Handler(Looper.getMainLooper())
    private val btEnforcementRunnable = object : Runnable {
        override fun run() {
            if (prefs.getBoolean("force_bt_enabled", false)) {
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
            Log.i(TAG, "Force Bluetooth Enabled is active. Attempting direct toggle...")
            
            // Proactively ensure UserService is bound if Shizuku is authorized
            if (userService == null) {
                ensureUserServiceBound()
            }

            var directSuccess = false
            try {
                userService?.let {
                    Log.i(TAG, "Attempting direct Bluetooth enable via Shizuku UserService")
                    directSuccess = it.setBluetoothEnabled(true)
                    Log.i(TAG, "Direct Bluetooth enable result: $directSuccess")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable Bluetooth via Shizuku: ${e.message}")
            }

            if (!directSuccess) {
                Log.w(TAG, "Direct enable failed or UserService not available, falling back to manual request")
                withContext(Dispatchers.Main) {
                    // Trigger screen wake
                    wakeScreenForBluetooth()
                    
                    // Attempt to enable
                    try {
                        @SuppressLint("MissingPermission")
                        val success = adapter.enable()
                        if (!success) {
                            Log.w(TAG, "adapter.enable() returned false, triggering REQUEST_ENABLE activity")
                            triggerBluetoothEnableRequest()
                        } else {
                            Log.i(TAG, "adapter.enable() success (legacy or authorized)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error enabling Bluetooth: ${e.message}")
                        triggerBluetoothEnableRequest()
                    }
                }
            }
        }
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private var panManager: BluetoothPanManager? = null
    private var currentDevice: BluetoothDevice? = null

    @Volatile private var isStopping = false

    interface ServiceCallback {
        fun onStatusChanged(status: String)
        fun onDeviceConnected(deviceName: String, transportType: String)
        fun onDeviceDisconnected()
        fun onError(message: String)
        fun onScanResult(devices: List<BluetoothDevice>)

        fun onUploadProgress(progress: Int)
        fun onDownloadProgress(progress: Int)
        fun onFileListReceived(path: String, filesJson: String)
        fun onWatchStatusUpdated(
                batteryLevel: Int,
                isCharging: Boolean,
                wifiSsid: String,
                wifiEnabled: Boolean,
                dndEnabled: Boolean,
                ipAddress: String? = null
        ) {}
        fun onPhoneBatteryUpdated(battery: Int, isCharging: Boolean) {}
    }

    var callback: ServiceCallback? = null

    @Volatile var currentStatus: String = "" // Will be initialized in onCreate
    @Volatile var currentDeviceName: String? = null
    @Volatile var currentTransportType: String = ""
    @Volatile var isConnected: Boolean = false

    fun getConnectedDeviceMac(): String? {
        val state = transportManager.connectionState.value
        return if (state is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected) {
            state.deviceMac
        } else {
            null
        }
    }

    // JSON Protocol - message types defined in Protocol.kt

    companion object {
        const val ACTION_SEND_NOTIF_TO_WATCH = "com.ailife.rtosify.ACTION_SEND_NOTIF"
        const val ACTION_SEND_REMOVE_TO_WATCH = "com.ailife.rtosify.ACTION_SEND_REMOVE"
        const val ACTION_WATCH_DISMISSED_LOCAL = "com.ailife.rtosify.DISMISSED_LOCAL"
        const val ACTION_CMD_DISMISS_ON_PHONE = "com.ailife.rtosify.CMD_DISMISS_PHONE"
        const val ACTION_CMD_EXECUTE_ACTION = "com.ailife.rtosify.CMD_EXECUTE_ACTION"
        const val ACTION_CMD_SEND_REPLY = "com.ailife.rtosify.CMD_SEND_REPLY"
        const val ACTION_DND_UPDATED = "com.ailife.rtosifycompanion.ACTION_DND_UPDATED"
        const val ACTION_SCREEN_DATA_RECEIVED = "com.ailife.rtosifycompanion.SCREEN_DATA_RECEIVED"
        const val ACTION_STOP_MIRROR = "com.ailife.rtosifycompanion.STOP_MIRROR"
        const val ACTION_SEND_REMOTE_INPUT = "com.ailife.rtosifycompanion.SEND_REMOTE_INPUT"
        const val ACTION_SCREEN_DATA_AVAILABLE = "com.ailife.rtosifycompanion.SCREEN_DATA_AVAILABLE"
        const val ACTION_UPDATE_DI_SETTINGS = "com.ailife.rtosifycompanion.UPDATE_DI_SETTINGS"
        const val ACTION_SHOW_IN_DYNAMIC_ISLAND = "com.ailife.rtosifycompanion.SHOW_IN_DI"
        const val ACTION_DISMISS_FROM_DYNAMIC_ISLAND = "com.ailife.rtosifycompanion.DISMISS_FROM_DI"
        const val ACTION_REQUEST_CONNECTION_STATE =
                "com.ailife.rtosifycompanion.REQUEST_CONNECTION_STATE"
        const val ACTION_CONNECTION_STATE_CHANGED =
                "com.ailife.rtosifycompanion.CONNECTION_STATE_CHANGED"

        const val EXTRA_NOTIF_JSON = "extra_notif_json"
        const val EXTRA_NOTIF_KEY = "extra_notif_key"
        const val EXTRA_ACTION_KEY = "extra_action_key"
        const val EXTRA_REPLY_TEXT = "extra_reply_text"

        // IDs DISTINTOS para garantir limpeza correta ao trocar de estado
        const val NOTIFICATION_ID_WAITING = 10
        const val NOTIFICATION_ID_CONNECTED = 11
        const val NOTIFICATION_ID_DISCONNECTED = 12
        const val NOTIFICATION_ID_DISCONNECTION_ALERT = 13 // Alert shown when phone disconnects

        const val MIRRORED_NOTIFICATION_ID_START = 1000

        const val CHANNEL_ID_WAITING = "channel_status_waiting"
        const val CHANNEL_ID_CONNECTED = "channel_status_connected"
        const val CHANNEL_ID_DISCONNECTED = "channel_status_disconnected"
        const val CHANNEL_ID_DISCONNECTION_ALERT = "channel_disconnection_alert"

        const val MIRRORED_CHANNEL_ID = "mirrored_notifications"

        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"

        private const val TAG = "BluetoothService"
        private const val DEBUG_NOTIFICATIONS = false // Ative para debug
    }

    private val internalReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(
                            TAG,
                            "internalReceiver triggered: action=${intent?.action}, connected=$isConnected"
                    )
                    if (!isConnected) return
                    when (intent?.action) {
                        ACTION_SEND_NOTIF_TO_WATCH -> {
                            val jsonString = intent.getStringExtra(EXTRA_NOTIF_JSON)
                            if (jsonString != null) {
                                try {
                                    val gson = com.google.gson.Gson()
                                    val notifData =
                                            gson.fromJson(jsonString, NotificationData::class.java)
                                    sendMessage(ProtocolHelper.createNotificationPosted(notifData))
                                } catch (_: Exception) {}
                            }
                        }
                        ACTION_SEND_REMOVE_TO_WATCH -> {
                            val key = intent.getStringExtra(EXTRA_NOTIF_KEY)
                            if (key != null) {
                                sendMessage(ProtocolHelper.createNotificationRemoved(key))
                            }
                        }
                        ACTION_SEND_REMOTE_INPUT -> {
                            val x = intent.getIntExtra("x", 0)
                            val y = intent.getIntExtra("y", 0)
                            val action = intent.getIntExtra("action", 0)
                            Log.d(TAG, "Sending remote input: $action at ($x, $y)")
                            sendMessage(
                                    ProtocolHelper.createRemoteInput(
                                            action,
                                            x.toFloat(),
                                            y.toFloat()
                                    )
                            )
                        }
                        ACTION_CMD_DISMISS_ON_PHONE -> {
                            val key = intent.getStringExtra(EXTRA_NOTIF_KEY)
                            if (key != null) {
                                Log.d(TAG, "Internal request: Dismiss on phone: $key")
                                sendMessage(ProtocolHelper.createDismissNotification(key))
                            }
                        }
                        ACTION_CMD_EXECUTE_ACTION -> {
                            val notifKey = intent.getStringExtra(EXTRA_NOTIF_KEY)
                            val actionKey = intent.getStringExtra(EXTRA_ACTION_KEY)
                            if (notifKey != null && actionKey != null) {
                                Log.d(
                                        TAG,
                                        "Internal request: Execute action $actionKey for $notifKey"
                                )
                                sendMessage(
                                        ProtocolHelper.createExecuteNotificationAction(
                                                notifKey,
                                                actionKey
                                        )
                                )
                            }
                        }
                        ACTION_CMD_SEND_REPLY -> {
                            val notifKey = intent.getStringExtra(EXTRA_NOTIF_KEY)
                            val actionKey = intent.getStringExtra(EXTRA_ACTION_KEY)
                            val replyText = intent.getStringExtra(EXTRA_REPLY_TEXT)
                            if (notifKey != null && actionKey != null && replyText != null) {
                                Log.d(
                                        TAG,
                                        "Internal request: Send reply '$replyText' for $notifKey"
                                )
                                sendMessage(
                                        ProtocolHelper.createSendNotificationReply(
                                                notifKey,
                                                actionKey,
                                                replyText
                                        )
                                )
                            }
                        }
                        ACTION_SEND_REMOTE_INPUT -> {
                            val action = intent.getIntExtra("action", -1)
                            val x = intent.getFloatExtra("x", 0f)
                            val y = intent.getFloatExtra("y", 0f)
                            Log.d(TAG, "Sending remote input: action=$action, x=$x, y=$y")
                            if (action != -1) {
                                sendMessage(
                                        ProtocolMessage(
                                                type = MessageType.REMOTE_INPUT,
                                                data =
                                                        com.google.gson.JsonObject().apply {
                                                            addProperty("action", action)
                                                            addProperty("x", x)
                                                            addProperty("y", y)
                                                        }
                                        )
                                )
                            }
                        }
                        ACTION_SCREEN_DATA_AVAILABLE -> {
                            val base64Data = intent.getStringExtra("data") ?: return
                            val isKeyFrame = intent.getBooleanExtra("isKeyFrame", false)
                            Log.d(
                                    TAG,
                                    "Forwarding frame: size=${base64Data.length}B, keyframe=$isKeyFrame"
                            )
                            val msg = ProtocolHelper.createMirrorData(base64Data, isKeyFrame)
                            Log.d(
                                    TAG,
                                    "Calling sendMessage for mirror_data, isConnected=$isConnected"
                            )
                            sendMessage(msg)
                            Log.d(TAG, "sendMessage returned")
                        }
                        "com.ailife.rtosifycompanion.SEND_MIRROR_STOP" -> {
                            Log.d(TAG, "Sending mirror stop command to phone")
                            sendMessage(
                                    ProtocolMessage(
                                            type = MessageType.SCREEN_MIRROR_STOP,
                                            data = com.google.gson.JsonObject()
                                    )
                            )
                        }
                        "com.ailife.rtosifycompanion.UPDATE_REMOTE_RESOLUTION" -> {
                            val width = intent.getIntExtra("width", 0)
                            val height = intent.getIntExtra("height", 0)
                            val density = intent.getIntExtra("density", 0)
                            val reset = intent.getBooleanExtra("reset", false)
                            val mode = intent.getIntExtra("mode", ResolutionData.MODE_RESOLUTION)
                            sendMessage(
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

    private val watchDismissReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_WATCH_DISMISSED_LOCAL) {
                        val key = intent.getStringExtra(EXTRA_NOTIF_KEY)
                        if (key != null && isConnected) {
                            sendMessage(ProtocolHelper.createDismissNotification(key))
                            notificationMap.remove(key)
                        }
                    }
                }
            }

    private val dynamicIslandHandshakeReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_REQUEST_CONNECTION_STATE) {
                        Log.d(TAG, "Dynamic Island requested connection state: $isConnected")
                        val response = Intent(ACTION_CONNECTION_STATE_CHANGED)
                        response.putExtra("connected", isConnected)
                        response.setPackage(packageName)
                        sendBroadcast(response)
                    }
                }
            }

    private val aclDisconnectReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        Log.d(TAG, "ACL Disconnected: ${device?.address}, current isConnected=$isConnected")
                        if (isConnected) {
                            // Link layer disconnect detected, force transport cleanup
                            transportManager.forceDisconnect()
                        }
                    }
                }
            }

    private val batteryReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                        handleBatteryChanged(intent)
                    }
                }
            }

    private val wifiStateReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        WifiManager.WIFI_STATE_CHANGED_ACTION,
                        WifiManager.NETWORK_STATE_CHANGED_ACTION,
                        WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                            Log.d(
                                    TAG,
                                    "WiFi state changed: ${intent.action}, sending status update"
                            )
                            // Send updated status to phone when WiFi state changes
                            serviceScope.launch {
                                delay(500) // Small delay to let the state settle
                                if (isConnected) {
                                    try {
                                        val status = collectWatchStatus()
                                        sendMessage(ProtocolHelper.createStatusUpdate(status))
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error sending WiFi status update: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                }
            }

    private var userServiceConnection: Shizuku.UserServiceArgs? = null
    private var userService: IUserService? = null

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
                        Log.i(TAG, "UserService connected successfully")
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    userService = null
                    Log.w(TAG, "UserService disconnected")
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
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        // bluetoothAdapter assignment removed (property removed)
        createNotificationChannel()
        
        // Initialize WiFi transport infrastructure
        encryptionManager = com.ailife.rtosifycompanion.security.EncryptionManager(this)
        mdnsDiscovery = com.ailife.rtosifycompanion.communication.MdnsDiscovery(this)

        transportManager = com.ailife.rtosifycompanion.communication.TransportManager(
            this,
            serviceScope,
            encryptionManager,
            mdnsDiscovery!!
        )

        serviceScope.launch {
            transportManager.incomingMessages.collect { message ->
                lastMessageTime = System.currentTimeMillis()
                processReceivedMessage(message)
            }
        }

        serviceScope.launch {
            transportManager.connectionState.collect { state ->
                when (state) {
                    is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected -> {
                        val deviceName = state.deviceName ?: getString(R.string.device_name_default)
                        handleDeviceConnected(deviceName, state.deviceMac, state.type)
                    }
                    is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Disconnected -> {
                        handleDeviceDisconnected()
                    }
                    is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Waiting -> {
                        updateStatus(getString(R.string.status_waiting))
                        // If we were connected, Waiting means we just lost it
                        if (isConnected) {
                            Log.d(TAG, "TransportManager transitioned to Waiting state - treating as disconnection")
                            handleDeviceDisconnected()
                        }
                    }
                    else -> {}
                }
            }
        }

        val filterInternal =
                IntentFilter().apply {
                    addAction(ACTION_SEND_NOTIF_TO_WATCH)
                    addAction(ACTION_SEND_REMOVE_TO_WATCH)
                    addAction(ACTION_SEND_REMOTE_INPUT) // For touch events from MirrorActivity
                    addAction(
                            ACTION_SCREEN_DATA_AVAILABLE
                    ) // For video frames from MirroringService
                    addAction(
                            "com.ailife.rtosifycompanion.SEND_MIRROR_STOP"
                    ) // For stopping mirroring
                    addAction(
                            "com.ailife.rtosifycompanion.UPDATE_REMOTE_RESOLUTION"
                    ) // For resolution reset
                    addAction(ACTION_CMD_DISMISS_ON_PHONE)
                    addAction(ACTION_CMD_EXECUTE_ACTION)
                    addAction(ACTION_CMD_SEND_REPLY)
                }
        val filterWatch = IntentFilter(ACTION_WATCH_DISMISSED_LOCAL)
        val filterHandshake = IntentFilter(ACTION_REQUEST_CONNECTION_STATE)
        val filterWifi =
                IntentFilter().apply {
                    addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                    addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                    addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
                }

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
            ContextCompat.registerReceiver(
                    this,
                    wifiStateReceiver,
                    filterWifi,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                    this,
                    dynamicIslandHandshakeReceiver,
                    filterHandshake,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                    this,
                    aclDisconnectReceiver,
                    IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED),
                    ContextCompat.RECEIVER_EXPORTED // Needs to be exported for system ACL events
            )
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } else {
            registerReceiver(internalReceiver, filterInternal)
            registerReceiver(watchDismissReceiver, filterWatch)
            registerReceiver(wifiStateReceiver, filterWifi)
            registerReceiver(dynamicIslandHandshakeReceiver, filterHandshake)
            registerReceiver(aclDisconnectReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED))
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }

        // Initialize health data collector
        healthDataCollector = HealthDataCollector(this)

        // Initialize PAN manager
        panManager = BluetoothPanManager(this)

        // Start battery history tracking
        startBatteryHistoryTracking()

        deviceInfoManager = DeviceInfoManager(this)

        // Initialize alarm manager
        watchAlarmManager = WatchAlarmManager(this)

        Log.d(TAG, "BluetoothService created")
        
        if (prefs.getBoolean("force_bt_enabled", false)) {
            startBluetoothEnforcement()
        }

        // Bind to Shizuku UserService if available
        bindUserServiceIfNeeded()
    }

    private fun bindUserServiceIfNeeded() {
        try {
            if (Shizuku.pingBinder() &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "✅ Binding to Shizuku UserService")
                Shizuku.bindUserService(userServiceArgs, userServiceConn)
                userServiceConnection = userServiceArgs
            } else {
                Log.w(TAG, "❌ Shizuku not available or permission not granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to bind UserService: ${e.message}")
            e.printStackTrace()
        }

        if (DEBUG_NOTIFICATIONS) Log.d(TAG, "onCreate: Service created")
    }

    private suspend fun ensureUserServiceBound() {
        if (userService == null) {
            synchronized(shizukuLock) {
                if (userService == null) {
                    Log.d(TAG, "UserService is null, attempting to bind...")

                    // Check if Shizuku is even available
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

                    // Try to bind (this is not suspend)
                    bindUserServiceIfNeeded()
                }
            }

            // Wait for binding to complete (up to 3 seconds)
            // We wait OUTSIDE the synchronized block to avoid deadlocking if onServiceConnected
            // needs it (though it shouldn't)
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

    private fun handleUpdateDndSettings(message: ProtocolMessage) {
        try {
            val settings = ProtocolHelper.extractData<DndSettingsData>(message)
            Log.d(TAG, "Received DND settings update: $settings")

            prefs.edit()
                    .apply {
                        putBoolean("dnd_schedule_enabled", settings.scheduleEnabled)
                        putString("dnd_start_time", settings.startTime)
                        putString("dnd_end_time", settings.endTime)
                    }
                    .apply()

            if (settings.scheduleEnabled) {
                setupDndSchedule(settings.startTime, settings.endTime)
            } else {
                cancelDndSchedule()
            }

            // Quick duration
            settings.quickDurationMinutes?.let { minutes -> enableQuickDnd(minutes) }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling DND settings update: ${e.message}")
        }
    }

    private fun setupDndSchedule(start: String?, end: String?) {
        if (start == null || end == null) return
        cancelDndSchedule()

        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Start alarm
        val startIntent =
                Intent(this, DndReceiver::class.java).apply { action = DndReceiver.ACTION_DND_ON }
        val startPI =
                PendingIntent.getBroadcast(
                        this,
                        101,
                        startIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        // End alarm
        val endIntent =
                Intent(this, DndReceiver::class.java).apply { action = DndReceiver.ACTION_DND_OFF }
        val endPI =
                PendingIntent.getBroadcast(
                        this,
                        102,
                        endIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        val startTime = parseTimeToMillis(start)
        val endTime = parseTimeToMillis(end)

        am.setRepeating(AlarmManager.RTC_WAKEUP, startTime, AlarmManager.INTERVAL_DAY, startPI)
        am.setRepeating(AlarmManager.RTC_WAKEUP, endTime, AlarmManager.INTERVAL_DAY, endPI)

        Log.d(TAG, "DND Alarms set: Start $start, End $end")
    }

    private fun cancelDndSchedule() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val startPI =
                PendingIntent.getBroadcast(
                        this,
                        101,
                        Intent(this, DndReceiver::class.java),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
        val endPI =
                PendingIntent.getBroadcast(
                        this,
                        102,
                        Intent(this, DndReceiver::class.java),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )

        startPI?.let {
            am.cancel(it)
            it.cancel()
        }
        endPI?.let {
            am.cancel(it)
            it.cancel()
        }
        Log.d(TAG, "DND Schedule alarms cancelled")
    }

    @android.annotation.SuppressLint("ScheduleExactAlarm")
    private fun enableQuickDnd(minutes: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent =
                    Intent(this, DndReceiver::class.java).apply {
                        action = DndReceiver.ACTION_DND_OFF
                    }
            val pi =
                    PendingIntent.getBroadcast(
                            this,
                            103,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

            val triggerAt = System.currentTimeMillis() + (minutes * 60 * 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Quick DND enabled for $minutes minutes")

            // Notify phone immediately
            serviceScope.launch {
                delay(500)
                val status = collectWatchStatus()
                sendMessage(ProtocolHelper.createStatusUpdate(status))
            }
        }
    }

    private fun parseTimeToMillis(time: String): Long {
        val parts = time.split(":")
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()

        val calendar =
                java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }

        if (calendar.timeInMillis < System.currentTimeMillis()) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopServiceCompletely()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_DND_UPDATED) {
            val enabled = intent.getBooleanExtra("enabled", false)
            Log.d(TAG, "DND state updated via Receiver: $enabled")
            serviceScope.launch {
                delay(500)
                if (isConnected) {
                    val status = collectWatchStatus()
                    sendMessage(ProtocolHelper.createStatusUpdate(status))
                }
            }
        }

        if (intent?.action == "STOP_ALARM") {
            stopFindDeviceAlarm()
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

                // CRITICAL: Refresh the notification locally to stop the "spinning" indicator
                // and show the reply in history immediately
                refreshNotification(notifKey, replyText)
            }
            return START_NOT_STICKY
        }

        // CRÍTICO: Chamar startForeground imediatamente para cumprir a promessa feita no
        // BootReceiver.
        updateForegroundNotification()

        // Initialize logic if not connected
        if (!isConnected) {
            initializeLogicFromPrefs()
        }

        if (DEBUG_NOTIFICATIONS)
                Log.d(TAG, "onStartCommand: Service started, isConnected=$isConnected")

        isStopping = false // Reset on start
        return START_STICKY
    }

    private fun initializeLogicFromPrefs() {
        if (!prefs.getBoolean("service_enabled", true)) {
            // Even if main service logic is disabled, we might need to start DI if it was enabled
            if (prefs.getString("notification_style", "android") == "dynamic_island") {
                startDynamicIslandService()
            }
            return
        }

        // Companion app is watch-only, always start watch logic
        startWatchLogic()

        // Ensure Dynamic Island is started if enabled
        if (prefs.getString("notification_style", "android") == "dynamic_island") {
            startDynamicIslandService()
        }
    }

    @SuppressLint("MissingPermission")
    fun startWatchLogic() {
        // Start servers via TransportManager
        
        // Bluetooth Server
    val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter = btManager.adapter

    val watchMac = prefs.getString("wifi_advertised_mac", null)
    if (watchMac != null) {
        val deviceName = adapter?.name ?: Build.MODEL
        Log.d(TAG, "Starting WiFi server for local=$watchMac, remote=$watchMac")
        transportManager.startWifiServer(deviceName, watchMac, watchMac)
        // Start WiFi server watchdog
        transportManager.startWifiServerWatchdog(this, deviceName, watchMac, watchMac)
    }

    // Bluetooth Server
    transportManager.startBluetoothServer(adapter)
    // Start Bluetooth server watchdog
    transportManager.startBluetoothServerWatchdog(adapter)
    }

    private fun handleDeviceConnected(deviceName: String, mac: String?, transportType: String = "") {
        if (isConnected && currentTransportType == transportType) return 
        
        isConnected = true
        isTransferring = false
        lastMessageTime = System.currentTimeMillis()
        currentDeviceName = deviceName
        currentTransportType = transportType
        
        updateStatus(getString(R.string.status_connected_to, deviceName))
        
        // Notify Dynamic Island
        sendBroadcast(
            Intent(ACTION_CONNECTION_STATE_CHANGED).apply {
                putExtra("connected", true)
                putExtra("transport", transportType)
                setPackage(packageName)
            }
        )
        
        serviceScope.launch(Dispatchers.Main) { callback?.onDeviceConnected(deviceName, transportType) }
        
        // Start automation and encryption
        serviceScope.launch {
             // Trigger automation on connection
             onConnectionEstablished()
             
             // Initialize encryption if MAC is available
             mac?.let {
                 initializeEncryptionForDevice(it)
             }
        }
    }
    
    private fun handleDeviceDisconnected() {
        if (!isConnected) return
        
        val wasConnected = isConnected
        isConnected = false
        currentDeviceName = null
        
        // Notify Dynamic Island
        sendBroadcast(
            Intent(ACTION_CONNECTION_STATE_CHANGED).apply {
                putExtra("connected", false)
                setPackage(packageName)
            }
        )
        
        if (wasConnected) {
            updateStatus(getString(R.string.status_disconnected))
            
            if (prefs.getBoolean("notify_on_disconnect", false)) {
                showDisconnectionNotification()
            }
            
            onConnectionLost()
        }
        
        serviceScope.launch(Dispatchers.Main) { callback?.onDeviceDisconnected() }
        
        // Restart servers if needed
        startWatchLogic()
    }

    // Old connection handling methods removed - replaced by TransportManager


    private suspend fun processReceivedMessage(message: ProtocolMessage) {
        Log.d(TAG, "📩 Processing message type: ${message.type}")
        when (message.type) {
            MessageType.HEARTBEAT -> {
                /* IGNORE */
            }
            MessageType.REQUEST_APPS -> handleRequestApps()
// REMOVED: MessageType.RESPONSE_APPS -> handleResponseApps(message)
            MessageType.NOTIFICATION_POSTED -> showMirroredNotification(message)
            MessageType.NOTIFICATION_REMOVED -> dismissLocalNotification(message)
            MessageType.DISMISS_NOTIFICATION -> requestDismissOnPhone(message)
            MessageType.EXECUTE_NOTIFICATION_ACTION ->
                    handleExecuteNotificationAction(message)
            MessageType.SEND_NOTIFICATION_REPLY -> handleSendNotificationReply(message)
            MessageType.FILE_TRANSFER_START -> handleFileTransferStart(message)
            MessageType.FILE_CHUNK -> handleFileChunk(message)
            MessageType.FILE_TRANSFER_END -> handleFileTransferEnd(message)
            MessageType.SHUTDOWN -> handleShutdownCommand()
            MessageType.REBOOT -> handleRebootCommand()
            MessageType.LOCK_DEVICE -> handleLockDeviceCommand()
            MessageType.FIND_DEVICE -> handleFindDeviceCommand(message)
            MessageType.STATUS_UPDATE -> handleStatusUpdateReceived(message)
            MessageType.SET_DND -> handleSetDndCommand(message)
            MessageType.UPDATE_DND_SETTINGS -> handleUpdateDndSettings(message)
            MessageType.SET_WIFI -> handleSetWifiCommand(message)
            MessageType.REQUEST_WIFI_SCAN -> serviceScope.launch { handleRequestWifiScan() }
            MessageType.CONNECT_WIFI -> handleConnectWifi(message)
            MessageType.CAMERA_FRAME -> handleCameraFrame(message)
            MessageType.REQUEST_FILE_LIST -> handleRequestFileList(message)
            MessageType.REQUEST_FILE_DOWNLOAD -> handleRequestFileDownload(message)
            MessageType.DELETE_FILE -> handleDeleteFile(message)
            MessageType.UPDATE_SETTINGS -> handleUpdateSettings(message)
            MessageType.UPDATE_WIFI_RULE -> handleUpdateWifiRule(message)
            MessageType.REQUEST_HEALTH_DATA -> handleRequestHealthData()
            MessageType.REQUEST_HEALTH_HISTORY -> handleRequestHealthHistory(message)
            MessageType.START_LIVE_MEASUREMENT -> handleStartLiveMeasurement(message)
            MessageType.STOP_LIVE_MEASUREMENT -> handleStopLiveMeasurement()
            MessageType.UPDATE_HEALTH_SETTINGS -> handleUpdateHealthSettings(message)
            MessageType.REQUEST_HEALTH_SETTINGS -> handleRequestHealthSettings()
            MessageType.SYNC_CALENDAR -> handleSyncCalendar(message)
            MessageType.SYNC_CONTACTS -> handleSyncContacts(message)
            MessageType.RENAME_FILE -> handleRenameFile(message)
            MessageType.MOVE_FILE -> handleMoveFile(message)
            MessageType.SET_WATCH_FACE -> handleSetWatchFace(message)
            MessageType.CREATE_FOLDER -> handleCreateFolder(message)
            MessageType.REQUEST_PREVIEW -> handleRequestPreview(message)
            MessageType.UNINSTALL_APP -> handleUninstallApp(message)
            MessageType.INCOMING_CALL -> handleIncomingCall(message)
            MessageType.CALL_STATE_CHANGED -> handleCallStateChanged(message)
            MessageType.REQUEST_BATTERY_LIVE -> handleRequestBatteryLive()
            MessageType.REQUEST_BATTERY_STATIC -> handleRequestBatteryStatic()
            MessageType.PHONE_BATTERY_UPDATE -> handlePhoneBatteryUpdate(message)
            MessageType.REQUEST_WATCH_STATUS -> handleRequestWatchStatus()
            MessageType.REQUEST_DEVICE_INFO_UPDATE -> handleRequestDeviceInfo()
            MessageType.UPDATE_BATTERY_SETTINGS -> handleUpdateBatterySettings(message)
            MessageType.CLIPBOARD_SYNC -> handleClipboardSync(message)
            MessageType.ENABLE_BT_INTERNET -> handleEnableBluetoothInternet(message)
            MessageType.REQUEST_ALARMS -> handleRequestAlarms()
            MessageType.ADD_ALARM -> handleAddAlarm(message)
            MessageType.UPDATE_ALARM -> handleUpdateAlarm(message)
            MessageType.DELETE_ALARM -> handleDeleteAlarm(message)
            MessageType.SCREEN_MIRROR_START -> handleMirrorStart(message)
            MessageType.SCREEN_MIRROR_STOP -> handleMirrorStop()
            MessageType.SCREEN_MIRROR_DATA -> handleMirrorData(message)
            MessageType.REMOTE_INPUT -> handleRemoteInput(message)
            MessageType.UPDATE_RESOLUTION -> handleUpdateResolution(message)
            MessageType.MIRROR_RES_CHANGE -> handleMirrorResChange(message)
            MessageType.EXECUTE_SHELL_COMMAND -> handleExecuteShellCommand(message)
            MessageType.CANCEL_SHELL_COMMAND -> handleCancelShellCommand(message)
            MessageType.REQUEST_PERMISSION_INFO -> handleRequestPermissionInfo()
            MessageType.WIFI_KEY_EXCHANGE -> handleWifiKeyExchange(message)
            MessageType.WIFI_TEST_ENCRYPT -> handleWifiTestEncrypt(message)
            MessageType.SYNC_PHONE_STATE -> handleSyncPhoneState(message)
            MessageType.SHARE_SYNC -> handleShareReceived(message)
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
        }
    }

    private fun handleSyncPhoneState(message: ProtocolMessage) {
        val isForeground = ProtocolHelper.extractBooleanField(message, "isForeground")
        Log.d(TAG, "Syncing phone foreground state: $isForeground")
        transportManager.updatePhoneForegroundState(isForeground)
    }

    fun requestPhoneBattery() {
        sendMessage(ProtocolHelper.createRequestPhoneBattery())
    }

    private fun handlePhoneBatteryUpdate(message: ProtocolMessage) {
        try {
            val data = ProtocolHelper.extractData<PhoneBatteryData>(message)
            serviceScope.launch(Dispatchers.Main) {
                callback?.onPhoneBatteryUpdated(data.level, data.isCharging)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing phone battery update: ${e.message}", e)
        }
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

    private fun wakeScreen(tag: String = "General") {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isInteractive) {
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "rtosify:WakeLock:$tag"
                )
                wakeLock.acquire(3000)
                Log.d(TAG, "Screen woken up for: $tag")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen ($tag): ${e.message}")
        }
    }

    private fun wakeScreenForBluetooth() {
        wakeScreen("BluetoothEnforcement")
    }

    private fun triggerBluetoothEnableRequest() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ACTION_REQUEST_ENABLE: ${e.message}")
        }
    }

    private fun handleUpdateSettings(message: ProtocolMessage) {
        try {
            val settings = ProtocolHelper.extractData<SettingsUpdateData>(message)
            Log.d(
                    TAG,
                    "Received settings update: notifyOnDisconnect=${settings.notifyOnDisconnect}, clipboard=${settings.clipboardSyncEnabled}, wifi=${settings.autoWifiEnabled}, data=${settings.autoDataEnabled}, btTether=${settings.autoBtTetherEnabled}"
            )

            // Store all automation preferences
            settings.notifyOnDisconnect?.let {
                prefs.edit().putBoolean("notify_on_disconnect", it).apply()
            }
            settings.notificationMirroringEnabled?.let {
                prefs.edit().putBoolean("notification_mirroring_enabled", it).apply()
            }
            settings.skipScreenOnEnabled?.let {
                prefs.edit().putBoolean("skip_screen_on_enabled", it).apply()
            }
            settings.forwardOngoingEnabled?.let {
                prefs.edit().putBoolean("forward_ongoing_enabled", it).apply()
            }
            settings.forwardSilentEnabled?.let {
                prefs.edit().putBoolean("forward_silent_enabled", it).apply()
            }
            settings.clipboardSyncEnabled?.let {
                val wasEnabled = prefs.getBoolean("clipboard_sync_enabled", false)
                prefs.edit().putBoolean("clipboard_sync_enabled", it).apply()

                // Start/stop clipboard monitoring based on change
                if (it && !wasEnabled) {
                    startClipboardMonitoring()
                    Log.d(TAG, "Started clipboard monitoring")
                } else if (!it && wasEnabled) {
                    stopClipboardMonitoring()
                    Log.d(TAG, "Stopped clipboard monitoring")
                }
            }
            settings.autoWifiEnabled?.let {
                prefs.edit().putBoolean("auto_wifi_enabled", it).apply()
                Log.d(TAG, "Auto WiFi setting updated: $it")
                
                // Stop WiFi transport if phone disabled it to save battery
                val state = transportManager.connectionState.value
                val wifiConnected = state is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected && 
                                   (state.type == "WiFi" || state.type == "Dual")
                if (!it && wifiConnected) {
                    serviceScope.launch {
                        transportManager.stopWifiServer()
                        Log.d(TAG, "WiFi transport stopped due to phone preference")
                    }
                }
            }
            settings.autoDataEnabled?.let {
                prefs.edit().putBoolean("auto_data_enabled", it).apply()
                Log.d(TAG, "Auto Data setting updated: $it")
            }
            settings.autoBtTetherEnabled?.let {
                prefs.edit().putBoolean("auto_bt_tether_enabled", it).apply()
                Log.d(TAG, "Auto BT Tether setting updated: $it")
            }
            settings.wakeScreenEnabled?.let {
                prefs.edit().putBoolean("wake_screen_enabled", it).apply()
                Log.d(TAG, "Wake Screen setting updated: $it")
            }
            settings.vibrateEnabled?.let {
                prefs.edit().putBoolean("vibrate_enabled", it).apply()
                Log.d(TAG, "Vibrate setting updated: $it")
            }
            settings.vibrateInSilentEnabled?.let {
                prefs.edit().putBoolean("vibrate_silent_enabled", it).apply()
                Log.d(TAG, "Vibrate in Silent setting updated: $it")
            }
            settings.notificationStyle?.let {
                prefs.edit().putString("notification_style", it).apply()
                Log.d(TAG, "Notification Style setting updated: $it")

                // Start or stop Dynamic Island service based on style
                if (it == "dynamic_island") {
                    startDynamicIslandService()
                } else {
                    stopDynamicIslandService()
                }
            }
            settings.dynamicIslandTimeout?.let {
                prefs.edit().putInt("dynamic_island_timeout", it).apply()
                Log.d(TAG, "Dynamic Island timeout updated: $it seconds")
            }
            settings.dynamicIslandY?.let {
                prefs.edit().putInt("dynamic_island_y", it).apply()
                Log.d(TAG, "Dynamic Island Y updated: $it dp")
            }
            settings.dynamicIslandWidth?.let {
                prefs.edit().putInt("dynamic_island_width", it).apply()
                Log.d(TAG, "Dynamic Island Width updated: $it dp")
            }
            settings.dynamicIslandHeight?.let {
                prefs.edit().putInt("dynamic_island_height", it).apply()
                Log.d(TAG, "Dynamic Island Height updated: $it dp")
            }
            settings.dynamicIslandHideWhenIdle?.let {
                prefs.edit().putBoolean("dynamic_island_hide_idle", it).apply()
                Log.d(TAG, "Dynamic Island Hide When Idle updated: $it")
            }
            settings.dynamicIslandTextMultiplier?.let {
                prefs.edit().putFloat("dynamic_island_text_multiplier", it).apply()
                Log.d(TAG, "Dynamic Island Text Multiplier updated: $it")
            }
            settings.dynamicIslandLimitMessageLength?.let {
                prefs.edit().putBoolean("dynamic_island_limit_message_length", it).apply()
                Log.d(TAG, "Dynamic Island Limit Message Length updated: $it")
            }
            settings.forceBtEnabled?.let {
                prefs.edit().putBoolean("force_bt_enabled", it).apply()
                Log.d(TAG, "Force Bluetooth setting updated: $it")
                if (it) {
                    startBluetoothEnforcement()
                } else {
                    stopBluetoothEnforcement()
                }
            }
            settings.shareSyncEnabled?.let {
                prefs.edit().putBoolean("sharing_sync_enabled", it).apply()
                Log.d(TAG, "Sharing Sync setting updated: $it")
            }

            // Notify DynamicIslandService of settings change
            sendBroadcast(Intent(ACTION_UPDATE_DI_SETTINGS).setPackage(packageName))

            Log.d(TAG, "Automation settings updated from phone")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling settings update: ${e.message}")
        }
    }
    
    private fun handleUpdateWifiRule(message: ProtocolMessage) {
        try {
            val wifiActivationRule = message.data.get("rule")?.asInt
            wifiActivationRule?.let {
                prefs.edit().putInt("wifi_activation_rule", it).apply()
                transportManager.updateWifiRule(it)
                Log.d(TAG, "WiFi activation rule updated to: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating WiFi rule", e)
        }
    }

    fun sendShareSync(shareData: ShareData) {
        if (!prefs.getBoolean("sharing_sync_enabled", false)) return
        sendMessage(ProtocolHelper.createShareSync(shareData))
    }

    // Clipboard sync functions
    private fun startClipboardMonitoring() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // Android 9 and below: Use standard ClipboardManager listener only
            startStandardClipboardListener()
            Log.d(TAG, "Clipboard monitoring started (Android 9- mode - listener only)")
        } else {
            // Android 10+: Shizuku UserService ONLY
            if (userService != null) {
                startClipboardPolling(3000)
                Log.d(TAG, "Clipboard monitoring started (Shizuku mode)")
            } else {
                Log.w(
                        TAG,
                        "Shizuku UserService not available, clipboard sync disabled on Android 10+"
                )
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
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    // Android 9-: Use standard ClipboardManager
                    val clip = clipboardManager?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).text?.toString()
                    } else null
                } else {
                    // Android 10+: Use Shizuku UserService
                    try {
                        userService?.primaryClipText
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading clipboard via Shizuku: ${e.message}")
                        null
                    }
                }

        if (text != null && text != lastClipboardText) {
            lastClipboardText = text
            Log.d(TAG, "Clipboard changed (polling): $text")
            if (isConnected) {
                sendMessage(ProtocolHelper.createClipboardSync(text))
            }
        }
    }

    private fun handleClipboardSync(message: ProtocolMessage) {
        val text = ProtocolHelper.extractStringField(message, "text") ?: return
        Log.d(TAG, "Received clipboard text from phone: ${text.take(50)}...")

        mainHandler.post {
            try {
                val clipboard =
                        getSystemService(Context.CLIPBOARD_SERVICE) as
                                android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("rtosify_clipboard", text)
                clipboard.setPrimaryClip(clip)
                lastClipboardText = text
                Log.d(TAG, "✅ Clipboard updated on watch")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to update clipboard: ${e.message}")
            }
        }
    }

    private fun handleShareReceived(message: ProtocolMessage) {
        if (!prefs.getBoolean("sharing_sync_enabled", false)) return
        try {
            val shareData = ProtocolHelper.extractData<ShareData>(message)
            mainHandler.post {
                showShareChooser(shareData.title ?: "Share", shareData.text, shareData.type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SHARE_SYNC: ${e.message}")
        }
    }

    // Alarm management handlers
    private fun handleRequestAlarms() {
        serviceScope.launch {
            try {
                val alarms = watchAlarmManager.getAllAlarms()
                sendMessage(ProtocolHelper.createResponseAlarms(alarms))
                Log.d(TAG, "Sent ${alarms.size} alarms to phone")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling REQUEST_ALARMS: ${e.message}")
            }
        }
    }

    private fun handleAddAlarm(message: ProtocolMessage) {
        serviceScope.launch {
            try {
                val alarm = ProtocolHelper.extractData<AlarmData>(message)
                val addedAlarm = watchAlarmManager.addAlarm(alarm)

                //  Send updated alarm list back to phone
                val alarms = watchAlarmManager.getAllAlarms()
                sendMessage(ProtocolHelper.createResponseAlarms(alarms))

                Log.d(TAG, "Added alarm: ${addedAlarm.hour}:${addedAlarm.minute}")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling ADD_ALARM: ${e.message}")
            }
        }
    }

    private fun handleUpdateAlarm(message: ProtocolMessage) {
        serviceScope.launch {
            try {
                val alarm = ProtocolHelper.extractData<AlarmData>(message)
                watchAlarmManager.updateAlarm(alarm)

                // Send updated alarm list back to phone
                val alarms = watchAlarmManager.getAllAlarms()
                sendMessage(ProtocolHelper.createResponseAlarms(alarms))

                Log.d(TAG, "Updated alarm: ${alarm.id}, enabled: ${alarm.enabled}")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling UPDATE_ALARM: ${e.message}")
            }
        }
    }

    private fun handleDeleteAlarm(message: ProtocolMessage) {
        serviceScope.launch {
            try {
                val alarmId = ProtocolHelper.extractStringField(message, "alarmId") ?: return@launch
                watchAlarmManager.deleteAlarm(alarmId)

                // Send updated alarm list back to phone
                val alarms = watchAlarmManager.getAllAlarms()
                sendMessage(ProtocolHelper.createResponseAlarms(alarms))

                Log.d(TAG, "Deleted alarm: $alarmId")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling DELETE_ALARM: ${e.message}")
            }
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

    // WiFi/Data automation control
    private fun enableWifiAutomation() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 9 and below - standard API still works
                val wm = getSystemService(WIFI_SERVICE) as WifiManager
                wm.isWifiEnabled = true
                Log.d(TAG, "Auto WiFi: Enabled via standard API")
            } else {
                // Android 10+ - requires privileged access via Shizuku
                userService?.setWifiEnabled(true)
                Log.d(TAG, "Auto WiFi: Enabled via Shizuku")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable WiFi: ${e.message}")
        }
    }

    private fun disableWifiAutomation() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 9 and below - standard API still works
                val wm = getSystemService(WIFI_SERVICE) as WifiManager
                wm.isWifiEnabled = false
                Log.d(TAG, "Auto WiFi: Disabled via standard API")
            } else {
                // Android 10+ - requires privileged access via Shizuku
                userService?.setWifiEnabled(false)
                Log.d(TAG, "Auto WiFi: Disabled via Shizuku")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable WiFi: ${e.message}")
        }
    }

    private fun enableMobileDataAutomation() {
        try {
            // Try privileged method via Shizuku
            userService?.setMobileDataEnabled(true)
            Log.d(TAG, "Auto Data: Enabled via Shizuku")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable mobile data: ${e.message}")
        }
    }

    private fun disableMobileDataAutomation() {
        try {
            // Try privileged method via Shizuku
            userService?.setMobileDataEnabled(false)
            Log.d(TAG, "Auto Data: Disabled via Shizuku")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable mobile data: ${e.message}")
        }
    }

    private fun handleEnableBluetoothInternet(message: ProtocolMessage) {
        val enabled = ProtocolHelper.extractBooleanField(message, "enabled")
        try {
            if (enabled) {
                Log.d(TAG, "🔵 Bluetooth Internet Access enable requested via accessibility...")
                currentDevice?.let { device ->
                    RtosifyAccessibilityService.enableBluetoothTethering(
                            device.name ?: device.address
                    )
                }
                        ?: run {
                            RtosifyAccessibilityService.enableBluetoothTethering(currentDeviceName)
                        }
            } else {
                Log.d(
                        TAG,
                        "🔵 Bluetooth Internet Access disable not implemented (disconnect BT to disable)"
                )
                // Disabling is automatic when Bluetooth disconnects
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Exception toggling Bluetooth internet: ${e.message}", e)
        }
    }

    // Connection state automation - called on connection
    private fun onConnectionEstablished() {
        serviceScope.launch(Dispatchers.IO) {
            delay(1000) // Debounce

            // Check clipboard pref
            if (prefs.getBoolean("clipboard_sync_enabled", false)) {
                startClipboardMonitoring()
            }

            // Auto WiFi: Disable WiFi when BT connects
            if (prefs.getBoolean("auto_wifi_enabled", false)) {
                disableWifiAutomation()
            }

            // Auto Data: Disable mobile data when BT connects
            if (prefs.getBoolean("auto_data_enabled", false)) {
                disableMobileDataAutomation()
            }

            // Auto BT Tether: Enable Bluetooth PAN when connected
            // NOTE: We don't trigger it here anymore because the phone sends an explicit
            // 'enable_bt_internet' message after it has enabled its own tethering toggle,
            // which ensures the sequence is correct.
            if (prefs.getBoolean("auto_bt_tether_enabled", false)) {
                Log.d(TAG, "Auto BT Tether enabled, waiting for phone signal...")
            }
        }
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

            // Auto WiFi: Enable WiFi when BT disconnects
            if (prefs.getBoolean("auto_wifi_enabled", false)) {
                enableWifiAutomation()
            }

            // Auto Data: Enable mobile data when BT disconnects
            if (prefs.getBoolean("auto_data_enabled", false)) {
                enableMobileDataAutomation()
            }

            // Auto BT Tether: Bluetooth PAN automatically disables when BT disconnects
            // No need to explicitly disable it
        }
    }

    private fun showDisconnectionNotification() {
        Log.d(TAG, "Showing disconnection notification")
        val title = getString(R.string.notification_phone_disconnected_title)
        val text = getString(R.string.notification_phone_disconnected_text)

        val notification =
                NotificationCompat.Builder(this, CHANNEL_ID_DISCONNECTION_ALERT)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setAutoCancel(true)
                        .setVibrate(longArrayOf(0, 500, 200, 500))
                        .build()

        notificationManager.notify(NOTIFICATION_ID_DISCONNECTION_ALERT, notification)
    }

    // Message reading is now handled by transport layer

    // ===== WiFi Transport Management =====
    
    /**
     * Initialize encryption for WiFi and start listening as server.
     * Watch always acts as server.
     */
    private fun initializeEncryptionForDevice(deviceMac: String) {
        try {
            encryptionManager.initializeForDevice(deviceMac)
            encryptionManager.setActiveDevice(deviceMac)
            Log.d(TAG, "initializeEncryptionForDevice: mac=$deviceMac")
            
            // Watch always starts WiFi as server
            serviceScope.launch {
                val watchMac = prefs.getString("wifi_advertised_mac", "") ?: ""
                val deviceName = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name ?: android.os.Build.MODEL
                transportManager.startWifiServer(deviceName, watchMac, watchMac)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encryption", e)
        }
    }
    
    // Legacy startWifiTransportServer removed

    
    // Removed stopWifiTransport

    // REMOVED: syncSettingsToPhone() - Empty method, no longer needed

    fun sendMessage(message: ProtocolMessage) {
        serviceScope.launch(Dispatchers.IO) {
            if (!transportManager.send(message)) {
                Log.w(TAG, "sendMessage: Failed to send message type=${message.type}")
            }
        }
    }

    private suspend fun sendMessageSync(message: ProtocolMessage) {
        transportManager.send(message)
    }

    // ========================================================================
    // WATCH STATUS (BATTERY, WIFI, DND)
    // ========================================================================

    private fun handleRequestWatchStatus() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val status = collectWatchStatus()
                sendMessage(ProtocolHelper.createStatusUpdate(status))
            } catch (e: Exception) {
                Log.e(TAG, "Error sending status: ${e.message}")
            }
        }
    }

    private fun handleRequestDeviceInfo() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val info = deviceInfoManager.getDeviceInfo(userService)
                sendMessage(ProtocolHelper.createDeviceInfoUpdate(info))
            } catch (e: Exception) {
                Log.e(TAG, "Error sending device info: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectWatchStatus(): StatusUpdateData {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val dndEnabled = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        var wifiSsid: String
        var wifiEnabled = false
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiEnabled = wm.isWifiEnabled

            if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (wifiEnabled) {
                    val info = wm.connectionInfo

                    if (info != null && info.supplicantState == SupplicantState.COMPLETED) {
                        val rawSsid = info.ssid
                        Log.d(TAG, "WiFi Status: rawSsid='$rawSsid', supplicantState=${info.supplicantState}")

                        // Handle various SSID formats and edge cases
                        wifiSsid = when {
                            rawSsid.isNullOrEmpty() -> "Connected"
                            rawSsid == "<unknown ssid>" -> "Connected"
                            rawSsid == "\"<unknown ssid>\"" -> "Connected"
                            rawSsid == "0x" -> "Connected" // Some devices return this
                            else -> {
                                // Remove quotes if present
                                val cleanSsid = rawSsid.replace("\"", "").trim()
                                if (cleanSsid.isEmpty() || cleanSsid == "<unknown ssid>") {
                                    "Connected"
                                } else {
                                    cleanSsid
                                }
                            }
                        }
                    } else {
                        wifiSsid = getString(R.string.wifi_status_disconnected)
                        Log.d(TAG, "WiFi Status: Not connected, info=$info, supplicantState=${info?.supplicantState}")
                    }
                } else {
                    wifiSsid = getString(R.string.wifi_status_disabled)
                }
            } else {
                wifiSsid = getString(R.string.wifi_status_no_permission)
                Log.w(TAG, "WiFi Status: No location permission")
            }
        } catch (e: Exception) {
            wifiSsid = getString(R.string.wifi_status_error)
            Log.e(TAG, "WiFi Status: Error collecting status", e)
        }

        val ipAddress = getIpAddress()
        Log.d(TAG, "WiFi Status collected: ssid='$wifiSsid', enabled=$wifiEnabled, ip=$ipAddress")

        return StatusUpdateData(
                battery = batteryLevel,
                charging = isCharging,
                dnd = dndEnabled,
                wifi = wifiSsid,
                wifiEnabled = wifiEnabled,
                ipAddress = ipAddress
        )
    }

    private fun getIpAddress(): String? {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            if (wm == null) {
                Log.w(TAG, "WiFi IP: WifiManager is null")
                return null
            }
            
            val connectionInfo = wm.connectionInfo
            if (connectionInfo == null) {
                Log.w(TAG, "WiFi IP: connectionInfo is null")
                return null
            }
            
            val ip = connectionInfo.ipAddress
            if (ip == 0) {
                Log.d(TAG, "WiFi IP: IP address is 0 (not connected or no IP assigned)")
                null
            } else {
                val ipString = String.format(
                        "%d.%d.%d.%d",
                        (ip and 0xff),
                        (ip shr 8 and 0xff),
                        (ip shr 16 and 0xff),
                        (ip shr 24 and 0xff)
                )
                Log.d(TAG, "WiFi IP: Retrieved IP address: $ipString")
                ipString
            }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi IP: Error getting IP address", e)
            null
        }
    }

    // ========================================================================
    // HEALTH DATA (STEPS, HR, OXYGEN)
    // ========================================================================

    private suspend fun handleRequestHealthData() {
        try {
            Log.d(TAG, "Received REQUEST_HEALTH_DATA, collecting data...")
            // Collect health data once when requested (not continuously)
            val healthData = healthDataCollector.collectCurrentHealthData()
            Log.d(
                    TAG,
                    "Collected health data: steps=${healthData.steps}, cal=${healthData.calories}, hr=${healthData.heartRate}"
            )
            sendMessage(ProtocolHelper.createHealthDataUpdate(healthData))
            Log.d(TAG, "Sent health data update to phone")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting health data: ${e.message}")
        }
    }

    private fun startLiveMeasurementMode(type: String) {
        liveMeasurementJob?.cancel()
        liveMeasurementJob =
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        // Perform one high-priority measurement (can take up to 60s)
                        val healthData =
                                healthDataCollector.measureNow(type) { instantValue ->
                                    // Send instant update to phone
                                    sendMessage(
                                            ProtocolHelper.createHealthDataUpdate(
                                                    HealthDataUpdate(
                                                            steps = 0,
                                                            distance = 0f,
                                                            calories = 0,
                                                            heartRate =
                                                                    if (type == "HR") instantValue
                                                                    else null,
                                                            heartRateTimestamp =
                                                                    System.currentTimeMillis(),
                                                            bloodOxygen =
                                                                    if (type == "OXYGEN")
                                                                            instantValue
                                                                    else null,
                                                            oxygenTimestamp =
                                                                    System.currentTimeMillis(),
                                                            isInstant = true
                                                    )
                                            )
                                    )
                                }
                        sendMessage(ProtocolHelper.createHealthDataUpdate(healthData))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in live measurement: ${e.message}")
                    } finally {
                        liveMeasurementJob = null
                    }
                }
    }

    private suspend fun handleRequestHealthHistory(message: ProtocolMessage) {
        try {
            val request = ProtocolHelper.extractData<HealthHistoryRequest>(message)
            Log.d(
                    TAG,
                    "Health history request: ${request.type}, ${request.startTime}-${request.endTime}"
            )

            val response =
                    healthDataCollector.queryHistoricalData(
                            request.type,
                            request.startTime,
                            request.endTime
                    )
            sendMessage(ProtocolHelper.createResponseHealthHistory(response))
        } catch (e: Exception) {
            Log.e(TAG, "Error handling health history request: ${e.message}")
        }
    }

    private fun handleStartLiveMeasurement(message: ProtocolMessage) {
        try {
            val request = ProtocolHelper.extractData<LiveMeasurementRequest>(message)
            Log.d(TAG, "Starting live measurement: ${request.type}")
            startLiveMeasurementMode(request.type)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting live measurement: ${e.message}")
        }
    }

    private fun handleStopLiveMeasurement() {
        Log.d(TAG, "Stopping live measurement")
        liveMeasurementJob?.cancel()
        liveMeasurementJob = null
    }

    private suspend fun handleUpdateHealthSettings(message: ProtocolMessage) {
        try {
            val settings = ProtocolHelper.extractData<HealthSettingsUpdate>(message)
            Log.d(TAG, "Updating health settings")
            healthDataCollector.updateHealthSettings(settings)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating health settings: ${e.message}")
        }
    }

    private suspend fun handleRequestHealthSettings() {
        try {
            Log.d(TAG, "Reading current health settings")
            val settings = healthDataCollector.readHealthSettings()
            val response = ProtocolHelper.createResponseHealthSettings(settings)
            sendMessage(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading health settings: ${e.message}")
        }
    }

    private suspend fun handleStatusUpdateReceived(message: ProtocolMessage) {
        try {
            val status = ProtocolHelper.extractData<StatusUpdateData>(message)
            withContext(Dispatchers.Main) {
                callback?.onWatchStatusUpdated(
                        status.battery,
                        status.charging,
                        status.wifi,
                        status.wifiEnabled,
                        status.dnd
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Status parser error: ${e.message}")
        }
    }

    private suspend fun handleRequestWifiScan() {
        Log.d(TAG, "Requesting WiFi scan...")
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        // Trigger a fresh scan if possible
        try {
            wm.startScan()
            // Try privileged trigger as well
            userService?.startWifiScan()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start WiFi scan: ${e.message}")
        }

        // Wait for scan results to populate (typically 3-4 seconds)
        Log.d(TAG, "Waiting 4 seconds for hardware to find networks...")
        delay(4000)

        val hasPermission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.NEARBY_WIFI_DEVICES
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                }

        var results =
                if (hasPermission) {
                    wm.scanResults
                            .map {
                                WifiScanResultData(
                                        ssid = it.SSID,
                                        bssid = it.BSSID,
                                        signalLevel = WifiManager.calculateSignalLevel(it.level, 5),
                                        isSecure =
                                                it.capabilities.contains("WPA") ||
                                                        it.capabilities.contains("WEP") ||
                                                        it.capabilities.contains("SAE")
                                )
                            }
                            .filter { it.ssid.isNotEmpty() }
                            .distinctBy { it.ssid }
                            .sortedByDescending { it.signalLevel }
                } else {
                    Log.e(TAG, "No permission (Location/Nearby) for WiFi scan")
                    emptyList()
                }

        // Fallback to privileged service if results are empty
        if (results.isEmpty()) {
            Log.d(TAG, "Standard scan results empty, trying privileged fallback...")
            try {
                val privilegedJson = userService?.getWifiScanResults()
                if (privilegedJson != null) {
                    val lines =
                            Gson().fromJson<List<String>>(
                                            privilegedJson,
                                            object : TypeToken<List<String>>() {}.type
                                    )
                    Log.d(TAG, "Privileged scan returned ${lines.size} lines")
                    if (lines.size > 1) { // Header + at least one result
                        results = parseWifiScanResults(lines)
                    }
                } else {
                    Log.w(TAG, "UserService returned null for scan results")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fail in privileged scan fallback: ${e.message}")
            }
        }

        Log.d(TAG, "Sending ${results.size} WiFi scan results")
        sendMessage(ProtocolHelper.createWifiScanResults(results))
    }

    private fun parseWifiScanResults(lines: List<String>): List<WifiScanResultData> {
        val results = mutableListOf<WifiScanResultData>()
        if (lines.isEmpty()) return results

        // Header example: BSSID              Frequency  Signal  Flags             SSID
        // OR: BSSID              Frequency      RSSI           Age(sec)     SSID
        //              Flags
        val header = lines[0]
        Log.d(TAG, "Parsing WiFi header: $header")

        val ssidIndex = header.indexOf("SSID")
        var signalIndex = header.indexOf("Signal")
        if (signalIndex == -1) signalIndex = header.indexOf("RSSI")
        if (signalIndex == -1) signalIndex = header.indexOf("Level")

        val bssidIndex = header.indexOf("BSSID")
        val flagsIndex = header.indexOf("Flags")

        // If we can't find SSID or some signal indicator, the format is too unexpected
        if (ssidIndex == -1 || (signalIndex == -1 && flagsIndex == -1)) {
            Log.w(TAG, "Unexpected WiFi scan header format")
            return results
        }

        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue

            try {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 3) continue

                // Locate SSID and Flags based on headers if possible, otherwise heuristic
                var ssid = ""
                var flags = ""
                var level = -100
                var bssid = parts[0]

                if (ssidIndex != -1 && flagsIndex != -1) {
                    if (ssidIndex < flagsIndex) {
                        // SSID before Flags
                        ssid = line.substring(ssidIndex, flagsIndex).trim()
                        flags = line.substring(flagsIndex).trim()
                    } else {
                        // Flags before SSID (standard `cmd wifi list-scan-results` output often has
                        // SSID last)
                        flags = line.substring(flagsIndex, ssidIndex).trim()
                        ssid = line.substring(ssidIndex).trim()
                    }
                } else if (ssidIndex != -1) {
                    ssid = line.substring(ssidIndex).trim()
                }

                // Try to find RSSI/Level in parts
                // Usually it's the 3rd column (index 2)
                if (parts.size > 2) {
                    // Try to find a part that looks like a signal level (-30 to -100)
                    for (part in parts) {
                        val v = part.toIntOrNull()
                        if (v != null && v < 0 && v > -120) {
                            level = v
                            break
                        }
                    }
                }

                if (ssid.isEmpty() || ssid == "<unknown ssid>") {
                    // Fallback to parts if substring fails
                    ssid = parts.last()
                }

                if (ssid.isEmpty() || ssid == "<unknown ssid>") continue

                results.add(
                        WifiScanResultData(
                                ssid = ssid,
                                bssid = bssid,
                                signalLevel = WifiManager.calculateSignalLevel(level, 5),
                                isSecure =
                                        flags.contains("WPA") ||
                                                flags.contains("WEP") ||
                                                flags.contains("SAE") ||
                                                flags.contains("EAP")
                        )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing wifi line: $line - ${e.message}")
            }
        }
        val distinct = results.distinctBy { it.ssid }.sortedByDescending { it.signalLevel }
        Log.d(TAG, "Parsed ${distinct.size} distinct WiFi results")
        return distinct
    }

    private fun handleConnectWifi(message: ProtocolMessage) {
        val data = ProtocolHelper.extractData<WifiConnectData>(message)
        val ssid = data.ssid
        val password = data.password

        Log.d(TAG, "Connecting to WiFi: $ssid (password: ${!password.isNullOrEmpty()})")

        // For Android 10+: Use combination of Suggestion (for saving) + Specifier (for immediate
        // connection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWifiModern(ssid, password)
        }
        // For Android 9 and below: Use legacy WifiConfiguration API
        else {
            connectWifiLegacy(ssid, password)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWifiModern(ssid: String, password: String?) {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: Use incremental priority system

                // Get current timestamp as priority (ensures newest selection has highest priority)
                val currentPriority = (System.currentTimeMillis() / 1000).toInt()

                // Get all existing suggestions from our app
                val existingSuggestions = wm.networkSuggestions

                // Check if this network already exists
                val existingSuggestion = existingSuggestions.find { it.ssid == "\"$ssid\"" }

                // If network exists with old priority, remove it so we can re-add with new priority
                if (existingSuggestion != null) {
                    Log.d(TAG, "Removing old suggestion for $ssid to update priority")
                    wm.removeNetworkSuggestions(listOf(existingSuggestion))
                }

                // Build network suggestion with new highest priority
                val suggestionBuilder =
                        WifiNetworkSuggestion.Builder()
                                .setSsid(ssid)
                                .setPriority(
                                        currentPriority
                                ) // Newest selection gets current timestamp as priority
                                .setIsInitialAutojoinEnabled(true)

                if (!password.isNullOrEmpty()) {
                    suggestionBuilder.setWpa2Passphrase(password)
                }

                val suggestion = suggestionBuilder.build()
                val status = wm.addNetworkSuggestions(listOf(suggestion))

                when (status) {
                    WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> {
                        Log.i(
                                TAG,
                                "✓ Network suggestion added with priority $currentPriority: $ssid"
                        )
                        Log.i(TAG, "  Old networks kept with lower priorities for auto-connect")
                        wm.startScan()
                    }
                    WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> {
                        Log.i(TAG, "✓ Network suggestion exists: $ssid")
                        wm.startScan()
                    }
                    else -> {
                        Log.e(TAG, "✗ Failed to add suggestion: status=$status")
                    }
                }
            } else {
                // Android 10: No priority support, just add/update
                val suggestionBuilder = WifiNetworkSuggestion.Builder().setSsid(ssid)

                if (!password.isNullOrEmpty()) {
                    suggestionBuilder.setWpa2Passphrase(password)
                }

                val status = wm.addNetworkSuggestions(listOf(suggestionBuilder.build()))

                if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    Log.i(TAG, "✓ Network suggestion added: $ssid")
                    wm.startScan()
                }
            }

            // Send status update after delay
            serviceScope.launch {
                delay(3000)
                val status = collectWatchStatus()
                sendMessage(ProtocolHelper.createStatusUpdate(status))
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "✗ Security exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Exception: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun connectWifiLegacy(ssid: String, password: String?) {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) wm.isWifiEnabled = true

        @Suppress("DEPRECATION")
        val wifiConfig =
                android.net.wifi.WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (password != null) {
                        preSharedKey = "\"$password\""
                    } else {
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                    }
                }

        @Suppress("DEPRECATION") val netId = wm.addNetwork(wifiConfig)
        if (netId != -1) {
            wm.disconnect()
            wm.enableNetwork(netId, true)
            wm.reconnect()
            Log.d(TAG, "Legacy connection started for $ssid")
        }
    }

    fun sendDndCommand(enable: Boolean) {
        sendMessage(ProtocolHelper.createSetDnd(enable))
    }

    fun sendMakeCall(phoneNumber: String) {
        sendMessage(ProtocolHelper.createMakeCall(phoneNumber))
    }

    fun sendRejectCall() {
        sendMessage(ProtocolHelper.createRejectCall())
    }

    fun sendAnswerCall() {
        sendMessage(ProtocolHelper.createAnswerCall())
    }

    fun sendMediaCommand(command: String, volume: Int? = null) {
        sendMessage(ProtocolHelper.createMediaControl(command, volume))
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

    private fun handleSetWifiCommand(message: ProtocolMessage) {
        val enable = ProtocolHelper.extractBooleanField(message, "enabled")
        Log.i(TAG, "Handling SET_WIFI: $enable")

        // Try UserService (Shizuku) first as it's more reliable on Android 10+
        if (userService != null) {
            try {
                userService?.setWifiEnabled(enable)
            } catch (e: Exception) {
                Log.e(TAG, "Error calling userService.setWifiEnabled: ${e.message}")
            }
        } else {
            // Standard API fallback
            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION") wm.isWifiEnabled = enable
            } catch (e: Exception) {
                Log.e(TAG, "Error setting WiFi via WifiManager: ${e.message}")
            }
        }

        // Update status and notify phone
        serviceScope.launch {
            delay(1000) // Give it a second to change state
            val status = collectWatchStatus()
            sendMessage(ProtocolHelper.createStatusUpdate(status))
        }
    }

    private fun handleCameraFrame(message: ProtocolMessage) {
        try {
            val data = ProtocolHelper.extractData<CameraFrameData>(message)
            val intent = Intent(CameraRemoteActivity.ACTION_FRAME_RECEIVED)
            intent.putExtra(CameraRemoteActivity.EXTRA_FRAME_DATA, data.imageBase64)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error handling camera frame: ${e.message}")
        }
    }

    private fun handleWifiKeyExchange(message: ProtocolMessage) {
        val phoneMac = ProtocolHelper.extractStringField(message, "deviceMac") ?: getConnectedDeviceMac() ?: return
        val watchMac = ProtocolHelper.extractStringField(message, "targetMac") ?: ""
        val encryptionKey = ProtocolHelper.extractStringField(message, "encryptionKey") ?: return
        
        Log.i(TAG, "Received WiFi key exchange request from $phoneMac for watch $watchMac")
        // CRITICAL: Import key using watchMac (companion's own MAC), not phoneMac
        // because all WiFi encryption/decryption uses the companion's MAC as the key identifier
        val success = encryptionManager.importKey(watchMac, encryptionKey)
        if (success) {
            encryptionManager.setActiveDevice(watchMac)
            
            // Save MACs for future restarts
            prefs.edit().apply {
                putString("wifi_advertised_mac", watchMac)
                putString("paired_phone_mac", phoneMac)
            }.apply()
            
            // Enable pairing mode to keep WiFi server running during pairing
            transportManager.enableWifiPairingMode()
            
            // Start WiFi server
            serviceScope.launch {
                val deviceName = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name ?: android.os.Build.MODEL
                transportManager.startWifiServer(deviceName, watchMac, watchMac)
            }
        }
        
        sendMessage(ProtocolHelper.createWifiKeyAck(phoneMac, success))
    }

    private fun handleWifiTestEncrypt(message: ProtocolMessage) {
        val testContent = ProtocolHelper.extractStringField(message, "message")
        Log.i(TAG, "Received WiFi encryption test message: $testContent")
        
        // Respond with ACK over the same transport (WiFi if it's currently active)
        sendMessage(ProtocolHelper.createWifiTestAck(true))
        
        // Also initiate a test from our side to verify bidirectional
        serviceScope.launch {
            delay(500)
            sendMessage(ProtocolHelper.createWifiTestEncrypt("test_from_companion"))
        }
    }

    // File transfer state (receiving)
    private var receivingFile: File? = null
    private var receivingFileStream: java.io.RandomAccessFile? = null
    private var expectedFileSize: Long = 0
    private var receivedFileSize: Long = 0
    private var expectedChecksum: String = ""
    private var receivingFileType: String = "REGULAR"
    private val receivedChunks = mutableListOf<ByteArray>()

    private var finalDestinationPath: String? = null
    private var waitingForFileAck = false
    private var fileAckDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    private suspend fun handleFileTransferStart(message: ProtocolMessage) {
        try {
            val fileData = ProtocolHelper.extractData<FileTransferData>(message)
            android.util.Log.d(
                    TAG,
                    "File transfer starting: ${fileData.name}, ${fileData.size} bytes, path: ${fileData.path}"
            )

            val root = android.os.Environment.getExternalStorageDirectory()
            var targetFile: File
            finalDestinationPath = null

            if (fileData.path != null) {
                val absolutePath = toAbsolutePath(fileData.path)
                val isRestricted = absolutePath.contains("Android/data", ignoreCase = true)

                if (isRestricted) {
                    android.util.Log.d(
                            TAG,
                            "Target path is restricted, using temp file in externalCacheDir"
                    )
                    // Use externalCacheDir so Shizuku/Shell has read access for move later
                    val cacheDirToUse = externalCacheDir ?: cacheDir
                    targetFile = File(cacheDirToUse, fileData.name)
                    finalDestinationPath = absolutePath
                } else {
                    targetFile = File(absolutePath)
                    if (targetFile.isDirectory) {
                        targetFile = File(targetFile, fileData.name)
                    }
                }
            } else if (fileData.type == "SHARE" || fileData.type == "REGULAR") {
                val downloadsDir =
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS
                        )
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                targetFile = File(downloadsDir, fileData.name)
            } else {
                val cacheDirToUse = externalCacheDir ?: cacheDir
                targetFile = File(cacheDirToUse, fileData.name)
            }

            android.util.Log.d(TAG, "Receiving file to: ${targetFile.absolutePath}")

            // Ensure parent directory exists (if possible)
            targetFile.parentFile?.mkdirs()

            receivingFile = targetFile
            receivingFileStream = java.io.RandomAccessFile(targetFile, "rw")
            receivingFileStream?.setLength(fileData.size)
            expectedFileSize = fileData.size
            receivedFileSize = 0
            expectedChecksum = fileData.checksum
            receivingFileType = fileData.type
            receivedChunks.clear()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error starting file transfer: ${e.message}")
        }
    }

    private suspend fun handleFileChunk(message: ProtocolMessage) {
        try {
            val chunkData = ProtocolHelper.extractData<FileChunkData>(message)

            // Decode chunk
            val chunkBytes = android.util.Base64.decode(chunkData.data, android.util.Base64.DEFAULT)

            // Write to file at the correct offset (handles out-of-order chunks)
            receivingFileStream?.let { raf ->
                raf.seek(chunkData.offset)
                raf.write(chunkBytes)
            }
            receivedFileSize += chunkBytes.size

            android.util.Log.d(
                    TAG,
                    "Received chunk ${chunkData.chunkNumber}/${chunkData.totalChunks} at offset ${chunkData.offset}"
            )
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
                    return
                }
            }

            // Send success acknowledgment to phone
            sendMessage(ProtocolHelper.createFileTransferEnd(success = true))
            android.util.Log.d(TAG, "Sent file transfer acknowledgment")

            android.util.Log.d(TAG, "File received successfully at: ${file.absolutePath}")

            // Handle restricted destination
            val finalPath = finalDestinationPath
            if (finalPath != null) {
                android.util.Log.d(TAG, "Moving file to restricted destination: $finalPath")
                val successMove = moveFileDispatcher(file.absolutePath, finalPath)
                if (!successMove) {
                    android.util.Log.e(TAG, "Failed to move file to restricted destination")
                    sendMessage(
                            ProtocolHelper.createFileTransferEnd(
                                    success = false,
                                    error = "Failed to move file to restricted destination"
                            )
                    )
                    cleanupFileTransfer()
                    return
                }
                // Update file reference for further processing (like APK install)
                // Note: File install might fail if it's in a restricted area even with Shizuku
                // move,
                // but we can try.
            }

            // Show install dialog ONLY if type is "APK" (sent from app install section, not file
            // manager)
            if (receivingFileType == "APK") {
                val installFile = if (finalPath != null) File(finalPath) else file
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        showInstallApkDialog(installFile)
                        android.util.Log.d(TAG, "showInstallApkDialog completed")
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error showing install dialog: ${e.message}", e)
                    }
                }
            } else if (receivingFileType == FileTransferData.TYPE_WATCHFACE) {
                val wfFile = if (finalPath != null) File(finalPath) else file
                handleWatchFaceReceived(wfFile)
            } else if (receivingFileType == "SHARE") {
                val shareFile = if (finalPath != null) File(finalPath) else file
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val authority = "${packageName}.fileprovider"
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@BluetoothService, authority, shareFile)
                    showShareChooser("Received File: ${shareFile.name}", null, contentResolver.getType(uri) ?: "*/*", uri)
                    showFileReceivedNotification(shareFile)
                    Toast.makeText(
                                    this@BluetoothService,
                                    "File Received: ${shareFile.name}",
                                    Toast.LENGTH_LONG
                            )
                            .show()
                }
            } else {
                android.util.Log.d(
                        TAG,
                        "Regular file received (type=$receivingFileType), no install dialog needed."
                )
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
        finalDestinationPath = null
    }

    private fun getRelPath(absolutePath: String): String {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        return absolutePath.removePrefix(root).removePrefix("/")
    }

    private fun translatePathForRoot(path: String): String {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        // Prefer /sdcard for root operations as it proved more reliable for 'ls' on some devices
        return when {
            path.startsWith(root) -> path.replaceFirst(root, "/sdcard")
            path.startsWith("/storage/emulated/0") ->
                    path.replaceFirst("/storage/emulated/0", "/sdcard")
            path.startsWith("/data/media/0") -> path.replaceFirst("/data/media/0", "/sdcard")
            else -> path
        }
    }

    private fun toAbsolutePath(path: String): String {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        return when {
            path == "/" || path.isEmpty() -> root
            path.startsWith("/") -> {
                if (path.startsWith("/storage/") ||
                                path.startsWith("/sdcard/") ||
                                path.startsWith("/data/")
                ) {
                    path
                } else {
                    File(root, path.substring(1)).absolutePath
                }
            }
            else -> File(root, path).absolutePath
        }
    }

    private suspend fun handleRequestFileList(message: ProtocolMessage) {
        val path = ProtocolHelper.extractStringField(message, "path") ?: "/"
        val absolutePath = toAbsolutePath(path)

        // 1. Try Dispatcher (Shizuku -> Root -> Standard)
        var files: MutableList<FileInfo>? = listFilesDispatcher(absolutePath)?.toMutableList()

        // 2. Fallback to SAF for the specific watch face path if dispatcher fails or returns null
        if (files == null) {
            val watchFaceRelPath = "Android/data/com.ailife.ClockSkinCoco"
            val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
            if (absolutePath.startsWith(storageRoot)) {
                val relPath = absolutePath.removePrefix(storageRoot).removePrefix("/")
                if (relPath.startsWith(watchFaceRelPath)) {
                    val safFiles = listUsingSaf(relPath)
                    if (safFiles != null) {
                        sendMessage(ProtocolHelper.createResponseFileList(path, safFiles))
                        return
                    }
                }
            }
        }

        // 3. Last Resort Fallback (Standard API)
        if (files == null) {
            val targetDir = File(absolutePath)
            if (targetDir.exists() && targetDir.isDirectory) {
                files =
                        targetDir
                                .listFiles()
                                ?.map {
                                    FileInfo(
                                            it.name,
                                            it.length(),
                                            it.isDirectory,
                                            it.lastModified()
                                    )
                                }
                                ?.toMutableList()
            }
        }

        val resultFiles = files ?: mutableListOf()

        // 4. Inject "com.ailife.ClockSkinCoco" if we are in Android/data and it's missing (hidden
        // by Android 11+)
        val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (absolutePath == File(storageRoot, "Android/data").absolutePath) {
            if (resultFiles.none { it.name == "com.ailife.ClockSkinCoco" }) {
                resultFiles.add(
                        FileInfo("com.ailife.ClockSkinCoco", 0, true, System.currentTimeMillis())
                )
            }
        }

        sendMessage(ProtocolHelper.createResponseFileList(path, resultFiles))
    }

    private fun listUsingSaf(path: String): List<FileInfo>? {
        return try {
            val doc = getDocumentFileForPath(path, false)
            doc?.listFiles()?.map {
                FileInfo(
                        name = it.name ?: "unknown",
                        size = it.length(),
                        isDirectory = it.isDirectory,
                        lastModified = it.lastModified()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files using SAF for $path: ${e.message}")
            null
        }
    }

    private suspend fun handleRequestFileDownload(message: ProtocolMessage) {
        val path = ProtocolHelper.extractStringField(message, "path") ?: return
        val absPath = toAbsolutePath(path)
        android.util.Log.d(TAG, "Download requested: $path -> $absPath")

        var file = File(absPath)
        var isRestricted = false

        if (!file.exists() || !file.canRead()) {
            android.util.Log.d(TAG, "File restricted or not found, trying copy via Shizuku/Root...")
            // Use externalCacheDir so Shizuku/Shell has write access
            val cacheDirToUse = externalCacheDir ?: cacheDir
            val tempFile = File(cacheDirToUse, "download_temp_${System.currentTimeMillis()}")

            // Try Shizuku copy
            var copied = false
            if (isUsingShizuku()) {
                ensureUserServiceBound()
                copied = userService?.copyFile(absPath, tempFile.absolutePath) ?: false
            }

            // Try Root copy
            if (!copied && Shell.getShell().isRoot) {
                val rootSrc = translatePathForRoot(absPath)
                val result = Shell.cmd("cp \"$rootSrc\" \"${tempFile.absolutePath}\"").exec()
                if (result.isSuccess) {
                    Shell.cmd("chmod 666 \"${tempFile.absolutePath}\"").exec()
                    copied = true
                }
            }

            // Try SAF fallback
            if (!copied) {
                val relPath = getRelPath(absPath)
                if (relPath.startsWith("Android/data/com.ailife.ClockSkinCoco")) {
                    val doc = getDocumentFileForPath(relPath, false)
                    if (doc != null && doc.exists() && doc.isFile) {
                        try {
                            contentResolver.openInputStream(doc.uri)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (tempFile.exists()) {
                                copied = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "SAF copy failed: ${e.message}")
                        }
                    }
                }
            }

            if (copied && tempFile.exists()) {
                file = tempFile
                isRestricted = true
            }
        }

        if (file.exists() && file.isFile) {
            // Send file name as original, not temp name
            val originalName = absPath.substringAfterLast("/")
            sendFile(file, "REGULAR", null, originalName)
            if (isRestricted) {
                // We keep it in cacheDir for now, sendFile is async and we don't want to delete it
                // too early
                // cleanupFileTransfer will handle its own temp files, but this is a manual download
                // temp.
            }
        } else {
            sendMessage(
                    ProtocolHelper.createFileTransferEnd(
                            success = false,
                            error = "File not found or inaccessible"
                    )
            )
        }
    }

    private suspend fun handleDeleteFile(message: ProtocolMessage) {
        val path = ProtocolHelper.extractStringField(message, "path") ?: return
        val absolutePath = toAbsolutePath(path)

        android.util.Log.d(TAG, "Delete requested: $path -> $absolutePath")

        val success = deleteFileDispatcher(absolutePath)

        if (success) {
            val parentPath =
                    if (path.lastIndexOf('/') > 0) path.substring(0, path.lastIndexOf('/')) else "/"
            handleRequestFileList(ProtocolHelper.createRequestFileList(parentPath))
        }
    }

    private suspend fun handleRenameFile(message: ProtocolMessage) {
        val oldPath = message.data.get("oldPath")?.asString ?: return
        val newPath = message.data.get("newPath")?.asString ?: return

        val absOld = toAbsolutePath(oldPath)
        val absNew = toAbsolutePath(newPath)

        android.util.Log.d(TAG, "Rename requested: $absOld -> $absNew")

        val success = renameFileDispatcher(absOld, absNew)

        if (success) {
            val parentPath =
                    if (newPath.lastIndexOf('/') > 0) newPath.substring(0, newPath.lastIndexOf('/'))
                    else "/"
            handleRequestFileList(ProtocolHelper.createRequestFileList(parentPath))
        }
    }

    private suspend fun handleMoveFile(message: ProtocolMessage) {
        val srcPath = message.data.get("srcPath")?.asString ?: return
        val dstPath = message.data.get("dstPath")?.asString ?: return

        val absSrc = toAbsolutePath(srcPath)
        val absDst = toAbsolutePath(dstPath)

        android.util.Log.d(TAG, "Move requested: $absSrc -> $absDst")

        val success = moveFileDispatcher(absSrc, absDst)

        if (success) {
            val parentPath =
                    if (dstPath.lastIndexOf('/') > 0) dstPath.substring(0, dstPath.lastIndexOf('/'))
                    else "/"
            handleRequestFileList(ProtocolHelper.createRequestFileList(parentPath))
        }
    }

    private suspend fun handleCreateFolder(message: ProtocolMessage) {
        val path = message.data.get("path")?.asString ?: return
        val absPath = toAbsolutePath(path)

        android.util.Log.d(TAG, "Create folder requested: $absPath")

        val success = createFolderDispatcher(absPath)

        if (success) {
            val parentPath =
                    if (path.lastIndexOf('/') > 0) path.substring(0, path.lastIndexOf('/')) else "/"
            handleRequestFileList(ProtocolHelper.createRequestFileList(parentPath))
        }
    }

    private suspend fun handleSetWatchFace(message: ProtocolMessage) {
        val facePath = message.data.get("facePath")?.asString ?: return
        android.util.Log.d(TAG, "Set watch face requested: $facePath")

        // Send broadcast to change watch face
        val intent = Intent("com.android.watchengine.changeface")
        intent.putExtra("faceName", facePath)
        sendBroadcast(intent)

        withContext(Dispatchers.Main) {
            Toast.makeText(this@BluetoothService, "Watch face set: $facePath", Toast.LENGTH_SHORT)
                    .show()
        }
    }

    // ========================================================================
    // UNIFIED FILE OPERATION DISPATCHERS (Shizuku -> Root -> Standard)
    // ========================================================================

    private suspend fun listFilesDispatcher(path: String): List<FileInfo>? {
        // 1. Try Shizuku (UserService)
        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                val json = userService?.listFiles(path)
                if (json != null && json != "[]") {
                    Log.d(TAG, "Shizuku listFiles success for $path")
                    return Gson().fromJson(
                                    json,
                                    object : com.google.gson.reflect.TypeToken<List<FileInfo>>() {}
                                            .type
                            )
                }
                Log.d(TAG, "Shizuku listFiles returned empty or null for $path")
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku listFiles failed: ${e.message}")
            }
        }

        // 2. Try Root (libsu) fallback for listing
        if (Shell.getShell().isRoot) {
            val rootPath = translatePathForRoot(path)
            try {
                // Use -L to follow symlinks and append / to path to ensure directory listing
                val cmdPath = if (rootPath.endsWith("/")) rootPath else "$rootPath/"
                Log.d(TAG, "Root attempting ls -F1L \"$cmdPath\"")
                val result = Shell.cmd("ls -F1L \"$cmdPath\"").exec()
                if (result.isSuccess && result.out.isNotEmpty()) {
                    return result.out.map { line ->
                        // ls -F markers: / folder, @ link, * executable, | FIFO, = socket, > door
                        val isDir = line.endsWith("/")
                        val cleanName = line.trimEnd('/', '@', '*', '|', '=', '>')
                        FileInfo(cleanName, 0, isDir, System.currentTimeMillis())
                    }
                } else if (!result.isSuccess) {
                    Log.e(
                            TAG,
                            "Root ls failed for $rootPath: code=${result.code}, err=${result.err}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Root listFiles failed: ${e.message}")
            }
        }

        // 3. Standard Fallback
        val dir = File(path)
        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles()
            if (files != null) {
                return files.map {
                    FileInfo(it.name, it.length(), it.isDirectory, it.lastModified())
                }
            }
        }

        return null
    }

    private suspend fun deleteFileDispatcher(path: String): Boolean {
        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                if (userService?.deleteFile(path) == true) return true
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku deleteFile failed: ${e.message}")
            }
        }

        if (Shell.getShell().isRoot) {
            val rootPath = translatePathForRoot(path)
            val result = Shell.cmd("rm -rf \"$rootPath\"").exec()
            if (result.isSuccess) return true
        }

        // 3. Fallback to SAF
        val watchFacePath = "Android/data/com.ailife.ClockSkinCoco"
        val relPath = getRelPath(path)
        if (relPath.startsWith(watchFacePath)) {
            val doc = getDocumentFileForPath(relPath, false)
            if (doc != null && doc.exists()) return doc.delete()
        }

        // 4. Standard Fallback
        return File(path).deleteRecursively()
    }

    private suspend fun renameFileDispatcher(oldPath: String, newPath: String): Boolean {
        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                if (userService?.renameFile(oldPath, newPath) == true) return true
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku renameFile failed: ${e.message}")
            }
        }

        if (Shell.getShell().isRoot) {
            val rootOld = translatePathForRoot(oldPath)
            val rootNew = translatePathForRoot(newPath)
            val result = Shell.cmd("mv \"$rootOld\" \"$rootNew\"").exec()
            if (result.isSuccess) return true
        }

        // 3. Fallback to SAF
        val watchFacePath = "Android/data/com.ailife.ClockSkinCoco"
        val relPath = getRelPath(oldPath)
        if (relPath.startsWith(watchFacePath)) {
            val doc = getDocumentFileForPath(relPath, false)
            if (doc != null && doc.exists()) {
                val newName = newPath.substringAfterLast("/")
                return doc.renameTo(newName)
            }
        }

        return File(oldPath).renameTo(File(newPath))
    }

    private suspend fun moveFileDispatcher(src: String, dst: String): Boolean {
        Log.d(TAG, "moveFileDispatcher: Moving $src -> $dst")
        
        // Ensure destination parent directory exists
        val dstParent = dst.substringBeforeLast("/", "/")
        if (dstParent != "/") {
            Log.d(TAG, "moveFileDispatcher: Creating parent directory: $dstParent")
            val dirCreated = createFolderDispatcher(dstParent)
            Log.d(TAG, "moveFileDispatcher: Parent directory creation result: $dirCreated")
        }

        // Check source file exists
        val srcFile = File(src)
        if (!srcFile.exists()) {
            Log.e(TAG, "moveFileDispatcher: Source file does not exist: $src")
            return false
        }
        Log.d(TAG, "moveFileDispatcher: Source file exists, size=${srcFile.length()} bytes")

        if (isUsingShizuku()) {
            try {
                Log.d(TAG, "moveFileDispatcher: Trying Shizuku...")
                ensureUserServiceBound()
                if (userService?.moveFile(src, dst) == true) {
                    Log.d(TAG, "moveFileDispatcher: ✅ Shizuku moveFile succeeded")
                    return true
                } else {
                    Log.w(TAG, "moveFileDispatcher: Shizuku moveFile returned false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "moveFileDispatcher: Shizuku moveFile failed: ${e.message}")
            }
        } else {
            Log.d(TAG, "moveFileDispatcher: Shizuku not available")
        }

        if (Shell.getShell().isRoot) {
            Log.d(TAG, "moveFileDispatcher: Trying Root shell...")
            val rootSrc = translatePathForRoot(src)
            val rootDst = translatePathForRoot(dst)
            val result = Shell.cmd("mv \"$rootSrc\" \"$rootDst\"").exec()
            if (result.isSuccess) {
                Log.d(TAG, "moveFileDispatcher: ✅ Root shell move succeeded")
                return true
            } else {
                Log.w(TAG, "moveFileDispatcher: Root shell move failed: ${result.err.joinToString()}")
            }
        } else {
            Log.d(TAG, "moveFileDispatcher: Root shell not available")
        }

        // 3. Standard Fallback - this should work on Android <10 with WRITE_EXTERNAL_STORAGE
        Log.d(TAG, "moveFileDispatcher: Trying standard File.renameTo()...")
        val dstFile = File(dst)
        
        // Check destination parent is writable
        val dstParentFile = dstFile.parentFile
        if (dstParentFile != null) {
            Log.d(TAG, "moveFileDispatcher: Destination parent exists=${dstParentFile.exists()}, canWrite=${dstParentFile.canWrite()}")
        }
        
        if (srcFile.renameTo(dstFile)) {
            Log.d(TAG, "moveFileDispatcher: ✅ Standard renameTo() succeeded")
            return true
        } else {
            Log.w(TAG, "moveFileDispatcher: Standard renameTo() failed")
        }

        Log.d(TAG, "moveFileDispatcher: Trying copyRecursively as last resort...")
        return try {
            srcFile.copyRecursively(dstFile, true)
            Log.d(TAG, "moveFileDispatcher: Copy completed, deleting source...")
            val deleteResult = srcFile.deleteRecursively()
            if (!deleteResult) {
                Log.w(TAG, "moveFileDispatcher: Source deletion failed but copy succeeded")
            }
            Log.d(TAG, "moveFileDispatcher: ✅ copyRecursively succeeded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "moveFileDispatcher: ❌ copyRecursively failed: ${e.message}", e)
            false
        }
    }

    private suspend fun createFolderDispatcher(path: String): Boolean {
        Log.d(TAG, "createFolderDispatcher: Creating directory: $path")
        
        // Check if directory already exists
        val dir = File(path)
        if (dir.exists()) {
            if (dir.isDirectory) {
                Log.d(TAG, "createFolderDispatcher: Directory already exists")
                return true
            } else {
                Log.e(TAG, "createFolderDispatcher: Path exists but is not a directory!")
                return false
            }
        }
        
        // 1. Try Shizuku
        if (isUsingShizuku()) {
            try {
                Log.d(TAG, "createFolderDispatcher: Trying Shizuku...")
                ensureUserServiceBound()
                if (userService?.makeDirectory(path) == true) {
                    Log.d(TAG, "createFolderDispatcher: ✅ Shizuku succeeded")
                    return true
                } else {
                    Log.w(TAG, "createFolderDispatcher: Shizuku returned false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "createFolderDispatcher: Shizuku failed: ${e.message}")
            }
        } else {
            Log.d(TAG, "createFolderDispatcher: Shizuku not available")
        }

        // 2. Try Root
        if (Shell.getShell().isRoot) {
            Log.d(TAG, "createFolderDispatcher: Trying Root shell...")
            val rootPath = translatePathForRoot(path)
            val result = Shell.cmd("mkdir -p \"$rootPath\"").exec()
            if (result.isSuccess) {
                Log.d(TAG, "createFolderDispatcher: ✅ Root shell succeeded")
                return true
            } else {
                Log.w(TAG, "createFolderDispatcher: Root shell failed: ${result.err.joinToString()}")
            }
        } else {
            Log.d(TAG, "createFolderDispatcher: Root shell not available")
        }

        // 3. Fallback to SAF
        val watchFacePath = "Android/data/com.ailife.ClockSkinCoco"
        val relPath = getRelPath(path)
        if (relPath.startsWith(watchFacePath)) {
            Log.d(TAG, "createFolderDispatcher: Trying SAF for watchface path...")
            val doc = getDocumentFileForPath(relPath, true)
            val success = doc != null && doc.isDirectory
            if (success) {
                Log.d(TAG, "createFolderDispatcher: ✅ SAF succeeded")
            } else {
                Log.w(TAG, "createFolderDispatcher: SAF failed (doc=$doc)")
            }
            return success
        }

        // 4. Standard Fallback - this should work on Android <10 with WRITE_EXTERNAL_STORAGE
        Log.d(TAG, "createFolderDispatcher: Trying standard File.mkdirs()...")
        val result = dir.mkdirs()
        if (result) {
            Log.d(TAG, "createFolderDispatcher: ✅ Standard mkdirs() succeeded")
        } else {
            Log.e(TAG, "createFolderDispatcher: ❌ Standard mkdirs() failed")
        }
        return result
    }

    // Helper to find or create DocumentFile given a relative path from external storage root
    // Only works if path is within a granted permission tree (e.g.
    // Android/data/com.ailife.ClockSkinCoco)
    private fun getDocumentFileForPath(
            path: String,
            createIfNotExists: Boolean
    ): androidx.documentfile.provider.DocumentFile? {
        try {
            val targetFolder = "Android/data/com.ailife.ClockSkinCoco"

            // Case insensitive check if we are targeting this folder
            if (!path.startsWith(targetFolder, ignoreCase = true)) return null

            // Find permission
            val perm =
                    contentResolver.persistedUriPermissions.firstOrNull {
                        val decodedUri = android.net.Uri.decode(it.uri.toString())
                        decodedUri.contains("com.ailife.ClockSkinCoco", ignoreCase = true) &&
                                it.isWritePermission
                    }

            if (perm != null) {
                var docFile =
                        androidx.documentfile.provider.DocumentFile.fromTreeUri(this, perm.uri)
                                ?: return null

                // Determine relative path from the treeUri root to our target path
                val decodedTreeUri = android.net.Uri.decode(perm.uri.toString())
                val treePathPart = decodedTreeUri.substringAfterLast(":", "").removePrefix("/")

                val normalizedTarget = path.removePrefix("/").removeSuffix("/")

                if (normalizedTarget.startsWith(treePathPart, ignoreCase = true)) {
                    val subPath = normalizedTarget.substring(treePathPart.length).removePrefix("/")
                    if (subPath.isNotEmpty()) {
                        val parts = subPath.split("/")
                        for (part in parts) {
                            var nextFile =
                                    docFile.listFiles().find {
                                        it.name?.equals(part, ignoreCase = true) == true
                                    }

                            if (nextFile == null && createIfNotExists) {
                                try {
                                    nextFile = docFile.createDirectory(part)
                                    Log.i(TAG, "SAF: Created directory '$part'")
                                } catch (e: Exception) {
                                    Log.e(TAG, "SAF: Create dir failed: ${e.message}")
                                }
                            }

                            if (nextFile != null) {
                                docFile = nextFile
                            } else {
                                Log.w(TAG, "SAF: Could not find/create subpath '$part' in $path")
                                return null
                            }
                        }
                    }
                }
                return docFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "SAF Error for $path: ${e.message}")
        }
        return null
    }

    private fun handleWatchFaceReceived(file: File) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Define target dir
                val root = android.os.Environment.getExternalStorageDirectory().absolutePath
                val clockSkinPath = "$root/Android/data/com.ailife.ClockSkinCoco/files/ClockSkin"

                val targetFile = File(clockSkinPath, file.name)

                // If the file is NOT already in the target directory, move it
                if (file.absolutePath != targetFile.absolutePath) {
                    android.util.Log.d(
                            TAG,
                            "Moving watch face to restricted dir: ${targetFile.absolutePath}"
                    )
                    val success = moveFileDispatcher(file.absolutePath, targetFile.absolutePath)
                    if (!success) {
                        android.util.Log.e(TAG, "Failed to move watch face to restricted dir")
                        // Clean up the cache file if move failed
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete cache file after failed move: ${e.message}")
                        }
                        return@launch
                    }
                } else {
                    android.util.Log.d(
                            TAG,
                            "Watch face already in target directory: ${file.absolutePath}"
                    )
                }

                // Broadcast change face
                val intent = Intent("com.android.watchengine.changeface")
                // handleSetWatchFace uses "faceName" with relative path, so let's try to match that
                intent.putExtra("faceName", file.name)
                // Keep "path" with absolute path just in case some engines use it
                intent.putExtra("path", targetFile.absolutePath)
                sendBroadcast(intent)
                android.util.Log.d(
                        TAG,
                        "Broadcasted changeface for: ${file.name} / ${targetFile.absolutePath}"
                )

                android.util.Log.d(
                        TAG,
                        "Watch face applied successfully: ${targetFile.absolutePath}"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling watch face: ${e.message}")
                // Only delete cache file if it exists and is NOT in the target directory
                try {
                    if (file.exists() && !file.absolutePath.contains("ClockSkinCoco")) {
                        file.delete()
                    }
                } catch (deleteEx: Exception) {
                    Log.w(TAG, "Failed to delete cache file in error handler: ${deleteEx.message}")
                }
            }
        }
    }

    private fun isUsingShizuku(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
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

    private suspend fun showInstallApkDialog(tempFile: File) {
        android.util.Log.d(TAG, "showInstallApkDialog called for: ${tempFile.absolutePath}")

        // 1. Try Shizuku (UserService)
        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                val pfd =
                        android.os.ParcelFileDescriptor.open(
                                tempFile,
                                android.os.ParcelFileDescriptor.MODE_READ_ONLY
                        )
                if (userService?.installAppFromPfd(pfd) == true) {
                    Log.i(TAG, "✅ Silent install via Shizuku Succeeded: ${tempFile.name}")
                    mainHandler.post {
                        Toast.makeText(
                                        this,
                                        getString(R.string.upload_complete_title) +
                                                ": ${tempFile.name}",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                    tempFile.delete()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku silent install failed: ${e.message}")
            }
        }

        // 2. Try Root (libsu)
        if (Shell.getShell().isRoot) {
            val rootPath = translatePathForRoot(tempFile.absolutePath)
            val result = Shell.cmd("pm install -r \"$rootPath\"").exec()
            if (result.isSuccess) {
                Log.i(TAG, "✅ Silent install via Root succeeded: ${tempFile.name}")
                mainHandler.post {
                    Toast.makeText(
                                    this,
                                    getString(R.string.upload_complete_title) +
                                            ": ${tempFile.name}",
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
                tempFile.delete()
                return
            }
        }

        // Wake screen for manual fallback
        wakeScreen("ApkInstall")

        // Directly launch install intent instead of notification
        try {
            val authority = "${packageName}.fileprovider"
            android.util.Log.d(TAG, "Using FileProvider authority: $authority")
            val installUri = FileProvider.getUriForFile(this, authority, tempFile)
            android.util.Log.d(TAG, "Install URI: $installUri")
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(installUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
            android.util.Log.d(TAG, "Directed to system install dialog")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error launching install intent: ${e.message}", e)
            mainHandler.post {
                Toast.makeText(this, "Failed to start install: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun executeShellCommand(command: String) {
        serviceScope.launch(Dispatchers.IO) {
            Log.d(TAG, "⚡ Attempting to execute: $command")

            // Try Shizuku first (if available)
            try {
                if (Shizuku.pingBinder() &&
                                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "Using Shizuku for execution")
                    executeWithShizuku(command)
                    return@launch
                } else {
                    Log.d(TAG, "Shizuku not available, falling back to libsu")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku execution failed: ${e.message}, trying libsu")
            }

            // Fallback to libsu (Root)
            try {
                val result = Shell.cmd(command).exec()
                Log.i(
                        TAG,
                        "✅ Shell command executed via libsu. Success: ${result.isSuccess}, Code: ${result.code}"
                )
                result.out.forEach { Log.d(TAG, "OUT: $it") }
                result.err.forEach { Log.e(TAG, "ERR: $it") }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Root execution failed: ${e.message}")
            }
        }
    }

    private fun executeWithShizuku(command: String) {
        try {
            // Parse command to determine action
            val powerctlValue =
                    when {
                        command.contains("shutdown") -> "shutdown"
                        command.contains("reboot") -> "reboot"
                        else -> {
                            Log.e(TAG, "Unknown command: $command")
                            return
                        }
                    }

            // Use Shizuku to set system property via shell
            // We need to create a process that runs as shell/root via Shizuku
            val processBuilder = ProcessBuilder("sh")
            val process = processBuilder.start()

            // Write command to stdin
            process.outputStream.bufferedWriter().use { writer ->
                writer.write("setprop sys.powerctl $powerctlValue\n")
                writer.write("exit\n")
                writer.flush()
            }

            val exitCode = process.waitFor()
            Log.i(TAG, "✅ Shizuku command executed. Exit code: $exitCode")

            // Read any output
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { Log.d(TAG, "OUT: $it") }
            }
            process.errorStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { Log.e(TAG, "ERR: $it") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku execution error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleShutdownCommand() {
        Log.i(TAG, "🔴 Shutdown command received")
        mainHandler.post {
            Toast.makeText(this, "Shutdown command received", Toast.LENGTH_SHORT).show()
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                ensureUserServiceBound()
                val service = userService
                if (service != null) {
                    Log.i(TAG, "🚀 Calling UserService.shutdown()")
                    service.shutdown()
                } else {
                    Log.w(TAG, "UserService not available, falling back to shell command")
                    executeShellCommand("svc power shutdown")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calling shutdown: ${e.message}")
            }
        }
    }

    private fun handleRebootCommand() {
        Log.i(TAG, "🔄 Reboot command received")
        mainHandler.post {
            Toast.makeText(this, "Reboot command received", Toast.LENGTH_SHORT).show()
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                ensureUserServiceBound()
                val service = userService
                if (service != null) {
                    Log.i(TAG, "🚀 Calling UserService.reboot()")
                    service.reboot()
                } else {
                    Log.w(TAG, "UserService not available, falling back to shell command")
                    executeShellCommand("svc power reboot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calling reboot: ${e.message}")
            }
        }
    }

    private fun handleLockDeviceCommand() {
        Log.i(TAG, "🔒 Lock command received")
        mainHandler.post {
            Toast.makeText(this, "Lock command received", Toast.LENGTH_SHORT).show()
        }
        try {
            val intent =
                    Intent().apply {
                        component =
                                ComponentName(
                                        "com.ailife.ClockSkinCoco",
                                        "com.ailife.ClockSkinCoco.UBSLauncherActivity"
                                )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error locking device: ${e.message}")
        }
    }

    private var findDeviceRingtone: Ringtone? = null

    private fun handleFindDeviceCommand(message: ProtocolMessage) {
        val enabled = ProtocolHelper.extractBooleanField(message, "enabled")
        Log.i(TAG, "🔍 Find Device command: enabled=$enabled")
        mainHandler.post {
            Toast.makeText(this, "Find Device: $enabled", Toast.LENGTH_SHORT).show()
        }
        if (enabled) {
            startFindDeviceAlarm()
        } else {
            stopFindDeviceAlarm()
        }
    }

    private fun startFindDeviceAlarm() {
        stopFindDeviceAlarm()
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            val alarmUri =
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            findDeviceRingtone =
                    RingtoneManager.getRingtone(this, alarmUri).apply {
                        @Suppress("DEPRECATION") streamType = AudioManager.STREAM_ALARM
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            isLooping = true
                        }
                        play()
                    }

            // Start activity to allow stopping locally
            val intent =
                    Intent(this, FindDeviceActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            startActivity(intent)

            Log.i(TAG, "Alarm started at max volume")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting alarm: ${e.message}")
        }
    }

    private fun stopFindDeviceAlarm() {
        findDeviceRingtone?.stop()
        findDeviceRingtone = null
        Log.i(TAG, "Alarm stopped")
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

    private suspend fun handleUninstallApp(message: ProtocolMessage) {
        val pkg =
                ProtocolHelper.extractStringField(message, "package")
                        ?: run {
                            android.util.Log.e(TAG, "Uninstall failed: package field missing")
                            return
                        }
        android.util.Log.d(TAG, "Requesting uninstall for: $pkg")

        // 1. Try Shizuku (UserService)
        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                if (userService?.uninstallApp(pkg) == true) {
                    Log.i(TAG, "✅ Silent uninstall via Shizuku succeeded: $pkg")
                    mainHandler.post {
                        Toast.makeText(this, "Uninstalled: $pkg", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku silent uninstall failed: ${e.message}")
            }
        }

        // 2. Try Root (libsu)
        if (Shell.getShell().isRoot) {
            val result = Shell.cmd("pm uninstall $pkg").exec()
            if (result.isSuccess) {
                Log.i(TAG, "✅ Silent uninstall via Root succeeded: $pkg")
                mainHandler.post {
                    Toast.makeText(this, "Uninstalled: $pkg", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

        // Wake screen for manual fallback
        wakeScreen("AppUninstall")

        // Fallback to manual intent
        mainHandler.post { Toast.makeText(this, "Uninstalling: $pkg", Toast.LENGTH_SHORT).show() }

        try {
            // Standard intent to uninstall
            val intent =
                    Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.parse("package:$pkg")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            startActivity(intent)
            android.util.Log.d(TAG, "Uninstall intent started for: $pkg")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error starting uninstall intent: ${e.message}", e)
            mainHandler.post {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun sendApkFile(uri: Uri) {
        sendUriFile(uri, "APK")
    }

    fun sendUriFile(uri: Uri, type: String = "SHARE") {
        serviceScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val fileName = getFileNameFromUri(uri) ?: "shared_file"
                    val tempFile = File(cacheDir, fileName)
                    tempFile.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
                    sendFile(tempFile, type)
                }
            } catch (e: Exception) {
                Log.e("BluetoothService", "Error sending URI file: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback?.onError(getString(R.string.toast_upload_failed))
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

    fun sendFile(
            file: File,
            type: String = "REGULAR",
            remotePath: String? = null,
            preferredName: String? = null
    ) {
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!isConnected) {
                withContext(Dispatchers.Main) {
                    callback?.onError(getString(R.string.toast_watch_not_connected))
                }
                return@launch
            }
            isTransferring = true

            try {
                val fileBytes = file.readBytes()
                val md5 = java.security.MessageDigest.getInstance("MD5")
                md5.update(fileBytes)
                val checksum = md5.digest().joinToString("") { "%02x".format(it) }

                val fileData =
                        FileTransferData(
                                name = preferredName ?: file.name,
                                size = fileBytes.size.toLong(),
                                checksum = checksum,
                                type = type,
                                path = remotePath
                        )
                sendMessage(ProtocolHelper.createFileTransferStart(fileData))
                delay(100)

                val chunkSize = 32 * 1024
                val totalChunks = (fileBytes.size + chunkSize - 1) / chunkSize
                var offset = 0
                var chunkNumber = 0

                while (offset < fileBytes.size) {
                    val end = minOf(offset + chunkSize, fileBytes.size)
                    val chunk = fileBytes.copyOfRange(offset, end)
                    val base64Chunk =
                            android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)

                    val chunkData =
                            FileChunkData(
                                    offset = offset.toLong(),
                                    data = base64Chunk,
                                    chunkNumber = chunkNumber,
                                    totalChunks = totalChunks
                            )
                    sendMessage(ProtocolHelper.createFileChunk(chunkData))

                    // Report progress
                    val progress = ((chunkNumber + 1) * 95 / totalChunks)
                    withContext(Dispatchers.Main) { callback?.onUploadProgress(progress) }

                    offset = end
                    chunkNumber++
                    delay(10)
                }

                fileAckDeferred = kotlinx.coroutines.CompletableDeferred()
                waitingForFileAck = true
                sendMessageSync(ProtocolHelper.createFileTransferEnd(success = true))

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
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending file: ${e.message}")
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
            } finally {
                isTransferring = false
                waitingForFileAck = false
                fileAckDeferred = null
                lastMessageTime = System.currentTimeMillis()
            }
        }
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

    private fun handleRequestApps() {
        val apps = getInstalledAppsWithIcons()
        sendMessage(ProtocolHelper.createResponseApps(apps))
    }

// REMOVED: handleResponseApps()

    @SuppressLint("QueryPermissionsNeeded")
    private fun getInstalledAppsWithIcons(): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        val pm = packageManager
        val packages = pm.getInstalledPackages(0)

        for (packageInfo in packages) {
            try {
                val appInfo = packageInfo.applicationInfo ?: continue
                // Skip system apps without launch intent to keep list clean
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                                pm.getLaunchIntentForPackage(packageInfo.packageName) == null
                )
                        continue

                val appName = appInfo.loadLabel(pm).toString()
                val packageName = packageInfo.packageName

                // Get icon and convert to base64
                val icon = appInfo.loadIcon(pm)
                val bitmap = icon.toBitmap(48, 48)
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val iconBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                apps.add(AppInfo(name = appName, packageName = packageName, icon = iconBase64))
            } catch (_: Exception) {}
        }
        return apps
    }

    private fun showMirroredNotification(message: ProtocolMessage) {
        try {
            val notif = ProtocolHelper.extractData<NotificationData>(message)

            // Check notification style preference
            val notificationStyle = prefs.getString("notification_style", "android") ?: "android"

            if (notificationStyle == "dynamic_island") {
                // Route to Dynamic Island
                performNotificationAlert()

                val intent =
                        Intent(ACTION_SHOW_IN_DYNAMIC_ISLAND).apply {
                            putExtra(EXTRA_NOTIF_JSON, Gson().toJson(notif))
                            setPackage(packageName)
                        }
                sendBroadcast(intent)
                Log.d(TAG, "Notification sent to Dynamic Island: ${notif.title}")
                return
            }

            // Otherwise show as Android notification (existing code)
            val title = notif.title
            val text = notif.text
            val pkg = notif.packageName
            val appName = notif.appName ?: pkg
            val key = notif.key

            val isUpdate = notificationMap.containsKey(key)
            val notifId =
                    notificationMap.getOrPut(key) {
                        (System.currentTimeMillis() % 10000).toInt() +
                                MIRRORED_NOTIFICATION_ID_START
                    }

            // Store data for local refresh (stops spinning after reply)
            notificationDataMap[key] = notif

            val deleteIntent =
                    Intent(ACTION_WATCH_DISMISSED_LOCAL).apply {
                        putExtra(EXTRA_NOTIF_KEY, key)
                        setPackage(packageName)
                    }
            val deletePending =
                    PendingIntent.getBroadcast(
                            this,
                            notifId,
                            deleteIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

            // Use NotificationCompat.Builder (from androidx) for better compatibility and features
            val builder =
                    NotificationCompat.Builder(this, MIRRORED_CHANNEL_ID)
                            .setContentTitle(title)
                            .setContentText(text)
                            .setSubText(appName)
                            .setOnlyAlertOnce(isUpdate)
                            .setAutoCancel(true)
                            .setDeleteIntent(deletePending)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)

            performNotificationAlert()

            var defaults = NotificationCompat.DEFAULT_ALL
            val vibrate = prefs.getBoolean("vibrate_enabled", false)
            val vibrateInSilent = prefs.getBoolean("vibrate_silent_enabled", false)

            if (vibrate) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val isSilent =
                        nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

                if (isSilent && !vibrateInSilent) {
                    defaults = defaults and NotificationCompat.DEFAULT_VIBRATE.inv()
                } else {
                    // Force vibration pattern if enabled
                    builder.setVibrate(longArrayOf(0, 250, 250, 250))
                }
            } else {
                defaults = defaults and NotificationCompat.DEFAULT_VIBRATE.inv()
            }
            builder.setDefaults(defaults)

            // De-base64 all available icons
            val largeIconBitmap = notif.largeIcon?.let { decodeBase64ToBitmap(it) }
            val groupIconBitmap = notif.groupIcon?.let { decodeBase64ToBitmap(it) }
            val senderIconBitmap = notif.senderIcon?.let { decodeBase64ToBitmap(it) }

            // 1. Set Large Icon (Principal background or group icon)
            // Priority: Group Icon > Original Large Icon > Sender Icon
            val mainIcon = groupIconBitmap ?: largeIconBitmap ?: senderIconBitmap
            mainIcon?.let {
                builder.setLargeIcon(it)
                Log.d(TAG, "Main icon set for notification")
            }

            // 4. Use MessagingStyle if we have messages or it's a group
            if (notif.messages.isNotEmpty() || notif.groupIcon != null) {
                val userName = getString(R.string.reply_sender_me)

                // Use app small icon as a "default" for the user to avoid wrong fallbacks
                val userIcon = notif.smallIcon?.let { decodeBase64ToBitmap(it) }
                val userBuilder = androidx.core.app.Person.Builder().setName(userName)
                userIcon?.let {
                    userBuilder.setIcon(
                            androidx.core.graphics.drawable.IconCompat.createWithBitmap(it)
                    )
                }
                val user = userBuilder.build()

                val style = NotificationCompat.MessagingStyle(user)

                if (notif.groupIcon != null || notif.messages.any { it.senderName != null }) {
                    style.conversationTitle = title
                    style.isGroupConversation = notif.groupIcon != null
                }

                // Add all messages from the history
                for (msg in notif.messages) {
                    val msgSenderName = msg.senderName

                    // Robust "Me" detection:
                    // 1. Exact name match ("Me")
                    // 2. OR null name (commonly used for 'self' in MessagingStyle history)
                    val isMe = msgSenderName == null || msgSenderName == userName

                    if (isMe) {
                        // For outgoing/echoed replies, pass null or the user person
                        // to let the system use the default user icon
                        style.addMessage(msg.text, msg.timestamp, user)
                    } else {
                        val sender =
                                androidx.core.app.Person.Builder()
                                        .setName(msgSenderName ?: "Sender")
                                        .apply {
                                            msg.senderIcon?.let {
                                                decodeBase64ToBitmap(it)?.let { bitmap ->
                                                    setIcon(
                                                            androidx.core.graphics.drawable
                                                                    .IconCompat.createWithBitmap(
                                                                    bitmap
                                                            )
                                                    )
                                                }
                                            }
                                        }
                                        .build()
                        style.addMessage(msg.text, msg.timestamp, sender)
                    }
                }

                builder.setStyle(style)
                Log.d(TAG, "MessagingStyle applied with ${notif.messages.size} messages")
            }

            // Set small icon using the bitmap sent from the phone (API 26+)
            // This displays the actual app icon as the notification small icon
            if (notif.smallIcon != null) {
                try {
                    val iconBytes = Base64.decode(notif.smallIcon, Base64.NO_WRAP)
                    val bitmap =
                            android.graphics.BitmapFactory.decodeByteArray(
                                    iconBytes,
                                    0,
                                    iconBytes.size
                            )
                    if (bitmap != null) {
                        builder.setSmallIcon(
                                androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap)
                        )
                    } else {
                        // Fallback if bitmap is null
                        builder.setSmallIcon(R.drawable.ic_smartwatch_notification)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding small icon: ${e.message}")
                    builder.setSmallIcon(R.drawable.ic_smartwatch_notification)
                }
            } else {
                // No small icon provided, use default
                builder.setSmallIcon(R.drawable.ic_smartwatch_notification)
            }

            // Add action buttons
            notif.actions.forEachIndexed { index, action ->
                val actionIntent =
                        if (action.isReplyAction) {
                            Intent(this, NotificationActionReceiver::class.java).apply {
                                setAction("${key}_${action.actionKey}_$index")
                                putExtra(EXTRA_NOTIF_KEY, key)
                                putExtra(EXTRA_ACTION_KEY, action.actionKey)
                                putExtra("is_reply", true)
                            }
                        } else {
                            Intent(this, NotificationActionReceiver::class.java).apply {
                                setAction("${key}_${action.actionKey}_$index")
                                putExtra(EXTRA_NOTIF_KEY, key)
                                putExtra(EXTRA_ACTION_KEY, action.actionKey)
                                putExtra("is_reply", false)
                            }
                        }

                val actionPendingIntent =
                        PendingIntent.getBroadcast(
                                this,
                                (notifId + index + 1),
                                actionIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )

                if (action.isReplyAction) {
                    // Create reply action with RemoteInput
                    val remoteInput =
                            androidx.core.app.RemoteInput.Builder(BluetoothService.EXTRA_REPLY_TEXT)
                                    .setLabel(action.title)
                                    .build()

                    val replyAction =
                            NotificationCompat.Action.Builder(
                                            androidx.core.graphics.drawable.IconCompat
                                                    .createWithResource(
                                                            this,
                                                            android.R.drawable.ic_menu_send
                                                    ),
                                            action.title,
                                            actionPendingIntent
                                    )
                                    .addRemoteInput(remoteInput)
                                    .build()

                    builder.addAction(replyAction)
                } else {
                    // Regular action button
                    val regularAction =
                            NotificationCompat.Action.Builder(
                                            androidx.core.graphics.drawable.IconCompat
                                                    .createWithResource(
                                                            this,
                                                            android.R.drawable.ic_menu_view
                                                    ),
                                            action.title,
                                            actionPendingIntent
                                    )
                                    .build()

                    builder.addAction(regularAction)
                }
            }

            // Set big picture style if available
            notif.bigPicture?.let { pictureBase64 ->
                try {
                    Log.d(TAG, "Decoding big picture: ${pictureBase64.length} chars")
                    val pictureBytes = Base64.decode(pictureBase64, Base64.NO_WRAP)
                    val pictureBitmap =
                            android.graphics.BitmapFactory.decodeByteArray(
                                    pictureBytes,
                                    0,
                                    pictureBytes.size
                            )
                    if (pictureBitmap != null) {
                        Log.d(
                                TAG,
                                "Big picture decoded: ${pictureBitmap.width}x${pictureBitmap.height}"
                        )
                        val bigPictureStyle =
                                NotificationCompat.BigPictureStyle()
                                        .bigPicture(pictureBitmap)
                                        .bigLargeIcon(
                                                null as Bitmap?
                                        ) // Hide large icon when expanded
                        builder.setStyle(bigPictureStyle)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding big picture: ${e.message}", e)
                }
            }

            val notification = builder.build()
            notificationManager.notify(notifId, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing mirrored notification: ${e.message}", e)
        }
    }

    private fun refreshNotification(key: String, replyText: String? = null) {
        val data = notificationDataMap[key] ?: return

        val updatedData =
                if (replyText != null) {
                    // Append local reply to history for immediate feedback
                    val newMessage =
                            NotificationMessageData(
                                    text = replyText,
                                    timestamp = System.currentTimeMillis(),
                                    senderName = getString(R.string.reply_sender_me)
                            )
                    data.copy(messages = data.messages + newMessage)
                } else {
                    data
                }

        // Update stored data so subsequent refreshes find the new history
        notificationDataMap[key] = updatedData

        val message = ProtocolHelper.createNotificationPosted(updatedData)
        showMirroredNotification(message)
        Log.d(TAG, "Notification refreshed locally for key: $key")
    }

    private fun dismissLocalNotification(message: ProtocolMessage) {
        val key = ProtocolHelper.extractStringField(message, "key") ?: return

        // Dismiss from Android notification if present
        notificationMap[key]?.let { id ->
            notificationManager.cancel(id)
            notificationMap.remove(key)
            notificationDataMap.remove(key)
        }

        // Also dismiss from Dynamic Island if it's running
        val intent =
                Intent(ACTION_DISMISS_FROM_DYNAMIC_ISLAND).apply {
                    putExtra(EXTRA_NOTIF_KEY, key)
                    setPackage(packageName)
                }
        sendBroadcast(intent)
    }

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

    private fun performNotificationAlert() {
        val wakeScreen = prefs.getBoolean("wake_screen_enabled", false)
        val vibrate = prefs.getBoolean("vibrate_enabled", false)
        val vibrateInSilent = prefs.getBoolean("vibrate_silent_enabled", false)

        if (wakeScreen) {
            wakeDeviceScreen()
        }

        if (vibrate) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val isSilent =
                    nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

            if (!isSilent || vibrateInSilent) {
                try {
                    val vibrator =
                            ContextCompat.getSystemService(this, android.os.Vibrator::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(
                                android.os.VibrationEffect.createOneShot(
                                        500,
                                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                                )
                        )
                    } else {
                        @Suppress("DEPRECATION") vibrator?.vibrate(500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to vibrate: ${e.message}")
                }
            }
        }
    }

    private fun wakeDeviceScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("deprecation")
            val wakeLock =
                    pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                                    PowerManager.ON_AFTER_RELEASE,
                            "RTOSify:NotificationWakeLock"
                    )
            wakeLock.acquire(3000)
            Log.d(TAG, "Screen wake requested via SCREEN_BRIGHT_WAKE_LOCK")
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen: ${e.message}")
        }
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

    fun resetApp() {
        stopConnectionLoopOnly()
        prefs.edit().clear().apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isActive(): Boolean {
        return serverJob?.isActive == true
    }

    fun stopConnectionLoopOnly() {
        // REMOVED: connectionJob?.cancel() - watch app no longer has client connection job
        serverJob?.cancel()
        statusUpdateJob?.cancel()

        val wasConnected = isConnected
        stopAllCommunication()
        isConnected = false
        currentDeviceName = null
// REMOVED: bluetoothSocket cleanup

        // Notify Dynamic Island of disconnection
        sendBroadcast(
                Intent("com.ailife.rtosifycompanion.CONNECTION_STATE_CHANGED").apply {
                    putExtra("connected", false)
                    setPackage(packageName)
                }
        )

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
    private fun updateStatus(text: String) {
        if (isStopping) return // Prevent updates if stopping

        currentStatus = text
        currentNotificationStatus = text

        mainHandler.post {
            try {
                updateForegroundNotification()
            } catch (e: Exception) {
                if (DEBUG_NOTIFICATIONS) Log.e(TAG, "updateStatus erro: ${e.message}")
            }
            callback?.onStatusChanged(text)
        }
    }

    /**
     * DEFINITIVE FIX FOR BLOCKED CHANNELS: Instead of using a single ID, we use a
     * different ID for each state (Waiting, Connected, etc).
     *
     * Logic:
     * 1. Determine the Current State -> New ID and New Channel.
     * 2. Call startForeground with the NEW ID. This "holds" the service and prevents crashes.
     * 3. If the user blocked this channel, Android shows nothing (correct).
     * 4. CRUCIAL: Call .cancel() on the IDs of the old states. This ensures that the notification
     *    visually disappears if we change to "Connected", even if "Connected" is hidden.
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

            // Start/Update Foreground in the correct ID for current state
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

            // CLEANUP: Visually remove notifications from other states.
            // This fixes the bug where "Waiting" persisted if "Connected" was blocked.
            if (targetId != NOTIFICATION_ID_WAITING)
                    notificationManager.cancel(NOTIFICATION_ID_WAITING)
            if (targetId != NOTIFICATION_ID_CONNECTED)
                    notificationManager.cancel(NOTIFICATION_ID_CONNECTED)
            if (targetId != NOTIFICATION_ID_DISCONNECTED)
                    notificationManager.cancel(NOTIFICATION_ID_DISCONNECTED)
        } catch (e: Exception) {
            if (DEBUG_NOTIFICATIONS)
                    Log.e(TAG, "updateForegroundNotification: Error: ${e.message}", e)
        }
    }

    // Returns Triple(ID, Channel, Text)
    private fun determineNotificationState(): Triple<Int, String, String> {
        return when {
            isConnected && currentDeviceName != null -> {
                val state = transportManager.connectionState.value
                val statusText = when {
                    state is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected && state.type == "Dual" ->
                        getString(R.string.status_connected_to, currentDeviceName!!) + " via BT & WiFi"
                    state is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected && state.type == "WiFi" ->
                        getString(R.string.status_connected_to, currentDeviceName!!) + " via WiFi"
                    else ->
                        getString(R.string.status_connected_to, currentDeviceName!!) + " via BT"
                }
                Triple(NOTIFICATION_ID_CONNECTED, CHANNEL_ID_CONNECTED, statusText)
            }
            isConnected && currentDeviceName == null -> {
                val state = transportManager.connectionState.value
                val statusText = when {
                     state is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected && state.type == "Dual" ->
                        getString(R.string.status_connected) + " via BT & WiFi"
                    state is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected && state.type == "WiFi" ->
                        getString(R.string.status_connected) + " via WiFi"
                    else ->
                        getString(R.string.status_connected)
                }
                Triple(NOTIFICATION_ID_CONNECTED, CHANNEL_ID_CONNECTED, statusText)
            }
            currentNotificationStatus.contains(getString(R.string.status_waiting), ignoreCase = true) ||
                    currentNotificationStatus.contains("Waiting", ignoreCase = true) ||
                    currentNotificationStatus.contains(getString(R.string.status_scanning), ignoreCase = true) ||
                    currentNotificationStatus.contains("Scanning", ignoreCase = true) ||
                    currentNotificationStatus.contains(getString(R.string.status_searching), ignoreCase = true) ||
                    currentNotificationStatus.contains("Searching", ignoreCase = true) ||
                    currentNotificationStatus.contains(getString(R.string.status_started), ignoreCase = true) ||
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
                        getString(R.string.notif_channel_wait),
                        NotificationManager.IMPORTANCE_LOW
                )
        )
        notificationManager.createNotificationChannel(
                NotificationChannel(
                        CHANNEL_ID_CONNECTED,
                        getString(R.string.notif_channel_connected),
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
                NotificationChannel(
                                CHANNEL_ID_DISCONNECTION_ALERT,
                                getString(R.string.notif_channel_disconnection_alert),
                                NotificationManager.IMPORTANCE_HIGH
                        )
                        .apply {
                            enableVibration(true)
                            vibrationPattern = longArrayOf(0, 500, 200, 500)
                        }
        )

        notificationManager.createNotificationChannel(
                NotificationChannel(
                        MIRRORED_CHANNEL_ID,
                        getString(R.string.notif_channel_mirrored),
                        NotificationManager.IMPORTANCE_HIGH
                )
        )
    }

    private fun savePreference(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun handleSyncCalendar(message: ProtocolMessage) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.e(TAG, "WRITE_CALENDAR permission not granted")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val eventsJson = message.data.getAsJsonArray("events")
                val type = object : com.google.gson.reflect.TypeToken<List<CalendarEvent>>() {}.type
                val events: List<CalendarEvent> = ProtocolHelper.gson.fromJson(eventsJson, type)
                android.util.Log.d(TAG, "Received ${events.size} calendar events to write")

                // 1. Find a calendar ID to write to
                var calendarId: Long = -1
                contentResolver.query(
                                CalendarContract.Calendars.CONTENT_URI,
                                arrayOf(CalendarContract.Calendars._ID),
                                null,
                                null,
                                null
                        )
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                calendarId = cursor.getLong(0)
                            }
                        }

                if (calendarId == -1L) {
                    android.util.Log.e(TAG, "No system calendar found to write to")
                    return@launch
                }

                var count = 0
                for (event in events) {
                    // Check for duplicates (same title and start time)
                    val exists =
                            contentResolver.query(
                                            CalendarContract.Events.CONTENT_URI,
                                            arrayOf(CalendarContract.Events._ID),
                                            "${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DTSTART} = ?",
                                            arrayOf(event.title, event.startTime.toString()),
                                            null
                                    )
                                    ?.use { it.count > 0 }
                                    ?: false

                    if (!exists) {
                        val values =
                                ContentValues().apply {
                                    put(CalendarContract.Events.DTSTART, event.startTime)
                                    put(CalendarContract.Events.DTEND, event.endTime)
                                    put(CalendarContract.Events.TITLE, event.title)
                                    put(CalendarContract.Events.DESCRIPTION, event.description)
                                    put(CalendarContract.Events.EVENT_LOCATION, event.location)
                                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                                    put(
                                            CalendarContract.Events.EVENT_TIMEZONE,
                                            java.util.TimeZone.getDefault().id
                                    )
                                }
                        contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                        count++
                    }
                }

                mainHandler.post {
                    Toast.makeText(
                                    this@BluetoothService,
                                    "Synced $count new calendar events to system",
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error writing calendar events: ${e.message}")
            }
        }
    }

    private fun handleSyncContacts(message: ProtocolMessage) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.e(TAG, "WRITE_CONTACTS permission not granted")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val contactsJson = message.data.getAsJsonArray("contacts")
                val type = object : com.google.gson.reflect.TypeToken<List<Contact>>() {}.type
                val contacts: List<Contact> = ProtocolHelper.gson.fromJson(contactsJson, type)
                android.util.Log.d(TAG, "Received ${contacts.size} contacts to write")

                var count = 0
                for (contact in contacts) {
                    // Check for duplicate (same name)
                    val exists =
                            contentResolver.query(
                                            ContactsContract.Data.CONTENT_URI,
                                            arrayOf(ContactsContract.Data._ID),
                                            "${ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                                            arrayOf(
                                                    contact.name,
                                                    ContactsContract.CommonDataKinds.StructuredName
                                                            .CONTENT_ITEM_TYPE
                                            ),
                                            null
                                    )
                                    ?.use { it.count > 0 }
                                    ?: false

                    if (!exists) {
                        val ops = arrayListOf<ContentProviderOperation>()
                        ops.add(
                                ContentProviderOperation.newInsert(
                                                ContactsContract.RawContacts.CONTENT_URI
                                        )
                                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                                        .build()
                        )

                        // Add Name
                        ops.add(
                                ContentProviderOperation.newInsert(
                                                ContactsContract.Data.CONTENT_URI
                                        )
                                        .withValueBackReference(
                                                ContactsContract.Data.RAW_CONTACT_ID,
                                                0
                                        )
                                        .withValue(
                                                ContactsContract.Data.MIMETYPE,
                                                ContactsContract.CommonDataKinds.StructuredName
                                                        .CONTENT_ITEM_TYPE
                                        )
                                        .withValue(
                                                ContactsContract.CommonDataKinds.StructuredName
                                                        .DISPLAY_NAME,
                                                contact.name
                                        )
                                        .build()
                        )

                        // Add Phone Numbers
                        for (number in contact.phoneNumbers) {
                            ops.add(
                                    ContentProviderOperation.newInsert(
                                                    ContactsContract.Data.CONTENT_URI
                                            )
                                            .withValueBackReference(
                                                    ContactsContract.Data.RAW_CONTACT_ID,
                                                    0
                                            )
                                            .withValue(
                                                    ContactsContract.Data.MIMETYPE,
                                                    ContactsContract.CommonDataKinds.Phone
                                                            .CONTENT_ITEM_TYPE
                                            )
                                            .withValue(
                                                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                                                    number
                                            )
                                            .withValue(
                                                    ContactsContract.CommonDataKinds.Phone.TYPE,
                                                    ContactsContract.CommonDataKinds.Phone
                                                            .TYPE_MOBILE
                                            )
                                            .build()
                            )
                        }

                        // Add Emails if any
                        contact.emails?.forEach { email ->
                            ops.add(
                                    ContentProviderOperation.newInsert(
                                                    ContactsContract.Data.CONTENT_URI
                                            )
                                            .withValueBackReference(
                                                    ContactsContract.Data.RAW_CONTACT_ID,
                                                    0
                                            )
                                            .withValue(
                                                    ContactsContract.Data.MIMETYPE,
                                                    ContactsContract.CommonDataKinds.Email
                                                            .CONTENT_ITEM_TYPE
                                            )
                                            .withValue(
                                                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                                                    email
                                            )
                                            .withValue(
                                                    ContactsContract.CommonDataKinds.Email.TYPE,
                                                    ContactsContract.CommonDataKinds.Email.TYPE_WORK
                                            )
                                            .build()
                            )
                        }

                        contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                        count++
                    }
                }

                mainHandler.post {
                    Toast.makeText(
                                    this@BluetoothService,
                                    "Synced $count new contacts to system",
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error writing contacts: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "BluetoothService destroying...")
        isStopping = true
        
        // Clean up WiFi transport
        serviceScope.launch {
            transportManager.stopAll()
        }

        panManager?.close()
        panManager = null

        // Unbind UserService
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConn as ServiceConnection, true)
            userServiceConnection = null
            userService = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding UserService: ${e.message}")
        }

        super.onDestroy()
        try {
            unregisterReceiver(internalReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(watchDismissReceiver)
        } catch (_: Exception) {}

        try {
            unregisterReceiver(wifiStateReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(dynamicIslandHandshakeReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}

        if (MirroringService.isRunning) {
            Log.d(TAG, "onDestroy: Mirroring is active, stopping it.")
            val stopIntent = Intent(this, MirroringService::class.java)
            stopService(stopIntent)
        }

        mainHandler.removeCallbacksAndMessages(null)
        stopAllCommunication()
        serviceScope.cancel()
    }

    private fun stopAllCommunication() {
        if (MirroringService.isRunning) {
            val stopIntent = Intent(this, MirroringService::class.java)
            stopService(stopIntent)
        }
        
        batteryHistoryJob?.cancel()
        deviceInfoUpdateJob?.cancel()
        
        transportManager.stopAll()
    }

    private fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        return try {
            val iconBytes = Base64.decode(base64Str, Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64 bitmap: ${e.message}")
            null
        }
    }

    private fun handleRequestPreview(message: ProtocolMessage) {
        val path = ProtocolHelper.extractStringField(message, "path") ?: return
        val absPath = toAbsolutePath(path)

        serviceScope.launch(Dispatchers.IO) {
            try {
                var bitmap: Bitmap? = null

                // 1. Try to check if it's a directory or file using dispatcher logic
                val isDir = isDirectoryDispatcher(absPath)

                if (isDir) {
                    val candidates = listOf("preview.png", "img_gear_0.png", "preview.jpg")
                    for (c in candidates) {
                        val imgPath = if (absPath.endsWith("/")) "$absPath$c" else "$absPath/$c"
                        bitmap = loadBitmapFromRestrictedPath(imgPath)
                        if (bitmap != null) break
                    }
                } else {
                    // It's likely a ZIP or .watch file
                    bitmap = loadPreviewFromZip(absPath)
                }

                if (bitmap != null) {
                    val thumb = Bitmap.createScaledBitmap(bitmap!!, 200, 200, true)
                    val output = ByteArrayOutputStream()
                    thumb.compress(Bitmap.CompressFormat.JPEG, 70, output)
                    val base64 =
                            android.util.Base64.encodeToString(
                                    output.toByteArray(),
                                    android.util.Base64.NO_WRAP
                            )
                    sendMessage(ProtocolHelper.createResponsePreview(path, base64))
                } else {
                    sendMessage(ProtocolHelper.createResponsePreview(path, null))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating preview for $path: ${e.message}")
                sendMessage(ProtocolHelper.createResponsePreview(path, null))
            }
        }
    }

    private suspend fun isDirectoryDispatcher(absPath: String): Boolean {
        val file = File(absPath)
        if (file.exists()) return file.isDirectory

        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                return userService?.isDirectory(absPath) ?: false
            } catch (_: Exception) {}
        }

        if (Shell.getShell().isRoot) {
            val rootPath = translatePathForRoot(absPath)
            val result = Shell.cmd("test -d \"$rootPath\"").exec()
            if (result.isSuccess) return true
        }

        val relPath = getRelPath(absPath)
        val doc = getDocumentFileForPath(relPath, false)
        return doc?.isDirectory ?: false
    }

    private suspend fun loadBitmapFromRestrictedPath(absPath: String): Bitmap? {
        val file = File(absPath)
        if (file.exists() && file.canRead()) {
            return android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        }

        var tempFile: File? = null
        try {
            // Use externalCacheDir if available so Shizuku/Shell has better chance of writing to it
            val cacheDirToUse = externalCacheDir ?: cacheDir
            tempFile = File.createTempFile("pview_", ".png", cacheDirToUse)
            var copied = false
            if (isUsingShizuku()) {
                try {
                    ensureUserServiceBound()
                    copied = userService?.copyFile(absPath, tempFile.absolutePath) ?: false
                } catch (_: Exception) {}
            }

            if (!copied && Shell.getShell().isRoot) {
                val rootSrc = translatePathForRoot(absPath)
                val result = Shell.cmd("cp \"$rootSrc\" \"${tempFile.absolutePath}\"").exec()
                if (result.isSuccess) {
                    Shell.cmd("chmod 666 \"${tempFile.absolutePath}\"").exec()
                    copied = true
                }
            }

            if (!copied) {
                val relPath = getRelPath(absPath)
                val doc = getDocumentFileForPath(relPath, false)
                if (doc != null && doc.exists() && !doc.isDirectory) {
                    contentResolver.openInputStream(doc.uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    copied = true
                }
            }

            if (copied && tempFile.exists()) {
                android.util.Log.d(
                        TAG,
                        "Successfully copied to temp file: ${tempFile.absolutePath}, size: ${tempFile.length()}"
                )
                return android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
            } else {
                android.util.Log.e(
                        TAG,
                        "Failed to copy to temp file or file does not exist. copied=$copied"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from restricted path $absPath: ${e.message}", e)
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
        return null
    }

    private suspend fun loadPreviewFromZip(absPath: String): Bitmap? {
        val file = File(absPath)
        var zipFileToUse = file
        var isTemp = false
        var tempFile: File? = null

        if (!file.exists() || !file.canRead()) {
            try {
                // Use externalCacheDir if available so Shizuku/Shell has better chance of writing
                // to it
                val cacheDirToUse = externalCacheDir ?: cacheDir
                tempFile = File.createTempFile("pzip_", ".zip", cacheDirToUse)
                var copied = false
                if (isUsingShizuku()) {
                    try {
                        ensureUserServiceBound()
                        android.util.Log.d(
                                TAG,
                                "Attempting Shizuku copy for ZIP preview: $absPath -> ${tempFile.absolutePath}"
                        )
                        copied = userService?.copyFile(absPath, tempFile.absolutePath) ?: false
                        android.util.Log.d(TAG, "Shizuku copy result: $copied")
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Shizuku copy failed", e)
                    }
                }

                if (!copied && Shell.getShell().isRoot) {
                    val rootSrc = translatePathForRoot(absPath)
                    val result = Shell.cmd("cp \"$rootSrc\" \"${tempFile.absolutePath}\"").exec()
                    if (result.isSuccess) {
                        Shell.cmd("chmod 666 \"${tempFile.absolutePath}\"").exec()
                        copied = true
                    }
                }

                if (!copied) {
                    val relPath = getRelPath(absPath)
                    val doc = getDocumentFileForPath(relPath, false)
                    if (doc != null && doc.exists() && !doc.isDirectory) {
                        contentResolver.openInputStream(doc.uri)?.use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        copied = true
                    }
                }

                if (copied) {
                    zipFileToUse = tempFile
                    isTemp = true
                } else {
                    tempFile.delete()
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy remote ZIP for preview: ${e.message}")
                tempFile?.delete()
                return null
            }
        }

        return try {
            java.util.zip.ZipFile(zipFileToUse).use { zip ->
                val candidates = listOf("preview.png", "img_gear_0.png", "preview.jpg")
                for (c in candidates) {
                    val entry =
                            zip.entries().asSequence().find {
                                it.name.endsWith(c, true) && !it.isDirectory
                            }
                    if (entry != null) {
                        zip.getInputStream(entry)
                                .use { input -> android.graphics.BitmapFactory.decodeStream(input) }
                                ?.let {
                                    return it
                                }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting preview from ZIP: ${e.message}")
            null
        } finally {
            if (isTemp && zipFileToUse.exists()) zipFileToUse.delete()
        }
    }

    private fun getForegroundApp(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null

        try {
            val usageStatsManager =
                    getSystemService(Context.USAGE_STATS_SERVICE) as
                            android.app.usage.UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 5 * 60 * 1000 // Last 5 minutes

            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = android.app.usage.UsageEvents.Event()

            var lastPackage: String? = null
            var lastTime: Long = 0

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (event.timeStamp > lastTime) {
                        lastTime = event.timeStamp
                        lastPackage = event.packageName
                    }
                }
            }
            return lastPackage
        } catch (e: Exception) {
            Log.e(TAG, "Error getting foreground app: ${e.message}")
            return null
        }
    }

    private fun startBatteryHistoryTracking() {
        batteryHistoryJob?.cancel()

        val detailedEnabled = prefs.getBoolean("batt_detailed_log", false)
        val interval = if (detailedEnabled) 2 * 60 * 1000L else 30 * 60 * 1000L // 2 min vs 30 min

        Log.d(
                TAG,
                "Starting battery history tracking. Detailed: $detailedEnabled, Interval: ${interval}ms"
        )

        batteryHistoryJob =
                serviceScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            saveBatteryHistoryPoint()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving battery history: ${e.message}")
                        }
                        delay(interval)
                    }
                }
    }

    private fun saveBatteryHistoryPoint() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toInt()
        val batteryStatus: Intent? =
                IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                    registerReceiver(null, ifilter)
                }
        val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val packageName = getForegroundApp()

        val newPoint =
                BatteryHistoryPoint(
                        System.currentTimeMillis(),
                        level,
                        voltage,
                        current,
                        packageName
                )

        val currentHistoryJson = prefs.getString("battery_history", "[]")
        val type = object : TypeToken<MutableList<BatteryHistoryPoint>>() {}.type
        val history: MutableList<BatteryHistoryPoint> =
                Gson().fromJson(currentHistoryJson, type) ?: mutableListOf()

        history.add(newPoint)

        // Keep last 1000 points (approx 33 hours at 2-min interval, or 20 days at 30-min interval)
        if (history.size > 1000) {
            history.removeAt(0)
        }

        prefs.edit().putString("battery_history", Gson().toJson(history)).apply()
    }

    private fun handleRequestBatteryLive() {
        Log.d(TAG, "Handling battery live request")
        serviceScope.launch(Dispatchers.IO) {
            try {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager

                val batteryStatus: Intent? =
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                            registerReceiver(null, ifilter)
                        }

                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct =
                        if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt()
                        else 0

                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging =
                        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL

                val currentNow =
                        bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toInt()
                val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

                // Estimation
                var remainingTimeMillis: Long? = null
                if (isCharging) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val chargeTime = bm.computeChargeTimeRemaining()
                        if (chargeTime > 0) remainingTimeMillis = chargeTime
                    }
                } else {
                    // Current is usually negative when discharging
                    val absCurrent = if (currentNow < 0) -currentNow.toDouble() else 0.0
                    // Heuristic: If it's > 5000, it's likely microamperes. If < 5000, it might be
                    // milliamperes.
                    val dischargeCurrentMa =
                            if (absCurrent > 5000) absCurrent / 1000.0 else absCurrent

                    if (dischargeCurrentMa > 1.0) { // More than 1mA discharge
                        var chargeCounterMah =
                                bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                                        .toDouble() / 1000.0

                        // Fallback if chargeCounter is unavailable (returns 0 or very small)
                        if (chargeCounterMah <= 0) {
                            // Assume a typical watch capacity of 300mAh for estimation if unknown
                            val totalCapacityMah = 300.0
                            chargeCounterMah = (batteryPct / 100.0) * totalCapacityMah
                        }

                        val hoursRemaining = chargeCounterMah / dischargeCurrentMa
                        if (hoursRemaining in 0.1..500.0) {
                            remainingTimeMillis = (hoursRemaining * 3600 * 1000).toLong()
                        }
                    }
                }

                val detail =
                        BatteryDetailData(
                                batteryLevel = batteryPct,
                                isCharging = isCharging,
                                currentNow = currentNow,
                                currentAverage =
                                        bm.getLongProperty(
                                                        BatteryManager
                                                                .BATTERY_PROPERTY_CURRENT_AVERAGE
                                                )
                                                .toInt(),
                                voltage = voltage,
                                chargeCounter =
                                        bm.getLongProperty(
                                                        BatteryManager
                                                                .BATTERY_PROPERTY_CHARGE_COUNTER
                                                )
                                                .toInt(),
                                energyCounter =
                                        bm.getLongProperty(
                                                BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER
                                        ),
                                capacity = getBatteryCapacity(),
                                temperature =
                                        batteryStatus?.getIntExtra(
                                                BatteryManager.EXTRA_TEMPERATURE,
                                                0
                                        )
                                                ?: 0,
                                remainingTimeMillis = remainingTimeMillis
                        )

                sendMessage(ProtocolHelper.createBatteryDetailUpdate(detail))
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting live battery data: ${e.message}")
            }
        }
    }

    private fun handleRequestBatteryStatic() {
        Log.d(TAG, "Handling battery static request")
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Collect App Usage
                val appUsage = collectAppUsage()

                // Get History
                val currentHistoryJson = prefs.getString("battery_history", "[]")
                val type = object : TypeToken<List<BatteryHistoryPoint>>() {}.type
                val history: List<BatteryHistoryPoint> =
                        Gson().fromJson(currentHistoryJson, type) ?: emptyList()

                val detail =
                        BatteryDetailData(
                                batteryLevel = 0,
                                isCharging = false,
                                currentNow = 0,
                                currentAverage = 0,
                                voltage = 0,
                                chargeCounter = 0,
                                energyCounter = 0L,
                                capacity = getBatteryCapacity(),
                                appUsage = appUsage,
                                history = history
                        )

                sendMessage(ProtocolHelper.createBatteryDetailUpdate(detail))
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting static battery data: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun collectAppUsage(): List<AppUsageData> {
        val usageStatsManager =
                getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 24 * 60 * 60 * 1000 // Last 24 hours

        val usageStats =
                try {
                    usageStatsManager.queryUsageStats(
                            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                            startTime,
                            endTime
                    )
                } catch (e: Exception) {
                    Log.e(
                            TAG,
                            "Error querying usage stats (permission likely missing): ${e.message}"
                    )
                    return emptyList()
                }

        if (usageStats.isNullOrEmpty()) return emptyList()

        // Map packageName -> Pair(powerMah, drainSpeed)
        val batteryStatsMap = mutableMapOf<String, Pair<Double, Double>>()

        // -------------------------------------------------------------------------
        // METHOD 1: BatteryStatsManager via Reflection (Android 12+)
        // -------------------------------------------------------------------------
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                // Log.d(TAG, "Attempting to get battery stats via REFLECTION (Android
                // ${android.os.Build.VERSION.SDK_INT})")
                @SuppressLint("WrongConstant")
                val batteryStatsManager = getSystemService("batterystats")

                val queryClass = Class.forName("android.os.BatteryUsageStatsQuery")
                val queryBuilderClass = Class.forName("android.os.BatteryUsageStatsQuery\$Builder")

                // Find constructor (handle hidden/different signatures)
                var builderConstructor: java.lang.reflect.Constructor<*>? = null
                try {
                    val personConstructors = queryBuilderClass.declaredConstructors
                    if (personConstructors.isNotEmpty()) {
                        builderConstructor = personConstructors[0]
                    }
                } catch (e: Exception) {}

                if (builderConstructor == null) {
                    builderConstructor = queryBuilderClass.getDeclaredConstructor()
                }

                builderConstructor?.isAccessible = true
                val builder = builderConstructor?.newInstance()

                try {
                    queryBuilderClass.getMethod("includeProcessStateData").invoke(builder)
                } catch (_: Exception) {}

                val query = queryBuilderClass.getMethod("build").invoke(builder)

                val getBatteryUsageStatsMethod =
                        batteryStatsManager.javaClass.getMethod("getBatteryUsageStats", queryClass)
                val batteryUsageStats = getBatteryUsageStatsMethod.invoke(batteryStatsManager)

                val getUidBatteryConsumersMethod =
                        batteryUsageStats.javaClass.getMethod("getUidBatteryConsumers")
                val uidBatteryConsumers =
                        getUidBatteryConsumersMethod.invoke(batteryUsageStats) as? List<*>

                uidBatteryConsumers?.forEach { consumer ->
                    try {
                        if (consumer != null) {
                            val getUidMethod = consumer.javaClass.getMethod("getUid")
                            val uid = getUidMethod.invoke(consumer) as Int
                            val packageName = packageManager.getNameForUid(uid)

                            if (packageName != null) {
                                val getConsumedPowerMethod =
                                        consumer.javaClass.getMethod("getConsumedPower")
                                val consumedPowerMah =
                                        getConsumedPowerMethod.invoke(consumer) as Double

                                if (consumedPowerMah > 0) {
                                    val appUsageStat =
                                            usageStats.find { it.packageName == packageName }
                                    val hoursForeground =
                                            (appUsageStat?.totalTimeInForeground
                                                    ?: 0L) / (1000.0 * 3600.0)
                                    val drainSpeed =
                                            if (hoursForeground > 0.01)
                                                    consumedPowerMah / hoursForeground
                                            else 0.0
                                    batteryStatsMap[packageName] =
                                            Pair(consumedPowerMah, drainSpeed)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                if (batteryStatsMap.isNotEmpty())
                        Log.d(TAG, "✅ Success: Retrieved battery stats via Reflection")
            } catch (e: Exception) {
                Log.e(
                        TAG,
                        "Reflective BatteryStatsManager failed, trying next method: ${e.message}"
                )
            }
        }

        // -------------------------------------------------------------------------
        // METHOD 2: Direct Dumpsys (If app has permissions)
        // -------------------------------------------------------------------------
        // -------------------------------------------------------------------------
        // METHOD 2: SystemHealthManager (Requires BATTERY_STATS)
        // -------------------------------------------------------------------------
        if (batteryStatsMap.isEmpty()) {
            if (checkSelfPermission(android.Manifest.permission.BATTERY_STATS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    Log.d(TAG, "Attempting SystemHealthManager (Method 2)...")
                    val healthStatsManager =
                            getSystemService(android.content.Context.SYSTEM_HEALTH_SERVICE) as?
                                    android.os.health.SystemHealthManager

                    if (healthStatsManager != null) {
                        // 1. Get all installed UIDs
                        val pm = packageManager
                        val installedPackages = pm.getInstalledPackages(0)
                        val validPackages = installedPackages.filter { it.applicationInfo != null }
                        val uids =
                                validPackages
                                        .mapNotNull { it.applicationInfo?.uid }
                                        .distinct()
                                        .toIntArray()

                        // 2. Take Snapshot
                        Log.d(TAG, "Requesting HealthStats for ${uids.size} UIDs...")
                        val healthStats = healthStatsManager.takeUidSnapshots(uids)

                        // 3. Process Results
                        healthStats.forEachIndexed { index, stats ->
                            if (stats != null) {
                                val uid = uids[index]

                                // Direct constants for CPU Timers (Not exposed in UidHealthStats
                                // public API consistently)
                                val TIMER_CPU_USER_MS = 10061
                                val TIMER_CPU_SYSTEM_MS = 10062

                                var cpuMs = 0L

                                // Helper to safely get timer
                                fun getTime(key: Int): Long {
                                    return if (stats.hasTimer(key)) stats.getTimerTime(key) else 0L
                                }
                                fun getMeasurement(key: Int): Long {
                                    return if (stats.hasMeasurement(key)) stats.getMeasurement(key)
                                    else 0L
                                }

                                // Probe other keys
                                val TIMER_PROCESS_STATE_TOP_MS = 10038
                                val MEASUREMENT_REALTIME_BATTERY_MS = 10001

                                val fgMs = getTime(TIMER_PROCESS_STATE_TOP_MS)
                                val realTime = getMeasurement(MEASUREMENT_REALTIME_BATTERY_MS)

                                // Debug logging for the first few items to see what's happening
                                if (index < 5) {
                                    Log.d(
                                            TAG,
                                            "UID=$uid CPU_USER=${getTime(TIMER_CPU_USER_MS)} CPU_SYSTEM=${getTime(TIMER_CPU_SYSTEM_MS)}"
                                    )
                                    Log.d(
                                            TAG,
                                            "Has USER_MS: ${stats.hasTimer(TIMER_CPU_USER_MS)} Has SYSTEM_MS: ${stats.hasTimer(TIMER_CPU_SYSTEM_MS)}"
                                    )
                                    Log.d(
                                            TAG,
                                            "FG_MS=$fgMs Has FG: ${stats.hasTimer(TIMER_PROCESS_STATE_TOP_MS)}"
                                    )
                                    Log.d(
                                            TAG,
                                            "REALTIME=$realTime Has REALTIME: ${stats.hasMeasurement(MEASUREMENT_REALTIME_BATTERY_MS)}"
                                    )
                                }

                                if (cpuMs > 0) {
                                    // Map UID back to Package Name
                                    val pkgName =
                                            validPackages
                                                    .firstOrNull { it.applicationInfo?.uid == uid }
                                                    ?.packageName
                                    if (pkgName != null) {
                                        // Calculate Power: cpuMs * AVG_CPU_mA / 3600000
                                        val AVG_CPU_mA = 150.0
                                        val powerMah = (cpuMs / 3600000.0) * AVG_CPU_mA
                                        batteryStatsMap[pkgName] = Pair(powerMah, fgMs.toDouble())
                                    }
                                }
                            }
                        }
                        if (batteryStatsMap.isEmpty()) {
                            Log.d(
                                    TAG,
                                    "SystemHealthManager returned valid stats but map empty after filtering."
                            )
                        } else {
                            Log.d(
                                    TAG,
                                    "✅ Success: Retrieved ${batteryStatsMap.size} items via SystemHealthManager"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SystemHealthManager failed: ${e.message}")
                }
            } else {
                Log.d(TAG, "Skipping Method 2: BATTERY_STATS permission not granted.")
            }
        }

        // -------------------------------------------------------------------------
        // METHOD 3: Direct Dumpsys (Legacy / Fallback)
        // -------------------------------------------------------------------------
        if (batteryStatsMap.isEmpty()) {
            if (checkSelfPermission(android.Manifest.permission.DUMP) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    Log.d(TAG, "Attempting direct dumpsys batterystats (Method 3)...")
                    val process = Runtime.getRuntime().exec("dumpsys batterystats --checkin")
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    // val error = process.errorStream.bufferedReader().use { it.readText() } //
                    // Optional: read stderr if needed
                    val exitCode = process.waitFor()

                    if (exitCode == 0 && output.isNotEmpty()) {
                        if (output.contains("9,0,l,cpu", ignoreCase = true) ||
                                        output.contains(",l,cpu,", ignoreCase = true)
                        ) {
                            parseDumpsysBatteryStats(output).forEach { (pkg, power) ->
                                batteryStatsMap[pkg] = Pair(power, 0.0)
                            }
                            if (batteryStatsMap.isNotEmpty()) {
                                Log.d(TAG, "✅ Success: Retrieved battery stats via Direct Dumpsys")
                            }
                        }
                    } else {
                        Log.d(
                                TAG,
                                "Direct Dumpsys failed or empty (Exit: $exitCode, Len: ${output.length})"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Direct dumpsys failed: ${e.message}")
                }
            } else {
                Log.d(TAG, "Skipping Method 3: DUMP permission not granted.")
            }
        }

        // -------------------------------------------------------------------------
        // METHOD 4: Shizuku Dumpsys
        // -------------------------------------------------------------------------
        if (batteryStatsMap.isEmpty()) {
            if (isUsingShizuku()) {
                try {
                    ensureUserServiceBound()
                    val dumpOutput =
                            userService?.runShellCommandWithOutput("dumpsys batterystats --checkin")
                    if (!dumpOutput.isNullOrEmpty()) {
                        parseDumpsysBatteryStats(dumpOutput).forEach { (pkg, power) ->
                            batteryStatsMap[pkg] = Pair(power, 0.0)
                        }
                        if (batteryStatsMap.isNotEmpty())
                                Log.d(TAG, "✅ Success: Retrieved battery stats via Shizuku")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku dumpsys failed: ${e.message}")
                }
            } else if (Shell.getShell().isRoot) {
                try {
                    val result = Shell.cmd("dumpsys batterystats --checkin").exec()
                    if (result.isSuccess) {
                        val dumpOutput = result.out.joinToString("\n")
                        parseDumpsysBatteryStats(dumpOutput).forEach { (pkg, power) ->
                            batteryStatsMap[pkg] = Pair(power, 0.0)
                        }
                        if (batteryStatsMap.isNotEmpty())
                                Log.d(TAG, "✅ Success: Retrieved battery stats via Root")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Root dumpsys failed: ${e.message}")
                }
            }
        }

        // -------------------------------------------------------------------------
        // METHOD 4: FALLBACK (Heuristic / Fake Data)
        // -------------------------------------------------------------------------
        if (batteryStatsMap.isEmpty()) {
            Log.e(TAG, "⚠️⚠️⚠️ WARNING WARNING WARNING ⚠️⚠️⚠️")
            Log.e(TAG, "USING FAKE (SIMULATED) BATTERY DATA!")
            Log.e(
                    TAG,
                    "Could not retrieve real battery stats via API, Direct Dumpsys, Shizuku, or Root."
            )
            Log.e(
                    TAG,
                    "The values shown below are ESTIMATED based on foreground time + random variation."
            )
            Log.e(TAG, "⚠️⚠️⚠️ WARNING WARNING WARNING ⚠️⚠️⚠️")
        }

        // Final Fallback: Estimated heuristic
        val useFallback = batteryStatsMap.isEmpty()

        return usageStats
                .asSequence()
                .filter { it.totalTimeInForeground > 0 }
                .sortedByDescending { it.totalTimeInForeground }
                .take(15)
                .map { stats ->
                    var name = stats.packageName
                    var iconBase64: String? = null
                    try {
                        val appInfo = packageManager.getApplicationInfo(stats.packageName, 0)
                        name = packageManager.getApplicationLabel(appInfo).toString()
                        val icon = packageManager.getApplicationIcon(appInfo)
                        val bitmap = icon.toBitmap(48, 48)
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        iconBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    } catch (_: Exception) {}

                    var powerMah = 0.0
                    var drainSpeed = 0.0

                    // Calculate hours for drain speed in all cases
                    val hours = stats.totalTimeInForeground / (1000.0 * 3600.0)

                    if (batteryStatsMap.containsKey(stats.packageName)) {
                        // Use real data
                        val s = batteryStatsMap[stats.packageName]!!
                        powerMah = s.first
                        // Calculate drain speed: Total Power / Foreground Time
                        if (hours > 0.0001) {
                            drainSpeed = powerMah / hours
                        } else {
                            drainSpeed = 0.0 // Avoid division by zero or unrealistic huge speed
                        }
                    } else if (!useFallback) {
                        // Real data was found for OTHER apps, but not this one.
                        // Strict mode: Assume 0 usage for this app.
                        powerMah = 0.0
                        drainSpeed = 0.0
                    } else {
                        // ⚠️ FAKE DATA GENERATION ⚠️ (Only if useFallback == true)
                        val hours = stats.totalTimeInForeground / (1000.0 * 3600.0)

                        // Deterministic "random" variation
                        val hash = stats.packageName.hashCode()
                        val variance = (hash % 50) - 25 // -25 to +25 mA
                        var baseDrainRate = 100.0 + variance // ~100mA average
                        if (baseDrainRate < 20) baseDrainRate = 20.0

                        drainSpeed = baseDrainRate
                        powerMah = drainSpeed * hours

                        Log.w(
                                TAG,
                                "USING FAKE DATA for ${stats.packageName}: DrainRate=$drainSpeed mAh/h, EstPower=$powerMah mAh"
                        )
                    }

                    AppUsageData(
                            packageName = stats.packageName,
                            name = name,
                            usageTimeMillis = stats.totalTimeInForeground,
                            icon = iconBase64,
                            batteryPowerMah = powerMah,
                            drainSpeed = drainSpeed
                    )
                }
                .toList()
    }

    private fun handleUpdateBatterySettings(message: ProtocolMessage) {
        val settings = ProtocolHelper.extractData<BatterySettingsData>(message)
        prefs.edit()
                .apply {
                    putBoolean("batt_notify_full", settings.notifyFull)
                    putBoolean("batt_notify_low", settings.notifyLow)
                    putInt("batt_low_threshold", settings.lowThreshold)
                    putBoolean("batt_detailed_log", settings.detailedLogEnabled)
                }
                .apply()

        // Restart tracking with new settings (interval might change)
        startBatteryHistoryTracking()

        // Trigger an immediate check
        checkBatteryStateForNotification()
    }

    private var lastBatteryLevel = -1
    private var lastChargingState = false

    private fun checkBatteryStateForNotification() {
        val batteryStatus: Intent? =
                IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                    registerReceiver(null, ifilter)
                }
        if (batteryStatus != null) {
            handleBatteryChanged(batteryStatus)
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = (level * 100 / scale.toFloat()).toInt()

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)

        if (batteryPct != lastBatteryLevel || isCharging != lastChargingState) {
            lastBatteryLevel = batteryPct
            lastChargingState = isCharging

            // Notification logic
            val notifyFull = prefs.getBoolean("batt_notify_full", false)
            val notifyLow = prefs.getBoolean("batt_notify_low", false)
            val lowThreshold = prefs.getInt("batt_low_threshold", 20)

            if (notifyFull && batteryPct >= 100 && isCharging) {
                if (isConnected) {
                    sendMessage(ProtocolHelper.createBatteryAlert("FULL", batteryPct))
                }
            } else if (notifyLow && batteryPct <= lowThreshold && !isCharging) {
                if (isConnected) {
                    sendMessage(ProtocolHelper.createBatteryAlert("LOW", batteryPct))
                }
            }
        }
    }

    private fun getBatteryCapacity(): Double {
        try {
            val powerProfileClass = "com.android.internal.os.PowerProfile"
            val mPowerProfile =
                    Class.forName(powerProfileClass)
                            .getConstructor(Context::class.java)
                            .newInstance(this)
            val batteryCapacity =
                    Class.forName(powerProfileClass)
                            .getMethod("getBatteryCapacity")
                            .invoke(mPowerProfile) as
                            Double
            if (batteryCapacity > 0) return batteryCapacity
        } catch (e: Exception) {
            Log.w(TAG, "Error getting capacity via PowerProfile: ${e.message}")
        }

        try {
            val id = resources.getIdentifier("config_batteryCapacity", "dimen", "android")
            if (id > 0) {
                val capacity = resources.getDimension(id).toDouble()
                if (capacity > 0) return capacity
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting capacity via dimensions: ${e.message}")
        }

        return 0.0
    }

    private fun handleIncomingCall(message: ProtocolMessage) {
        val number = ProtocolHelper.extractStringField(message, "number") ?: "Unknown"
        val callerId = ProtocolHelper.extractStringField(message, "callerId")

        Log.d(TAG, "Incoming call: $number ($callerId)")

        val intent =
                Intent(this, CallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("number", number)
                    putExtra("callerId", callerId)
                }
        startActivity(intent)
    }

    private fun handleCallStateChanged(message: ProtocolMessage) {
        val state = ProtocolHelper.extractStringField(message, "state")
        Log.d(TAG, "Call state changed: $state")

        // Notify CallActivity to close if it's idle
        val intent =
                Intent(this, CallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("state", state)
                }
        startActivity(intent)
    }

    private fun parseDumpsysBatteryStats(output: String): Map<String, Double> {
        val stats = mutableMapOf<String, Double>()
        // Map uid -> cpu time
        val uidCpuTime = mutableMapOf<Int, Long>()

        output.lines().forEach { line ->
            // Format: 9,0,l,cpu,user_ms,system_ms,power_ma (fake?)
            // Real Format from checkin: 9,uid,l,cpu,user_ms,system_ms,0
            // Example: 9,10383,l,cpu,3468,1776,0

            val parts = line.split(",")
            if (parts.size >= 7 && parts[2] == "l" && parts[3] == "cpu") {
                try {
                    val uid = parts[1].toInt()
                    val userMs = parts[4].toLong()
                    val systemMs = parts[5].toLong()
                    uidCpuTime[uid] = userMs + systemMs
                } catch (_: Exception) {}
            }
        }

        if (uidCpuTime.isEmpty()) return emptyMap()

        // Estimate power from CPU time
        // Assumption: Active CPU consumes ~100-200mA average. Let's pick 150mA.
        // Power (mAh) = (Time (ms) / 3600000) * 150 mA
        val AVG_CPU_mA = 150.0

        uidCpuTime.forEach { (uid, timeMs) ->
            val powerMah = (timeMs / 3600000.0) * AVG_CPU_mA
            val pkgName = packageManager.getNameForUid(uid)
            if (pkgName != null && powerMah > 0.0) {
                stats[pkgName] = powerMah
            }
        }

        return stats
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
                        addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
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
        sendBroadcast(Intent(ACTION_STOP_MIRROR))
    }

    private fun handleMirrorData(message: ProtocolMessage) {
        val data = ProtocolHelper.extractData<MirrorData>(message)
        Log.d(TAG, "Received mirror frame: size=${data.data.length}B, keyframe=${data.isKeyFrame}")
        val intent = Intent(ACTION_SCREEN_DATA_RECEIVED)
        intent.putExtra("data", data.data)
        intent.putExtra("isKeyFrame", data.isKeyFrame)
        sendBroadcast(intent)
    }

    private fun handleRemoteInput(message: ProtocolMessage) {
        val data = ProtocolHelper.extractData<RemoteInputData>(message)
        Log.d(
                TAG,
                "Forwarding touch event to accessibility service: action=${data.action}, x=${data.x}, y=${data.y}"
        )

        val intent = Intent("com.ailife.rtosifycompanion.DISPATCH_REMOTE_INPUT")
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
                        // Calculate aspect ratio matching (Watch mode: expand short axis)
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

                        // For watch, we "expand the axis that is short"
                        // If current aspect is wider than target aspect, height is relatively short
                        // -> expand height
                        if (physW.toFloat() / physH.toFloat() > targetAspect) {
                            // Expand height: H' = W / targetAspect
                            targetW = physW
                            targetH = (physW / targetAspect).toInt()
                        } else {
                            // Expand width: W' = H * targetAspect
                            targetW = (physH * targetAspect).toInt()
                            targetH = physH
                        }
                        targetD = metrics.densityDpi
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
                    val refreshIntent = Intent("com.ailife.rtosifycompanion.REFRESH_MIRROR")
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

        val intent = Intent("com.ailife.rtosifycompanion.MIRROR_RES_CHANGE")
        intent.setPackage(packageName)
        intent.putExtra("width", data.width)
        intent.putExtra("height", data.height)
        intent.putExtra("density", data.density)
        sendBroadcast(intent)
    }

    private fun startDynamicIslandService() {
        try {
            // Check for overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "Cannot start DynamicIslandService: Overlay permission not granted")
                    return
                }
            }

            if (!DynamicIslandService.isRunning) {
                val intent = Intent(this, DynamicIslandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Log.d(TAG, "Started DynamicIslandService")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DynamicIslandService: ${e.message}")
        }
    }

    private fun stopDynamicIslandService() {
        try {
            if (DynamicIslandService.isRunning) {
                val intent = Intent(this, DynamicIslandService::class.java)
                stopService(intent)
                Log.d(TAG, "Stopped DynamicIslandService")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop DynamicIslandService: ${e.message}")
        }
    }

    // Terminal / Shell Command Handlers
    private fun handleExecuteShellCommand(message: ProtocolMessage) {
        val request = ProtocolHelper.extractData<ShellCommandRequest>(message)

        // Check if already running a command for this session
        if (shellCommandJobs.containsKey(request.sessionId)) {
            Log.w(TAG, "Command already running for session ${request.sessionId}")
            return
        }

        Log.d(TAG, "Executing shell command: ${request.command}")

        // Initialize sequence counter for this session
        shellCommandSequences[request.sessionId] = 0

        // Launch command execution in a coroutine
        val job = serviceScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            
            // Determine execution mode and target UID
            val hasRoot = Shell.getShell().isRoot
            val hasShizuku = try { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Exception) { false }
            
            val execPermLevel = when {
                hasRoot -> "root"
                hasShizuku -> "shizuku"
                else -> "app"
            }
            
            val execUid = when (execPermLevel) {
                "root" -> 0
                "shizuku" -> 2000
                else -> android.os.Process.myUid()
            }

            fun getNextSeq(): Int {
                synchronized(shellCommandSequences) {
                    val seq = shellCommandSequences[request.sessionId] ?: 0
                    shellCommandSequences[request.sessionId] = seq + 1
                    return seq
                }
            }

            try {
                if (execPermLevel == "shizuku" && userService != null) {
                    // Use UserService for Shizuku (handles privileged execution)
                    // Note: This is one-shot, but we'll simulate streaming for the phone
                    val responseJson = userService?.executeShellCommandFull(request.command)
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    if (responseJson != null) {
                        try {
                            val json = JSONObject(responseJson)
                            val stdout = json.optString("stdout", "")
                            val stderr = json.optString("stderr", "")
                            val exitCode = json.optInt("exitCode", 0)
                            
                            // Send stdout lines
                            if (stdout.isNotEmpty()) {
                                stdout.split("\n").forEach { line ->
                                    if (!isActive) return@forEach
                                    sendMessage(ProtocolHelper.createShellCommandResponse(
                                        ShellCommandResponse(
                                            sessionId = request.sessionId,
                                            stdout = line,
                                            uid = execUid,
                                            permissionLevel = execPermLevel,
                                            isStreaming = true,
                                            sequenceNumber = getNextSeq()
                                        )
                                    ))
                                }
                            }
                            
                            // Send stderr lines
                            if (stderr.isNotEmpty()) {
                                stderr.split("\n").forEach { line ->
                                    if (!isActive) return@forEach
                                    sendMessage(ProtocolHelper.createShellCommandResponse(
                                        ShellCommandResponse(
                                            sessionId = request.sessionId,
                                            stderr = line,
                                            uid = execUid,
                                            permissionLevel = execPermLevel,
                                            isStreaming = true,
                                            sequenceNumber = getNextSeq()
                                        )
                                    ))
                                }
                            }
                            
                            // Send completion
                            sendMessage(ProtocolHelper.createShellCommandResponse(
                                ShellCommandResponse(
                                    sessionId = request.sessionId,
                                    exitCode = exitCode,
                                    executionTimeMs = executionTime,
                                    uid = execUid,
                                    permissionLevel = execPermLevel,
                                    isComplete = true,
                                    sequenceNumber = getNextSeq()
                                )
                            ))
                        } catch (e: Exception) {
                            throw e
                        }
                    } else {
                        throw Exception("Shizuku UserService returned null")
                    }
                } else {
                    // Execute command and stream output using the best possible shell
                    val process: Process = if (execPermLevel == "root") {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", request.command))
                    } else {
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", request.command))
                    }
                    
                    runningShellProcesses[request.sessionId] = process

                    // Read stdout and stderr in parallel
                    val stdoutReader = process.inputStream.bufferedReader()
                    val stderrReader = process.errorStream.bufferedReader()

                    // Stream output line by line - launch and track the jobs
                    val stdoutJob = launch {
                        try {
                            stdoutReader.useLines { lines ->
                                lines.forEach { line ->
                                    if (!isActive) return@forEach
                                    sendMessage(ProtocolHelper.createShellCommandResponse(
                                        ShellCommandResponse(
                                            sessionId = request.sessionId,
                                            stdout = line,
                                            uid = execUid,
                                            permissionLevel = execPermLevel,
                                            isStreaming = true,
                                            sequenceNumber = getNextSeq()
                                        )
                                    ))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Stdout reader error: ${e.message}")
                        }
                    }

                    // Stream stderr
                    val stderrJob = launch {
                        try {
                            stderrReader.useLines { lines ->
                                lines.forEach { line ->
                                    if (!isActive) return@forEach
                                    sendMessage(ProtocolHelper.createShellCommandResponse(
                                        ShellCommandResponse(
                                            sessionId = request.sessionId,
                                            stderr = line,
                                            uid = execUid,
                                            permissionLevel = execPermLevel,
                                            isStreaming = true,
                                            sequenceNumber = getNextSeq()
                                        )
                                    ))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Stderr reader error: ${e.message}")
                        }
                    }

                    // Wait for process to complete
                    val exitCode = process.waitFor()
                    val executionTime = System.currentTimeMillis() - startTime

                    // CRITICAL: Wait for all output to be sent before sending completion
                    stdoutJob.join()
                    stderrJob.join()

                    // Now send final response - this will have the highest sequence number
                    sendMessage(ProtocolHelper.createShellCommandResponse(
                        ShellCommandResponse(
                            sessionId = request.sessionId,
                            exitCode = exitCode,
                            executionTimeMs = executionTime,
                            uid = execUid,
                            permissionLevel = execPermLevel,
                            isComplete = true,
                            sequenceNumber = getNextSeq()
                        )
                    ))
                }

                Log.d(TAG, "Command completed (UID: $execUid)")
            } catch (e: Exception) {
                Log.e(TAG, "Error executing command: ${e.message}", e)
                sendMessage(ProtocolHelper.createShellCommandResponse(
                    ShellCommandResponse(
                        sessionId = request.sessionId,
                        exitCode = -1,
                        stderr = e.message ?: "Unknown error",
                        uid = execUid,
                        permissionLevel = execPermLevel,
                        isComplete = true,
                        sequenceNumber = getNextSeq()
                    )
                ))
            } finally {
                runningShellProcesses.remove(request.sessionId)
                shellCommandJobs.remove(request.sessionId)
                shellCommandSequences.remove(request.sessionId)
            }
        }

        shellCommandJobs[request.sessionId] = job
    }

    private fun handleCancelShellCommand(message: ProtocolMessage) {
        val request = ProtocolHelper.extractData<CancelShellCommandRequest>(message)
        Log.d(TAG, "Canceling shell command: ${request.sessionId}")

        // Kill the process
        runningShellProcesses[request.sessionId]?.destroy()

        // Cancel the job
        shellCommandJobs[request.sessionId]?.cancel()

        // Clean up
        runningShellProcesses.remove(request.sessionId)
        shellCommandJobs.remove(request.sessionId)
    }

    private fun handleRequestPermissionInfo() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Handling permission info request")
                ensureUserServiceBound()

                val infoJson = userService?.getPermissionInfo() ?: run {
                    // Fallback to detect locally
                    val currentUid = android.os.Process.myUid()
                    val hasRoot = Shell.getShell().isRoot

                    ProtocolHelper.gson.toJson(mapOf(
                        "level" to if (hasRoot) "root" else "app",
                        "uid" to currentUid,
                        "hasShizuku" to false,
                        "hasRoot" to hasRoot
                    ))
                }

                val infoMap = ProtocolHelper.gson.fromJson(infoJson,
                    object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type) as Map<String, Any>

                val info = PermissionInfoData(
                    level = infoMap["level"] as String,
                    uid = (infoMap["uid"] as Double).toInt(),
                    hasShizuku = infoMap["hasShizuku"] as Boolean,
                    hasRoot = infoMap["hasRoot"] as Boolean
                )

                sendMessage(ProtocolHelper.createPermissionInfoResponse(info))
                Log.d(TAG, "Permission info sent: level=${info.level}, uid=${info.uid}")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting permission info: ${e.message}", e)
            }
        }
    }
}
