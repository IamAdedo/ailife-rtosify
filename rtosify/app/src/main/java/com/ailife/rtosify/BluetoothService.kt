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
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import com.ailife.rtosify.R
import android.view.KeyEvent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
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
import android.widget.Toast
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import android.telephony.TelephonyManager
import android.telecom.TelecomManager
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

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
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

    // REFATORAÇÃO: Controle mais robusto de notificações
    @Volatile
    private var currentNotificationStatus: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    @Volatile
    private var isStopping = false

    interface ServiceCallback {
        fun onStatusChanged(status: String)
        fun onDeviceConnected(deviceName: String)
        fun onDeviceDisconnected()
        fun onError(message: String)
        fun onScanResult(devices: List<BluetoothDevice>)
        fun onAppListReceived(appsJson: String)
        fun onUploadProgress(progress: Int)
        fun onDownloadProgress(progress: Int)
        fun onFileListReceived(path: String, filesJson: String)
        fun onWatchStatusUpdated(batteryLevel: Int, isCharging: Boolean, wifiSsid: String, wifiEnabled: Boolean, dndEnabled: Boolean) {}
        fun onHealthDataUpdated(healthData: HealthDataUpdate) {}
        fun onHealthHistoryReceived(historyData: HealthHistoryResponse) {}
        fun onHealthSettingsReceived(settings: HealthSettingsUpdate) {}
        fun onPreviewReceived(path: String, imageBase64: String?) {}
    }

    var callback: ServiceCallback? = null

    @Volatile
    var currentStatus: String = "" // Will be initialized in onCreate
    @Volatile
    var currentDeviceName: String? = null
    @Volatile
    var isConnected: Boolean = false

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                
                Log.d(TAG, "Phone State Changed: $state, Number: $number")
                
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        if (number != null) {
                            val callerName = getContactName(number)
                            sendMessage(ProtocolHelper.createIncomingCall(number, callerName))
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
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
        const val ACTION_WATCH_DISMISSED_LOCAL = "com.ailife.rtosify.DISMISSED_LOCAL"
        const val ACTION_CMD_DISMISS_ON_PHONE = "com.ailife.rtosify.CMD_DISMISS_PHONE"
        const val ACTION_CMD_EXECUTE_ACTION = "com.ailife.rtosify.CMD_EXECUTE_ACTION"
        const val ACTION_CMD_SEND_REPLY = "com.ailife.rtosify.CMD_SEND_REPLY"

        const val EXTRA_NOTIF_JSON = "extra_notif_json"
        const val EXTRA_NOTIF_KEY = "extra_notif_key"
        const val EXTRA_ACTION_KEY = "extra_action_key"
        const val EXTRA_REPLY_TEXT = "extra_reply_text"

        const val ACTION_UPDATE_SETTINGS = "com.ailife.rtosify.ACTION_UPDATE_SETTINGS"

        // IDs DISTINTOS para garantir limpeza correta ao trocar de estado
        const val NOTIFICATION_ID_WAITING = 10
        const val NOTIFICATION_ID_CONNECTED = 11
        const val NOTIFICATION_ID_DISCONNECTED = 12

        const val INSTALL_NOTIFICATION_ID = 2
        const val MIRRORED_NOTIFICATION_ID_START = 1000

        const val CHANNEL_ID_WAITING = "channel_status_waiting"
        const val CHANNEL_ID_CONNECTED = "channel_status_connected"
        const val CHANNEL_ID_DISCONNECTED = "channel_status_disconnected"

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
                ACTION_UPDATE_SETTINGS -> {
                    Log.d(TAG, "Received ACTION_UPDATE_SETTINGS broadcast")
                    syncSettingsToWatch()
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
            addAction(ACTION_UPDATE_SETTINGS)
        }
        val filterWatch = IntentFilter(ACTION_WATCH_DISMISSED_LOCAL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(internalReceiver, filterInternal, RECEIVER_NOT_EXPORTED)
            registerReceiver(watchDismissReceiver, filterWatch, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(internalReceiver, filterInternal)
            registerReceiver(watchDismissReceiver, filterWatch)
        }

        val filterPhone = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        registerReceiver(phoneStateReceiver, filterPhone)

        if (DEBUG_NOTIFICATIONS) Log.d(TAG, "onCreate: Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // CRÍTICO: Chamar startForeground imediatamente para cumprir a promessa feita no BootReceiver.
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
                sendMessage(ProtocolHelper.createSendNotificationReply(notifKey, actionKey, replyText))
            }
            return START_NOT_STICKY
        }

        // Inicializa lógica se não estiver conectado
        if (!isConnected) {
            initializeLogicFromPrefs()
        }

        if (DEBUG_NOTIFICATIONS) Log.d(TAG, "onStartCommand: Service started, isConnected=$isConnected")

        isStopping = false // Reset on start
        return START_STICKY
    }

    private fun initializeLogicFromPrefs() {
        if (!prefs.getBoolean("service_enabled", true)) return

        val deviceType = prefs.getString("device_type", null)
        if (deviceType != null) {
            if (deviceType == "PHONE") startSmartphoneLogic() else startWatchLogic()
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkAndEnableBluetooth(): Boolean {
        if (bluetoothAdapter?.isEnabled == true) return true

        Log.w(TAG, "Bluetooth is disabled.")
        updateStatus("Bluetooth Disabled")

        // Tenta habilitar automaticamente
        try {
            if (bluetoothAdapter?.enable() == true) {
                updateStatus("Enabling Bluetooth...")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-enable Bluetooth: ${e.message}")
        }

        // Se falhar, solicita ao usuário
        mainHandler.post {
            Toast.makeText(this, "Bluetooth required. Please enable it.", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start enable bluetooth intent: ${e.message}")
            }
        }
        
        return false
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
            // Loop while Bluetooth is disabled
            while (isActive && !checkAndEnableBluetooth()) {
                delay(3000)
            }
            if (!isActive) return@launch

            updateStatus(getString(R.string.status_searching))

            // Wait a bit if we just enabled extensions
            if (bluetoothAdapter?.state == BluetoothAdapter.STATE_TURNING_ON) {
                 delay(2000)
            }

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
                    updateStatus(getString(R.string.status_no_watch_found))
                    callback?.onError(getString(R.string.status_open_app_watch))
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
                if (!checkAndEnableBluetooth()) {
                    delay(3000)
                    continue
                }

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
                if (!checkAndEnableBluetooth()) {
                    delay(3000)
                    continue
                }

                updateStatus(getString(R.string.status_waiting))

                var serverSocket: BluetoothServerSocket?
                try {
                    serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                } catch (_: IOException) {
                    delay(3000)
                    continue
                }

                var socket: BluetoothSocket? = null
                while (socket == null && isActive) {
                    // Check BT again inside the waiting loop
                    if (bluetoothAdapter?.isEnabled != true) {
                        try { serverSocket?.close() } catch(e:Exception){}
                        serverSocket = null
                        break 
                    }

                    try {
                        socket = serverSocket?.accept(5000)
                    } catch (_: IOException) {
                    }
                }

                if (serverSocket == null && socket == null) continue

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

        // Sync settings on connection
        syncSettingsToWatch()

        val deviceType = prefs.getString("device_type", "PHONE")
        if (deviceType == "WATCH") {
            startWatchStatusSender()
        }

        try {
            while (currentCoroutineContext().isActive) {
                val message = readMessage(inputStream)
                lastMessageTime = System.currentTimeMillis()

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
                    MessageType.STATUS_UPDATE -> handleStatusUpdateReceived(message)
                    MessageType.SET_DND -> handleSetDndCommand(message)
                    MessageType.FIND_PHONE -> handleFindPhoneCommand(message)
                    MessageType.MEDIA_CONTROL -> handleMediaControl(message)
                    MessageType.CAMERA_START -> handleCameraStart()
                    MessageType.CAMERA_STOP -> handleCameraStop()
                    MessageType.CAMERA_SHUTTER -> handleCameraShutter()
                    MessageType.RESPONSE_FILE_LIST -> handleResponseFileList(message)
                    MessageType.HEALTH_DATA_UPDATE -> handleHealthDataReceived(message)
                    MessageType.RESPONSE_HEALTH_HISTORY -> handleHealthHistoryReceived(message)
                    MessageType.RESPONSE_HEALTH_SETTINGS -> handleHealthSettingsReceived(message)
                    MessageType.RESPONSE_PREVIEW -> handlePreviewReceived(message)
                    MessageType.MAKE_CALL -> handleMakeCallCommand(message)
                    MessageType.REJECT_CALL -> handleRejectCallCommand()
                    MessageType.ANSWER_CALL -> handleAnswerCallCommand()
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
            }

            withContext(Dispatchers.Main) {
                callback?.onDeviceDisconnected()
            }
        }
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
    }

    private fun syncSettingsToWatch() {
        if (!isConnected) {
            Log.d(TAG, "Not syncing settings: Not connected")
            return
        }
        val notifyOnDisconnect = prefs.getBoolean("notify_on_disconnect", false)
        Log.d(TAG, "Syncing settings to watch: notifyOnDisconnect=$notifyOnDisconnect")
        sendMessage(ProtocolHelper.createUpdateSettings(SettingsUpdateData(notifyOnDisconnect = notifyOnDisconnect)))
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

    private suspend fun handleStatusUpdateReceived(message: ProtocolMessage) {
        try {
            val status = ProtocolHelper.extractData<StatusUpdateData>(message)
            withContext(Dispatchers.Main) {
                callback?.onWatchStatusUpdated(status.battery, status.charging, status.wifi, status.wifiEnabled, status.dnd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro parser status: ${e.message}")
        }
    }

    private suspend fun handleHealthDataReceived(message: ProtocolMessage) {
        try {
            val healthData = ProtocolHelper.extractData<HealthDataUpdate>(message)
            withContext(Dispatchers.Main) {
                callback?.onHealthDataUpdated(healthData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing health data: ${e.message}")
        }
    }

    private suspend fun handleHealthHistoryReceived(message: ProtocolMessage) {
        try {
            val historyData = ProtocolHelper.extractData<HealthHistoryResponse>(message)
            withContext(Dispatchers.Main) {
                callback?.onHealthHistoryReceived(historyData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing health history: ${e.message}")
        }
    }

    private suspend fun handleHealthSettingsReceived(message: ProtocolMessage) {
        try {
            val settings = ProtocolHelper.extractData<HealthSettingsUpdate>(message)
            withContext(Dispatchers.Main) {
                callback?.onHealthSettingsReceived(settings)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing health settings: ${e.message}")
        }
    }

    fun requestHealthData() {
        sendMessage(ProtocolHelper.createRequestHealthData())
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

    fun sendDndCommand(enable: Boolean) {
        sendMessage(ProtocolHelper.createSetDnd(enable))
    }

    fun sendWifiCommand(enable: Boolean) {
        sendMessage(ProtocolHelper.createSetWifi(enable))
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

    private fun handleMediaControl(message: ProtocolMessage) {
        try {
            val controlData = ProtocolHelper.extractData<MediaControlData>(message)
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            when (controlData.command) {
                MediaControlData.CMD_PLAY, MediaControlData.CMD_PAUSE, MediaControlData.CMD_PLAY_PAUSE,
                MediaControlData.CMD_NEXT, MediaControlData.CMD_PREVIOUS -> {
                    val keyCode = when(controlData.command) {
                        MediaControlData.CMD_PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                        MediaControlData.CMD_PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                        MediaControlData.CMD_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        MediaControlData.CMD_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                        MediaControlData.CMD_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> 0
                    }
                    if (keyCode != 0) {
                        try {
                            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                        } catch (e: Exception) {
                            // Fallback to broadcast if dispatchMediaKeyEvent fails
                            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                            intent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                            sendOrderedBroadcast(intent, null)

                            val intentUp = Intent(Intent.ACTION_MEDIA_BUTTON)
                            intentUp.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
                            sendOrderedBroadcast(intentUp, null)
                        }
                    }
                }
                MediaControlData.CMD_VOL_UP -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                MediaControlData.CMD_VOL_DOWN -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling media control: ${e.message}")
        }
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
        val intent = if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
            }
        } else {
            Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun handleCameraShutter() {
        val intent = Intent(CameraActivity.ACTION_TAKE_PICTURE)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    fun sendCameraFrame(base64: String) {
        sendMessage(ProtocolHelper.createCameraFrame(base64))
    }

    private fun startFindPhoneAlarm() {
        stopFindPhoneAlarm()
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            findPhoneRingtone = RingtoneManager.getRingtone(this, alarmUri).apply {
                @Suppress("DEPRECATION")
                streamType = AudioManager.STREAM_ALARM
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }

            // Start activity to allow stopping locally
            val intent = Intent().apply {
                component = ComponentName("com.ailife.rtosify", "com.ailife.rtosify.FindPhoneActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            Log.i(TAG, "Phone alarm started at max volume")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting phone alarm: ${e.message}")
        }
    }

    private fun stopFindPhoneAlarm() {
        findPhoneRingtone?.stop()
        findPhoneRingtone = null
        Log.i(TAG, "Phone alarm stopped")
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
            android.util.Log.d(TAG, "File transfer starting: ${fileData.name}, ${fileData.size} bytes")

            // Prepare to receive file
            // If it's a regular file (not APK), save to Downloads
            receivingFile = if (fileData.type == "REGULAR") {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                File(downloadsDir, fileData.name)
            } else {
                File(cacheDir, fileData.name)
            }

            // Use RandomAccessFile for random write access (out-of-order chunks)
            receivingFileStream = java.io.RandomAccessFile(receivingFile!!, "rw")
            receivingFileStream?.setLength(fileData.size) // Pre-allocate file size
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

            // Report progress
            if (expectedFileSize > 0) {
                val progress = (receivedFileSize * 100 / expectedFileSize).toInt().coerceIn(0, 99)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    callback?.onDownloadProgress(progress)
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

            // Send success acknowledgment to sender
            sendMessage(ProtocolHelper.createFileTransferEnd(success = true))
            android.util.Log.d(TAG, "Sent file transfer acknowledgment")

            // Show install dialog ONLY if type is "APK" (sent from app install section, not file manager download)
            if (receivingFileType == "APK") {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    showInstallApkDialog(file)
                }
            } else {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    callback?.onDownloadProgress(100)
                    Toast.makeText(this@BluetoothService, getString(R.string.upload_complete_title) + ": ${file.name}", Toast.LENGTH_LONG).show()
                }
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
        Log.i(TAG, "Comando de shutdown recebido")
        serviceScope.launch(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p"))
                withTimeout(3000) { process.waitFor() }
            } catch (_: Exception) {
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")) } catch (_: Exception) {}
            }
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

    fun syncCalendar() {
        if (!isConnected) {
            mainHandler.post { Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT).show() }
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            mainHandler.post { Toast.makeText(this, "Permission READ_CALENDAR not granted", Toast.LENGTH_SHORT).show() }
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val events = mutableListOf<CalendarEvent>()
            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION
            )
            
            val now = System.currentTimeMillis()
            val weekFromNow = now + (7 * 24 * 60 * 60 * 1000L)
            val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?)"
            val selectionArgs = arrayOf(now.toString(), weekFromNow.toString())

            try {
                contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${CalendarContract.Events.DTSTART} ASC"
                )?.use { cursor ->
                    val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                    val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                    val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                    val locIdx = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                    val descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)

                    while (cursor.moveToNext()) {
                        events.add(CalendarEvent(
                            title = cursor.getString(titleIdx) ?: "No Title",
                            startTime = cursor.getLong(startIdx),
                            endTime = cursor.getLong(endIdx),
                            location = cursor.getString(locIdx),
                            description = cursor.getString(descIdx)
                        ))
                    }
                }

                if (events.isNotEmpty()) {
                    sendMessage(ProtocolHelper.createSyncCalendar(events))
                    mainHandler.post { Toast.makeText(this@BluetoothService, getString(R.string.toast_sync_success, "Calendar"), Toast.LENGTH_SHORT).show() }
                } else {
                    mainHandler.post { Toast.makeText(this@BluetoothService, "No upcoming calendar events found", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                Log.e("BluetoothService", "Error syncing calendar: ${e.message}")
                mainHandler.post { Toast.makeText(this@BluetoothService, getString(R.string.toast_sync_failed, "Calendar"), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun syncContacts() {
        if (!isConnected) {
            mainHandler.post { Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT).show() }
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            mainHandler.post { Toast.makeText(this, "Permission READ_CONTACTS not granted", Toast.LENGTH_SHORT).show() }
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            val contactsList = mutableListOf<Contact>()
            
            val projection = arrayOf(
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
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

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
                    mainHandler.post { Toast.makeText(this@BluetoothService, getString(R.string.toast_sync_success, "Contacts"), Toast.LENGTH_SHORT).show() }
                } else {
                    mainHandler.post { Toast.makeText(this@BluetoothService, "No contacts found", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                Log.e("BluetoothService", "Error syncing contacts: ${e.message}")
                mainHandler.post { Toast.makeText(this@BluetoothService, getString(R.string.toast_sync_failed, "Contacts"), Toast.LENGTH_SHORT).show() }
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

    fun sendFile(file: File, type: String = "REGULAR", remotePath: String? = null) {
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!isConnected) {
                withContext(Dispatchers.Main) { callback?.onError("Não conectado.") }
                return@launch
            }
            isTransferring = true

            try {
                val fileBytes = file.readBytes()
                val md5 = java.security.MessageDigest.getInstance("MD5")
                md5.update(fileBytes)
                val checksum = md5.digest().joinToString("") { "%02x".format(it) }

                val fileData = FileTransferData(
                    name = file.name,
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
        sendMessage(ProtocolHelper.createDeleteFile(path))
    }

    private suspend fun handleResponseFileList(message: ProtocolMessage) {
        val path = ProtocolHelper.extractStringField(message, "path") ?: "/"
        val filesJson = message.data.get("files").toString()
        withContext(Dispatchers.Main) {
            callback?.onFileListReceived(path, filesJson)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            var fileName: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
            fileName
        } catch (_: Exception) {
            null
        }
    }

    private fun showInstallNotification(tempFile: File) {
        val authority = "${packageName}.fileprovider"
        val installUri = FileProvider.getUriForFile(this, authority, tempFile)
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
            val key = notif.key

            val notifId = notificationMap.getOrPut(key) { (System.currentTimeMillis() % 10000).toInt() + MIRRORED_NOTIFICATION_ID_START }

            val deleteIntent = Intent(ACTION_WATCH_DISMISSED_LOCAL).apply { putExtra(EXTRA_NOTIF_KEY, key); setPackage(packageName) }
            val deletePending = PendingIntent.getBroadcast(this, notifId, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val builder = NotificationCompat.Builder(this, MIRRORED_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(pkg)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDeleteIntent(deletePending)

            // Set large icon if available
            notif.largeIcon?.let { iconBase64 ->
                try {
                    Log.d(TAG, "Decoding large icon: ${iconBase64.length} chars")
                    val iconBytes = Base64.decode(iconBase64, Base64.NO_WRAP)
                    Log.d(TAG, "Icon bytes decoded: ${iconBytes.size} bytes")
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                    if (bitmap != null) {
                        Log.d(TAG, "Bitmap decoded: ${bitmap.width}x${bitmap.height}")
                        builder.setLargeIcon(bitmap)
                    } else {
                        Log.e(TAG, "Failed to decode bitmap - bitmap is null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding large icon: ${e.message}", e)
                }
            } ?: Log.d(TAG, "No large icon in notification data")

            // Set small icon - always use default for now (Android requires resource ID)
            // The original small icon is extracted but can't be used directly in NotificationCompat
            // TODO: Could save as temp resource or use as large icon for better visibility
            builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
            Log.d(TAG, "Small icon extraction: ${if (notif.smallIcon != null) "available but using default (API limitation)" else "not available"}")

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
                    val remoteInput = androidx.core.app.RemoteInput.Builder("reply_text")
                        .setLabel(getString(R.string.reply_label))
                        .build()

                    val replyAction = NotificationCompat.Action.Builder(
                        android.R.drawable.ic_menu_send,
                        action.title,
                        actionPendingIntent
                    ).addRemoteInput(remoteInput).build()

                    builder.addAction(replyAction)
                } else {
                    // Regular action button
                    builder.addAction(
                        android.R.drawable.ic_menu_view,
                        action.title,
                        actionPendingIntent
                    )
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

    private fun dismissLocalNotification(message: ProtocolMessage) {
        val key = ProtocolHelper.extractStringField(message, "key") ?: return
        notificationMap[key]?.let { id ->
            notificationManager.cancel(id)
            notificationMap.remove(key)
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

    fun requestRemoteAppList() { sendMessage(ProtocolHelper.createRequestApps()) }

    fun sendUninstallCommand(packageName: String) {
        android.util.Log.d(TAG, "Sending uninstall command for: $packageName")
        mainHandler.post {
            Toast.makeText(this, "Sending uninstall: $packageName", Toast.LENGTH_SHORT).show()
        }
        sendMessage(ProtocolHelper.createUninstallApp(packageName))
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

        notificationManager.createNotificationChannel(NotificationChannel(INSTALL_CHANNEL_ID, "APKs", NotificationManager.IMPORTANCE_HIGH))
        notificationManager.createNotificationChannel(NotificationChannel(MIRRORED_CHANNEL_ID, "Notificações do celular", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun savePreference(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun handleRejectCallCommand() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
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
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
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
        try { unregisterReceiver(internalReceiver) } catch(_:Exception){}
        try { unregisterReceiver(watchDismissReceiver) } catch(_:Exception){}
        try { unregisterReceiver(phoneStateReceiver) } catch(_:Exception){}
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        forceDisconnect()
    }

    private suspend fun handlePreviewReceived(message: ProtocolMessage) {
        val path = ProtocolHelper.extractStringField(message, "path")
        val imageBase64 = ProtocolHelper.extractStringField(message, "imageBase64")
        if (path != null) {
            withContext(Dispatchers.Main) {
                callback?.onPreviewReceived(path, imageBase64)
            }
        }
    }


}