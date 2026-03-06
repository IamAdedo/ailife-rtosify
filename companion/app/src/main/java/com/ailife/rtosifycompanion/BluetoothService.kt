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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import android.os.Bundle
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
import com.ailife.rtosifycompanion.widget.MediaWidget
import com.ailife.rtosifycompanion.widget.DashboardWidget
import android.appwidget.AppWidgetManager
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
import org.json.JSONObject
import rikka.shizuku.Shizuku
import com.ailife.rtosifycompanion.utils.DeviceActionManager
import com.ailife.rtosifycompanion.servicehelper.BatteryAppUsageHandler

class BluetoothService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var batteryAppUsageHandler: BatteryAppUsageHandler


    private var serverJob: Job? = null
    private var statusUpdateJob: Job? = null
    private lateinit var healthDataCollector: HealthDataCollector
    private var liveMeasurementJob: Job? = null

    // Shell command tracking
    private val runningShellProcesses = mutableMapOf<String, Process>()
    private val shellCommandJobs = mutableMapOf<String, Job>()
    private val shellCommandSequences = mutableMapOf<String, Int>() // Track sequence per session
    private var deviceInfoUpdateJob: Job? = null
    private lateinit var deviceInfoManager: DeviceInfoManager
    private lateinit var watchAlarmManager: WatchAlarmManager
    private lateinit var fileManager: FileManager

    // Device Action Manager
    private lateinit var deviceActionManager: DeviceActionManager


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
    @Volatile private var isTransferCancelled: Boolean = false

    // File Reception Variables
    private var receiveFile: File? = null
    private var receiveFileOutputStream: FileOutputStream? = null
    private var receiveTotalSize: Long = 0
    private var receiveBytesRead: Long = 0

    private val HEARTBEAT_INTERVAL = 20000L
    private val CONNECTION_TIMEOUT = 60000L

    private val notificationMap = ConcurrentHashMap<String, Int>()
    private val notificationDataMap = ConcurrentHashMap<String, NotificationData>()
    
    // Track previous connection state to ensure valid disconnect handling even after transport clears
    private var lastConnectedState = false

    // REFACTORING: More robust notification control
    @Volatile private var currentNotificationStatus: String = ""

    // Widget Data Cache
    private var lastPhoneBattery = -1
    private var lastRingerMode = 2 // Default to NORMAL (2)

    // ANCS Client for iOS notifications
    private var ancsClient: AncsClient? = null
    private var isIosConnected = false

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
            Log.i(TAG, "Force Bluetooth Enabled is active. Triggering enforcement...")
            deviceActionManager.setBluetoothEnabled(true)
        }
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    // Audio notification playback
    private var audioNotifPlayer: android.media.MediaPlayer? = null
    private var audioNotifCurrentKey: String? = null
    private var audioNotifCurrentNotifId: Int = 0
    private var audioNotifIsPlaying: Boolean = false
    private val audioProgressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var audioProgressRunnable: Runnable? = null

    // Track audio notification states: key -> state (downloading, playing, paused)
    private data class AudioNotifState(
        var state: String = "idle", // idle, downloading, playing, paused
        var localPath: String? = null,
        var downloadProgress: Int = 0,
        var playbackProgress: Int = 0,
        var duration: Int = 0
    )
    private val audioNotifStates = mutableMapOf<String, AudioNotifState>()

    private var panManager: BluetoothPanManager? = null
    private var currentDevice: BluetoothDevice? = null

    @Volatile private var isStopping = false
    private var ringtonePlayer: android.media.MediaPlayer? = null
    private var currentRingtone: android.media.Ringtone? = null

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
                ipAddress: String? = null,
                wifiState: String? = null
        ) {}
        fun onTransportStatusChanged(status: com.ailife.rtosifycompanion.communication.TransportManager.TransportStatus) {}
        fun onPhoneBatteryUpdated(battery: Int, isCharging: Boolean) {}
    }

    var callback: ServiceCallback? = null

    @Volatile var currentStatus: String = "Disconnected"
    @Volatile var currentDeviceName: String? = null
    @Volatile var currentTransportType: String = ""
    val isConnected: Boolean get() = if (::transportManager.isInitialized) transportManager.isAnyTransportConnected() else false

    fun isLanConnected(): Boolean = if (::transportManager.isInitialized) transportManager.isLanConnected() else false

    fun getConnectedDeviceMac(): String? {
        val state = transportManager.connectionState.value
        return if (state is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected) {
            state.deviceMac
        } else {
            null
        }
    }

    fun getConnectionStatusString(): String {
        return transportManager.getConnectionStatusString()
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
        const val ACTION_DISMISS_FROM_FULL_SCREEN = "com.ailife.rtosifycompanion.DISMISS_FROM_FULL_SCREEN"
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

        const val MIRRORED_CHANNEL_ID = "mirrored_notifications_v2"

        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_LITE_MODE_UPDATE = "com.ailife.rtosifycompanion.ACTION_LITE_MODE_UPDATE"
        const val ACTION_RESTART_BLE_ADVERTISING = "com.ailife.rtosifycompanion.ACTION_RESTART_BLE_ADVERTISING"
        const val ACTION_IOS_CONNECTED = "com.ailife.rtosifycompanion.ACTION_IOS_CONNECTED"
        const val ACTION_FILE_DETECTED = "com.ailife.rtosifycompanion.ACTION_FILE_DETECTED"
        const val ACTION_FILE_DOWNLOAD_COMPLETE = "com.ailife.rtosifycompanion.ACTION_FILE_DOWNLOAD_COMPLETE"
        const val ACTION_FILE_DOWNLOAD_PROGRESS = "com.ailife.rtosifycompanion.ACTION_FILE_DOWNLOAD_PROGRESS"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_SUCCESS = "extra_success"
        const val EXTRA_PROGRESS = "extra_progress"

        // Audio notification playback actions
        const val ACTION_AUDIO_DOWNLOAD = "com.ailife.rtosifycompanion.ACTION_AUDIO_DOWNLOAD"
        const val ACTION_AUDIO_PLAY_PAUSE = "com.ailife.rtosifycompanion.ACTION_AUDIO_PLAY_PAUSE"
        const val ACTION_AUDIO_STOP = "com.ailife.rtosifycompanion.ACTION_AUDIO_STOP"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
        const val EXTRA_FILE_KEY = "extra_file_key"

        const val ACTION_STOP_RINGTONE = "com.ailife.rtosifycompanion.ACTION_STOP_RINGTONE"
        const val ACTION_REQUEST_WATCH_RINGTONE_PICKER = "com.ailife.rtosifycompanion.ACTION_REQUEST_WATCH_RINGTONE_PICKER"
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
                        "com.ailife.rtosifycompanion.CALL_ACTION" -> {
                            val action = intent.getStringExtra("action")
                            Log.d(TAG, "Handling Dynamic Island call action: $action")
                            if (action == "answer") {
                                stopRingtone()
                                sendMessage(ProtocolMessage(type = MessageType.ANSWER_CALL))
                            } else if (action == "reject") {
                                stopRingtone()
                                sendMessage(ProtocolMessage(type = MessageType.REJECT_CALL))
                            }
                        }
                        ACTION_STOP_RINGTONE -> {
                            Log.d(TAG, "Stopping ringtone via broadcast")
                            stopRingtone()
                        }
                        "com.ailife.rtosifycompanion.ALARM_ACTION" -> {
                            val action = intent.getStringExtra("action")
                            Log.d(TAG, "Handling Dynamic Island alarm action: $action")
                            
                            val alarmIntent = Intent()
                            if (action == "snooze") {
                                alarmIntent.action = "com.ailife.rtosifycompanion.SNOOZE_ALARM"
                            } else {
                                alarmIntent.action = "com.ailife.rtosifycompanion.DISMISS_ALARM"
                            }
                            
                            alarmIntent.setPackage(packageName)
                            sendBroadcast(alarmIntent)
                        }
                        "com.ailife.rtosifycompanion.MEDIA_ACTION" -> {
                            val action = intent.getStringExtra("action")
                            Log.d(TAG, "Handling Dynamic Island media action: $action")
                            if (action == "play") {
                                sendMediaCommand(MediaControlData.CMD_PLAY)
                            } else if (action == "pause") {
                                sendMediaCommand(MediaControlData.CMD_PAUSE)
                            } else if (action == "prev") {
                                sendMediaCommand(MediaControlData.CMD_PREVIOUS)
                            } else if (action == "next") {
                                sendMediaCommand(MediaControlData.CMD_NEXT)
                            } else if (action == "vol_up") {
                                sendMediaCommand(MediaControlData.CMD_VOL_UP)
                            } else if (action == "vol_down") {
                                sendMediaCommand(MediaControlData.CMD_VOL_DOWN)
                            }
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
                        MediaWidget.ACTION_CMD_PLAY_PAUSE -> sendMediaCommand(MediaControlData.CMD_PLAY_PAUSE)
                        MediaWidget.ACTION_CMD_NEXT -> sendMediaCommand(MediaControlData.CMD_NEXT)
                        MediaWidget.ACTION_CMD_PREV -> sendMediaCommand(MediaControlData.CMD_PREVIOUS)
                        MediaWidget.ACTION_CMD_VOL_UP -> sendMediaCommand(MediaControlData.CMD_VOL_UP)
                        MediaWidget.ACTION_CMD_VOL_DOWN -> sendMediaCommand(MediaControlData.CMD_VOL_DOWN)
                        MediaWidget.ACTION_MEDIA_UPDATE -> {
                            if (isConnected) {
                                sendMessage(ProtocolMessage(type = MessageType.REQUEST_MEDIA_STATE))
                            }
                        }
                        DashboardWidget.ACTION_CMD_CYCLE_RINGER -> {
                            val nextMode = when (lastRingerMode) {
                                2 -> 1 // Normal -> Vibrate
                                1 -> 0 // Vibrate -> Silent
                                else -> 2 // Silent (0) or other -> Normal
                            }
                            if (isConnected) {
                                sendMessage(ProtocolHelper.createSetRingerMode(nextMode))
                            }
                        }
                        DashboardWidget.ACTION_REQUEST_DASHBOARD_UPDATE -> {
                            if (isConnected) {
                                requestPhoneBattery()
                                Log.d(TAG, "onStartCommand: Requested phone battery from widget")
                            }
                            // Also update immediately with whatever local data we have
                            updateDashboardWidget()
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
                        val connected = isConnected
                        Log.d(TAG, "Dynamic Island requested connection state: $connected")
                        val response = Intent(ACTION_CONNECTION_STATE_CHANGED)
                        response.putExtra("connected", connected)
                        if (::transportManager.isInitialized) {
                            response.putExtra("transport", transportManager.status.value.typeString)
                        }
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
                        Log.d(TAG, "ACL Disconnected for $device. Cleaning up Bluetooth transport.")
                        // Only force disconnect Bluetooth, letting other transports (LAN/Internet) survive if they are active
                        if (::transportManager.isInitialized) {
                            transportManager.forceDisconnectBluetooth()
                        }
                    }
                }
            }


    // Receiver for audio notification playback controls (doesn't require connection)
    private val audioNotificationReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.d(TAG, "audioNotificationReceiver.onReceive: action=${intent?.action}")
                    val fileKey = intent?.getStringExtra(EXTRA_FILE_KEY)
                    val notifId = intent?.getIntExtra(EXTRA_NOTIF_ID, 0) ?: 0
                    
                    Log.d(TAG, "Audio action received: action=${intent?.action}, fileKey=$fileKey, notifId=$notifId")

                    if (fileKey == null) {
                        Log.w(TAG, "Audio action missing fileKey")
                        return
                    }

                    when (intent.action) {
                        ACTION_AUDIO_DOWNLOAD -> {
                            Log.d(TAG, "Handling audio download for $fileKey")
                            handleAudioDownload(fileKey, notifId)
                        }
                        ACTION_AUDIO_PLAY_PAUSE -> {
                            Log.d(TAG, "Handling audio play/pause for $fileKey")
                            handleAudioPlayPause(fileKey, notifId)
                        }
                        ACTION_AUDIO_STOP -> {
                            Log.d(TAG, "Handling audio stop for $fileKey")
                            handleAudioStop(fileKey, notifId)
                        }
                        else -> {
                            Log.w(TAG, "Unknown audio action: ${intent.action}")
                        }
                    }
                }
            }

    private val localBatteryReceiver = object : BroadcastReceiver() {
        private var lastLevel = -1
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level != -1 && scale != -1) {
                    val batteryPct = (level * 100) / scale
                    if (batteryPct != lastLevel) {
                        lastLevel = batteryPct
                        Log.d(TAG, "Local watch battery changed to $batteryPct%, updating dashboard widget")
                        updateDashboardWidget()
                    }
                }
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
    var userService: IUserService? = null

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

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun shizukuAvailable(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }



    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        NotificationLogManager.init(this)
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        deviceActionManager = com.ailife.rtosifycompanion.utils.DeviceActionManager(this, userService) {
            bindUserServiceIfNeeded()
        }
        
        batteryAppUsageHandler = BatteryAppUsageHandler(
            context = this,
            serviceScope = serviceScope,
            prefs = prefs,
            sendMessage = { msg -> sendMessage(msg) },
            isConnected = { isConnected },
            isUsingShizuku = { isUsingShizuku() },
            ensureUserServiceBound = { ensureUserServiceBound() },
            getUserService = { userService }
        )
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        // bluetoothAdapter assignment removed (property removed)
        currentStatus = getString(R.string.status_disconnected)
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
        
        // Load saved internet settings into TransportManager
        notifyInternetSettingsChanged()

        // Restore active watchdogs if we have paired device info
        val savedWatchMac = prefs.getString("wifi_advertised_mac", null)
        // Restore active watchdogs. Prefer paired phone MAC (Identity Address) over last connected MAC (which might be random/BLE)
        val savedPhoneMac = prefs.getString("paired_phone_mac", null) ?: prefs.getString("last_phone_mac", null)

        if (savedWatchMac != null && savedPhoneMac != null) {
            Log.i(TAG, "Restoring Transport Watchdogs for Phone: $savedPhoneMac")
            transportManager.startWifiServerWatchdog(this, android.os.Build.MODEL, savedWatchMac, savedPhoneMac)
            transportManager.startInternetMonitorWatchdog(android.os.Build.MODEL, savedWatchMac, savedPhoneMac)
        } else {
            Log.w(TAG, "Not restoring Transport Watchdogs - missing MACs (watch=$savedWatchMac, phone=$savedPhoneMac)")
        }

        // Load saved WiFi rule into TransportManager
        val savedWifiRule = prefs.getInt("wifi_activation_rule", 0)
        transportManager.updateWifiRule(savedWifiRule)
        Log.d(TAG, "Loaded WiFi activation rule from prefs: $savedWifiRule")

        // Initialize FileManager
        fileManager = FileManager(this) { 
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
        }

        serviceScope.launch {
            transportManager.incomingMessages.collect { message ->
                lastMessageTime = System.currentTimeMillis()
                processReceivedMessage(message)
            }
        }

        serviceScope.launch {
            transportManager.status.collect { status ->
                val state = status.state
                serviceScope.launch(Dispatchers.Main) { callback?.onTransportStatusChanged(status) }
                when (state) {
                    is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected -> {
                        val deviceName = status.deviceName ?: getString(R.string.device_name_default)
                        handleDeviceConnected(deviceName, status.deviceMac, status.typeString)
                    }
                    is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Disconnected -> {
                        handleDeviceDisconnected()
                    }
                    is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Waiting -> {
                         // If we were connected, Waiting means we just lost it
                         if (isConnected || lastConnectedState) {
                             Log.d(TAG, "TransportManager status updated to Waiting - treating as disconnection")
                             handleDeviceDisconnected()
                         } else {
                             updateStatus(getString(R.string.status_waiting))
                         }
                    }
                    is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connecting -> {
                        // If we were connected and switch to Connecting, it means we lost the active link
                        if (isConnected || lastConnectedState) {
                             Log.d(TAG, "TransportManager transitioned to Connecting state - treating as disconnection")
                             handleDeviceDisconnected()
                        } else {
                             updateStatus(getString(R.string.status_connecting))
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
                    addAction(MediaWidget.ACTION_CMD_PLAY_PAUSE)
                    addAction(MediaWidget.ACTION_CMD_NEXT)
                    addAction(MediaWidget.ACTION_CMD_PREV)
                    addAction(MediaWidget.ACTION_CMD_VOL_UP)
                    addAction(MediaWidget.ACTION_CMD_VOL_DOWN)
                    addAction(DashboardWidget.ACTION_CMD_CYCLE_RINGER)
                    addAction("com.ailife.rtosifycompanion.CALL_ACTION")
                    addAction(ACTION_STOP_RINGTONE)
                    addAction("com.ailife.rtosifycompanion.ALARM_ACTION")
                    addAction("com.ailife.rtosifycompanion.MEDIA_ACTION")
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
            ContextCompat.registerReceiver(
                    this,
                    batteryAppUsageHandler.batteryReceiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                    this,
                    audioNotificationReceiver,
                    IntentFilter().apply {
                        addAction(ACTION_AUDIO_DOWNLOAD)
                        addAction(ACTION_AUDIO_PLAY_PAUSE)
                        addAction(ACTION_AUDIO_STOP)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                    this,
                    localBatteryReceiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(internalReceiver, filterInternal)
            registerReceiver(watchDismissReceiver, filterWatch)
            registerReceiver(wifiStateReceiver, filterWifi)
            registerReceiver(dynamicIslandHandshakeReceiver, filterHandshake)
            registerReceiver(aclDisconnectReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED))
            registerReceiver(batteryAppUsageHandler.batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registerReceiver(audioNotificationReceiver, IntentFilter().apply {
                addAction(ACTION_AUDIO_DOWNLOAD)
                addAction(ACTION_AUDIO_PLAY_PAUSE)
                addAction(ACTION_AUDIO_STOP)
            })
            registerReceiver(localBatteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }

        // Initialize health data collector
        healthDataCollector = HealthDataCollector(this)

        // Initialize PAN manager
        panManager = BluetoothPanManager(this)

        // Start battery history tracking
        batteryAppUsageHandler.startBatteryHistoryTracking()

        deviceInfoManager = DeviceInfoManager(this)

        // Initialize alarm manager
        watchAlarmManager = WatchAlarmManager(this)

        Log.d(TAG, "BluetoothService created")
        
        if (prefs.getBoolean("force_bt_enabled", false)) {
            startBluetoothEnforcement()
        }

        // Bind to Shizuku UserService if available
        bindUserServiceIfNeeded()

        // Register direct frame callback for Mirroring
        MirroringService.frameCallback = { data, isKeyFrame ->
            if (isConnected) {
                // Use runBlocking to enforce backpressure.
                // This blocks the encoding thread until the frame is sent or dropped.
                try {
                    kotlinx.coroutines.runBlocking {
                        transportManager.send(ProtocolHelper.createMirrorData(data, isKeyFrame))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending mirror frame", e)
                }
            }
        }

        MirroringService.onResolutionChange = { w, h, d ->
            if (isConnected) {
                Log.d(TAG, "Mirroring resolution changed to ${w}x${h}, notifying phone in 300ms")
                serviceScope.launch {
                    kotlinx.coroutines.delay(300)
                    sendMessage(ProtocolHelper.createMirrorResChange(w, h, d))
                }
            }
        }
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

        if (intent?.action == ACTION_RESTART_BLE_ADVERTISING) {
            Log.i(TAG, "Restarting BLE advertising as requested")
            serviceScope.launch {
                transportManager.stopBleServer()
                delay(500)
                val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                transportManager.startBleServerWatchdog(adapter)
            }
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

        if (intent?.action == DashboardWidget.ACTION_CMD_CYCLE_RINGER) {
            updateForegroundNotification() // Ensure startForeground is called
            val nextMode = when (lastRingerMode) {
                2 -> 1 // Normal -> Vibrate
                1 -> 0 // Vibrate -> Silent
                else -> 2 // Silent (0) or other -> Normal
            }
            if (isConnected) {
                sendMessage(ProtocolHelper.createSetRingerMode(nextMode))
                Log.d(TAG, "Cycled ringer mode via onStartCommand to $nextMode")
            }
            return START_NOT_STICKY
        }

        if (intent?.action == DashboardWidget.ACTION_REQUEST_DASHBOARD_UPDATE) {
            updateForegroundNotification() // Ensure startForeground is called
            Log.d(TAG, "onStartCommand: Received Dashboard update request")
            if (isConnected) {
                requestPhoneBattery()
            }
            updateDashboardWidget()
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
        if (intent?.action == "com.ailife.rtosifycompanion.ACTION_RINGTONE_SELECTED") {
            val uri = intent.getStringExtra("uri") ?: ""
            val name = intent.getStringExtra("name") ?: "Default"
            handleRingtoneSelected(uri, name)
        }

        return START_STICKY
    }

    private fun handleRingtoneSelected(uri: String, name: String) {
        serviceScope.launch {
            sendMessage(ProtocolHelper.createRingtonePickerResponse(uri, name))
        }
        // Also update local prefs for consistency
        prefs.edit().apply {
            putString("notification_sound_uri", uri)
            putString("notification_sound_name", name)
            apply()
        }
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
        
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        val deviceName = adapter?.name ?: Build.MODEL

        val watchMac = prefs.getString("wifi_advertised_mac", null)
        val lastPhoneMac = prefs.getString("last_phone_mac", null)

        if (watchMac != null) {
            Log.d(TAG, "Starting WiFi server for local=$watchMac")
            // CRITICAL FIX: Always use 'paired_phone_mac' if available. 
            // 'lastPhoneMac' might be a BLE address if we just connected via BLE, which mismatches the encryption key.
            
            val lastPhoneMac = prefs.getString("last_phone_mac", null)
            val remoteMac = prefs.getString("paired_phone_mac", null) ?: lastPhoneMac ?: ""
            
            // WiFi Server
            transportManager.startWifiServer(deviceName, watchMac, remoteMac)
            
            // Start WiFi watchdog if we have a remote target
            if (remoteMac.isNotEmpty()) {
                transportManager.startWifiServerWatchdog(this, deviceName, watchMac, remoteMac)
                
                val internetUrl = prefs.getString("internet_signaling_url", "http://signaling.rtosify.ai-life.xyz:8080") ?: "http://signaling.rtosify.ai-life.xyz:8080"
            val internetRule = prefs.getInt("internet_activation_rule", 0)
                transportManager.updateInternetSettings(internetRule, internetUrl)
                transportManager.startInternetMonitorWatchdog(deviceName, watchMac, remoteMac)
            }
        }

        // Start BOTH Bluetooth Classic and BLE servers
        // The phone will decide which one to connect to based on its configuration
        transportManager.startBluetoothServer(adapter)
        transportManager.startBluetoothServerWatchdog(adapter)
        
        transportManager.startBleServer(adapter)
        transportManager.startBleServerWatchdog(adapter)
        
        Log.i(TAG, "Started both Bluetooth Classic (RFCOMM) and BLE servers - phone will choose transport")
    }

    private fun handleDeviceConnected(deviceName: String, mac: String?, transportType: String = "") {
        if (isConnected && currentTransportType == transportType) return 
        
        lastConnectedState = true
        
        // No manual assignment needed
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
             // Save phone MAC for future reconnection
             if (mac != null) {
                 val oldMac = prefs.getString("last_phone_mac", null)
                 if (mac != oldMac) {
                     prefs.edit().putString("last_phone_mac", mac).apply()
                 }
                 
                 // Update watchdogs with the real phone MAC if it was the same as watchMac before
                 val watchMac = prefs.getString("wifi_advertised_mac", null)
                 if (watchMac != null) {
                     Log.i(TAG, "Updating WiFi/Internet watchdogs for Phone MAC: $mac")
                     transportManager.startWifiServerWatchdog(this@BluetoothService, Build.MODEL, watchMac, mac)
                     transportManager.startInternetMonitorWatchdog(Build.MODEL, watchMac, mac)
                 }
             }

             // Sync phone settings on connect
             sendMessage(ProtocolMessage(type = MessageType.REQUEST_PHONE_SETTINGS))

             Log.d(TAG, "Device fully connected, synced phone settings")
             // Trigger automation on connection
             onConnectionEstablished(transportType)
             
             // MAC Discovery: Tell the phone its real MAC address
             if (mac != null) {
                 Log.d(TAG, "Sending discovered local MAC to phone: $mac")
                 sendMessage(ProtocolHelper.createSyncMac(mac))
             }
             // Initialize encryption if MAC is available
             // CRITICAL FIX: Always use the paired phone MAC (Classic) for encryption, 
             // because BLE MACs are random (RPA) and will cause "No keyset" errors.
             val stableMac = prefs.getString("paired_phone_mac", null) ?: mac
             if (stableMac != null) {
                 Log.d(TAG, "Initializing encryption using stable MAC: $stableMac (Connected MAC was: $mac)")
                 initializeEncryptionForDevice(stableMac)
                 // Also ensure WiFi server is using this stable MAC
                 val deviceName = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name ?: android.os.Build.MODEL
                 transportManager.startWifiServer(deviceName, prefs.getString("wifi_advertised_mac", "") ?: "", stableMac)
             }
        }
    }
    
    private fun handleDeviceDisconnected() {
        // Use logic-OR with lastConnectedState to catch disconnection even if isConnected is already false
        val wasConnected = isConnected || lastConnectedState
        lastConnectedState = false
        
        // Capture transport type before resetting
        val lastTransport = currentTransportType

        // Explicitly reset transport state so that reconnections of the same type are processed as "new"
        currentDeviceName = null
        currentTransportType = ""
        
        // Notify Dynamic Island - Explicitly clear transport
        Log.d(TAG, "Broadcasting disconnect state to Dynamic Island")
        sendBroadcast(
            Intent(ACTION_CONNECTION_STATE_CHANGED).apply {
                putExtra("connected", false)
                putExtra("transport", "") // Explicitly clear transport
                setPackage(packageName)
            }
        )
        
        if (wasConnected) {
            updateStatus(getString(R.string.status_disconnected))
            
            if (prefs.getBoolean("notify_on_disconnect", false)) {
                val style = prefs.getString("notification_style", "android")
                val diShowDisconnect = prefs.getBoolean("di_show_disconnect", true)
                
                // Skip android notification if using Dynamic Island and DI disconnect notification is enabled
                if (style == "dynamic_island" && diShowDisconnect) {
                    Log.d(TAG, "Dynamic Island disconnect notification is enabled, skipping standard android notification")
                } else {
                    Log.i(TAG, "Disconnection notification is enabled, showing now")
                    showDisconnectionNotification()
                }
                
                // Also log to NotificationLogManager for history
                val disconnectData = NotificationData(
                    packageName = "com.ailife.rtosify",
                    title = getString(R.string.notification_phone_disconnected_title),
                    text = getString(R.string.notification_phone_disconnected_text),
                    key = "system_disconnect_${System.currentTimeMillis()}",
                    appName = "System"
                )
                NotificationLogManager.addLog(this, disconnectData)
            } else {
                Log.d(TAG, "Disconnection notification is disabled, skipping")
            }
            
            onConnectionLost(lastTransport)
        }
        
        serviceScope.launch(Dispatchers.Main) { callback?.onDeviceDisconnected() }
        
        // Restart servers if needed (only if NOT stopping)
        if (!isStopping) {
            startWatchLogic()
        } else {
            Log.d(TAG, "Service is stopping, skipping server restart.")
        }
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
            MessageType.FIND_PHONE -> handleFindPhoneCommand(message)
            MessageType.FIND_DEVICE_LOCATION_REQUEST -> handleFindDeviceLocationRequest(message)
            MessageType.FIND_DEVICE_LOCATION_UPDATE -> handleFindDeviceLocationUpdate(message)
            MessageType.STATUS_UPDATE -> handleStatusUpdateReceived(message)
            MessageType.SET_DND -> handleSetDndCommand(message)
            MessageType.UPDATE_DND_SETTINGS -> handleUpdateDndSettings(message)
            MessageType.SET_WIFI -> handleSetWifiCommand(message)
            MessageType.REQUEST_WIFI_SCAN -> serviceScope.launch { handleRequestWifiScan() }
            MessageType.CONNECT_WIFI -> handleConnectWifi(message)
            MessageType.CAMERA_FRAME -> handleCameraFrame(message)
            MessageType.CAMERA_RECORDING_STATUS -> handleCameraRecordingStatus(message)
            MessageType.REQUEST_FILE_LIST -> handleRequestFileList(message)
            MessageType.REQUEST_FILE_DOWNLOAD -> handleRequestFileDownload(message)
            MessageType.DELETE_FILES -> handleDeleteFiles(message)
            MessageType.UPDATE_SETTINGS -> handleUpdateSettings(message)
            MessageType.UPDATE_WIFI_RULE -> handleUpdateWifiRule(message)
            MessageType.REQUEST_HEALTH_DATA -> handleRequestHealthData(message)
            MessageType.REQUEST_HEALTH_HISTORY -> handleRequestHealthHistory(message)
            MessageType.START_LIVE_MEASUREMENT -> handleStartLiveMeasurement(message)
            MessageType.STOP_LIVE_MEASUREMENT -> handleStopLiveMeasurement()
            MessageType.UPDATE_HEALTH_SETTINGS -> handleUpdateHealthSettings(message)
            MessageType.REQUEST_HEALTH_SETTINGS -> handleRequestHealthSettings()
            MessageType.SYNC_CALENDAR -> handleSyncCalendar(message)
            MessageType.SYNC_CONTACTS -> handleSyncContacts(message)
            MessageType.RENAME_FILE -> handleRenameFile(message)
            MessageType.MOVE_FILES -> handleMoveFiles(message)
            MessageType.COPY_FILES -> handleCopyFiles(message)
            MessageType.SET_WATCH_FACE -> handleSetWatchFace(message)
            MessageType.CREATE_FOLDER -> handleCreateFolder(message)
            MessageType.REQUEST_PREVIEW -> handleRequestPreview(message)
            MessageType.UNINSTALL_APP -> handleUninstallApp(message)
            MessageType.INCOMING_CALL -> handleIncomingCall(message)
            MessageType.REQUEST_RINGTONE_PICKER -> handleRequestRingtonePicker()
            MessageType.CALL_STATE_CHANGED -> handleCallStateChanged(message)
            MessageType.REQUEST_BATTERY_LIVE -> batteryAppUsageHandler.handleRequestBatteryLive()
            MessageType.REQUEST_BATTERY_STATIC -> batteryAppUsageHandler.handleRequestBatteryStatic()
            MessageType.PHONE_BATTERY_UPDATE -> handlePhoneBatteryUpdate(message)
            MessageType.REQUEST_WATCH_STATUS -> handleRequestWatchStatus()
            MessageType.REQUEST_DEVICE_INFO_UPDATE -> handleRequestDeviceInfo()
            MessageType.UPDATE_BATTERY_SETTINGS -> batteryAppUsageHandler.handleUpdateBatterySettings(message)
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
            MessageType.UPDATE_INTERNET_SETTINGS -> handleUpdateInternetSettings(message)
            MessageType.MEDIA_STATE_UPDATE -> handleMediaStateUpdate(message)
            MessageType.NAVIGATION_INFO -> handleNavigationInfo(message)
            MessageType.NOTIFICATION_LITE -> handleNotificationLite(message)
            MessageType.SET_LITE_MODE -> handleSetLiteMode(message)
            MessageType.IOS_CONNECTED -> handleIosConnected(message)
            MessageType.FILE_DETECTED -> handleFileDetected(message)
            MessageType.SET_DYNAMIC_ISLAND_BACKGROUND -> handleSetDynamicIslandBackground(message)
            MessageType.SET_DYNAMIC_ISLAND_COLOR -> handleSetDynamicIslandColor(message)
            MessageType.PHONE_SETTINGS_UPDATE -> handlePhoneSettingsUpdate(message)
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
        }
    }

    private fun handleSetDynamicIslandBackground(message: ProtocolMessage) {
        try {
            val imageBase64 = ProtocolHelper.extractStringField(message, "image")
            val opacity = try { ProtocolHelper.extractIntField(message, "opacity") } catch (e: Exception) { 255 }
            
            Log.d(TAG, "Received SET_DYNAMIC_ISLAND_BACKGROUND: base64Length=${imageBase64?.length}, opacity=$opacity")
            
            if (imageBase64 != null) {
                // Decode
                val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
                Log.d(TAG, "Decoded image bytes: ${bytes.size}")
                
                // Save to internal storage
                val file = File(filesDir, "di_background.webp")
                FileOutputStream(file).use { it.write(bytes) }
                
                Log.d(TAG, "Saved Dynamic Island background to ${file.absolutePath}")
                
                // Save Mode and Opacity
                prefs.edit()
                    .putInt("di_background_mode", 0) // Image
                    .putInt("di_background_opacity", opacity)
                    .apply()
                
                // Notify Service
                val intent = Intent(DynamicIslandService.ACTION_UPDATE_BACKGROUND)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling DI background: ${e.message}")
        }
    }

    private fun handlePhoneSettingsUpdate(message: ProtocolMessage) {
        try {
            val settings = ProtocolHelper.extractData<PhoneSettingsData>(message)
            Log.d(TAG, "Watch received PHONE_SETTINGS_UPDATE: ringerMode=${settings.ringerMode}, dndEnabled=${settings.dndEnabled}")
            
            lastRingerMode = settings.ringerMode
            updateDashboardWidget()

            val intent = Intent("com.ailife.rtosifycompanion.ACTION_PHONE_SETTINGS_UPDATE").apply {
                val gson = com.google.gson.Gson()
                putExtra("settings_json", gson.toJson(settings))
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing phone settings update: ${e.message}", e)
        }
    }

    private fun handleSetDynamicIslandColor(message: ProtocolMessage) {
        try {
            val color = ProtocolHelper.extractIntField(message, "color")
            val opacity = try { ProtocolHelper.extractIntField(message, "opacity") } catch (e: Exception) { 255 }
            
            Log.d(TAG, "Received SET_DYNAMIC_ISLAND_COLOR: color=$color, opacity=$opacity")
            
            // Save Color, Mode and Opacity
            prefs.edit()
                .putInt("di_background_mode", 1) // Color
                .putInt("di_background_color", color)
                .putInt("di_background_opacity", opacity)
                .apply()
            
            // Notify Service
            val intent = Intent(DynamicIslandService.ACTION_UPDATE_BACKGROUND)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling DI color: ${e.message}")
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
            Log.d("CHARGING_DEBUG", "WATCH received PHONE_BATTERY_UPDATE: level=${data.level}, isCharging=${data.isCharging}")
            lastPhoneBattery = data.level
            updateDashboardWidget()
            serviceScope.launch(Dispatchers.Main) {
                callback?.onPhoneBatteryUpdated(data.level, data.isCharging)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing phone battery update: ${e.message}", e)
        }
    }
    
    private fun handleMediaStateUpdate(message: ProtocolMessage) {
        try {
            val data = ProtocolHelper.extractData<MediaStateData>(message)
            
            // Broadcast to MediaControlActivity
            val intent = Intent(MediaControlActivity.ACTION_MEDIA_STATE_UPDATE).apply {
                putExtra("isPlaying", data.isPlaying)
                putExtra("title", data.title)
                putExtra("artist", data.artist)
                putExtra("duration", data.duration)
                putExtra("position", data.position)
                putExtra("volume", data.volume)
                putExtra("albumArtBase64", data.albumArtBase64)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            
            // Broadcast to MediaWidget
            val widgetIntent = Intent(com.ailife.rtosifycompanion.widget.MediaWidget.ACTION_MEDIA_UPDATE).apply {
                putExtra(com.ailife.rtosifycompanion.widget.MediaWidget.EXTRA_TITLE, data.title ?: getString(R.string.media_no_media_short))
                putExtra(com.ailife.rtosifycompanion.widget.MediaWidget.EXTRA_ARTIST, data.artist ?: "")
                putExtra(com.ailife.rtosifycompanion.widget.MediaWidget.EXTRA_IS_PLAYING, data.isPlaying)
                setPackage(packageName)
            }
            sendBroadcast(widgetIntent)
            
            Log.d(TAG, "Media state broadcast: ${data.title} - ${data.artist}, playing=${data.isPlaying}")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing media state update: ${e.message}", e)
        }
    }

    private fun handleNavigationInfo(message: ProtocolMessage) {
        try {
            val navInfo = ProtocolHelper.gson.fromJson(message.data, NavigationInfoData::class.java)
            val intent = Intent(this, NavigationOverlayActivity::class.java).apply {
                putExtra("EXTRA_IMAGE", navInfo.image)
                putExtra("EXTRA_TITLE", navInfo.title)
                putExtra("EXTRA_CONTENT", navInfo.content)
                putExtra("EXTRA_KEEP_SCREEN_ON", navInfo.keepScreenOn)
                putExtra("EXTRA_USE_GREY_BACKGROUND", navInfo.useGreyBackground)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            Log.d(TAG, "Started Navigation Overlay for ${navInfo.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Navigation Info: ${e.message}")
        }
    }

    private fun handleNotificationLite(message: ProtocolMessage) {
        try {
            val data = ProtocolHelper.extractData<NotificationLiteData>(message)
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "lite_notifications"
            
            // Ensure channel exists
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (notificationManager.getNotificationChannel(channelId) == null) {
                    val channel = NotificationChannel(channelId, "Lite Notifications", NotificationManager.IMPORTANCE_DEFAULT)
                    notificationManager.createNotificationChannel(channel)
                }
            }

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle(data.title)
                .setContentText(data.content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            // Use hash of ID or ID itself if integer for notification ID
            val notificationId = data.id.hashCode()
            notificationManager.notify(notificationId, notification)
            Log.d(TAG, "Shown Lite Notification: ${data.title}")

        } catch (e: Exception) {
            Log.e(TAG, "Error handling Lite Notification: ${e.message}")
        }
    }

    private fun handleSetLiteMode(message: ProtocolMessage) {
        try {
            val enabled = ProtocolHelper.extractBooleanField(message, "enabled")
            Log.d(TAG, "Received Set Lite Mode: $enabled")

            prefs.edit().putBoolean("lite_mode_enabled", enabled).apply()

            val intent = Intent(ACTION_LITE_MODE_UPDATE).apply {
                putExtra("enabled", enabled)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
             Log.e(TAG, "Error handling Set Lite Mode: ${e.message}")
        }
    }

    /**
     * Handle iOS connected message - start ANCS listener to receive iOS notifications
     */
    private fun handleIosConnected(message: ProtocolMessage) {
        try {
            val platform = ProtocolHelper.extractStringField(message, "platform")
            Log.i(TAG, "iOS device connected (platform: $platform)")

            isIosConnected = true
            prefs.edit().putBoolean("ios_connected", true).apply()

            // Get the connected iOS device from transport manager
            val connectedDevice = transportManager.getConnectedBluetoothDevice()
            if (connectedDevice != null) {
                startAncsClient(connectedDevice)
            } else {
                Log.w(TAG, "No Bluetooth device found for ANCS connection")
            }

            // Broadcast iOS connected state
            val intent = Intent(ACTION_IOS_CONNECTED).apply {
                putExtra("connected", true)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling iOS Connected: ${e.message}")
        }
    }

    /**
     * Start ANCS client to receive notifications from iOS
     */
    private fun startAncsClient(device: BluetoothDevice) {
        // Stop existing client if any
        ancsClient?.disconnect()

        ancsClient = AncsClient(this, object : AncsClient.AncsCallback {
            override fun onAncsConnected() {
                Log.i(TAG, "ANCS connected - ready to receive iOS notifications")
                mainHandler.post {
                    Toast.makeText(this@BluetoothService, getString(R.string.msg_ios_notif_enabled), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAncsDisconnected() {
                Log.i(TAG, "ANCS disconnected")
                isIosConnected = false
                prefs.edit().putBoolean("ios_connected", false).apply()
            }

            override fun onNotificationReceived(notification: AncsClient.AncsNotification) {
                Log.d(TAG, "ANCS Notification: ${notification.title} - ${notification.message}")
                showAncsNotification(notification)
            }

            override fun onNotificationRemoved(notificationUID: Int) {
                Log.d(TAG, "ANCS Notification removed: $notificationUID")
                dismissAncsNotification(notificationUID)
            }

            override fun onError(message: String) {
                Log.e(TAG, "ANCS Error: $message")
            }
        })

        ancsClient?.connect(device)
    }

    /**
     * Stop ANCS client
     */
    private fun stopAncsClient() {
        ancsClient?.disconnect()
        ancsClient = null
        isIosConnected = false
        prefs.edit().putBoolean("ios_connected", false).apply()
    }

    /**
     * Show notification received from iOS via ANCS (same format as notification_lite)
     */
    private fun showAncsNotification(notification: AncsClient.AncsNotification) {
        val title = notification.title ?: notification.appIdentifier ?: "iOS"
        val content = notification.message ?: ""

        // Use the same channel as lite notifications
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "lite_notifications"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(channelId, "Lite Notifications", NotificationManager.IMPORTANCE_DEFAULT)
                notificationManager.createNotificationChannel(channel)
            }
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notification.uid, notif)
        Log.d(TAG, "Shown ANCS Notification: $title")
    }

    /**
     * Dismiss ANCS notification
     */
    private fun dismissAncsNotification(notificationUID: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationUID)
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
                Log.d(TAG, "Syncing notifyOnDisconnect: $it")
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
                                   (state.type.contains("LAN") || state.type.contains("WiFi"))
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
            settings.aggressiveKeepaliveEnabled?.let {
                prefs.edit().putBoolean("aggressive_keepalive_enabled", it).apply()
                Log.d(TAG, "Aggressive Keepalive setting updated: $it")
                if (it) {
                    KeepaliveReceiver.schedule(this)
                } else {
                    KeepaliveReceiver.cancel(this)
                }
            }
            settings.wakeScreenEnabled?.let {
                prefs.edit().putBoolean("wake_screen_enabled", it).apply()
                Log.d(TAG, "Wake Screen setting updated: $it")
            }
            settings.wakeScreenDndEnabled?.let {
                prefs.edit().putBoolean("wake_screen_dnd_enabled", it).apply()
                Log.d(TAG, "Wake Screen DND setting updated: $it")
            }
            settings.vibrateEnabled?.let {
                prefs.edit().putBoolean("vibrate_enabled", it).apply()
                Log.d(TAG, "Vibrate setting updated: $it")
            }
            settings.vibrateInSilentEnabled?.let {
                prefs.edit().putBoolean("vibrate_silent_enabled", it).apply()
                Log.d(TAG, "Vibrate in Silent setting updated: $it")
            }
            settings.notificationSoundEnabled?.let {
                prefs.edit().putBoolean("notification_sound_enabled", it).apply()
            }
            settings.phoneCallRingingEnabled?.let {
                prefs.edit().putBoolean("phone_call_ringing_enabled", it).apply()
            }
            settings.notificationSoundUri?.let {
                prefs.edit().putString("notification_sound_uri", it).apply()
            }
            settings.notificationSoundName?.let {
                prefs.edit().putString("notification_sound_name", it).apply()
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
            settings.dynamicIslandGlobalOpacity?.let {
                prefs.edit().putInt("di_global_opacity", it).apply()
                Log.d(TAG, "Dynamic Island Global Opacity updated: $it")
            }
            // New auto-hide mode settings
            settings.dynamicIslandAutoHideMode?.let {
                prefs.edit().putInt("di_auto_hide_mode", it).apply()
                Log.d(TAG, "Dynamic Island Auto-Hide Mode updated: $it (0=Always, 1=Never, 2=Blacklist)")
            }
            settings.dynamicIslandBlacklistApps?.let {
                prefs.edit().putStringSet("di_blacklist_apps", it.toSet()).apply()
                Log.d(TAG, "Dynamic Island Blacklist Apps updated: ${it.size} apps")
            }
            settings.dynamicIslandHideWithActiveNotifs?.let {
                prefs.edit().putBoolean("di_hide_with_active_notifs", it).apply()
                Log.d(TAG, "Dynamic Island Hide With Active Notifs updated: $it")
            }
            // Feature toggles
            settings.diShowPhoneCalls?.let {
                prefs.edit().putBoolean("di_show_phone_calls", it).apply()
                Log.d(TAG, "Dynamic Island Show Phone Calls updated: $it")
            }
            settings.diShowAlarms?.let {
                prefs.edit().putBoolean("di_show_alarms", it).apply()
                Log.d(TAG, "Dynamic Island Show Alarms updated: $it")
            }
            settings.diShowDisconnect?.let {
                prefs.edit().putBoolean("di_show_disconnect", it).apply()
                Log.d(TAG, "Dynamic Island Show Disconnect updated: $it")
            }
            settings.diShowMedia?.let {
                prefs.edit().putBoolean("di_show_media", it).apply()
                Log.d(TAG, "Dynamic Island Show Media updated: $it")
            }
            settings.dynamicIslandFollowDnd?.let {
                prefs.edit().putBoolean("di_follow_dnd", it).apply()
                Log.d(TAG, "Dynamic Island Follow DND updated: $it")
            }
            settings.dynamicIslandBlacklistHidePeak?.let {
                prefs.edit().putBoolean("di_blacklist_hide_peak", it).apply()
                Log.d(TAG, "Dynamic Island Blacklist Hide Peak updated: $it")
            }
            settings.inAppReplyDialog?.let {
                prefs.edit().putBoolean("in_app_reply_dialog", it).apply()
                Log.d(TAG, "In-App Reply Dialog setting updated: $it")
            } ?: Log.d(TAG, "In-App Reply Dialog setting is NULL in update")

            settings.fullScreenStackingEnabled?.let {
                prefs.edit().putBoolean("full_screen_stacking_enabled", it).apply()
                Log.d(TAG, "Full Screen Stacking setting updated: $it")
            }

            settings.fullScreenDismissOnScreenOff?.let {
                prefs.edit().putBoolean("full_screen_close_on_screen_off", it).apply()
                Log.d(TAG, "Full Screen Dismiss on Screen Off setting updated: $it")
            }
            settings.fullScreenAppNameSize?.let {
                prefs.edit().putInt("full_screen_app_name_size", it).apply()
            }
            settings.fullScreenTitleSize?.let {
                prefs.edit().putInt("full_screen_title_size", it).apply()
            }
            settings.fullScreenContentSize?.let {
                prefs.edit().putInt("full_screen_content_size", it).apply()
            }
            settings.fullScreenAutoCloseEnabled?.let {
                prefs.edit().putBoolean("full_screen_auto_close_enabled", it).apply()
            }
            settings.fullScreenAutoCloseTimeout?.let {
                prefs.edit().putInt("full_screen_auto_close_timeout", it).apply()
            }
            settings.fullScreenKeepScreenOn?.let {
                prefs.edit().putBoolean("full_screen_keep_screen_on", it).apply()
                Log.d(TAG, "Full Screen Keep Screen On setting updated: $it")
            }
            
            // Broadcast settings update to Dynamic Island Service if any DI setting changed
            if (settings.notificationStyle != null || settings.dynamicIslandTimeout != null ||
                settings.dynamicIslandY != null || settings.dynamicIslandWidth != null ||
                settings.dynamicIslandHeight != null || settings.dynamicIslandAutoHideMode != null ||
                settings.dynamicIslandBlacklistApps != null || settings.dynamicIslandHideWithActiveNotifs != null ||  
                settings.dynamicIslandTextMultiplier != null || settings.dynamicIslandLimitMessageLength != null ||
                settings.diShowPhoneCalls != null || settings.diShowAlarms != null ||
                settings.diShowDisconnect != null || settings.diShowMedia != null ||
                settings.dynamicIslandFollowDnd != null || settings.dynamicIslandBlacklistHidePeak != null) {
                val intent = Intent(ACTION_UPDATE_DI_SETTINGS)
                intent.setPackage(packageName)
                sendBroadcast(intent)
                Log.d(TAG, "Broadcast ACTION_UPDATE_DI_SETTINGS to Dynamic Island Service")
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
            settings.internetActivationRule?.let {
                prefs.edit().putInt("internet_activation_rule", it).apply()
                Log.d(TAG, "Internet rule updated via SettingsUpdate: $it")
            }
            settings.internetSignalingUrl?.let {
                prefs.edit().putString("internet_signaling_url", it).apply()
                Log.d(TAG, "Internet signaling URL updated via SettingsUpdate: $it")
            }
            settings.hqLanEnabled?.let {
                prefs.edit().putBoolean("hq_lan_enabled", it).apply()
                Log.d(TAG, "HQ LAN setting updated: $it")
            }
            settings.vibrationStrength?.let {
                prefs.edit().putInt("vibration_strength", it).apply()
                Log.d(TAG, "Vibration Strength setting updated: $it")
            }
            settings.vibrationPattern?.let {
                prefs.edit().putInt("vibration_pattern", it).apply()
                Log.d(TAG, "Vibration Pattern setting updated: $it")
            }
            settings.vibrationCustomLength?.let {
                prefs.edit().putInt("vibration_custom_length", it).apply()
                Log.d(TAG, "Vibration Custom Length setting updated: $it")
            }

            // Apply updated settings to TransportManager if they changed
            val rule = settings.internetActivationRule ?: prefs.getInt("internet_activation_rule", 0)
            val url = settings.internetSignalingUrl ?: prefs.getString("internet_signaling_url", "") ?: ""
            transportManager.updateInternetSettings(rule, url)

            // Internet settings handled via dedicated rule message or inside SettingsUpdateData if added there
            // Checking if SettingsUpdateData was updated in Protocol.kt (wait, did I check Protocol.kt for internet settings?)

            Log.d(TAG, "Automation settings updated from phone")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling settings update: ${e.message}")
        }
    }
    
    private fun handleUpdateInternetSettings(message: ProtocolMessage) {
        try {
            val rule = message.data.get("rule")?.asInt ?: 0
            val url = message.data.get("url")?.asString ?: ""
            val stunUrl = message.data.get("stunUrl")?.asString ?: ""
            val turnUrl = message.data.get("turnUrl")?.asString ?: ""
            val turnUsername = message.data.get("turnUsername")?.asString ?: ""
            val turnPassword = message.data.get("turnPassword")?.asString ?: ""
            
            prefs.edit().apply {
                putInt("internet_activation_rule", rule)
                putString("internet_signaling_url", url)
                putString("internet_stun_url", stunUrl)
                putString("internet_turn_url", turnUrl)
                putString("internet_turn_username", turnUsername)
                putString("internet_turn_password", turnPassword)
            }.apply()
            
            transportManager.updateInternetSettings(
                rule, url, stunUrl, turnUrl, turnUsername, turnPassword
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling update internet settings: ${e.message}")
        }
    }
    
    fun notifyInternetSettingsChanged() {
        Log.d(TAG, "Local internet settings changed, updating transport manager")
        val rule = prefs.getInt("internet_activation_rule", 0)
        val url = prefs.getString("internet_signaling_url", "") ?: ""
        val stunUrl = prefs.getString("internet_stun_url", "stun:stun.cloudflare.com:3478") ?: ""
        val turnUrl = prefs.getString("internet_turn_url", "") ?: ""
        val turnUsername = prefs.getString("internet_turn_username", "") ?: ""
        val turnPassword = prefs.getString("internet_turn_password", "") ?: ""
        
        transportManager.updateInternetSettings(
            rule, url, stunUrl, turnUrl, turnUsername, turnPassword
        )
    }

    private fun handleUpdateWifiRule(message: ProtocolMessage) {
        try {
            // Default to current rule if not present in message, or 8 (BT_OR_APP) if never set
            val defaultRule = prefs.getInt("wifi_activation_rule", 8)
            val wifiActivationRule = message.data.get("rule")?.asInt ?: defaultRule
            
            prefs.edit().putInt("wifi_activation_rule", wifiActivationRule).apply()
            transportManager.updateWifiRule(wifiActivationRule)
            Log.d(TAG, "WiFi activation rule updated to: $wifiActivationRule")
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
        serviceScope.launch(Dispatchers.Main) {
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
    }

    private fun stopClipboardMonitoring() {
        Log.d(TAG, "Stopping clipboard monitoring")

        serviceScope.launch(Dispatchers.Main) {
            // Stop listener (Android 9-)
            clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
            clipboardListener = null
        }

        // Stop polling
        clipboardPollingRunnable?.let { clipboardPollingHandler.removeCallbacks(it) }
        clipboardPollingRunnable = null
    }

    private fun startStandardClipboardListener() {
        serviceScope.launch(Dispatchers.Main) {
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
        deviceActionManager.setWifiEnabled(true)
    }

    private fun disableWifiAutomation() {
        deviceActionManager.setWifiEnabled(false)
    }

    private fun enableMobileDataAutomation() {
        deviceActionManager.setMobileDataEnabled(true)
    }

    private fun disableMobileDataAutomation() {
        deviceActionManager.setMobileDataEnabled(false)
    }

    private fun handleEnableBluetoothInternet(message: ProtocolMessage) {
        val enabled = ProtocolHelper.extractBooleanField(message, "enabled")
        if (enabled) {
            val deviceName = currentDevice?.name ?: currentDevice?.address ?: currentDeviceName
            deviceActionManager.setBluetoothInternetEnabled(deviceName)
        }
    }

    // Connection state automation - called on connection
    private fun onConnectionEstablished(transport: String) {
        serviceScope.launch(Dispatchers.IO) {
            delay(1000) // Debounce

            // Check clipboard pref
            if (prefs.getBoolean("clipboard_sync_enabled", false)) {
                startClipboardMonitoring()
            }

            // Auto WiFi: Disable WiFi when BT connects
            if (prefs.getBoolean("auto_wifi_enabled", false)) {
                if (transport.contains("BT")) {
                    disableWifiAutomation()
                } else {
                    Log.d(TAG, "Auto WiFi: Skipping disable (Transport is $transport, not BT)")
                }
            }

            // Auto Data: Disable mobile data when BT connects
            if (prefs.getBoolean("auto_data_enabled", false)) {
                if (transport.contains("BT")) {
                    disableMobileDataAutomation()
                } else {
                    Log.d(TAG, "Auto Data: Skipping disable (Transport is $transport, not BT)")
                }
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

    private fun onConnectionLost(transport: String) {
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
                if (transport.contains("BT")) {
                    enableWifiAutomation()
                } else {
                    Log.d(TAG, "Auto WiFi: Skipping enable (Disconnected transport was $transport, not BT)")
                }
            }

            // Auto Data: Enable mobile data when BT disconnects
            if (prefs.getBoolean("auto_data_enabled", false)) {
                if (transport.contains("BT")) {
                    enableMobileDataAutomation()
                } else {
                    Log.d(TAG, "Auto Data: Skipping enable (Disconnected transport was $transport, not BT)")
                }
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
            // deviceMac is the phone's MAC - use it as remoteMac for encryption
            serviceScope.launch {
                val watchMac = prefs.getString("wifi_advertised_mac", "") ?: ""
                val deviceName = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name ?: android.os.Build.MODEL
                transportManager.startWifiServer(deviceName, watchMac, deviceMac)
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

        // Use ACTION_BATTERY_CHANGED intent for reliable charging detection (same as DynamicIslandService)
        val batteryIntent = ContextCompat.registerReceiver(this, null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        Log.d("CHARGING_DEBUG", "WATCH collectWatchStatus: battery=$batteryLevel, isCharging=$isCharging, status=$status")

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val dndEnabled = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        var wifiSsid: String? = null
        var wifiState: String
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

                        // Only extract actual SSID, don't use status messages
                        wifiSsid = when {
                            rawSsid.isNullOrEmpty() -> null
                            rawSsid == "<unknown ssid>" -> null
                            rawSsid == "\"<unknown ssid>\"" -> null
                            rawSsid == "0x" -> null
                            else -> {
                                val cleanSsid = rawSsid.replace("\"", "").trim()
                                if (cleanSsid.isEmpty() || cleanSsid == "<unknown ssid>") {
                                    null
                                } else {
                                    cleanSsid
                                }
                            }
                        }
                        wifiState = "CONNECTED"
                    } else {
                        wifiSsid = null
                        wifiState = "DISCONNECTED"
                        Log.d(TAG, "WiFi Status: Not connected, info=$info, supplicantState=${info?.supplicantState}")
                    }
                } else {
                    wifiSsid = null
                    wifiState = "DISABLED"
                }
            } else {
                wifiSsid = null
                wifiState = "NO_PERMISSION"
                Log.w(TAG, "WiFi Status: No location permission")
            }
        } catch (e: Exception) {
            wifiSsid = null
            wifiState = "ERROR"
            Log.e(TAG, "WiFi Status: Error collecting status", e)
        }

        val ipAddress = getIpAddress()
        Log.d(TAG, "WiFi Status collected: ssid='$wifiSsid', state='$wifiState', enabled=$wifiEnabled, ip=$ipAddress")

        return StatusUpdateData(
                battery = batteryLevel,
                charging = isCharging,
                dnd = dndEnabled,
                ipAddress = ipAddress,
                wifiSsid = wifiSsid,
                wifiState = wifiState
        )
    }

    private fun broadcastStatusUpdate(status: StatusUpdateData) {
        val intent = Intent("com.ailife.rtosifycompanion.STATUS_UPDATE")
        // Use Gson to serialize if needed, or just broadcast that update happened
        // For now, minimal implementation to satisfy compiler if it was missing
        // or check if it was intended to use LocalBroadcastManager
        
        // LocalBroadcastManager is deprecated/missing, using standard broadcast.
        // Ensure receivers use ContextCompat.registerReceiver with RECEIVER_NOT_EXPORTED if targeting Android 14+
        sendBroadcast(intent)
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

    private suspend fun handleRequestHealthData(message: ProtocolMessage) {
        try {
            Log.d(TAG, "Received REQUEST_HEALTH_DATA, collecting data...")
            // Extract type if provided (e.g. "RAW" or "TODAY")
            val type = ProtocolHelper.extractStringField(message, "type") ?: "TODAY"
            
            // Collect health data once when requested (not continuously)
            val healthData = healthDataCollector.collectCurrentHealthData(type)
            Log.d(
                    TAG,
                    "Collected health data (type=$type): steps=${healthData.steps}, cal=${healthData.calories}, hr=${healthData.heartRate}"
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
            Log.d("CHARGING_DEBUG", "WATCH received STATUS_UPDATE: battery=${status.battery}, charging=${status.charging}")
            serviceScope.launch(Dispatchers.Main) {
                // Determine logic for callback arguments based on new fields
                // wifiSsid: String
                // wifiEnabled: Boolean (inferred from State)
                // wifiState: String?
                
                val state = status.wifiState ?: "UNKNOWN"
                val enabled = state != "DISABLED" && state != "OFF"
                
                // Old 'wifi' string is gone. We must construct display SSID or pass null if not connected.
                // Callback expects 'wifiSsid' as String. 
                // If connected, pass SSID. If not, pass status string for display?
                // The MainActivity update handled this logic:
                // val displaySsid = if (wifiState == "CONNECTED") wifiSsid else wifiState ?: wifiSsid
                
                // So here we pass pure data.
                val ssidData = status.wifiSsid ?: "" 

                callback?.onWatchStatusUpdated(
                    status.battery,
                    status.charging,
                    ssidData,
                    enabled,
                    status.dnd,
                    status.ipAddress,
                    state
                )
            
                broadcastStatusUpdate(status)
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
        stopRingtone()
        sendMessage(ProtocolHelper.createRejectCall())
    }

    fun sendAnswerCall() {
        stopRingtone()
        sendMessage(ProtocolHelper.createAnswerCall())
    }

    fun stopRingtone() {
        try {
            ringtonePlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
                Log.d(TAG, "MediaPlayer ringtone stopped")
            }
            ringtonePlayer = null
            
            currentRingtone?.let {
                if (it.isPlaying) it.stop()
                Log.d(TAG, "Ringtone stopped")
            }
            currentRingtone = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone: ${e.message}")
        }
    }

    fun sendMediaCommand(command: String, volume: Int? = null, seekPosition: Long? = null) {
        val data = MediaControlData(command, volume, seekPosition)
        sendMessage(ProtocolHelper.createMediaControl(data))
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

    private fun handleRequestPhoneSettings() {
        val settings = collectPhoneSettings()
        sendMessage(ProtocolHelper.createPhoneSettingsUpdate(settings))
    }

    private fun handleSetRingerMode(message: ProtocolMessage) {
        val mode = ProtocolHelper.extractIntField(message, "mode")
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = mode
        
        // Notify watch of the change
        serviceScope.launch {
            delay(300)
            handleRequestPhoneSettings()
        }
    }

    private fun handleSetVolume(message: ProtocolMessage) {
        val streamType = ProtocolHelper.extractIntField(message, "streamType")
        val volume = ProtocolHelper.extractIntField(message, "volume")
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Ensure volume is within bounds [0, max]
        val maxVolume = am.getStreamMaxVolume(streamType)
        val targetVolume = volume.coerceIn(0, maxVolume)
        
        am.setStreamVolume(streamType, targetVolume, 0)
        
        // Notify watch of the change
        serviceScope.launch {
            delay(300)
            handleRequestPhoneSettings()
        }
    }

    private fun collectPhoneSettings(): PhoneSettingsData {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        val ringerMode = am.ringerMode
        val dndEnabled = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        
        val volumeChannels = listOf(
            AudioManager.STREAM_MUSIC to getString(R.string.volume_channel_media),
            AudioManager.STREAM_RING to getString(R.string.volume_channel_ring),
            AudioManager.STREAM_NOTIFICATION to getString(R.string.volume_channel_notification),
            AudioManager.STREAM_ALARM to getString(R.string.volume_channel_alarm),
            AudioManager.STREAM_SYSTEM to getString(R.string.volume_channel_system)
        ).map { (streamType, name) ->
            VolumeChannelData(
                streamType = streamType,
                name = name,
                currentVolume = am.getStreamVolume(streamType),
                maxVolume = am.getStreamMaxVolume(streamType)
            )
        }
        
        return PhoneSettingsData(
            ringerMode = ringerMode,
            dndEnabled = dndEnabled,
            volumeChannels = volumeChannels
        )
    }

    private fun handleSetWifiCommand(message: ProtocolMessage) {
        val enable = ProtocolHelper.extractBooleanField(message, "enabled")
        Log.i(TAG, "Handling SET_WIFI: $enable")

        deviceActionManager.setWifiEnabled(enable)

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

    private fun handleCameraRecordingStatus(message: ProtocolMessage) {
        try {
            val isRecording = ProtocolHelper.extractStringField(message, "isRecording")?.toBoolean() 
                ?: message.data.get("isRecording")?.asBoolean ?: false
            val intent = Intent(CameraRemoteActivity.ACTION_RECORD_STATUS_CHANGED)
            intent.putExtra(CameraRemoteActivity.EXTRA_RECORD_STATUS, isRecording)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling record status: ${e.message}")
        }
    }

    private fun handleWifiKeyExchange(message: ProtocolMessage) {
        val phoneMac = ProtocolHelper.extractStringField(message, "deviceMac") ?: getConnectedDeviceMac() ?: return
        val watchMac = ProtocolHelper.extractStringField(message, "targetMac") ?: ""
        val encryptionKey = ProtocolHelper.extractStringField(message, "encryptionKey") ?: return
        
        Log.i(TAG, "Received WiFi key exchange request from $phoneMac for watch $watchMac. KeyLength=${encryptionKey.length}")
        // Import key using phoneMac (remote device's MAC) because WifiIntranetTransport
        // uses remoteMac for encryption/decryption key lookups
        val success = encryptionManager.importKey(phoneMac, encryptionKey)
        if (success) {
            encryptionManager.setActiveDevice(phoneMac)
            
            // Save MACs for future restarts
            prefs.edit().apply {
                putString("wifi_advertised_mac", watchMac)
                putString("paired_phone_mac", phoneMac)
                putString("last_phone_mac", phoneMac) // CRITICAL: Save as last_phone_mac so startWatchLogic picks it up
            }.apply()
            
            // Enable pairing mode to keep WiFi server running during pairing
            transportManager.enableWifiPairingMode()

            // Start WiFi server with phoneMac as remoteMac for encryption
            serviceScope.launch {
                val deviceName = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name ?: android.os.Build.MODEL
                transportManager.startWifiServer(deviceName, watchMac, phoneMac)
                // CRITICAL: Update the watchdog with the new MAC so it doesn't restart with empty MAC
                transportManager.startWifiServerWatchdog(this@BluetoothService, deviceName, watchMac, phoneMac)
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

            // Global Unique Filename Logic (Applies to all paths)
            // If we have a final destination path (deferred move), we don't restart logic here usually, 
            // but for the temp file it doesn't matter much. 
            // However, sticking to the main logic:
            if (finalDestinationPath == null) { // Only rename if we are saving directly to final location
                var counter = 1
                var finalFile = targetFile
                while (finalFile.exists()) {
                     val name = targetFile.name
                     val dotIndex = name.lastIndexOf('.')
                     val newName = if (dotIndex != -1) {
                         "${name.substring(0, dotIndex)} ($counter)${name.substring(dotIndex)}"
                     } else {
                         "$name ($counter)"
                     }
                     finalFile = File(targetFile.parentFile, newName)
                     counter++
                }
                targetFile = finalFile
            }

            android.util.Log.d(TAG, "Receiving file to: ${targetFile.absolutePath}")

            // Ensure parent directory exists (if possible)
            targetFile.parentFile?.mkdirs()

            try {
                receivingFile = targetFile
                receivingFileStream = java.io.RandomAccessFile(targetFile, "rw")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to open primary target ${targetFile.absolutePath}, trying fallback: ${e.message}")
                val cacheDirToUse = externalCacheDir ?: cacheDir
                val fallbackFile = File(cacheDirToUse, fileData.name)
                receivingFile = fallbackFile
                receivingFileStream = java.io.RandomAccessFile(fallbackFile, "rw")
                android.util.Log.d(TAG, "Fallback to cache success: ${fallbackFile.absolutePath}")
            }

            receivingFileStream?.setLength(fileData.size)
            expectedFileSize = fileData.size
            receivedFileSize = 0
            expectedChecksum = fileData.checksum
            receivingFileType = fileData.type
            receivedChunks.clear()

            // Report 0 progress to UI
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

            // Report progress
            if (expectedFileSize > 0) {
                val progress = (receivedFileSize * 100 / expectedFileSize).toInt()
                withContext(Dispatchers.Main) {
                    callback?.onDownloadProgress(progress)
                    // Update audio notification progress if applicable
                    onAudioFileDownloadProgress(progress)
                }
                // Broadcast progress for Dynamic Island
                val progressIntent = Intent(ACTION_FILE_DOWNLOAD_PROGRESS)
                progressIntent.putExtra(EXTRA_PROGRESS, progress)
                progressIntent.setPackage(packageName)
                sendBroadcast(progressIntent)
            }

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
            withContext(Dispatchers.Main) {
                callback?.onDownloadProgress(100)
                // Notify audio notification if applicable
                onAudioFileDownloadComplete(file.absolutePath)
            }

            // Broadcast completion for Dynamic Island or other listeners
            val intent = Intent(ACTION_FILE_DOWNLOAD_COMPLETE)
            intent.putExtra(EXTRA_FILE_PATH, file.absolutePath)
            intent.putExtra(EXTRA_SUCCESS, true)
            intent.setPackage(packageName)
            sendBroadcast(intent)

            // Send success acknowledgment to phone
            sendMessage(ProtocolHelper.createFileTransferEnd(success = true))
            android.util.Log.d(TAG, "Sent file transfer acknowledgment")

            android.util.Log.d(TAG, "File received successfully at: ${file.absolutePath}")

            // Handle restricted destination
            val finalPath = finalDestinationPath
            if (finalPath != null) {
                android.util.Log.d(TAG, "Moving file to restricted destination: $finalPath")
                val successMove = fileManager.renameFile(file.absolutePath, finalPath)
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
        Log.d(TAG, "Request file list for: $path")
        
        try {
            val files = fileManager.listFiles(path)
            sendMessage(ProtocolHelper.createResponseFileList(path, files))
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files: ${e.message}")
            sendMessage(ProtocolHelper.createResponseFileList(path, emptyList()))
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

            // Try copy via FileManager (handles Native/Shizuku/Root)
            var copied = false
            if (fileManager.copyFile(absPath, tempFile.absolutePath)) {
                copied = true
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

    private suspend fun handleDeleteFiles(message: ProtocolMessage) {
        val pathsJson = message.data.getAsJsonArray("paths") ?: return
        var successCount = 0
        
        val iterator = pathsJson.iterator()
        while(iterator.hasNext()) {
             val path = iterator.next().asString
             android.util.Log.d(TAG, "Delete requested: $path")
             if (fileManager.deleteFile(path)) {
                 successCount++
             }
        }

        if (pathsJson.size() > 0) {
            val firstPath = pathsJson[0].asString
            val parentPath = if (firstPath.lastIndexOf('/') > 0) firstPath.substring(0, firstPath.lastIndexOf('/')) else "/"
            handleRequestFileList(ProtocolHelper.createRequestFileList(parentPath))
        }
    }

    private suspend fun handleRenameFile(message: ProtocolMessage) {
        val oldPath = message.data.get("oldPath")?.asString ?: return
        val newPath = message.data.get("newPath")?.asString ?: return

        android.util.Log.d(TAG, "Rename requested: $oldPath -> $newPath")

        val success = fileManager.renameFile(oldPath, newPath)

        if (success) {
            val parentPath =
                    if (newPath.lastIndexOf('/') > 0) newPath.substring(0, newPath.lastIndexOf('/'))
                    else "/"
            handleRequestFileList(ProtocolHelper.createRequestFileList(parentPath))
        }
    }

    private suspend fun handleMoveFiles(message: ProtocolMessage) {
        val srcPathsJson = message.data.getAsJsonArray("srcPaths") ?: return
        val dstPath = message.data.get("dstPath")?.asString ?: return

        android.util.Log.d(TAG, "Move requested to: $dstPath")

        val iterator = srcPathsJson.iterator()
        while(iterator.hasNext()) {
             val srcPath = iterator.next().asString
             val filename = srcPath.substringAfterLast('/')
             val targetPath = if (dstPath.endsWith("/")) dstPath + filename else "$dstPath/$filename"
             
             fileManager.renameFile(srcPath, targetPath)
        }
        
        handleRequestFileList(ProtocolHelper.createRequestFileList(dstPath))
    }

    private suspend fun handleCopyFiles(message: ProtocolMessage) {
        val srcPathsJson = message.data.getAsJsonArray("srcPaths") ?: return
        val dstPath = message.data.get("dstPath")?.asString ?: return

        android.util.Log.d(TAG, "Copy requested to: $dstPath")
        
        val iterator = srcPathsJson.iterator()
        while(iterator.hasNext()) {
             val srcPath = iterator.next().asString
             val filename = srcPath.substringAfterLast('/')
             val targetPath = if (dstPath.endsWith("/")) dstPath + filename else "$dstPath/$filename"
             
             fileManager.copyFile(srcPath, targetPath)
        }
        handleRequestFileList(ProtocolHelper.createRequestFileList(dstPath))
    }


    private suspend fun handleCreateFolder(message: ProtocolMessage) {
        val path = message.data.get("path")?.asString ?: return
        android.util.Log.d(TAG, "Create folder requested: $path")

        val success = fileManager.createFolder(path)

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
            Toast.makeText(this@BluetoothService, getString(R.string.toast_watch_face_set, facePath), Toast.LENGTH_SHORT)
                    .show()
        }
    }

    // ========================================================================
    // UNIFIED FILE OPERATIONS DELEGATED TO FILE MANAGER
    // ========================================================================











    // Helper to find or create DocumentFile given a relative path from external storage root
    // Only works if path is within a granted permission tree (e.g.
    // Android/data/com.ailife.ClockSkinCoco)


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
                    val success = fileManager.renameFile(file.absolutePath, targetFile.absolutePath)
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

        val authority = "${packageName}.fileprovider"
        val installUri = FileProvider.getUriForFile(this, authority, tempFile)
        
        deviceActionManager.installApk(tempFile.absolutePath, installUri)
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

    private fun handleLockDeviceCommand() {
        Log.i(TAG, "🔒 Lock command received")
        mainHandler.post {
            Toast.makeText(this, getString(R.string.toast_lock_received), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.toast_find_device, enabled.toString()), Toast.LENGTH_SHORT).show()
        }
        if (enabled) {
            startFindDeviceAlarm()
        } else {
            stopFindDeviceAlarm()
        }
    }

    private fun handleFindPhoneCommand(message: ProtocolMessage) {
        val enabled = ProtocolHelper.extractBooleanField(message, "enabled")
        if (!enabled) {
            // Phone stopped the alarm, update watch UI
            val intent = Intent("com.ailife.rtosifycompanion.STOP_RINGING")
            sendBroadcast(intent)
            Log.i(TAG, "Phone stopped alarm, updating watch UI")
        }
    }

    private var findDeviceLocationListener: LocationListener? = null
    private var findDeviceRssiReceiver: BroadcastReceiver? = null
    private var bleRssiMonitor: com.ailife.rtosifycompanion.ble.BleRssiMonitor? = null
    @Volatile private var currentFindDeviceRssi: Int = 0

    private fun handleFindDeviceLocationRequest(message: ProtocolMessage) {
        val enabled = ProtocolHelper.extractBooleanField(message, "enabled")
        Log.i(TAG, "Location request received from phone: enabled=$enabled")
        
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        
        if (enabled) {
            // Start BLE RSSI Monitoring
            if (bleRssiMonitor == null) {
                currentFindDeviceRssi = 0
                bleRssiMonitor = com.ailife.rtosifycompanion.ble.BleRssiMonitor(this) { rssi ->
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
                        Log.i(TAG, "Started GPS updates for phone find request")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start location updates for phone request: ${e.message}")
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
                Log.i(TAG, "Stopped GPS updates for phone find request")
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
            androidx.core.content.ContextCompat.registerReceiver(this, findDeviceRssiReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                btAdapter.startDiscovery()
            }
        }
    }

    private fun startFindDeviceAlarm() {
        // Start FindDeviceAlarmActivity which handles the loud sound and UI
        val intent = Intent(this, FindDeviceAlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        Log.i(TAG, "Launched FindDeviceAlarmActivity")
    }

    private fun stopFindDeviceAlarm() {
        // Send broadcast to finish FindDeviceAlarmActivity if it's running
        val intent = Intent("com.ailife.rtosify.STOP_ALARM")
        sendBroadcast(intent)
        Log.i(TAG, "Stopped alarm via broadcast")
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
            
            Log.d(TAG, "📍 Received location update from phone: lat=${locationData.latitude}, lon=${locationData.longitude}, rssi=${locationData.rssi}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling find device location update: ${e.message}")
        }
    }



    private fun updateDashboardWidget() {
        try {
            val status = if (isConnected) getString(R.string.status_connected_to, currentDeviceName ?: "Phone") else getString(R.string.status_disconnected)
            
            // Get local battery level
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val watchBattery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            val intent = Intent(DashboardWidget.ACTION_DASHBOARD_UPDATE).apply {
                putExtra(DashboardWidget.EXTRA_STATUS, status)
                putExtra(DashboardWidget.EXTRA_PHONE_BATTERY, lastPhoneBattery)
                putExtra(DashboardWidget.EXTRA_WATCH_BATTERY, watchBattery)
                putExtra(DashboardWidget.EXTRA_TRANSPORT, currentTransportType)
                putExtra(DashboardWidget.EXTRA_RINGER_MODE, lastRingerMode)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating dashboard widget: ${e.message}")
        }
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

    private suspend fun handleUninstallApp(message: ProtocolMessage) {
        val pkg = ProtocolHelper.extractStringField(message, "package")
                ?: ProtocolHelper.extractStringField(message, "packageName")
                ?: run {
                    android.util.Log.e(TAG, "Uninstall failed: package field missing")
                    return
                }
        android.util.Log.d(TAG, "Requesting uninstall for: $pkg")
        deviceActionManager.uninstallApp(pkg)
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
            isTransferCancelled = false

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
                if (!transportManager.send(ProtocolHelper.createFileTransferStart(fileData))) {
                     Log.w(TAG, "Failed to send file start")
                     return@launch
                }
                // sendMessage(ProtocolHelper.createFileTransferStart(fileData))
                delay(100)

                val chunkSize = 32 * 1024
                val totalChunks = (fileBytes.size + chunkSize - 1) / chunkSize
                var lastReportedProgress = -1
                var lastReportTime = 0L
                var offset = 0
                var chunkNumber = 0

                while (offset < fileBytes.size) {
                    val end = minOf(offset + chunkSize, fileBytes.size)
                    val chunk = fileBytes.copyOfRange(offset, end)
                    val base64Chunk =
                            android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)

                    if (isTransferCancelled) {
                        Log.i(TAG, "File transfer cancelled by logic")
                        sendMessage(ProtocolHelper.createFileTransferEnd(success = false, error = "Cancelled by user"))
                         withContext(Dispatchers.Main) { 
                             // No UI callback needed for sender usually, or maybe error?
                         }
                        return@launch
                    }
                    val chunkData =
                            FileChunkData(
                                    offset = offset.toLong(),
                                    data = base64Chunk,
                                    chunkNumber = chunkNumber,
                                    totalChunks = totalChunks
                            )
                    // sendMessage(ProtocolHelper.createFileChunk(chunkData))
                    val chunkMsg = ProtocolHelper.createFileChunk(chunkData)
                    if (!transportManager.send(chunkMsg)) {
                        throw IOException("Failed to send chunk $chunkNumber")
                    }

                    // Report progress
                    val progress = ((chunkNumber + 1) * 95 / totalChunks.coerceAtLeast(1))
                    val currentTime = System.currentTimeMillis()
                    
                    // Throttle updates: Only update if changed AND (enough time passed OR it's the very first update)
                    if (progress > lastReportedProgress && (currentTime - lastReportTime >= 100 || lastReportedProgress == -1)) {
                        lastReportedProgress = progress
                        lastReportTime = currentTime
                        withContext(Dispatchers.Main) { callback?.onUploadProgress(progress) }
                    }

                    offset = end
                    chunkNumber++
                    //delay(10)
                }

                fileAckDeferred = kotlinx.coroutines.CompletableDeferred()
                waitingForFileAck = true
                if (!transportManager.send(ProtocolHelper.createFileTransferEnd(success = true))) {
                     Log.w(TAG, "Failed to send transfer end")
                }
                // sendMessageSync(ProtocolHelper.createFileTransferEnd(success = true))

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

                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                apps.add(AppInfo(name = appName, packageName = packageName, icon = iconBase64, isSystemApp = isSystemApp))
            } catch (_: Exception) {}
        }
        return apps
    }

    private fun showMirroredNotification(message: ProtocolMessage) {
        try {
            val notif = ProtocolHelper.extractData<NotificationData>(message)
            
            // Log notification
            NotificationLogManager.addLog(this, notif)

            // Check notification style preference
            val notificationStyle = prefs.getString("notification_style", "android") ?: "android"

            if (notificationStyle == "dynamic_island") {
                // Route to Dynamic Island
                performNotificationAlert()

                // Cache the notification data to avoid TransactionTooLargeException
                NotificationCache.put(notif.key, notif)

                val intent =
                        Intent(ACTION_SHOW_IN_DYNAMIC_ISLAND).apply {
                            putExtra(EXTRA_NOTIF_KEY, notif.key)
                            setPackage(packageName)
                        }
                sendBroadcast(intent)
                Log.d(TAG, "Notification sent to Dynamic Island: ${notif.title}")
                return
            } else if (notificationStyle == "full_screen") {
                 performNotificationAlert()
                 NotificationCache.put(notif.key, notif)
                 val intent = Intent(this, FullScreenNotificationActivity::class.java).apply {
                     putExtra(EXTRA_NOTIF_KEY, notif.key)
                     // Stacking Logic
                     val stacking = prefs.getBoolean("full_screen_stacking_enabled", true)
                     if (stacking) {
                         flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                     } else {
                         // If Stacking OFF, we clear top to replace existing activity
                         flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                     }
                 }
                 startActivity(intent)
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
                    builder.setVibrate(null)
                } else {
                    // Vibration handled by performNotificationAlert() manually
                    // Disable default vibration on the notification itself to avoid conflict
                    defaults = defaults and NotificationCompat.DEFAULT_VIBRATE.inv()
                    builder.setVibrate(null)
                }
            } else {
                defaults = defaults and NotificationCompat.DEFAULT_VIBRATE.inv()
                builder.setVibrate(null)
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
                // Use selfName from notification, fallback to localized "Me"
                val userName = notif.selfName ?: getString(R.string.reply_sender_me)

                // Use selfIcon if available, fallback to smallIcon
                val userIcon = (notif.selfIcon ?: notif.smallIcon)?.let { decodeBase64ToBitmap(it) }
                val userBuilder = androidx.core.app.Person.Builder().setName(userName)
                userIcon?.let {
                    userBuilder.setIcon(
                            androidx.core.graphics.drawable.IconCompat.createWithBitmap(it)
                    )
                }
                val user = userBuilder.build()

                val style = NotificationCompat.MessagingStyle(user)

                if (notif.isGroupConversation || notif.messages.any { it.senderName != null }) {
                    style.conversationTitle = notif.conversationTitle ?: title
                    style.isGroupConversation = notif.isGroupConversation
                }

                // Add all messages from the history
                for (msg in notif.messages) {
                    val msgSenderName = msg.senderName

                    // Robust "Me" detection:
                    // 1. null name (commonly used for 'self' in MessagingStyle)
                    // 2. Exact match with selfName from notification
                    // 3. Common self identifiers: "Me", "You"
                    val isMe = msgSenderName == null ||
                               msgSenderName == notif.selfName ||
                               msgSenderName.equals("Me", ignoreCase = true) ||
                               msgSenderName.equals("You", ignoreCase = true)

                    if (isMe) {
                        // For outgoing/echoed replies, use the user person with selfIcon
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
                                putExtra("app_name", appName)
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

                val useInAppReply = prefs.getBoolean("in_app_reply_dialog", false)
                Log.d(TAG, "showMirroredNotification: useInAppReply = $useInAppReply for key $key")

                if (action.isReplyAction) {
                    val actionBuilder = NotificationCompat.Action.Builder(
                        androidx.core.graphics.drawable.IconCompat.createWithResource(
                            this,
                            android.R.drawable.ic_menu_send
                        ),
                        action.title,
                        actionPendingIntent
                    )

                    if (!useInAppReply) {
                        // Create reply action with RemoteInput (standard behavior)
                        val remoteInput = androidx.core.app.RemoteInput.Builder(BluetoothService.EXTRA_REPLY_TEXT)
                            .setLabel(action.title)
                            .build()
                        actionBuilder.addRemoteInput(remoteInput)
                    }

                    builder.addAction(actionBuilder.build())
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

                        // Create intent to open full screen viewer
                        val fullScreenIntent = Intent(this, FullScreenMediaActivity::class.java).apply {
                            putExtra(FullScreenMediaActivity.EXTRA_NOTIFICATION_KEY, key)
                            putExtra(FullScreenMediaActivity.EXTRA_BIG_PICTURE_BASE64, pictureBase64)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        
                        val contentPendingIntent = PendingIntent.getActivity(
                            this,
                            (notifId * 31), // Unique request code to avoid collisions
                            fullScreenIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        builder.setContentIntent(contentPendingIntent)
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
                                    senderName = data.selfName ?: getString(R.string.reply_sender_me),
                                    senderIcon = data.selfIcon
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

        // Also dismiss from Full Screen if it's running
        val fullScreenIntent =
                Intent(ACTION_DISMISS_FROM_FULL_SCREEN).apply {
                    putExtra(EXTRA_NOTIF_KEY, key)
                    setPackage(packageName)
                }
        sendBroadcast(fullScreenIntent)
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

    private fun handleRequestRingtonePicker() {
        mainHandler.post {
            val intent = Intent(this, RingtonePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun playSound(uriString: String? = null) {
        if (!prefs.getBoolean("notification_sound_enabled", false)) return
        try {
            val uri = if (uriString != null) Uri.parse(uriString) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uriString != null) {
                // For custom sounds, MediaPlayer is usually better/more direct
                android.media.MediaPlayer().apply {
                    setDataSource(applicationContext, uri)
                    setAudioStreamType(AudioManager.STREAM_NOTIFICATION)
                    prepare()
                    start()
                    setOnCompletionListener { it.release() }
                }
            } else {
                // For default sounds, Ringtone class is more robust
                RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        audioAttributes = android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    } else {
                        streamType = AudioManager.STREAM_NOTIFICATION
                    }
                    play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound: ${e.message}")
        }
    }

    private fun playRingtone() {
        if (!prefs.getBoolean("phone_call_ringing_enabled", false)) return
        stopRingtone()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            currentRingtone = RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                } else {
                    streamType = AudioManager.STREAM_RING
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ringtone: ${e.message}")
        }
    }


    private fun performNotificationAlert() {
        val wakeScreen = prefs.getBoolean("wake_screen_enabled", false)
        val wakeScreenDnd = prefs.getBoolean("wake_screen_dnd_enabled", false)
        val vibrate = prefs.getBoolean("vibrate_enabled", false)
        val vibrateInSilent = prefs.getBoolean("vibrate_silent_enabled", false)

        if (wakeScreen) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val isSilent =
                    nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

            if (!isSilent || wakeScreenDnd) {
                wakeDeviceScreen()
            }
        }

        playSound(prefs.getString("notification_sound_uri", null))

        if (vibrate) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val isSilent =
                    nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

            if (!isSilent || vibrateInSilent) {
                try {
                    val vibrator = ContextCompat.getSystemService(this, android.os.Vibrator::class.java)
                    if (vibrator?.hasVibrator() == true) {
                        // Custom Vibration Logic
                        val strength = prefs.getInt("vibration_strength", 2) // 0=Off, 1=Low, 2=Med, 3=High
                        val patternIdx = prefs.getInt("vibration_pattern", 0) // 0=Default, 1=Double, 2=Long, 3=Heartbeat, 4=Tick, 5=Custom
                        val customLength = prefs.getInt("vibration_custom_length", 200)

                         Log.d(TAG, "performNotificationAlert: Vibrate enabled. Strength=$strength, Pattern=$patternIdx, CustomLength=$customLength")
                         if (strength > 0) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
                                // Map strength to Amplitude (1-255)
                                val amp = when (strength) {
                                    1 -> 80   // Low
                                    2 -> 160  // Medium
                                    3 -> 255  // High
                                    else -> 128
                                }

                                val effect: android.os.VibrationEffect? = when (patternIdx) {
                                    // 1: Double Click (Vibe-Pause-Vibe)
                                    1 -> android.os.VibrationEffect.createWaveform(longArrayOf(120, 80, 120), intArrayOf(amp, 0, amp), -1)
                                    // 2: Long
                                    2 -> android.os.VibrationEffect.createOneShot(600, amp)
                                    // 3: Heartbeat (Dub-dub)
                                    3 -> android.os.VibrationEffect.createWaveform(longArrayOf(100, 80, 120), intArrayOf(amp, 0, (amp * 0.8).toInt()), -1)
                                    // 4: Tick
                                    4 -> android.os.VibrationEffect.createOneShot(30, amp)
                                    // 5: Custom
                                    5 -> android.os.VibrationEffect.createOneShot(customLength.toLong(), amp)
                                    // 0: Default (Two pulses of 250ms)
                                    else -> android.os.VibrationEffect.createWaveform(longArrayOf(250, 250, 250), intArrayOf(amp, 0, amp), -1)
                                }
                                if (effect != null) {
                                    vibrator.vibrate(effect)
                                }
                            } else {
                                // Fallback for older APIs or devices without amplitude control
                                val pattern = when (patternIdx) {
                                    1 -> longArrayOf(0, 120, 80, 120)
                                    2 -> longArrayOf(0, 600)
                                    3 -> longArrayOf(0, 100, 80, 120)
                                    4 -> longArrayOf(0, 30)
                                    5 -> longArrayOf(0, customLength.toLong())
                                    else -> longArrayOf(0, 250, 250, 250)
                                }
                                vibrator.vibrate(pattern, -1)
                            }
                        }
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
        updateDashboardWidget()

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

    // Format transport type for display (e.g., "BT+BLE+LAN+Internet" -> "BT+BLE+WiFi+Net")
    private fun formatTransportType(type: String): String {
        val parts = mutableListOf<String>()
        if (type.contains("BT") || type.contains("Bluetooth")) parts.add("BT")
        if (type.contains("BLE")) parts.add("BLE")
        if (type.contains("LAN") || type.contains("WiFi")) parts.add("WiFi")
        if (type.contains("Internet")) parts.add("Net")
        return if (parts.isNotEmpty()) parts.joinToString("+") else type
    }

    // Returns Triple(ID, Channel, Text)
    private fun determineNotificationState(): Triple<Int, String, String> {
        return when {
            isConnected && currentDeviceName != null -> {
                val state = transportManager.connectionState.value
                val statusText = if (state is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected) {
                    getString(R.string.status_connected_to, currentDeviceName!!) + " via " + formatTransportType(state.type)
                } else {
                    getString(R.string.status_connected_to, currentDeviceName!!)
                }
                Triple(NOTIFICATION_ID_CONNECTED, CHANNEL_ID_CONNECTED, statusText)
            }
            isConnected && currentDeviceName == null -> {
                val state = transportManager.connectionState.value
                val statusText = if (state is com.ailife.rtosifycompanion.communication.TransportManager.ConnectionState.Connected) {
                    getString(R.string.status_connected) + " via " + formatTransportType(state.type)
                } else {
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
                ).apply {
                    enableVibration(false)
                    vibrationPattern = longArrayOf(0)
                    setShowBadge(true)
                }
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
                    // Find existing contact ID by name to delete it if it exists (overwrite behavior)
                    val existingContactId =
                            contentResolver.query(
                                            ContactsContract.Data.CONTENT_URI,
                                            arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
                                            "${ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                                            arrayOf(
                                                    contact.name,
                                                    ContactsContract.CommonDataKinds.StructuredName
                                                            .CONTENT_ITEM_TYPE
                                            ),
                                            null
                                    )
                                    ?.use {
                                        if (it.moveToFirst()) {
                                            it.getLong(it.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID))
                                        } else null
                                    }

                    if (existingContactId != null) {
                        try {
                            contentResolver.delete(
                                ContactsContract.RawContacts.CONTENT_URI,
                                "${ContactsContract.RawContacts._ID} = ?",
                                arrayOf(existingContactId.toString())
                            )
                            android.util.Log.d(TAG, "Deleted existing contact: ${contact.name} (ID: $existingContactId)")
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Error deleting existing contact ${contact.name}: ${e.message}")
                        }
                    }

                    if (true) { // Replaced !exists with true as we now delete existing ones above
                        val ops = arrayListOf<ContentProviderOperation>()
                        ops.add(
                                ContentProviderOperation.newInsert(
                                                ContactsContract.RawContacts.CONTENT_URI
                                        )
                                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                                        .withValue(ContactsContract.RawContacts.STARRED, if (contact.isStarred) 1 else 0)
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
            unregisterReceiver(batteryAppUsageHandler.batteryReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(aclDisconnectReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(audioNotificationReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(localBatteryReceiver)
        } catch (_: Exception) {}

        // Cleanup audio notification player
        releaseAudioNotificationPlayer()

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
        
        batteryAppUsageHandler.stopBatteryHistoryTracking()
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
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                val preview = fileManager.getFilePreview(path)
                sendMessage(ProtocolHelper.createResponsePreview(path, preview.base64, preview.textContent))
            } catch (e: Exception) {
                Log.e(TAG, "Error generating preview for $path: ${e.message}")
                sendMessage(ProtocolHelper.createResponsePreview(path, null, null))
            }
        }
    }






    private fun handleIncomingCall(message: ProtocolMessage) {
        val number = ProtocolHelper.extractStringField(message, "number") ?: "Unknown"
        val callerId = ProtocolHelper.extractStringField(message, "callerId")

        Log.d(TAG, "Incoming call: $number ($callerId)")

        playRingtone()

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val style = prefs.getString("notification_style", "android")
        val showCallsInDI = prefs.getBoolean("di_show_phone_calls", true)
        
        // Launch full-screen activity if NOT using dynamic island OR if phone calls are disabled in DI
        if (style != "dynamic_island" || !showCallsInDI) {
            val intent =
                    Intent(this, CallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("number", number)
                        putExtra("callerId", callerId)
                    }
            startActivity(intent)
        } else {
            // Route to Dynamic Island
            sendBroadcast(Intent("com.ailife.rtosifycompanion.INCOMING_CALL").apply {
                putExtra("number", number)
                putExtra("callerId", callerId)
                setPackage(packageName)
            })
        }
    }

    private fun handleCallStateChanged(message: ProtocolMessage) {
        val state = ProtocolHelper.extractStringField(message, "state")
        Log.d(TAG, "Call state changed: $state")

        if (state == "IDLE" || state == "OFFHOOK") {
            stopRingtone()
        }

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val style = prefs.getString("notification_style", "android")
        
        if (style != "dynamic_island" || state == "IDLE" || state == "OFFHOOK") {
            val intent =
                    Intent(this, CallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("state", state)
                    }
            startActivity(intent)
        }

        // Also broadcast to Dynamic Island
        sendBroadcast(Intent("com.ailife.rtosifycompanion.CALL_STATE_CHANGED").apply {
            putExtra("state", state)
            setPackage(packageName)
        })
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
        intent.setPackage(packageName) // Make it explicit for Android 14+
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

            val intent = Intent(this, DynamicIslandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "Started DynamicIslandService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DynamicIslandService: ${e.message}")
        }
    }

    private fun stopDynamicIslandService() {
        try {
            val intent = Intent(this, DynamicIslandService::class.java)
            stopService(intent)
            Log.d(TAG, "Stopped DynamicIslandService")
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

    private fun handleFileDetected(message: ProtocolMessage) {
        try {
            val data = Gson().fromJson(message.data, FileDetectedData::class.java)
            Log.d(TAG, "File Detected: ${data.name} (${data.type})")

            // Check notification style preference
            val notificationStyle = prefs.getString("notification_style", "android") ?: "android"

            performNotificationAlert()

            if (notificationStyle == "dynamic_island") {
                // Broadcast for DynamicIsland
                val intent = Intent(ACTION_FILE_DETECTED)
                intent.putExtra("data", Gson().toJson(data))
                intent.setPackage(packageName)
                sendBroadcast(intent)
            } else if (notificationStyle == "full_screen") {
                 val intent = Intent(this, FullScreenNotificationActivity::class.java).apply {
                     putExtra(EXTRA_NOTIF_JSON, Gson().toJson(data))
                     putExtra("is_file_detected", true)
                     putExtra("file_data_json", Gson().toJson(data))
                     // Stacking Logic - same as regular notifications
                     val stacking = prefs.getBoolean("full_screen_stacking_enabled", true)
                     if (stacking) {
                         flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                     } else {
                         // If Stacking OFF, we clear top to replace existing activity with latest
                         flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                     }
                 }
                 startActivity(intent)
            } else {
                // Show as standard Android notification
                showFileDetectedNotification(data)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling File Detected: ${e.message}")
        }
    }

    private fun showFileDetectedNotification(data: FileDetectedData) {
        val title = data.notificationTitle ?: getString(R.string.notification_file_detected_title)

        // Build notification text with filename, size, and duration
        val sizeText = formatFileSize(data.size)
        val durationText = data.duration?.let { " • ${formatDuration(it)}" } ?: ""
        val text = "${data.name} ($sizeText$durationText)"

        val notifId = ("file_${data.path}".hashCode() and 0x7FFFFFFF) + MIRRORED_NOTIFICATION_ID_START
        val notifKey = "file_${data.path.hashCode()}"

        // Cache the file data for later use
        fileDetectedCache[notifKey] = data

        val builder = NotificationCompat.Builder(this, MIRRORED_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(getFileTypeIcon(data.type))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVibrate(null) // Ensure builder doesn't trigger default vibration
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)

        // Decode bitmaps
        val thumbnail = data.thumbnail?.let { decodeBase64ToBitmap(it) }
        val appIcon = data.largeIcon?.let { decodeBase64ToBitmap(it) }

        // Large icon is always the app icon (or fallback to thumbnail)
        (appIcon ?: thumbnail)?.let { builder.setLargeIcon(it) }

        // Create tap intent to launch FullScreenMediaActivity
        val viewIntent = Intent(this, FullScreenMediaActivity::class.java).apply {
            putExtra(FullScreenMediaActivity.EXTRA_DATA_JSON, Gson().toJson(data))
            putExtra(FullScreenMediaActivity.EXTRA_NOTIFICATION_KEY, notifKey)
            data.thumbnail?.let { putExtra(FullScreenMediaActivity.EXTRA_BIG_PICTURE_BASE64, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(contentPendingIntent)

        when (data.type) {
            "image" -> {
                // BigPictureStyle: thumbnail as big picture, app icon stays as large icon
                if (thumbnail != null) {
                    builder.setStyle(NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbnail)
                        .bigLargeIcon(appIcon)) // App icon when expanded
                }
            }
            "video" -> {
                // BigPictureStyle for video thumbnail with play overlay effect
                if (thumbnail != null) {
                    builder.setStyle(NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbnail)
                        .bigLargeIcon(appIcon)
                        .setSummaryText(getString(R.string.notification_file_view)))
                }
            }
            "audio" -> {
                // Initialize audio state if not exists
                if (!audioNotifStates.containsKey(notifKey)) {
                    audioNotifStates[notifKey] = AudioNotifState()
                }

                // Add download action button for audio (initially shows download)
                val downloadIntent = Intent(ACTION_AUDIO_DOWNLOAD).apply {
                    putExtra(EXTRA_FILE_KEY, notifKey)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                    setPackage(packageName)
                }
                val downloadPendingIntent = PendingIntent.getBroadcast(
                    this,
                    notifId + 1,
                    downloadIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.stat_sys_download,
                    getString(R.string.notification_file_download),
                    downloadPendingIntent
                )

                // Show duration in expanded text if available
                data.duration?.let { duration ->
                    builder.setStyle(NotificationCompat.BigTextStyle()
                        .bigText("${data.name}\n${sizeText} • ${formatDuration(duration)}"))
                }

                // Make it ongoing so user can't dismiss while downloading/playing
                builder.setOngoing(false)
            }
            "text" -> {
                // BigTextStyle for text file preview
                if (!data.textContent.isNullOrEmpty()) {
                    builder.setStyle(NotificationCompat.BigTextStyle()
                        .bigText("${data.name}\n\n${data.textContent.take(200)}..."))
                }
            }
        }

        notificationManager.notify(notifId, builder.build())
        Log.d(TAG, "File notification shown: ${data.name}")
    }

    // Cache for file detected data (for FullScreenMediaActivity access)
    private val fileDetectedCache = mutableMapOf<String, FileDetectedData>()

    private fun getFileTypeIcon(type: String): Int {
        return when (type) {
            "image" -> android.R.drawable.ic_menu_gallery
            "video" -> android.R.drawable.ic_media_play
            "audio" -> android.R.drawable.ic_lock_silent_mode_off
            "text" -> android.R.drawable.ic_menu_edit
            else -> android.R.drawable.ic_menu_save
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    // ==================== Audio Notification Playback ====================

    private fun handleAudioDownload(fileKey: String, notifId: Int) {
        val fileData = fileDetectedCache[fileKey] ?: return
        val state = audioNotifStates.getOrPut(fileKey) { AudioNotifState() }

        // Check if already downloaded
        if (state.localPath != null && java.io.File(state.localPath!!).exists()) {
            // Already downloaded, start playing
            handleAudioPlayPause(fileKey, notifId)
            return
        }

        // Start downloading
        state.state = "downloading"
        state.downloadProgress = 0

        // Update notification to show downloading state
        updateAudioNotification(fileKey, notifId, fileData)

        // Request file download from phone
        if (isConnected) {
            Log.d(TAG, "Requesting audio file download: ${fileData.path}")
            val msg = ProtocolHelper.createRequestFileDownload(fileData.path)
            sendMessage(msg)

            // Store the key so we can match when download completes
            audioNotifCurrentKey = fileKey
            audioNotifCurrentNotifId = notifId
        }
    }

    private fun handleAudioPlayPause(fileKey: String, notifId: Int) {
        val fileData = fileDetectedCache[fileKey] ?: return
        val state = audioNotifStates.getOrPut(fileKey) { AudioNotifState() }

        when (state.state) {
            "idle", "downloading" -> {
                // Not downloaded yet, trigger download
                if (state.localPath == null || !java.io.File(state.localPath!!).exists()) {
                    handleAudioDownload(fileKey, notifId)
                    return
                }
                // Start playback
                startAudioNotifPlayback(fileKey, notifId, fileData, state)
            }
            "playing" -> {
                // Pause
                audioNotifPlayer?.pause()
                state.state = "paused"
                stopAudioProgressUpdater()
                updateAudioNotification(fileKey, notifId, fileData)
            }
            "paused" -> {
                // Resume
                audioNotifPlayer?.start()
                state.state = "playing"
                startAudioProgressUpdater(fileKey, notifId, fileData)
                updateAudioNotification(fileKey, notifId, fileData)
            }
        }
    }

    private fun handleAudioStop(fileKey: String, notifId: Int) {
        val fileData = fileDetectedCache[fileKey]
        val state = audioNotifStates[fileKey]

        releaseAudioNotificationPlayer()

        state?.apply {
            this.state = "idle"
            playbackProgress = 0
        }

        // Update notification back to download state or remove
        if (fileData != null && state != null) {
            updateAudioNotification(fileKey, notifId, fileData)
        }
    }

    private fun startAudioNotifPlayback(fileKey: String, notifId: Int, fileData: FileDetectedData, state: AudioNotifState) {
        releaseAudioNotificationPlayer()

        try {
            audioNotifPlayer = android.media.MediaPlayer().apply {
                setDataSource(this@BluetoothService, android.net.Uri.parse(state.localPath))
                setOnPreparedListener { mp ->
                    state.duration = mp.duration
                    mp.start()
                    state.state = "playing"
                    audioNotifIsPlaying = true
                    audioNotifCurrentKey = fileKey
                    audioNotifCurrentNotifId = notifId
                    updateAudioNotification(fileKey, notifId, fileData)
                    startAudioProgressUpdater(fileKey, notifId, fileData)
                }
                setOnCompletionListener {
                    state.state = "idle"
                    state.playbackProgress = 0
                    audioNotifIsPlaying = false
                    stopAudioProgressUpdater()
                    updateAudioNotification(fileKey, notifId, fileData)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Audio playback error: what=$what, extra=$extra")
                    state.state = "idle"
                    audioNotifIsPlaying = false
                    stopAudioProgressUpdater()
                    updateAudioNotification(fileKey, notifId, fileData)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio playback: ${e.message}")
            state.state = "idle"
        }
    }

    private fun startAudioProgressUpdater(fileKey: String, notifId: Int, fileData: FileDetectedData) {
        stopAudioProgressUpdater()

        audioProgressRunnable = object : Runnable {
            override fun run() {
                try {
                    val player = audioNotifPlayer
                    val state = audioNotifStates[fileKey]
                    if (player != null && state != null && player.isPlaying) {
                        state.playbackProgress = player.currentPosition
                        updateAudioNotification(fileKey, notifId, fileData)
                        audioProgressHandler.postDelayed(this, 1000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating audio progress: ${e.message}")
                }
            }
        }
        audioProgressHandler.post(audioProgressRunnable!!)
    }

    private fun stopAudioProgressUpdater() {
        audioProgressRunnable?.let { audioProgressHandler.removeCallbacks(it) }
        audioProgressRunnable = null
    }

    private fun releaseAudioNotificationPlayer() {
        stopAudioProgressUpdater()
        try {
            audioNotifPlayer?.stop()
            audioNotifPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio player: ${e.message}")
        }
        audioNotifPlayer = null
        audioNotifIsPlaying = false
    }

    private fun updateAudioNotification(fileKey: String, notifId: Int, fileData: FileDetectedData) {
        val state = audioNotifStates[fileKey] ?: return

        val title = fileData.notificationTitle ?: getString(R.string.notification_file_detected_title)
        val sizeText = formatFileSize(fileData.size)

        val builder = NotificationCompat.Builder(this, MIRRORED_CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)

        // Large icon
        val appIcon = fileData.largeIcon?.let { decodeBase64ToBitmap(it) }
        appIcon?.let { builder.setLargeIcon(it) }

        when (state.state) {
            "downloading" -> {
                builder.setContentText(getString(R.string.notification_file_downloading, state.downloadProgress))
                builder.setProgress(100, state.downloadProgress, false)
                builder.setOngoing(true)
            }
            "playing", "paused" -> {
                val progress = state.playbackProgress
                val duration = state.duration
                val progressText = "${formatDuration(progress.toLong())} / ${formatDuration(duration.toLong())}"
                builder.setContentText("${fileData.name} - $progressText")

                // Progress bar showing playback progress
                if (duration > 0) {
                    builder.setProgress(duration, progress, false)
                }

                // Play/Pause action
                val playPauseIntent = Intent(ACTION_AUDIO_PLAY_PAUSE).apply {
                    putExtra(EXTRA_FILE_KEY, fileKey)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                    setPackage(packageName)
                }
                val playPausePendingIntent = PendingIntent.getBroadcast(
                    this, notifId + 1, playPauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val playPauseIcon = if (state.state == "playing")
                    android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                val playPauseLabel = if (state.state == "playing")
                    getString(R.string.notification_file_pause_audio) else getString(R.string.notification_file_play_audio)
                builder.addAction(playPauseIcon, playPauseLabel, playPausePendingIntent)

                // Stop action
                val stopIntent = Intent(ACTION_AUDIO_STOP).apply {
                    putExtra(EXTRA_FILE_KEY, fileKey)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                    setPackage(packageName)
                }
                val stopPendingIntent = PendingIntent.getBroadcast(
                    this, notifId + 2, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_file_stop_audio), stopPendingIntent)

                builder.setOngoing(state.state == "playing")
            }
            else -> { // idle
                val durationText = fileData.duration?.let { " • ${formatDuration(it)}" } ?: ""
                builder.setContentText("${fileData.name} ($sizeText$durationText)")

                // Show download or play button depending on if file exists
                val hasLocalFile = state.localPath != null && java.io.File(state.localPath!!).exists()
                val actionIntent = Intent(if (hasLocalFile) ACTION_AUDIO_PLAY_PAUSE else ACTION_AUDIO_DOWNLOAD).apply {
                    putExtra(EXTRA_FILE_KEY, fileKey)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                    setPackage(packageName)
                }
                val actionPendingIntent = PendingIntent.getBroadcast(
                    this, notifId + 1, actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val actionIcon = if (hasLocalFile) android.R.drawable.ic_media_play else android.R.drawable.stat_sys_download
                val actionLabel = if (hasLocalFile) getString(R.string.notification_file_play_audio) else getString(R.string.notification_file_download)
                builder.addAction(actionIcon, actionLabel, actionPendingIntent)

                builder.setOngoing(false)
                builder.setAutoCancel(true)
            }
        }

        notificationManager.notify(notifId, builder.build())
    }

    // Called when file download completes (from handleFileTransferEnd)
    fun onAudioFileDownloadComplete(filePath: String) {
        val fileKey = audioNotifCurrentKey ?: return
        val notifId = audioNotifCurrentNotifId
        val fileData = fileDetectedCache[fileKey] ?: return
        val state = audioNotifStates[fileKey] ?: return

        Log.d(TAG, "Audio file download complete: $filePath")
        state.localPath = filePath
        state.state = "idle"
        state.downloadProgress = 100

        // Auto-start playback after download
        startAudioNotifPlayback(fileKey, notifId, fileData, state)
    }

    // Called during file download progress (from handleFileChunk)
    fun onAudioFileDownloadProgress(progress: Int) {
        val fileKey = audioNotifCurrentKey ?: return
        val notifId = audioNotifCurrentNotifId
        val fileData = fileDetectedCache[fileKey] ?: return
        val state = audioNotifStates[fileKey] ?: return

        if (state.state == "downloading") {
            state.downloadProgress = progress
            updateAudioNotification(fileKey, notifId, fileData)
        }
    }
}
