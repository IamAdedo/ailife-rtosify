package com.ailife.rtosifycompanion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import androidx.core.app.NotificationCompat
import com.google.gson.Gson

class DynamicIslandService : Service() {

    companion object {
        private const val TAG = "DynamicIslandService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "dynamic_island_channel"
        
        // Auto-hide modes
        private const val AUTO_HIDE_ALWAYS_SHOW = 0
        private const val AUTO_HIDE_NEVER_SHOW = 1
        private const val AUTO_HIDE_IN_BLACKLIST = 2

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

    private var isConnected = false
    private var isCharging = false
    private var lastIsCharging = false
    private var batteryPercent = 0
    private var lastBatteryPercent = -1
    private var isShowingTransientState = false
    private var currentTransport = ""
    private var isOverlayVisible = true
    private var currentVisibilityAnimator: ObjectAnimator? = null
    
    // Auto-hide mode variables
    private var autoHideMode = AUTO_HIDE_ALWAYS_SHOW
    private var blacklistedApps = emptySet<String>()
    private var hideWithActiveNotifs = false
    
    // Feature toggles
    private var showPhoneCalls = true
    private var showAlarms = true
    private var showDisconnect = true
    private var showMedia = true
    
    // Phone call state
    data class PhoneCallData(
        val number: String,
        val contactName: String?,
        val isRinging: Boolean
    )
    private var currentCall: PhoneCallData? = null

    private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothService.ACTION_SHOW_IN_DYNAMIC_ISLAND -> {
                            val notifKey = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
                            val notifJson = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_JSON)

                            if (notifKey != null) {
                                val cachedNotif = NotificationCache.get(notifKey)
                                if (cachedNotif != null) {
                                    showNotification(cachedNotif)
                                    return
                                }
                            }

                            // Fallback to JSON if old method or cache miss (though ideally avoided)
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
                        BluetoothService.ACTION_UPDATE_DI_SETTINGS -> {
                            loadSettings()
                        }
                        Intent.ACTION_BATTERY_CHANGED -> {
                            handleBatteryChanged(intent)
                        }
                        BluetoothService.ACTION_CONNECTION_STATE_CHANGED -> {
                            val connected = intent.getBooleanExtra("connected", false)
                            val transport = intent.getStringExtra("transport") ?: ""
                            // ALWAYS update state on broadcast to ensure UI is in sync
                            isConnected = connected
                            currentTransport = transport
                            if (showDisconnect || connected) {
                                showTransientConnectionState(connected, transport)
                            } else {
                                // Post standard Android notification when toggle is off
                                // TODO: Implement standard notification fallback
                            }
                        }
                        "com.ailife.rtosifycompanion.INCOMING_CALL" -> {
                            handleIncomingCallBroadcast(intent)
                        }
                        "com.ailife.rtosifycompanion.CALL_STATE_CHANGED" -> {
                            handleCallStateChanged(intent)
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

        // Load initial settings
        loadSettings()

        // Initialize connection state - request from BluetoothService
        isConnected = false
        requestInitialConnectionState()
        updateState()
    }

    private fun loadSettings() {
        val y = prefs.getInt("dynamic_island_y", 8).dpToPx()
        params.y = y
        try {
            windowManager.updateViewLayout(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update window layout: ${e.message}")
        }

        val width = prefs.getInt("dynamic_island_width", 150)
        val height = prefs.getInt("dynamic_island_height", 40)
        overlayView.updateDimensions(width, height)

        val textMultiplier = prefs.getFloat("dynamic_island_text_multiplier", 1.0f)
        val limitMessageLength = prefs.getBoolean("dynamic_island_limit_message_length", true)
        overlayView.updateTextSettings(textMultiplier, limitMessageLength)
        
        // Load auto-hide mode settings
        autoHideMode = prefs.getInt("di_auto_hide_mode", AUTO_HIDE_ALWAYS_SHOW)
        blacklistedApps = prefs.getStringSet("di_blacklist_apps", emptySet()) ?: emptySet()
        hideWithActiveNotifs = prefs.getBoolean("di_hide_with_active_notifs", false)
        
        // Load feature toggles
        showPhoneCalls = prefs.getBoolean("di_show_phone_calls", true)
        showAlarms = prefs.getBoolean("di_show_alarms", true)
        showDisconnect = prefs.getBoolean("di_show_disconnect", true)
        showMedia = prefs.getBoolean("di_show_media", true)
        
        Log.d(TAG, "Auto-hide mode: $autoHideMode, Blacklisted apps: ${blacklistedApps.size}, Hide with notifs: $hideWithActiveNotifs")
        Log.d(TAG, "Feature toggles - Calls: $showPhoneCalls, Alarms: $showAlarms, Disconnect: $showDisconnect, Media: $showMedia")

        updateState()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

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
                                    getString(R.string.di_channel_name),
                                    NotificationManager.IMPORTANCE_LOW
                            )
                            .apply {
                                description = getString(R.string.di_channel_desc)
                                setShowBadge(false)
                            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.di_active_title))
                .setContentText(getString(R.string.di_active_desc))
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
        
        overlayView.onCallAction = { action ->
            Log.d(TAG, "Call action: $action")
            // Send call action to BluetoothService
            val intent = Intent("com.ailife.rtosifycompanion.CALL_ACTION")
            intent.setPackage(packageName)
            intent.putExtra("action", action)
            sendBroadcast(intent)
        }
        
        overlayView.onAlarmAction = { action ->
            Log.d(TAG, "Alarm action: $action")
            // Send alarm action to BluetoothService
            val intent = Intent("com.ailife.rtosifycompanion.ALARM_ACTION")
            intent.setPackage(packageName)
            intent.putExtra("action", action)
            sendBroadcast(intent)
        }
        
        overlayView.onMediaAction = { action ->
            Log.d(TAG, "Media action: $action")
            // Send media action to BluetoothService
            val intent = Intent("com.ailife.rtosifycompanion.MEDIA_ACTION")
            intent.setPackage(packageName)
            intent.putExtra("action", action)
            sendBroadcast(intent)
        }

        windowManager.addView(overlayView, params)
        updateState()
    }

