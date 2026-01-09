package com.ailife.rtosifycompanion

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.*
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.google.gson.Gson

class DynamicIslandService : Service() {

    companion object {
        private const val TAG = "DynamicIslandService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "dynamic_island_channel"

        @Volatile
        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: DynamicIslandView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private val notificationQueue = mutableListOf<NotificationData>()
    private var currentNotification: NotificationData? = null
    private var isExpanded = false
    private var collapseRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isBluetoothConnected = false
    private var isCharging = false
    private var batteryPercent = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothService.ACTION_SHOW_IN_DYNAMIC_ISLAND -> {
                    val notifJson = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_JSON)
                    if (notifJson != null) {
                        try {
                            val notif = Gson().fromJson(notifJson, NotificationData::class.java)
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
                "com.ailife.rtosifycompanion.CONNECTION_STATE_CHANGED" -> {
                    isBluetoothConnected = intent.getBooleanExtra("connected", false)
                    if (!isBluetoothConnected && notificationQueue.isEmpty() && currentNotification == null) {
                        overlayView.showDisconnectedState()
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

        // Initialize connection state - will be updated via broadcasts
        isBluetoothConnected = false
        updateState()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dynamic Island",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
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
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
        }

        overlayView = DynamicIslandView(this)
        overlayView.setOnClickListener {
            if (isExpanded) {
                // If expanded and showing notification list, user clicked to interact
                // Keep expanded
            } else if (currentNotification != null) {
                // Collapsed with notification - expand to show list
                expandToList()
            } else {
                // Just the pill - do nothing or collapse further
            }
        }

        overlayView.onNotificationClick = { notif ->
            handleNotificationClick(notif)
        }

        overlayView.onNotificationDismiss = { notif ->
            handleNotificationDismiss(notif)
        }

        overlayView.onNotificationReply = { notif, replyText ->
            handleNotificationReply(notif, replyText)
        }

        windowManager.addView(overlayView, params)
        updateState()
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
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
        batteryPercent = if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            0
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        updateState()
    }

    private fun updateState() {
        handler.post {
            when {
                currentNotification != null -> {
                    // Showing a notification - already handled by expand/collapse logic
                }
                isCharging -> {
                    overlayView.showChargingState(batteryPercent)
                }
                !isBluetoothConnected -> {
                    overlayView.showDisconnectedState()
                }
                notificationQueue.isNotEmpty() -> {
                    overlayView.collapseToIcons(notificationQueue)
                }
                else -> {
                    // Just show the pill
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

        // Cancel any pending collapse
        collapseRunnable?.let { handler.removeCallbacks(it) }

        // Expand to show notification
        overlayView.expandWithNotification(notif)

        // Schedule auto-collapse
        val timeout = prefs.getInt("dynamic_island_timeout", 5) * 1000L
        collapseRunnable = Runnable {
            collapseNotification()
        }
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
        val intent = Intent(BluetoothService.ACTION_CMD_DISMISS_ON_PHONE).apply {
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
            val intent = Intent(BluetoothService.ACTION_CMD_SEND_REPLY).apply {
                putExtra(BluetoothService.EXTRA_NOTIF_KEY, notif.key)
                putExtra(BluetoothService.EXTRA_ACTION_KEY, replyAction.actionKey)
                putExtra(BluetoothService.EXTRA_REPLY_TEXT, replyText)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        // Collapse after replying
        handleNotificationDismiss(notif)
    }

    private fun dismissNotification(key: String) {
        notificationQueue.removeAll { it.key == key }

        if (currentNotification?.key == key) {
            currentNotification = null

            if (notificationQueue.isNotEmpty()) {
                displayNotification(notificationQueue[0])
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
