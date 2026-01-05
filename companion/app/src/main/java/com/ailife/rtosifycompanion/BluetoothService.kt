package com.ailife.rtosifycompanion

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import com.ailife.rtosifycompanion.R
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.content.ContentValues
import android.content.ContentProviderOperation
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
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
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var connectionJob: Job? = null
    private var serverJob: Job? = null
    private var statusUpdateJob: Job? = null
    private lateinit var healthDataCollector: HealthDataCollector
    private var liveMeasurementJob: Job? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private val shizukuLock = Any()
    private var globalOutputStream: DataOutputStream? = null

    private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val APP_NAME = "RTOSifyApp"

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "AppPrefs"

    private var lastValidWifiSsid: String = ""

    @Volatile
    private var lastMessageTime: Long = 0L

    @Volatile
    private var isTransferring: Boolean = false

    // Variáveis de Recepção de Arquivo
    private var receiveFile: File? = null
    private var receiveFileOutputStream: FileOutputStream? = null
    private var receiveTotalSize: Long = 0
    private var receiveBytesRead: Long = 0

    private val HEARTBEAT_INTERVAL = 20000L
    private val CONNECTION_TIMEOUT = 60000L

    private val notificationMap = ConcurrentHashMap<String, Int>()
    private val notificationDataMap = ConcurrentHashMap<String, NotificationData>()

    // REFATORAÇÃO: Controle mais robusto de notificações
    @Volatile
    private var currentNotificationStatus: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    // Cache do NotificationManager para evitar múltiplas chamadas getSystemService
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    interface ServiceCallback {
        fun onStatusChanged(status: String)
        fun onDeviceConnected(deviceName: String)
        fun onDeviceDisconnected()
        fun onError(message: String)
        fun onScanResult(devices: List<BluetoothDevice>)
        fun onAppListReceived(appsJson: String)
        fun onUploadProgress(progress: Int)
        fun onWatchStatusUpdated(batteryLevel: Int, isCharging: Boolean, wifiSsid: String, dndEnabled: Boolean) {}
    }

    var callback: ServiceCallback? = null

    @Volatile
    var currentStatus: String = "" // Will be initialized in onCreate
    @Volatile
    var currentDeviceName: String? = null
    @Volatile
    var isConnected: Boolean = false

    // JSON Protocol - message types defined in Protocol.kt

    companion object {
        const val ACTION_SEND_NOTIF_TO_WATCH = "com.ailife.rtosify.ACTION_SEND_NOTIF"
        const val ACTION_SEND_REMOVE_TO_WATCH = "com.ailife.rtosify.ACTION_SEND_REMOVE"
        const val ACTION_WATCH_DISMISSED_LOCAL = "com.ailife.rtosify.DISMISSED_LOCAL"
        const val ACTION_CMD_DISMISS_ON_PHONE = "com.ailife.rtosify.CMD_DISMISS_PHONE"
        const val ACTION_CMD_EXECUTE_ACTION = "com.ailife.rtosify.CMD_EXECUTE_ACTION"
        const val ACTION_CMD_SEND_REPLY = "com.ailife.rtosify.CMD_SEND_REPLY"

        const val EXTRA_NOTIF_JSON = "extra_notif_json"
        const val EXTRA_NOTIF_KEY = "extra_notif_key"
        const val EXTRA_ACTION_KEY = "extra_action_key"
        const val EXTRA_REPLY_TEXT = "extra_reply_text"

        // IDs DISTINTOS para garantir limpeza correta ao trocar de estado
        const val NOTIFICATION_ID_WAITING = 10
        const val NOTIFICATION_ID_CONNECTED = 11
        const val NOTIFICATION_ID_DISCONNECTED = 12
        const val NOTIFICATION_ID_DISCONNECTION_ALERT = 13  // Alert shown when phone disconnects

        const val INSTALL_NOTIFICATION_ID = 2
        const val MIRRORED_NOTIFICATION_ID_START = 1000

        const val CHANNEL_ID_WAITING = "channel_status_waiting"
        const val CHANNEL_ID_CONNECTED = "channel_status_connected"
        const val CHANNEL_ID_DISCONNECTED = "channel_status_disconnected"
        const val CHANNEL_ID_DISCONNECTION_ALERT = "channel_disconnection_alert"

        const val INSTALL_CHANNEL_ID = "install_channel"
        const val MIRRORED_CHANNEL_ID = "mirrored_notifications"

        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"

        private const val TAG = "BluetoothService"
        private const val DEBUG_NOTIFICATIONS = false // Ative para debug
    }

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isConnected) return
            when (intent?.action) {
                ACTION_SEND_NOTIF_TO_WATCH -> {
                    val jsonString = intent.getStringExtra(EXTRA_NOTIF_JSON)
                    if (jsonString != null) {
                        try {
                            val gson = com.google.gson.Gson()
                            val notifData = gson.fromJson(jsonString, NotificationData::class.java)
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
            }
        }
    }

    private val watchDismissReceiver = object : BroadcastReceiver() {
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

    private var userServiceConnection: Shizuku.UserServiceArgs? = null
    private var userService: IUserService? = null
    
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("user_service")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    private val userServiceConn = object : ServiceConnection {
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
        bluetoothAdapter = btManager.adapter
        createNotificationChannel()

        val filterInternal = IntentFilter().apply {
            addAction(ACTION_SEND_NOTIF_TO_WATCH)
            addAction(ACTION_SEND_REMOVE_TO_WATCH)
        }
        val filterWatch = IntentFilter(ACTION_WATCH_DISMISSED_LOCAL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(internalReceiver, filterInternal, RECEIVER_NOT_EXPORTED)
            registerReceiver(watchDismissReceiver, filterWatch, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(internalReceiver, filterInternal)
            registerReceiver(watchDismissReceiver, filterWatch)
        }

        // Initialize health data collector
        healthDataCollector = HealthDataCollector(this)

        // Bind to Shizuku UserService if available
        bindUserServiceIfNeeded()
    }
    
    private fun bindUserServiceIfNeeded() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
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
    
    private fun ensureUserServiceBound() {
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
                    
                    // Try to bind
                    bindUserServiceIfNeeded()
                    
                    // Wait for binding to complete (up to 3 seconds)
                    val maxWaitTime = 3000L
                    val startTime = System.currentTimeMillis()
                    while (userService == null && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                        Thread.sleep(100)
                    }
                    
                    if (userService != null) {
                        Log.i(TAG, "✅ UserService bound successfully after ${System.currentTimeMillis() - startTime}ms")
                    } else {
                        Log.w(TAG, "❌ UserService binding timed out after ${maxWaitTime}ms")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
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
                sendMessage(ProtocolHelper.createSendNotificationReply(notifKey, actionKey, replyText))
                
                // CRITICAL: Refresh the notification locally to stop the "spinning" indicator
                // and show the reply in history immediately
                refreshNotification(notifKey, replyText)
            }
            return START_NOT_STICKY
        }

        // CRÍTICO: Chamar startForeground imediatamente para cumprir a promessa feita no BootReceiver.
        updateForegroundNotification()

        // Inicializa lógica se não estiver conectado
        if (!isConnected) {
            initializeLogicFromPrefs()
        }

        if (DEBUG_NOTIFICATIONS) Log.d(TAG, "onStartCommand: Service started, isConnected=$isConnected")

        return START_STICKY
    }

    private fun initializeLogicFromPrefs() {
        val deviceType = prefs.getString("device_type", null)
        if (deviceType != null && bluetoothAdapter?.isEnabled == true) {
            if (deviceType == "PHONE") startSmartphoneLogic() else startWatchLogic()
        }
    }

    @SuppressLint("MissingPermission")
    fun startSmartphoneLogic() {
        if (connectionJob?.isActive == true) return
        val lastMac = prefs.getString("last_mac", null)
        if (lastMac != null) {
            val device = bluetoothAdapter?.getRemoteDevice(lastMac)
            if (device != null) startAutoReconnectLoop(device) else startScanForDevices()
        } else {
            startScanForDevices()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanForDevices() {
        updateStatus(getString(R.string.status_searching))
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            val pairedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
            val successfulConnections = mutableListOf<Pair<BluetoothDevice, BluetoothSocket>>()
            supervisorScope {
                val jobs = pairedDevices.map { device ->
                    async {
                        try {
                            val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                            socket.connect()
                            device to socket
                        } catch (_: IOException) { null }
                    }
                }
                jobs.awaitAll().filterNotNull().forEach { successfulConnections.add(it) }
            }
            withContext(Dispatchers.Main) {
                if (successfulConnections.isEmpty()) {
                    updateStatus(getString(R.string.status_no_phone_found))
                    callback?.onError(getString(R.string.status_open_app_phone))
                } else if (successfulConnections.size == 1) {
                    val (device, socket) = successfulConnections[0]
                    savePreference("last_mac", device.address)
                    startAutoReconnectLoop(device, socket)
                } else {
                    successfulConnections.forEach { try { it.second.close() } catch (_: Exception){} }
                    callback?.onScanResult(successfulConnections.map { it.first })
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        savePreference("last_mac", device.address)
        startAutoReconnectLoop(device)
    }

    @SuppressLint("MissingPermission")
    private fun startAutoReconnectLoop(device: BluetoothDevice, initialSocket: BluetoothSocket? = null) {
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            var socketToUse = initialSocket
            while (isActive) {
                try {
                    if (socketToUse == null) {
                        updateStatus(getString(R.string.status_connecting_to, device.name))
                        socketToUse = device.createRfcommSocketToServiceRecord(APP_UUID)
                        socketToUse!!.connect()
                    }
                    handleConnectedSocket(socketToUse, device.name)
                    socketToUse = null
                    if (isActive) { updateStatus(getString(R.string.status_starting)); delay(3000) }
                } catch (_: IOException) {
                    socketToUse = null
                    if (isActive) { updateStatus(getString(R.string.status_starting)); delay(5000) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startWatchLogic() {
        if (serverJob?.isActive == true) return
        serverJob?.cancel()
        serverJob = serviceScope.launch {
            updateStatus(getString(R.string.status_waiting))
            while (isActive) {
                var serverSocket: BluetoothServerSocket?
                try {
                    serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                } catch (_: IOException) {
                    delay(3000)
                    continue
                }

                var socket: BluetoothSocket? = null
                while (socket == null && isActive) {
                    try {
                        socket = serverSocket?.accept(5000)
                    } catch (_: IOException) {
                    }
                }

                if (socket != null) {
                    try {
                        serverSocket?.close()
                    } catch (_: Exception) {
                    }
                    handleConnectedSocket(socket, socket.remoteDevice?.name ?: getString(R.string.device_name_default))
                    if (isActive) {
                        updateStatus(getString(R.string.status_waiting))
                    }
                }
            }
        }
    }

    private suspend fun handleConnectedSocket(socket: BluetoothSocket, deviceName: String) {
        this.bluetoothSocket = socket
        globalOutputStream = DataOutputStream(socket.outputStream)
        val inputStream = DataInputStream(socket.inputStream)

        // Define estado de conexão ANTES de atualizar status
        isConnected = true
        isTransferring = false
        lastMessageTime = System.currentTimeMillis()
        currentDeviceName = deviceName

        // Atualiza status E notificação simultaneamente
        updateStatus(getString(R.string.status_connected_to, deviceName))

        // Notifica callback
        withContext(Dispatchers.Main) {
            callback?.onDeviceConnected(deviceName)
        }

        serviceScope.launch { heartbeatLoop() }

        val deviceType = prefs.getString("device_type", "PHONE")
        if (deviceType == "WATCH") {
            startWatchStatusSender()
            // Health data is now request-based, not continuous
        }

        try {
            while (currentCoroutineContext().isActive) {
                val message = readMessage(inputStream)
                lastMessageTime = System.currentTimeMillis()
                
                Log.d(TAG, "📩 Received message type: ${message.type}")

                when (message.type) {
                    MessageType.HEARTBEAT -> { /* IGNORE */ }
                    MessageType.REQUEST_APPS -> handleRequestApps()
                    MessageType.RESPONSE_APPS -> handleResponseApps(message)
                    MessageType.NOTIFICATION_POSTED -> showMirroredNotification(message)
                    MessageType.NOTIFICATION_REMOVED -> dismissLocalNotification(message)
                    MessageType.DISMISS_NOTIFICATION -> requestDismissOnPhone(message)
                    MessageType.EXECUTE_NOTIFICATION_ACTION -> handleExecuteNotificationAction(message)
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
                    MessageType.CAMERA_FRAME -> handleCameraFrame(message)
                    MessageType.REQUEST_FILE_LIST -> handleRequestFileList(message)
                    MessageType.REQUEST_FILE_DOWNLOAD -> handleRequestFileDownload(message)
                    MessageType.DELETE_FILE -> handleDeleteFile(message)
                    MessageType.UPDATE_SETTINGS -> handleUpdateSettings(message)
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
                }
            }
        } catch (_: IOException) {
            // Conexão perdida
        } finally {
            val wasConnected = isConnected

            forceDisconnect()
            isConnected = false
            currentDeviceName = null

            if (wasConnected) {
                updateStatus(getString(R.string.status_disconnected))
                
                val shouldNotify = prefs.getBoolean("notify_on_disconnect", false)
                Log.d(TAG, "Device disconnected. shouldNotify on disconnect: $shouldNotify")
                
                // Show disconnection alert if enabled
                if (shouldNotify) {
                    showDisconnectionNotification()
                }
            }

            withContext(Dispatchers.Main) {
                callback?.onDeviceDisconnected()
            }
        }
    }

    private fun handleUpdateSettings(message: ProtocolMessage) {
        try {
            val settings = ProtocolHelper.extractData<SettingsUpdateData>(message)
            Log.d(TAG, "Received settings update: notifyOnDisconnect=${settings.notifyOnDisconnect}")
            if (settings.notifyOnDisconnect != null) {
                prefs.edit().putBoolean("notify_on_disconnect", settings.notifyOnDisconnect).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling settings update: ${e.message}")
        }
    }

    private fun showDisconnectionNotification() {
        Log.d(TAG, "Showing disconnection notification")
        val title = getString(R.string.notification_phone_disconnected_title)
        val text = getString(R.string.notification_phone_disconnected_text)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_DISCONNECTION_ALERT)
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

    @Throws(IOException::class)
    private fun readMessage(inputStream: DataInputStream): ProtocolMessage {
        val length = inputStream.readInt()
        if (length !in 0..10_000_000) throw IOException("Invalid message size: $length")
        val buffer = ByteArray(length)
        inputStream.readFully(buffer)
        val json = String(buffer, Charsets.UTF_8)
        return ProtocolMessage.fromJson(json)
    }

    private suspend fun heartbeatLoop() {
        while (currentCoroutineContext().isActive) {
            delay(HEARTBEAT_INTERVAL)
            if (!isConnected) continue
            if (System.currentTimeMillis() - lastMessageTime > CONNECTION_TIMEOUT) {
                forceDisconnect()
                break
            }
            try { sendMessage(ProtocolHelper.createHeartbeat()) }
            catch (_: Exception) { break }
        }
    }

    private fun forceDisconnect() {
        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {}

        statusUpdateJob?.cancel()
        liveMeasurementJob?.cancel()
    }

    fun sendMessage(message: ProtocolMessage) {
        serviceScope.launch(Dispatchers.IO) {
            if (!isConnected) return@launch
            val out = globalOutputStream ?: return@launch
            try {
                val jsonBytes = message.toBytes()
                synchronized(out) {
                    out.writeInt(jsonBytes.size)
                    out.write(jsonBytes)
                    out.flush()
                }
            } catch (_: Exception) {
                forceDisconnect()
            }
        }
    }

    private suspend fun sendMessageSync(message: ProtocolMessage) {
        if (!isConnected) return
        val out = globalOutputStream ?: return
        try {
            val jsonBytes = message.toBytes()
            synchronized(out) {
                out.writeInt(jsonBytes.size)
                out.write(jsonBytes)
                out.flush()
            }
        } catch (_: Exception) {
            forceDisconnect()
        }
    }

    // ========================================================================
    // STATUS DO WATCH (BATERIA, WIFI, DND)
    // ========================================================================

    private fun startWatchStatusSender() {
        statusUpdateJob?.cancel()
        statusUpdateJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && isConnected) {
                try {
                    val status = collectWatchStatus()
                    sendMessage(ProtocolHelper.createStatusUpdate(status))
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao enviar status: ${e.message}")
                }
                delay(5000)
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
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

                if (wm.isWifiEnabled) {
                    val info = wm.connectionInfo

                    if (info != null && info.supplicantState == SupplicantState.COMPLETED) {
                        val rawSsid = info.ssid

                        if (rawSsid == "<unknown ssid>" || rawSsid.isEmpty()) {
                            wifiSsid = lastValidWifiSsid.ifEmpty {
                                "Conectado"
                            }
                        } else {
                            val cleanSsid = rawSsid.replace("\"", "")
                            if (cleanSsid != "<unknown ssid>") {
                                lastValidWifiSsid = cleanSsid
                                wifiSsid = cleanSsid
                            } else if (lastValidWifiSsid.isNotEmpty()) {
                                wifiSsid = lastValidWifiSsid
                            } else {
                                wifiSsid = "Conectado"
                            }
                        }
                    } else {
                        lastValidWifiSsid = ""
                        wifiSsid = "Desconectado"
                    }
                } else {
                    lastValidWifiSsid = ""
                    wifiSsid = "Wifi Off"
                }
            } else {
                wifiSsid = "Sem Permissão"
            }
        } catch (_: Exception) {
            wifiSsid = "Erro Wifi"
        }

        return StatusUpdateData(
            battery = batteryLevel,
            charging = isCharging,
            dnd = dndEnabled,
            wifi = wifiSsid
        )
    }

    // ========================================================================
    // HEALTH DATA (STEPS, HR, OXYGEN)
    // ========================================================================

    private suspend fun handleRequestHealthData() {
        try {
            Log.d(TAG, "Received REQUEST_HEALTH_DATA, collecting data...")
            // Collect health data once when requested (not continuously)
            val healthData = healthDataCollector.collectCurrentHealthData()
            Log.d(TAG, "Collected health data: steps=${healthData.steps}, cal=${healthData.calories}, hr=${healthData.heartRate}")
            sendMessage(ProtocolHelper.createHealthDataUpdate(healthData))
            Log.d(TAG, "Sent health data update to phone")
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting health data: ${e.message}")
        }
    }

    private fun startLiveMeasurementMode(type: String) {
        liveMeasurementJob?.cancel()
        liveMeasurementJob = serviceScope.launch(Dispatchers.IO) {
            try {
                // Perform one high-priority measurement (can take up to 60s)
                val healthData = healthDataCollector.measureNow(type) { instantValue ->
                    // Send instant update to phone
                    sendMessage(ProtocolHelper.createHealthDataUpdate(
                        HealthDataUpdate(
                            steps = 0,
                            distance = 0f,
                            calories = 0,
                            heartRate = if (type == "HR") instantValue else null,
                            heartRateTimestamp = System.currentTimeMillis(),
                            bloodOxygen = if (type == "OXYGEN") instantValue else null,
                            oxygenTimestamp = System.currentTimeMillis(),
                            isInstant = true
                        )
                    ))
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
            Log.d(TAG, "Health history request: ${request.type}, ${request.startTime}-${request.endTime}")

            val response = healthDataCollector.queryHistoricalData(
                request.type, request.startTime, request.endTime
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
                callback?.onWatchStatusUpdated(status.battery, status.charging, status.wifi, status.dnd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro parser status: ${e.message}")
        }
    }

    fun sendDndCommand(enable: Boolean) {
        sendMessage(ProtocolHelper.createSetDnd(enable))
    }

    fun sendMakeCall(phoneNumber: String) {
        sendMessage(ProtocolHelper.createMakeCall(phoneNumber))
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
            android.util.Log.d(TAG, "File transfer starting: ${fileData.name}, ${fileData.size} bytes, path: ${fileData.path}")

            val root = android.os.Environment.getExternalStorageDirectory()
            var targetFile: File
            finalDestinationPath = null

            if (fileData.path != null) {
                val absolutePath = toAbsolutePath(fileData.path)
                val isRestricted = absolutePath.contains("Android/data", ignoreCase = true)
                
                if (isRestricted) {
                    android.util.Log.d(TAG, "Target path is restricted, using temp file in externalCacheDir")
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

            android.util.Log.d(TAG, "Received chunk ${chunkData.chunkNumber}/${chunkData.totalChunks} at offset ${chunkData.offset}")

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
                    android.util.Log.e(TAG, "Checksum mismatch! Expected: $expectedChecksum, Got: $actualChecksum")
                    file.delete()
                    cleanupFileTransfer()
                    // Send failure acknowledgment
                    sendMessage(ProtocolHelper.createFileTransferEnd(success = false, error = "Checksum mismatch"))
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
                    sendMessage(ProtocolHelper.createFileTransferEnd(success = false, error = "Failed to move file to restricted destination"))
                    cleanupFileTransfer()
                    return
                }
                // Update file reference for further processing (like APK install)
                // Note: File install might fail if it's in a restricted area even with Shizuku move, 
                // but we can try.
            }

            // Show install dialog ONLY if type is "APK" (sent from app install section, not file manager)
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
            } else {
                android.util.Log.d(TAG, "Regular file received (type=$receivingFileType), no install dialog needed.")
            }

            cleanupFileTransfer()

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error finalizing file transfer: ${e.message}")
            cleanupFileTransfer()
            // Send failure acknowledgment
            sendMessage(ProtocolHelper.createFileTransferEnd(success = false, error = e.message ?: "Unknown error"))
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
            path.startsWith("/storage/emulated/0") -> path.replaceFirst("/storage/emulated/0", "/sdcard")
            path.startsWith("/data/media/0") -> path.replaceFirst("/data/media/0", "/sdcard")
            else -> path
        }
    }

    private fun toAbsolutePath(path: String): String {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        return when {
            path == "/" || path.isEmpty() -> root
            path.startsWith("/") -> {
                if (path.startsWith("/storage/") || path.startsWith("/sdcard/") || path.startsWith("/data/")) {
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
                files = targetDir.listFiles()?.map {
                    FileInfo(it.name, it.length(), it.isDirectory, it.lastModified())
                }?.toMutableList()
            }
        }

        val resultFiles = files ?: mutableListOf()

        // 4. Inject "com.ailife.ClockSkinCoco" if we are in Android/data and it's missing (hidden by Android 11+)
        val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (absolutePath == File(storageRoot, "Android/data").absolutePath) {
            if (resultFiles.none { it.name == "com.ailife.ClockSkinCoco" }) {
                resultFiles.add(FileInfo("com.ailife.ClockSkinCoco", 0, true, System.currentTimeMillis()))
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
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
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
                // We keep it in cacheDir for now, sendFile is async and we don't want to delete it too early
                // cleanupFileTransfer will handle its own temp files, but this is a manual download temp.
            }
        } else {
            sendMessage(ProtocolHelper.createFileTransferEnd(success = false, error = "File not found or inaccessible"))
        }
    }

    private suspend fun handleDeleteFile(message: ProtocolMessage) {
        val path = ProtocolHelper.extractStringField(message, "path") ?: return
        val absolutePath = toAbsolutePath(path)
        
        android.util.Log.d(TAG, "Delete requested: $path -> $absolutePath")
        
        val success = deleteFileDispatcher(absolutePath)

        if (success) {
            val parentPath = if (path.lastIndexOf('/') > 0) path.substring(0, path.lastIndexOf('/')) else "/"
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
            val parentPath = if (newPath.lastIndexOf('/') > 0) newPath.substring(0, newPath.lastIndexOf('/')) else "/"
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
            val parentPath = if (dstPath.lastIndexOf('/') > 0) dstPath.substring(0, dstPath.lastIndexOf('/')) else "/"
            handleRequestFileList(ProtocolHelper.createRequestFileList(parentPath))
        }
    }

    private suspend fun handleCreateFolder(message: ProtocolMessage) {
        val path = message.data.get("path")?.asString ?: return
        val absPath = toAbsolutePath(path)
        
        android.util.Log.d(TAG, "Create folder requested: $absPath")

        val success = createFolderDispatcher(absPath)

        if (success) {
            val parentPath = if (path.lastIndexOf('/') > 0) path.substring(0, path.lastIndexOf('/')) else "/"
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
            Toast.makeText(this@BluetoothService, "Watch face set: $facePath", Toast.LENGTH_SHORT).show()
        }
    }

    // ========================================================================
    // UNIFIED FILE OPERATION DISPATCHERS (Shizuku -> Root -> Standard)
    // ========================================================================

    private fun listFilesDispatcher(path: String): List<FileInfo>? {
        // 1. Try Shizuku (UserService)
        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                val json = userService?.listFiles(path)
                if (json != null && json != "[]") {
                    Log.d(TAG, "Shizuku listFiles success for $path")
                    return Gson().fromJson(json, object : com.google.gson.reflect.TypeToken<List<FileInfo>>() {}.type)
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
                    Log.e(TAG, "Root ls failed for $rootPath: code=${result.code}, err=${result.err}")
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

    private fun deleteFileDispatcher(path: String): Boolean {
        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                if (userService?.deleteFile(path) == true) return true
            } catch (e: Exception) { Log.e(TAG, "Shizuku deleteFile failed: ${e.message}") }
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
            } catch (e: Exception) { Log.e(TAG, "Shizuku renameFile failed: ${e.message}") }
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
        // Ensure destination parent directory exists
        val dstParent = dst.substringBeforeLast("/", "/")
        if (dstParent != "/") {
            createFolderDispatcher(dstParent)
        }

        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                if (userService?.moveFile(src, dst) == true) return true
            } catch (e: Exception) { Log.e(TAG, "Shizuku moveFile failed: ${e.message}") }
        }

        if (Shell.getShell().isRoot) {
            val rootSrc = translatePathForRoot(src)
            val rootDst = translatePathForRoot(dst)
            val result = Shell.cmd("mv \"$rootSrc\" \"$rootDst\"").exec()
            if (result.isSuccess) return true
        }

        // 3. Standard Fallback
        val srcFile = File(src)
        val dstFile = File(dst)
        if (srcFile.renameTo(dstFile)) return true
        
        return try {
            srcFile.copyRecursively(dstFile, true)
            srcFile.deleteRecursively()
            true
        } catch (e: Exception) { false }
    }

    private fun createFolderDispatcher(path: String): Boolean {
        // 1. Try Shizuku
        if (isUsingShizuku()) {
            try {
                ensureUserServiceBound()
                if (userService?.makeDirectory(path) == true) return true
            } catch (e: Exception) { Log.e(TAG, "Shizuku makeDirectory failed: ${e.message}") }
        }

        // 2. Try Root
        if (Shell.getShell().isRoot) {
            val rootPath = translatePathForRoot(path)
            val result = Shell.cmd("mkdir -p \"$rootPath\"").exec()
            if (result.isSuccess) return true
        }

        // 3. Fallback to SAF
        val watchFacePath = "Android/data/com.ailife.ClockSkinCoco"
        val relPath = getRelPath(path)
        if (relPath.startsWith(watchFacePath)) {
            val doc = getDocumentFileForPath(relPath, true)
            return doc != null && doc.isDirectory
        }

        // 4. Standard Fallback
        return File(path).mkdirs()
    }

    // Helper to find or create DocumentFile given a relative path from external storage root
    // Only works if path is within a granted permission tree (e.g. Android/data/com.ailife.ClockSkinCoco)
    private fun getDocumentFileForPath(path: String, createIfNotExists: Boolean): androidx.documentfile.provider.DocumentFile? {
          try {
            val targetFolder = "Android/data/com.ailife.ClockSkinCoco"
            
             // Case insensitive check if we are targeting this folder
            if (!path.startsWith(targetFolder, ignoreCase = true)) return null

            // Find permission
            val perm = contentResolver.persistedUriPermissions.firstOrNull { 
                val decodedUri = android.net.Uri.decode(it.uri.toString())
                decodedUri.contains("com.ailife.ClockSkinCoco", ignoreCase = true) && it.isWritePermission 
            }
            
            if (perm != null) {
                var docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, perm.uri) ?: return null
                
                // Determine relative path from the treeUri root to our target path
                val decodedTreeUri = android.net.Uri.decode(perm.uri.toString())
                val treePathPart = decodedTreeUri.substringAfterLast(":", "").removePrefix("/")
                
                val normalizedTarget = path.removePrefix("/").removeSuffix("/")
                
                if (normalizedTarget.startsWith(treePathPart, ignoreCase = true)) {
                    val subPath = normalizedTarget.substring(treePathPart.length).removePrefix("/")
                    if (subPath.isNotEmpty()) {
                        val parts = subPath.split("/")
                        for (part in parts) {
                            var nextFile = docFile.listFiles().find { it.name?.equals(part, ignoreCase = true) == true }
                            
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
          } catch(e: Exception) {
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
                    android.util.Log.d(TAG, "Moving watch face to restricted dir: ${targetFile.absolutePath}")
                    val success = moveFileDispatcher(file.absolutePath, targetFile.absolutePath)
                    if (!success) {
                        android.util.Log.e(TAG, "Failed to move watch face to restricted dir")
                        return@launch
                    }
                } else {
                    android.util.Log.d(TAG, "Watch face already in target directory: ${file.absolutePath}")
                }

                // Broadcast change face
                val intent = Intent("com.android.watchengine.changeface")
                // handleSetWatchFace uses "faceName" with relative path, so let's try to match that
                intent.putExtra("faceName", file.name)
                // Keep "path" with absolute path just in case some engines use it
                intent.putExtra("path", targetFile.absolutePath)
                sendBroadcast(intent)
                android.util.Log.d(TAG, "Broadcasted changeface for: ${file.name} / ${targetFile.absolutePath}")
                
                android.util.Log.d(TAG, "Watch face applied successfully: ${targetFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling watch face: ${e.message}")
            } finally {
                file.delete()
            }
        }
    }

    private fun isUsingShizuku(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
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
        android.util.Log.d(TAG, "showInstallApkDialog called for: ${tempFile.absolutePath}")
        val canInstall = packageManager.canRequestPackageInstalls()
        android.util.Log.d(TAG, "canRequestPackageInstalls: $canInstall")
        if (!canInstall) {
            android.util.Log.d(TAG, "Showing permission notification")
            showPermissionNotification()
            return
        }
        android.util.Log.d(TAG, "Showing install notification")
        showInstallNotification(tempFile)
    }

    private fun executeShellCommand(command: String) {
        serviceScope.launch(Dispatchers.IO) {
            Log.d(TAG, "⚡ Attempting to execute: $command")
            
            // Try Shizuku first (if available)
            try {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
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
                Log.i(TAG, "✅ Shell command executed via libsu. Success: ${result.isSuccess}, Code: ${result.code}")
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
            val powerctlValue = when {
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
        Log.i(TAG, "🔴 Comando de shutdown recebido")
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
        Log.i(TAG, "🔄 Comando de reboot recebido")
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
        Log.i(TAG, "🔒 Comando de lock recebido")
        mainHandler.post {
            Toast.makeText(this, "Lock command received", Toast.LENGTH_SHORT).show()
        }
        try {
            val intent = Intent().apply {
                component = ComponentName("com.ailife.ClockSkinCoco", "com.ailife.ClockSkinCoco.UBSLauncherActivity")
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

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            findDeviceRingtone = RingtoneManager.getRingtone(this, alarmUri).apply {
                @Suppress("DEPRECATION")
                streamType = AudioManager.STREAM_ALARM
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }

            // Start activity to allow stopping locally
            val intent = Intent(this, FindDeviceActivity::class.java).apply {
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

    private fun handleUninstallApp(message: ProtocolMessage) {
        val pkg = ProtocolHelper.extractStringField(message, "package") ?: run {
            android.util.Log.e(TAG, "Uninstall failed: package field missing")
            return
        }
        android.util.Log.d(TAG, "Requesting uninstall for: $pkg")
        
        mainHandler.post {
            Toast.makeText(this, "Uninstalling: $pkg", Toast.LENGTH_SHORT).show()
        }

        try {
            // Standard intent to uninstall
            val intent = Intent(Intent.ACTION_DELETE).apply {
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
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val tempFile = File(cacheDir, "temp_upload.apk")
            tempFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            sendFile(tempFile, "APK")
        }
    }

    fun sendFile(file: File, type: String = "REGULAR", remotePath: String? = null, preferredName: String? = null) {
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

                val fileData = FileTransferData(
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
                    val base64Chunk = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)

                    val chunkData = FileChunkData(
                        offset = offset.toLong(),
                        data = base64Chunk,
                        chunkNumber = chunkNumber,
                        totalChunks = totalChunks
                    )
                    sendMessage(ProtocolHelper.createFileChunk(chunkData))
                    
                    // Report progress
                    val progress = ((chunkNumber + 1) * 95 / totalChunks)
                    withContext(Dispatchers.Main) {
                        callback?.onUploadProgress(progress)
                    }

                    offset = end
                    chunkNumber++
                    delay(10)
                }

                fileAckDeferred = kotlinx.coroutines.CompletableDeferred()
                waitingForFileAck = true
                sendMessageSync(ProtocolHelper.createFileTransferEnd(success = true))

                val ackReceived = try {
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
                sendMessage(ProtocolHelper.createFileTransferEnd(success = false, error = e.message ?: "Unknown error"))
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

    private fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }

    private fun showInstallNotification(tempFile: File) {
        android.util.Log.d(TAG, "showInstallNotification: Creating notification for ${tempFile.name}")
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
            val pendingIntent = PendingIntent.getActivity(
                this, 0, installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(this, INSTALL_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_apk_received_title))
                .setContentText(getString(R.string.notification_apk_received_text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
            notificationManager.notify(INSTALL_NOTIFICATION_ID, builder.build())
            android.util.Log.d(TAG, "Notification posted with ID: $INSTALL_NOTIFICATION_ID")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error creating install notification: ${e.message}", e)
        }
    }

    private fun showPermissionNotification() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:$packageName".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, INSTALL_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_config_required_title))
            .setContentText(getString(R.string.notification_config_required_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        notificationManager.notify(INSTALL_NOTIFICATION_ID, builder.build())
    }

    private fun showErrorNotification(msg: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_DISCONNECTED)
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
                jsonArray.put(obj)
            }

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                callback?.onAppListReceived(jsonArray.toString())
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error parsing app list: ${e.message}")
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun getInstalledAppsWithIcons(): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        val pm = packageManager
        val packages = pm.getInstalledPackages(0)

        for (packageInfo in packages) {
            try {
                val appInfo = packageInfo.applicationInfo ?: continue
                // Skip system apps without launch intent to keep list clean
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && pm.getLaunchIntentForPackage(packageInfo.packageName) == null) continue

                val appName = appInfo.loadLabel(pm).toString()
                val packageName = packageInfo.packageName

                // Get icon and convert to base64
                val icon = appInfo.loadIcon(pm)
                val bitmap = icon.toBitmap(48, 48)
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val iconBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                apps.add(AppInfo(
                    name = appName,
                    packageName = packageName,
                    icon = iconBase64
                ))
            } catch (_: Exception) {}
        }
        return apps
    }


    private fun showMirroredNotification(message: ProtocolMessage) {
        try {
            val notif = ProtocolHelper.extractData<NotificationData>(message)
            val title = notif.title
            val text = notif.text
            val pkg = notif.packageName
            val appName = notif.appName ?: pkg
            val key = notif.key

            val isUpdate = notificationMap.containsKey(key)
            val notifId = notificationMap.getOrPut(key) { (System.currentTimeMillis() % 10000).toInt() + MIRRORED_NOTIFICATION_ID_START }
            
            // Store data for local refresh (stops spinning after reply)
            notificationDataMap[key] = notif

            val deleteIntent = Intent(ACTION_WATCH_DISMISSED_LOCAL).apply { putExtra(EXTRA_NOTIF_KEY, key); setPackage(packageName) }
            val deletePending = PendingIntent.getBroadcast(this, notifId, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            // Use NotificationCompat.Builder (from androidx) for better compatibility and features
            val builder = NotificationCompat.Builder(this, MIRRORED_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(appName)
                .setOnlyAlertOnce(isUpdate)
                .setAutoCancel(true)
                .setDeleteIntent(deletePending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(android.app.Notification.DEFAULT_ALL)


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
                    userBuilder.setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(it))
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
                        val sender = androidx.core.app.Person.Builder()
                            .setName(msgSenderName ?: "Sender")
                            .apply {
                                msg.senderIcon?.let { 
                                    decodeBase64ToBitmap(it)?.let { bitmap ->
                                        setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap))
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
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                    if (bitmap != null) {
                        builder.setSmallIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap))
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
                val actionIntent = if (action.isReplyAction) {
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

                val actionPendingIntent = PendingIntent.getBroadcast(
                    this,
                    (notifId + index + 1),
                    actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                if (action.isReplyAction) {
                    // Create reply action with RemoteInput
                    val remoteInput = androidx.core.app.RemoteInput.Builder(BluetoothService.EXTRA_REPLY_TEXT)
                        .setLabel(action.title)
                        .build()

                    val replyAction = NotificationCompat.Action.Builder(
                        androidx.core.graphics.drawable.IconCompat.createWithResource(this, android.R.drawable.ic_menu_send),
                        action.title,
                        actionPendingIntent
                    ).addRemoteInput(remoteInput).build()

                    builder.addAction(replyAction)
                } else {
                    // Regular action button
                    val regularAction = NotificationCompat.Action.Builder(
                        androidx.core.graphics.drawable.IconCompat.createWithResource(this, android.R.drawable.ic_menu_view),
                        action.title,
                        actionPendingIntent
                    ).build()
                    
                    builder.addAction(regularAction)
                }
            }

            // Set big picture style if available
            notif.bigPicture?.let { pictureBase64 ->
                try {
                    Log.d(TAG, "Decoding big picture: ${pictureBase64.length} chars")
                    val pictureBytes = Base64.decode(pictureBase64, Base64.NO_WRAP)
                    val pictureBitmap = android.graphics.BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size)
                    if (pictureBitmap != null) {
                        Log.d(TAG, "Big picture decoded: ${pictureBitmap.width}x${pictureBitmap.height}")
                        val bigPictureStyle = NotificationCompat.BigPictureStyle()
                            .bigPicture(pictureBitmap)
                            .bigLargeIcon(null as Bitmap?) // Hide large icon when expanded
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
        
        val updatedData = if (replyText != null) {
            // Append local reply to history for immediate feedback
            val newMessage = NotificationMessageData(
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
        notificationMap[key]?.let { id ->
            notificationManager.cancel(id)
            notificationMap.remove(key)
            notificationDataMap.remove(key)
        }
    }

    private fun requestDismissOnPhone(message: ProtocolMessage) {
        val key = ProtocolHelper.extractStringField(message, "key") ?: return
        val intent = Intent(ACTION_CMD_DISMISS_ON_PHONE).apply { putExtra(EXTRA_NOTIF_KEY, key); setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun handleExecuteNotificationAction(message: ProtocolMessage) {
        val notifKey = ProtocolHelper.extractStringField(message, "notifKey") ?: return
        val actionKey = ProtocolHelper.extractStringField(message, "actionKey") ?: return

        Log.d(TAG, "Executing notification action: $actionKey for notification: $notifKey")

        val intent = Intent(ACTION_CMD_EXECUTE_ACTION).apply {
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

        val intent = Intent(ACTION_CMD_SEND_REPLY).apply {
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
        return connectionJob?.isActive == true || serverJob?.isActive == true
    }

    fun stopConnectionLoopOnly() {
        connectionJob?.cancel()
        serverJob?.cancel()
        statusUpdateJob?.cancel()

        val wasConnected = isConnected

        forceDisconnect()
        isConnected = false
        currentDeviceName = null
        bluetoothSocket = null

        if (wasConnected || currentNotificationStatus != getString(R.string.status_stopped)) {
            updateStatus(getString(R.string.status_stopped))
        }
    }
    private fun updateStatus(text: String) {
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
     * CORREÇÃO DEFINITIVA PARA CANAIS BLOQUEADOS:
     * Ao invés de usar um único ID, usamos um ID diferente para cada estado (Waiting, Connected, etc).
     * * Lógica:
     * 1. Determinamos o Estado Atual -> Novo ID e Novo Canal.
     * 2. Chamamos startForeground com o NOVO ID. Isso "segura" o serviço e previne crashes.
     * 3. Se o usuário bloqueou esse canal, o Android não mostra nada (correto).
     * 4. CRUCIAL: Chamamos .cancel() nos IDs dos estados antigos. Isso garante que a notificação "Waiting"
     * suma visualmente se mudamos para "Connected", mesmo que "Connected" esteja oculto.
     */
    private fun updateForegroundNotification() {
        try {
            val (targetId, targetChannel, targetText) = determineNotificationState()

            val intent = Intent(this, MainActivity::class.java)
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(this, targetChannel)
                .setContentTitle(getString(R.string.notification_service_title))
                .setContentText(targetText)
                .setSmallIcon(R.drawable.ic_smartwatch_notification)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()

            // Inicia/Atualiza o Foreground no ID correto do estado atual
            if (Build.VERSION.SDK_INT >= 34) {
                val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val type = if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
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
            if (targetId != NOTIFICATION_ID_WAITING) notificationManager.cancel(NOTIFICATION_ID_WAITING)
            if (targetId != NOTIFICATION_ID_CONNECTED) notificationManager.cancel(NOTIFICATION_ID_CONNECTED)
            if (targetId != NOTIFICATION_ID_DISCONNECTED) notificationManager.cancel(NOTIFICATION_ID_DISCONNECTED)

        } catch (e: Exception) {
            if (DEBUG_NOTIFICATIONS) Log.e(TAG, "updateForegroundNotification: Erro: ${e.message}", e)
        }
    }

    // Retorna Triple(ID, Channel, Text)
    private fun determineNotificationState(): Triple<Int, String, String> {
        return when {
            isConnected && currentDeviceName != null ->
                Triple(NOTIFICATION_ID_CONNECTED, CHANNEL_ID_CONNECTED, "Conectado a $currentDeviceName")

            isConnected && currentDeviceName == null ->
                Triple(NOTIFICATION_ID_CONNECTED, CHANNEL_ID_CONNECTED, "Conectado")

            currentNotificationStatus.contains("Aguardando", ignoreCase = true) ||
                    currentNotificationStatus.contains("Waiting", ignoreCase = true) ||
                    currentNotificationStatus.contains("Escaneando", ignoreCase = true) ||
                    currentNotificationStatus.contains("Searching", ignoreCase = true) ||
                    currentNotificationStatus.contains("Iniciado", ignoreCase = true) ||
                    currentNotificationStatus.contains("Started", ignoreCase = true) ||
                    currentNotificationStatus.isEmpty() ->
                Triple(NOTIFICATION_ID_WAITING, CHANNEL_ID_WAITING, currentNotificationStatus.ifEmpty { getString(R.string.status_waiting) })

            else ->
                Triple(NOTIFICATION_ID_DISCONNECTED, CHANNEL_ID_DISCONNECTED, currentNotificationStatus)
        }
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_WAITING,
                "Notificação persistente - aguardando conexão",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_CONNECTED,
                "Notificação persistente - conectado",
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
                "Alerta de Desconexão",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
        )

        notificationManager.createNotificationChannel(NotificationChannel(INSTALL_CHANNEL_ID, "APKs", NotificationManager.IMPORTANCE_HIGH))
        notificationManager.createNotificationChannel(NotificationChannel(MIRRORED_CHANNEL_ID, "Notificações do celular", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun savePreference(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun handleSyncCalendar(message: ProtocolMessage) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
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
                    null, null, null
                )?.use { cursor ->
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
                    val exists = contentResolver.query(
                        CalendarContract.Events.CONTENT_URI,
                        arrayOf(CalendarContract.Events._ID),
                        "${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DTSTART} = ?",
                        arrayOf(event.title, event.startTime.toString()),
                        null
                    )?.use { it.count > 0 } ?: false

                    if (!exists) {
                        val values = ContentValues().apply {
                            put(CalendarContract.Events.DTSTART, event.startTime)
                            put(CalendarContract.Events.DTEND, event.endTime)
                            put(CalendarContract.Events.TITLE, event.title)
                            put(CalendarContract.Events.DESCRIPTION, event.description)
                            put(CalendarContract.Events.EVENT_LOCATION, event.location)
                            put(CalendarContract.Events.CALENDAR_ID, calendarId)
                            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                        }
                        contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                        count++
                    }
                }

                mainHandler.post {
                    Toast.makeText(this@BluetoothService, "Synced $count new calendar events to system", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error writing calendar events: ${e.message}")
            }
        }
    }

    private fun handleSyncContacts(message: ProtocolMessage) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
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
                    val exists = contentResolver.query(
                        ContactsContract.Data.CONTENT_URI,
                        arrayOf(ContactsContract.Data._ID),
                        "${ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(contact.name, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                        null
                    )?.use { it.count > 0 } ?: false

                    if (!exists) {
                        val ops = arrayListOf<ContentProviderOperation>()
                        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                            .build())

                        // Add Name
                        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.name)
                            .build())

                        // Add Phone Numbers
                        for (number in contact.phoneNumbers) {
                            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                                .build())
                        }

                        // Add Emails if any
                        contact.emails?.forEach { email ->
                             ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                                .build())
                        }

                        contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                        count++
                    }
                }

                mainHandler.post {
                    Toast.makeText(this@BluetoothService, "Synced $count new contacts to system", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error writing contacts: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        // Unbind UserService
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConn as ServiceConnection, true)
            userServiceConnection = null
            userService = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding UserService: ${e.message}")
        }
        
        super.onDestroy()
        try { unregisterReceiver(internalReceiver) } catch(_:Exception){}
        try { unregisterReceiver(watchDismissReceiver) } catch(_:Exception){}
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        forceDisconnect()
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
                    val base64 = android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
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

    private fun isDirectoryDispatcher(absPath: String): Boolean {
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

    private fun loadBitmapFromRestrictedPath(absPath: String): Bitmap? {
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
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    copied = true
                }
            }
            
            if (copied && tempFile.exists()) {
                android.util.Log.d(TAG, "Successfully copied to temp file: ${tempFile.absolutePath}, size: ${tempFile.length()}")
                return android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
            } else {
                 android.util.Log.e(TAG, "Failed to copy to temp file or file does not exist. copied=$copied")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from restricted path $absPath: ${e.message}", e)
        } finally {
            tempFile?.let { if (it.exists()) it.delete() }
        }
        return null
    }

    private fun loadPreviewFromZip(absPath: String): Bitmap? {
        val file = File(absPath)
        var zipFileToUse = file
        var isTemp = false
        var tempFile: File? = null
        
        if (!file.exists() || !file.canRead()) {
            try {
                // Use externalCacheDir if available so Shizuku/Shell has better chance of writing to it
                val cacheDirToUse = externalCacheDir ?: cacheDir
                tempFile = File.createTempFile("pzip_", ".zip", cacheDirToUse)
                var copied = false
                if (isUsingShizuku()) {
                    try {
                        ensureUserServiceBound()
                        android.util.Log.d(TAG, "Attempting Shizuku copy for ZIP preview: $absPath -> ${tempFile.absolutePath}")
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
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
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
                    val entry = zip.entries().asSequence().find { 
                        it.name.endsWith(c, true) && !it.isDirectory 
                    }
                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            android.graphics.BitmapFactory.decodeStream(input)
                        }?.let { return it }
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

}