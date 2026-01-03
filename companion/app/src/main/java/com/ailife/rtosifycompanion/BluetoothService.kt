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
import android.util.Base64
import android.util.Log
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
import org.json.JSONArray
import org.json.JSONObject
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

        const val EXTRA_NOTIF_JSON = "extra_notif_json"
        const val EXTRA_NOTIF_KEY = "extra_notif_key"

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
        }
        val filterWatch = IntentFilter(ACTION_WATCH_DISMISSED_LOCAL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(internalReceiver, filterInternal, RECEIVER_NOT_EXPORTED)
            registerReceiver(watchDismissReceiver, filterWatch, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(internalReceiver, filterInternal)
            registerReceiver(watchDismissReceiver, filterWatch)
        }

        if (DEBUG_NOTIFICATIONS) Log.d(TAG, "onCreate: Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
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
                    MessageType.FILE_TRANSFER_START -> handleFileTransferStart(message)
                    MessageType.FILE_CHUNK -> handleFileChunk(message)
                    MessageType.FILE_TRANSFER_END -> handleFileTransferEnd(message)
                    MessageType.SHUTDOWN -> handleShutdownCommand()
                    MessageType.STATUS_UPDATE -> handleStatusUpdateReceived(message)
                    MessageType.SET_DND -> handleSetDndCommand(message)
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

    private fun sendMessage(message: ProtocolMessage) {
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
                callback?.onWatchStatusUpdated(status.battery, status.charging, status.wifi, status.dnd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro parser status: ${e.message}")
        }
    }

    fun sendDndCommand(enable: Boolean) {
        sendMessage(ProtocolHelper.createSetDnd(enable))
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

    // File transfer state (receiving)
    private var receivingFile: File? = null
    private var receivingFileStream: FileOutputStream? = null
    private var expectedFileSize: Long = 0
    private var receivedFileSize: Long = 0
    private var expectedChecksum: String = ""
    private val receivedChunks = mutableListOf<ByteArray>()

    // File transfer state (sending - waiting for ack)
    private var waitingForFileAck = false
    private var fileAckDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    private suspend fun handleFileTransferStart(message: ProtocolMessage) {
        try {
            val fileData = ProtocolHelper.extractData<FileTransferData>(message)
            android.util.Log.d(TAG, "File transfer starting: ${fileData.name}, ${fileData.size} bytes")

            // Prepare to receive file
            receivingFile = File(cacheDir, fileData.name)
            receivingFileStream = FileOutputStream(receivingFile)
            expectedFileSize = fileData.size
            receivedFileSize = 0
            expectedChecksum = fileData.checksum
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

            // Write to file
            receivingFileStream?.write(chunkBytes)
            receivedFileSize += chunkBytes.size

            android.util.Log.d(TAG, "Received chunk ${chunkData.chunkNumber}/${chunkData.totalChunks}")

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

            android.util.Log.d(TAG, "About to show install dialog for file: ${file.absolutePath}, exists=${file.exists()}, size=${file.length()}")

            // Show install dialog
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    showInstallApkDialog(file)
                    android.util.Log.d(TAG, "showInstallApkDialog completed")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error showing install dialog: ${e.message}", e)
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

    fun sendApkFile(uri: Uri) {
        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!isConnected) {
                withContext(Dispatchers.Main) { callback?.onError("Não conectado.") }
                return@launch
            }
            isTransferring = true

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val fileBytes = inputStream.readBytes()

                    // Calculate MD5 checksum
                    val md5 = java.security.MessageDigest.getInstance("MD5")
                    md5.update(fileBytes)
                    val checksum = md5.digest().joinToString("") { "%02x".format(it) }

                    val fileName = getFileNameFromUri(uri) ?: "app.apk"

                    // Send start message
                    val fileData = FileTransferData(
                        name = fileName,
                        size = fileBytes.size.toLong(),
                        checksum = checksum
                    )
                    sendMessage(ProtocolHelper.createFileTransferStart(fileData))
                    delay(100) // Give receiver time to prepare

                    // Send file in chunks (32KB each)
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

                        // Report progress up to 95% while sending chunks
                        val progress = ((chunkNumber + 1) * 95 / totalChunks)
                        withContext(Dispatchers.Main) {
                            callback?.onUploadProgress(progress)
                        }

                        offset = end
                        chunkNumber++
                        delay(10) // Small delay between chunks
                    }

                    // Setup acknowledgment waiting BEFORE sending message
                    fileAckDeferred = kotlinx.coroutines.CompletableDeferred()
                    waitingForFileAck = true

                    // Send end message synchronously (ensures it's sent before we start waiting)
                    sendMessageSync(ProtocolHelper.createFileTransferEnd(success = true))

                    // Wait for acknowledgment with 10 second timeout
                    val ackReceived = try {
                        kotlinx.coroutines.withTimeout(10000) {
                            fileAckDeferred?.await() ?: false
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        android.util.Log.e(TAG, "File transfer acknowledgment timeout")
                        waitingForFileAck = false
                        fileAckDeferred = null
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
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error sending file: ${e.message}")
                sendMessage(ProtocolHelper.createFileTransferEnd(success = false, error = e.message ?: "Unknown error"))
                withContext(kotlinx.coroutines.Dispatchers.Main) {
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
            val key = notif.key

            val notifId = notificationMap.getOrPut(key) { (System.currentTimeMillis() % 10000).toInt() + MIRRORED_NOTIFICATION_ID_START }

            val deleteIntent = Intent(ACTION_WATCH_DISMISSED_LOCAL).apply { putExtra(EXTRA_NOTIF_KEY, key); setPackage(packageName) }
            val deletePending = PendingIntent.getBroadcast(this, notifId, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notification = NotificationCompat.Builder(this, MIRRORED_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(pkg)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDeleteIntent(deletePending)
                .build()

            notificationManager.notify(notifId, notification)
        } catch (_: Exception) {}
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

    fun requestRemoteAppList() {
        sendMessage(ProtocolHelper.createRequestApps())
    }

    fun resetApp() {
        stopConnectionLoopOnly()
        prefs.edit().clear().apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

        notificationManager.createNotificationChannel(NotificationChannel(INSTALL_CHANNEL_ID, "APKs", NotificationManager.IMPORTANCE_HIGH))
        notificationManager.createNotificationChannel(NotificationChannel(MIRRORED_CHANNEL_ID, "Notificações do celular", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun savePreference(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(internalReceiver) } catch(_:Exception){}
        try { unregisterReceiver(watchDismissReceiver) } catch(_:Exception){}
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        forceDisconnect()
    }
}