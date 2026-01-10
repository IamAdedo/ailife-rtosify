package com.ailife.rtosifycompanion

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson

class DynamicIslandService : Service() {

    companion object {
        private const val TAG = "DynamicIslandService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "dynamic_island_channel"

        @Volatile var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: DynamicIslandView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private val notificationQueue = mutableListOf<NotificationData>()
    private var currentNotification: NotificationData? = null
    private var isExpanded = false
    private var collapseRunnable: Runnable? = null
    private var transientStateRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isBluetoothConnected = false
    private var isCharging = false
    private var lastIsCharging = false
    private var batteryPercent = 0
    private var isShowingTransientState = false

    private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothService.ACTION_SHOW_IN_DYNAMIC_ISLAND -> {
                            val notifJson = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_JSON)
                            if (notifJson != null) {
                                try {
                                    val notif =
                                            Gson().fromJson(notifJson, NotificationData::class.java)
                                    showNotification(notif)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse notification: ${e.message}")
                                }
                            }
                        }
                        BluetoothService.ACTION_DISMISS_FROM_DYNAMIC_ISLAND -> {
                            val key = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
                            if (key != null) {
                                dismissNotification(key)
                            }
                        }
                        BluetoothService.ACTION_UPDATE_DI_TIMEOUT -> {
                            val timeout = intent.getIntExtra("timeout", 5)
                            prefs.edit().putInt("dynamic_island_timeout", timeout).apply()
                        }
                        Intent.ACTION_BATTERY_CHANGED -> {
                            handleBatteryChanged(intent)
                        }
                        BluetoothService.ACTION_CONNECTION_STATE_CHANGED -> {
                            val connected = intent.getBooleanExtra("connected", false)
                            if (connected != isBluetoothConnected) {
                                isBluetoothConnected = connected
                                showTransientConnectionState(connected)
                            }
                        }
                    }
                }
            }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DynamicIslandService onCreate")

        // Check if we have overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.e(TAG, "Overlay permission not granted! Service cannot start.")
                stopSelf()
                return
            }
        }

        isRunning = true

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        createOverlayView()
        registerReceivers()

        // Initialize connection state - request from BluetoothService
        isBluetoothConnected = false
        requestInitialConnectionState()
        updateState()
    }

    private fun requestInitialConnectionState() {
        Log.d(TAG, "Requesting initial connection state from BluetoothService")
        val intent = Intent(BluetoothService.ACTION_REQUEST_CONNECTION_STATE)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                                    CHANNEL_ID,
                                    "Dynamic Island",
                                    NotificationManager.IMPORTANCE_LOW
                            )
                            .apply {
                                description = "Dynamic Island notification overlay"
                                setShowBadge(false)
                            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Dynamic Island Active")
                .setContentText("Showing notifications")
                .setSmallIcon(R.drawable.ic_smartwatch_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val layoutFlag =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                }

        params =
                WindowManager.LayoutParams(
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                layoutFlag,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                                PixelFormat.TRANSLUCENT
                        )
                        .apply {
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            y = 0
                        }

        overlayView = DynamicIslandView(this)

        // Handle pill clicks (for expanding/collapsing list)
        overlayView.onPillClick = {
            if (isExpanded) {
                // If expanded list is showing, collapse it
                collapseList()
            } else if (notificationQueue.isNotEmpty()) {
                // Has notifications - expand to show list
                expandToList()
            }
        }

        overlayView.onNotificationClick = { notif -> handleNotificationClick(notif) }

        overlayView.onNotificationDismiss = { notif -> handleNotificationDismiss(notif) }

        overlayView.onNotificationReply = { notif, replyText ->
            handleNotificationReply(notif, replyText)
        }

        overlayView.onClearAllClicked = {
            Log.d(TAG, "Clear All clicked")
            // Make a copy to avoid ConcurrentModificationException
            val keys = notificationQueue.map { it.key }
            keys.forEach { key ->
                val notif = notificationQueue.find { it.key == key }
                if (notif != null) {
                    handleNotificationDismiss(notif)
                }
            }
            collapseList()
        }

        windowManager.addView(overlayView, params)
        updateState()
    }

    private fun registerReceivers() {
        val filter =
                IntentFilter().apply {
                    addAction(BluetoothService.ACTION_SHOW_IN_DYNAMIC_ISLAND)
                    addAction(BluetoothService.ACTION_DISMISS_FROM_DYNAMIC_ISLAND)
                    addAction(BluetoothService.ACTION_UPDATE_DI_TIMEOUT)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction("com.ailife.rtosifycompanion.CONNECTION_STATE_CHANGED")
                }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // Request initial battery status
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        batteryPercent =
                if (level >= 0 && scale > 0) {
                    (level * 100 / scale.toFloat()).toInt()
                } else {
                    0
                }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        isCharging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

        if (isCharging != lastIsCharging) {
            val wasJustConnected = isCharging && !lastIsCharging
            lastIsCharging = isCharging
            showTransientChargingState(isCharging, wasJustConnected)
        }
    }

    private fun showTransientChargingState(charging: Boolean, animate: Boolean = false) {
        isShowingTransientState = true
        transientStateRunnable?.let { handler.removeCallbacks(it) }

        if (charging) {
            overlayView.showChargingState(batteryPercent, animate)
        } else {
            updateState()
        }

        val timeout = prefs.getInt("dynamic_island_timeout", 5) * 1000L
        transientStateRunnable = Runnable {
            isShowingTransientState = false
            updateState()
        }
        handler.postDelayed(transientStateRunnable!!, timeout)
    }

    private fun showTransientConnectionState(connected: Boolean) {
        isShowingTransientState = true
        transientStateRunnable?.let { handler.removeCallbacks(it) }

        if (connected) {
            overlayView.showConnectedState() // Could be an expanded version if needed
        } else {
            overlayView.showDisconnectedState()
        }

        val timeout = prefs.getInt("dynamic_island_timeout", 5) * 1000L
        transientStateRunnable = Runnable {
            isShowingTransientState = false
            updateState()
        }
        handler.postDelayed(transientStateRunnable!!, timeout)
    }

    private fun updateState() {
        handler.post {
            if (isShowingTransientState) return@post

            // If expanded and queue is empty, we must collapse
            if (isExpanded && notificationQueue.isEmpty()) {
                isExpanded = false
            }

            if (isExpanded) return@post

            when {
                currentNotification != null -> {
                    // Already showing a notification
                }
                notificationQueue.isNotEmpty() -> {
                    overlayView.collapseToIcons(notificationQueue)
                }
                isCharging -> {
                    overlayView.showChargingState(batteryPercent)
                }
                !isBluetoothConnected -> {
                    overlayView.showDisconnectedState()
                }
                else -> {
                    overlayView.showIdleState()
                }
            }
        }
    }

    private fun showNotification(notif: NotificationData) {
        Log.d(TAG, "Showing notification in Dynamic Island: ${notif.title}")

        // Add to queue
        notificationQueue.removeAll { it.key == notif.key } // Remove duplicates
        notificationQueue.add(0, notif) // Add to front

        // If not currently showing a notification, show this one
        if (currentNotification == null) {
            displayNotification(notif)
        } else {
            // Already showing a notification, just update the queue icons
            overlayView.updateNotificationQueue(notificationQueue)
        }
    }

    private fun displayNotification(notif: NotificationData) {
        currentNotification = notif
        isExpanded = false
        isShowingTransientState = false

        // Cancel any pending collapse
        collapseRunnable?.let { handler.removeCallbacks(it) }

        // Expand to show notification
        overlayView.expandWithNotification(notif)

        // Schedule auto-collapse
        val timeout = prefs.getInt("dynamic_island_timeout", 5) * 1000L
        collapseRunnable = Runnable { collapseNotification() }
        handler.postDelayed(collapseRunnable!!, timeout)
    }

    private fun collapseNotification() {
        if (isExpanded) return // Don't auto-collapse if user opened the list

        currentNotification = null

        if (notificationQueue.isNotEmpty()) {
            overlayView.collapseToIcons(notificationQueue)
        } else {
            updateState()
        }
    }

    private fun expandToList() {
        if (notificationQueue.isEmpty()) return

        isExpanded = true
        collapseRunnable?.let { handler.removeCallbacks(it) }

        overlayView.expandToList(notificationQueue)
    }

    private fun collapseList() {
        isExpanded = false

        if (notificationQueue.isNotEmpty()) {
            overlayView.collapseToIcons(notificationQueue)
        } else {
            updateState()
        }
    }

    private fun handleNotificationClick(notif: NotificationData) {
        Log.d(TAG, "Notification clicked: ${notif.key}")
        // The notification system handles the click through PendingIntent
        // Just dismiss from Dynamic Island
        dismissNotification(notif.key)
    }

    private fun handleNotificationDismiss(notif: NotificationData) {
        Log.d(TAG, "Notification dismissed: ${notif.key}")

        // Remove from queue
        notificationQueue.removeAll { it.key == notif.key }

        if (currentNotification?.key == notif.key) {
            currentNotification = null
        }

        // Tell phone to dismiss
        val intent =
                Intent(BluetoothService.ACTION_CMD_DISMISS_ON_PHONE).apply {
                    putExtra(BluetoothService.EXTRA_NOTIF_KEY, notif.key)
                    setPackage(packageName)
                }
        sendBroadcast(intent)

        // Update display
        if (isExpanded && notificationQueue.isNotEmpty()) {
            overlayView.expandToList(notificationQueue)
        } else if (notificationQueue.isNotEmpty()) {
            overlayView.collapseToIcons(notificationQueue)
        } else {
            isExpanded = false
            updateState()
        }
    }

    private fun handleNotificationReply(notif: NotificationData, replyText: String) {
        Log.d(TAG, "Notification reply: ${notif.key} -> $replyText")

        // Find the reply action
        val replyAction = notif.actions.find { it.isReplyAction }
        if (replyAction != null) {
            val intent =
                    Intent(BluetoothService.ACTION_CMD_SEND_REPLY).apply {
                        putExtra(BluetoothService.EXTRA_NOTIF_KEY, notif.key)
                        putExtra(BluetoothService.EXTRA_ACTION_KEY, replyAction.actionKey)
                        putExtra(BluetoothService.EXTRA_REPLY_TEXT, replyText)
                        setPackage(packageName)
                    }
            sendBroadcast(intent)
        }

        // Add local reply to history for immediate feedback (mimics real Android behavior)
        val newMessage =
                NotificationMessageData(
                        text = replyText,
                        timestamp = System.currentTimeMillis(),
                        senderName = "Me" // Matches BluetoothService logic for user replies
                )

        // Update the notification in the queue
        notificationQueue.indexOfFirst { it.key == notif.key }.let { index ->
            if (index != -1) {
                val updatedNotif =
                        notificationQueue[index].copy(
                                messages = notificationQueue[index].messages + newMessage
                        )
                notificationQueue[index] = updatedNotif

                // Refresh display immediately
                if (isExpanded) {
                    overlayView.expandToList(notificationQueue)
                } else {
                    overlayView.expandWithNotification(updatedNotif)
                }
            }
        }
    }

    private fun dismissNotification(key: String) {
        val wasInQueue = notificationQueue.any { it.key == key }
        notificationQueue.removeAll { it.key == key }

        if (currentNotification?.key == key) {
            currentNotification = null

            if (notificationQueue.isNotEmpty()) {
                displayNotification(notificationQueue[0])
            } else {
                updateState()
            }
        } else if (wasInQueue) {
            // If it was in the queue but not current, we might need to refresh icons or expanded
            // list
            if (isExpanded) {
                overlayView.expandToList(notificationQueue)
            } else {
                updateState()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DynamicIslandService onDestroy")
        isRunning = false

        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Ignore
        }

        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            // Ignore
        }

        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