    private fun registerReceivers() {
        val filter =
                IntentFilter().apply {
                    addAction(BluetoothService.ACTION_SHOW_IN_DYNAMIC_ISLAND)
                    addAction(BluetoothService.ACTION_DISMISS_FROM_DYNAMIC_ISLAND)
                    addAction(BluetoothService.ACTION_UPDATE_DI_SETTINGS)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction("com.ailife.rtosifycompanion.CONNECTION_STATE_CHANGED")
                    addAction("com.ailife.rtosifycompanion.INCOMING_CALL")
                    addAction("com.ailife.rtosifycompanion.CALL_STATE_CHANGED")
                    addAction(MediaControlActivity.ACTION_MEDIA_STATE_UPDATE)
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

        if (isCharging != lastIsCharging || batteryPercent != lastBatteryPercent) {
            val wasJustConnected = isCharging && !lastIsCharging
            lastIsCharging = isCharging
            lastBatteryPercent = batteryPercent

            if (isCharging) {
                showTransientChargingState(isCharging, wasJustConnected)
            } else {
                updateState()
            }
        }
    }

    private fun showTransientChargingState(charging: Boolean, animate: Boolean = false) {
        lastIsCharging = charging
        isShowingTransientState = true
        transientStateRunnable?.let { handler.removeCallbacks(it) }

        // Update visibility and state first
        updateState()

        if (charging) {
            overlayView.showChargingState(batteryPercent, animate)
        }

        // Only auto-hide if the setting is enabled
        val hideWhenIdle = prefs.getBoolean("dynamic_island_hide_idle", false)
        // Always schedule auto-hide reset to avoid getting stuck in transient state
        val timeout = if (hideWhenIdle) {
            prefs.getInt("dynamic_island_timeout", 5) * 1000L
        } else {
            3000L // Default short timeout even if not hiding, to return to normal persistent logic
        }

        transientStateRunnable = Runnable {
            isShowingTransientState = false
            updateState()
        }
        handler.postDelayed(transientStateRunnable!!, timeout)
        // If hideWhenIdle is false, transient state stays visible until next state change
    }

    private fun showTransientConnectionState(connected: Boolean, transportType: String = "") {
        isShowingTransientState = true
        transientStateRunnable?.let { handler.removeCallbacks(it) }

        // Wake screen and vibrate on disconnect if enabled
        if (!connected && showDisconnect) {
            wakeScreenAndVibrate()
        }

        // Update visibility and state first
        updateState()

        if (connected) {
            overlayView.showConnectedState(transportType)
        } else {
            if (showDisconnect) {
                // Force show overlay for disconnect
                showOverlayAnimated()
                overlayView.showDisconnectedState()
            }
            // If showDisconnect is false, could post standard notification here
        }

        // Only auto-hide if the setting is enabled
        val hideWhenIdle = prefs.getBoolean("dynamic_island_hide_idle", false)
        val timeout = if (hideWhenIdle) {
            prefs.getInt("dynamic_island_timeout", 5) * 1000L
        } else {
            3000L
        }

        transientStateRunnable = Runnable {
            isShowingTransientState = false
            updateState()
        }
        handler.postDelayed(transientStateRunnable!!, timeout)
    }
    
    /**
     * Wake screen and vibrate if enabled in settings
     */
    private fun wakeScreenAndVibrate() {
        try {
            // Check if wake screen is enabled
            val wakeEnabled = prefs.getBoolean("wake_screen_enabled", false)
            if (wakeEnabled) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (powerManager != null) {
                    val wakeLock = powerManager.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or 
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "DynamicIsland:DisconnectWake"
                    )
                    wakeLock.acquire(3000) // 3 seconds
                    wakeLock.release()
                }
            }
            
            // Check if vibrate is enabled
            val vibrateEnabled = prefs.getBoolean("vibrate_enabled", false)
            if (vibrateEnabled) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(200)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen or vibrating: ${e.message}")
        }
    }

    private fun showOverlayAnimated() {
        if (isOverlayVisible) return

        currentVisibilityAnimator?.cancel()
        isOverlayVisible = true

        // Make visible before animating
        overlayView.visibility = View.VISIBLE

        // Animate from above the screen to normal position
        val slideDistance = overlayView.height.takeIf { it > 0 } ?: 100.dpToPx()
        overlayView.translationY = -slideDistance.toFloat()

        currentVisibilityAnimator = ObjectAnimator.ofFloat(overlayView, "translationY", -slideDistance.toFloat(), 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun hideOverlayAnimated() {
        if (!isOverlayVisible) return

        currentVisibilityAnimator?.cancel()
        isOverlayVisible = false

        // Animate to above the screen
        val slideDistance = overlayView.height.takeIf { it > 0 } ?: 100.dpToPx()

        currentVisibilityAnimator = ObjectAnimator.ofFloat(overlayView, "translationY", 0f, -slideDistance.toFloat()).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isOverlayVisible) {
                        overlayView.visibility = View.GONE
                        overlayView.translationY = 0f
                    }
                }
            })
            start()
        }
    }

    private fun updateState() {
        handler.post {
            if (isShowingTransientState) {
                showOverlayAnimated()
                // Don't override the transient UI with persistent state logic
                return@post
            }

            // Auto-hide mode logic
            val hasNotifications = notificationQueue.isNotEmpty()
            
            val shouldHide = when (autoHideMode) {
                AUTO_HIDE_ALWAYS_SHOW -> false // Never hide, always visible
                AUTO_HIDE_NEVER_SHOW -> !hasNotifications // Hide when no notifications
                AUTO_HIDE_IN_BLACKLIST -> isInBlacklistedApp() // Hide if current app is blacklisted
                else -> false
            }

            if (shouldHide) {
                overlayView.showIdleState(currentTransport)
                hideOverlayAnimated()
            } else {
                showOverlayAnimated()

                // Normal state logic
                when {
                    currentNotification != null -> {
                        // Already showing a notification (expanded or recent)
                    }
                    notificationQueue.isNotEmpty() -> {
                        overlayView.collapseToIcons(notificationQueue)
                    }
                    isCharging -> {
                        overlayView.showChargingState(batteryPercent)
                    }
                    !isConnected -> {
                        overlayView.showDisconnectedState()
                    }
                    else -> {
                        overlayView.showIdleState(currentTransport)
                    }
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

        // Force visibility update to unhide if necessary
        updateState()
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
    
    private fun handleMediaStateUpdate(intent: Intent) {
        if (!showMedia) {
            Log.d(TAG, "Media display disabled, skipping Dynamic Island display")
            return
        }
        
        val isPlaying = intent.getBooleanExtra("isPlaying", false)
        val title = intent.getStringExtra("title") ?: "Unknown"
        val artist = intent.getStringExtra("artist")
        val albumArtBase64 = intent.getStringExtra("albumArt")
        
        if (title.isNotEmpty()) {
            isShowingTransientState = true
            showOverlayAnimated()
            overlayView.showMediaPlaying(title, artist, isPlaying, albumArtBase64)
            
            // Keep showing until media stops
            transientStateRunnable?.let { handler.removeCallbacks(it) }
        }
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
                        senderName = notif.selfName ?: getString(R.string.reply_sender_me),
                        senderIcon = notif.selfIcon
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
    
    /**
     * Check if the current foreground app is in the blacklist
     */
    @SuppressLint("NewApi")
    private fun isInBlacklistedApp(): Boolean {
        if (blacklistedApps.isEmpty()) {
            return false
        }
        
        try {
            val currentPackage = getCurrentForegroundApp()
            if (currentPackage != null && blacklistedApps.contains(currentPackage)) {
                // App is blacklisted - check if we should hide even with active notifications
                if (hideWithActiveNotifs) {
                    // Hide regardless of notifications
                    return true
                } else {
                    // Only hide if no notifications
                    return notificationQueue.isEmpty()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking blacklisted app: ${e.message}")
        }
        
        return false
    }
    
    /**
     * Get the current foreground app package name using UsageStatsManager
     */
    @SuppressLint("NewApi")
    private fun getCurrentForegroundApp(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null
        }
        
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            if (usageStatsManager == null) {
                Log.w(TAG, "UsageStatsManager not available")
                return null
            }
            
            val currentTime = System.currentTimeMillis()
            // Query events from last 1 second
            val usageEvents = usageStatsManager.queryEvents(currentTime - 1000, currentTime)
            
            var lastEventPackage: String? = null
            val event = android.app.usage.UsageEvents.Event()
            
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastEventPackage = event.packageName
                }
            }
            
            return lastEventPackage
        } catch (e: Exception) {
            Log.e(TAG, "Error getting foreground app: ${e.message}")
            return null
        }
    }
    
    /**
     * Handle incoming call broadcast
     */
    private fun handleIncomingCallBroadcast(intent: Intent) {
        if (!showPhoneCalls) {
            Log.d(TAG, "Phone calls disabled in Dynamic Island, using standard UI")
            // BluetoothService will handle launching CallActivity
            return
        }
        
        val number = intent.getStringExtra("number") ?: "Unknown"
        val contactName = intent.getStringExtra("callerId")
        
        Log.d(TAG, "Incoming call in Dynamic Island: $number ($contactName)")
        
        currentCall = PhoneCallData(
            number = number,
            contactName = contactName,
            isRinging = true
        )
        
        // Show call in Dynamic Island
        isShowingTransientState = true
        showOverlayAnimated()
        overlayView.showPhoneCall(number, contactName, true)
    }
    
    /**
     * Handle call state changed
     */
    private fun handleCallStateChanged(intent: Intent) {
        val state = intent.getStringExtra("state") ?: return
        
        Log.d(TAG, "Call state changed: $state")
        
        when (state) {
            "ENDED", "IDLE" -> {
                // Call ended
                currentCall = null
                isShowingTransientState = false
                updateState()
            }
            "ACTIVE" -> {
                // Call answered - could show different UI or hide
                currentCall?.let {
                    currentCall = it.copy(isRinging = false)
                }
                // Hide Dynamic Island while in call
                isShowingTransientState = false
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
        currentVisibilityAnimator?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
