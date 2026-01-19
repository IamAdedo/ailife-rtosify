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
    private var foregroundCheckRunnable: Runnable? = null

    private var isConnected = false
    private var isCharging = false
    private var lastIsCharging = false
    private var batteryPercent = 0
    private var lastBatteryPercent = -1
    private var hasShownFullAnimation = false  // Track if we showed 100% animation
    private var currentTransport = ""
    private var isOverlayVisible = true
    private var currentVisibilityAnimator: ObjectAnimator? = null
    
    // Auto-hide mode variables
    private var autoHideMode = AUTO_HIDE_ALWAYS_SHOW
    private var blacklistedApps = emptySet<String>()
    private var hideWithActiveNotifs = false
    private var lastForegroundApp: String? = null
    private var isCurrentlyInBlacklistedApp = false
    
    // Feature toggles
    private var showPhoneCalls = true
    private var showAlarms = true
    private var showDisconnect = true
    private var showMedia = true
    
    // State data classes
    data class PhoneCallData(
        val number: String,
        val contactName: String?,
        val isRinging: Boolean
    )
    
    data class AlarmData(
        val id: String,
        val label: String
    )
    
    data class MediaData(
        val title: String,
        val artist: String?,
        val isPlaying: Boolean,
        val albumArtBase64: String?,
        val position: Long = 0,
        val duration: Long = 0,
        val volume: Int = 0
    )
    
    enum class TransientType {
        CONNECTION_CHANGE,
        CHARGING_ANIMATION,
        NONE
    }
    
    data class TransientState(
        val type: TransientType,
        val expiresAt: Long
    )
    
    // Specific state variables
    private var currentCall: PhoneCallData? = null
    private var currentAlarm: AlarmData? = null
    private var currentMedia: MediaData? = null
    private var activeState: TransientState? = null  // Renamed from transientState for clarity

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
                        MediaControlActivity.ACTION_MEDIA_STATE_UPDATE -> {
                            handleMediaStateUpdate(intent)
                        }
                        "com.ailife.rtosifycompanion.ALARM_TRIGGERED" -> {
                            handleAlarmTriggerBroadcast(intent)
                        }
                        "com.ailife.rtosifycompanion.ALARM_CLEARED" -> {
                            handleAlarmCleared()
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
        
        // Start/stop periodic foreground app checking based on auto-hide mode
        if (autoHideMode == AUTO_HIDE_IN_BLACKLIST && blacklistedApps.isNotEmpty()) {
            startForegroundAppChecking()
        } else {
            stopForegroundAppChecking()
        }
    }
    
    private fun startForegroundAppChecking() {
        stopForegroundAppChecking() // Stop any existing check first
        
        foregroundCheckRunnable = object : Runnable {
            override fun run() {
                // Update blacklist state
                val wasInBlacklist = isCurrentlyInBlacklistedApp
                isInBlacklistedApp()  // Updates isCurrentlyInBlacklistedApp
                
                if (wasInBlacklist != isCurrentlyInBlacklistedApp) {
                    // Blacklist state changed - update display
                    Log.d(TAG, "Blacklist state changed: $wasInBlacklist -> $isCurrentlyInBlacklistedApp")
                    updateState()
                } else if (isCurrentlyInBlacklistedApp) {
                    // In blacklist - only check if we should hide/show, don't re-render
                    checkBlacklistVisibility()
                }
                
                handler.postDelayed(this, 500) // Check every 500ms
            }
        }
        handler.post(foregroundCheckRunnable!!)
        Log.d(TAG, "Started periodic foreground app checking")
    }
    
    private fun stopForegroundAppChecking() {
        foregroundCheckRunnable?.let {
            handler.removeCallbacks(it)
            foregroundCheckRunnable = null
            Log.d(TAG, "Stopped periodic foreground app checking")
        }
    }
    
    /**
     * Check if overlay should be hidden/shown based on blacklist state
     * Does NOT re-render the current state, only toggles visibility
     */
    private fun checkBlacklistVisibility() {
        handler.post {
            // Determine current state
            val currentState = when {
                isExpanded -> "expanded"
                currentNotification != null -> "active_notification"
                currentCall != null -> "call"
                currentAlarm != null -> "alarm"
                activeState != null && System.currentTimeMillis() < activeState!!.expiresAt -> "active_transient"
                currentMedia != null -> "media"
                notificationQueue.isNotEmpty() -> "notification_icons"
                isCharging -> "charging"
                !isConnected -> "disconnected"
                else -> "idle"
            }
            
            // Check if should hide
            val shouldHide = shouldHideForBlacklist(currentState)
            
            Log.d(TAG, "checkBlacklistVisibility: state=$currentState, shouldHide=$shouldHide, isVisible=$isOverlayVisible")
            
            // Only toggle visibility, don't re-render
            if (shouldHide && isOverlayVisible) {
                Log.d(TAG, "Hiding overlay")
                hideOverlayAnimated()
            } else if (!shouldHide && !isOverlayVisible) {
                Log.d(TAG, "Showing overlay")
                showOverlayAnimated()
            }
        }
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
                // If expanded list/media is showing, collapse it
                collapseList()
            } else if (currentMedia != null) {
                // Media is playing - expand to show media controls
                expandMedia()
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
            Log.d(TAG, "Call action: $action - clearing Dynamic Island")
            // Send call action to BluetoothService
            val intent = Intent("com.ailife.rtosifycompanion.CALL_ACTION")
            intent.setPackage(packageName)
            intent.putExtra("action", action)
            sendBroadcast(intent)
            
            // Clear call state immediately
            currentCall = null
            stopVibration()
            updateState()
        }
        
        overlayView.onAlarmAction = { action ->
            Log.d(TAG, "Alarm action: $action - clearing Dynamic Island")
            // Send alarm action to BluetoothService to stop ringtone
            val intent = Intent("com.ailife.rtosifycompanion.ALARM_ACTION")
            intent.setPackage(packageName)
            intent.putExtra("action", action)
            sendBroadcast(intent)
            
            // Clear alarm state immediately
            currentAlarm = null
            stopVibration()
            updateState()
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
                    addAction(BluetoothService.ACTION_CONNECTION_STATE_CHANGED)
                    addAction("com.ailife.rtosifycompanion.INCOMING_CALL")
                    addAction("com.ailife.rtosifycompanion.CALL_STATE_CHANGED")
                    addAction(MediaControlActivity.ACTION_MEDIA_STATE_UPDATE)
                    addAction("com.ailife.rtosifycompanion.ALARM_TRIGGERED")
                    addAction("com.ailife.rtosifycompanion.ALARM_CLEARED")
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
        val newBatteryPercent =
                if (level >= 0 && scale > 0) {
                    (level * 100 / scale.toFloat()).toInt()
                } else {
                    0
                }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val newIsCharging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

        // Check if plug state changed or reached 100% (only once)
        val plugStateChanged = newIsCharging != lastIsCharging
        val reachedFull = newBatteryPercent == 100 && lastBatteryPercent != 100 && !hasShownFullAnimation
        
        batteryPercent = newBatteryPercent
        isCharging = newIsCharging
        
        // Reset full animation flag when battery drops below 100%
        if (newBatteryPercent < 100) {
            hasShownFullAnimation = false
        }

        if (plugStateChanged || reachedFull) {
            val wasJustConnected = newIsCharging && !lastIsCharging
            lastIsCharging = newIsCharging
            lastBatteryPercent = newBatteryPercent
            
            if (reachedFull) {
                hasShownFullAnimation = true
            }

            if (newIsCharging || reachedFull) {
                // Show animation when plugged in or reached 100%
                showTransientChargingState(newIsCharging, wasJustConnected || reachedFull)
            } else {
                // Unplugged
                updateState()
            }
        } else if (batteryPercent != lastBatteryPercent) {
            // Just percentage changed, update cached value and refresh display (no animation)
            lastBatteryPercent = batteryPercent
            updateState()
        }
    }

    private fun showTransientChargingState(charging: Boolean, animate: Boolean = false) {
        lastIsCharging = charging
        
        if (charging) {
            // Set active state with timeout
            val timeout = prefs.getInt("dynamic_island_timeout", 5) * 1000L
            val stateInstance = TransientState(
                type = if (animate) TransientType.CHARGING_ANIMATION else TransientType.NONE,
                expiresAt = System.currentTimeMillis() + timeout
            )
            activeState = stateInstance
            
            // Schedule state update when active state expires
            handler.postDelayed({
                if (activeState === stateInstance) {
                    activeState = null
                    updateState()
                }
            }, timeout)
        }
        
        updateState()
    }

    private fun showTransientConnectionState(connected: Boolean, transportType: String = "") {
        // Wake screen and vibrate on disconnect if enabled
        if (!connected && showDisconnect) {
            wakeScreenAndVibrate()
            
            // Trigger expanded disconnect alert
            overlayView.showExpandedDisconnected()
        }
        
        // Set active state with timeout
        val timeout = prefs.getInt("dynamic_island_timeout", 5) * 1000L
        val stateInstance = TransientState(
            type = TransientType.CONNECTION_CHANGE,
            expiresAt = System.currentTimeMillis() + timeout
        )
        activeState = stateInstance
        
        // Schedule state update when active state expires
        handler.postDelayed({
            if (activeState === stateInstance) {
                activeState = null
                updateState()
            }
        }, timeout)
        
        updateState()
    }
    
    /**
     * Wake screen and vibrate if enabled in settings
     */
    private fun wakeScreenAndVibrate() {
        try {
            // Check if wake screen is enabled
            val wakeEnabled = prefs.getBoolean("wake_screen_enabled", false)
            if (wakeEnabled) {
                wakeScreen(3000)
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

    private fun wakeScreen(durationMs: Long) {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (powerManager != null) {
                val wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "DynamicIsland:WakeLock"
                )
                wakeLock.acquire(durationMs)
                // It will auto-release after durationMs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen: ${e.message}")
        }
    }

    private fun startPriorityVibration() {
        val vibrateEnabled = prefs.getBoolean("vibrate_enabled", true)
        if (!vibrateEnabled) return

        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vibrator?.hasVibrator() == true) {
                val pattern = longArrayOf(0, 1000, 1000) // Vibrate 1s, Gap 1s
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0)) // 0 means repeat
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start priority vibration: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop vibration: ${e.message}")
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
            // Update blacklist state if needed
            if (autoHideMode == AUTO_HIDE_IN_BLACKLIST && blacklistedApps.isNotEmpty()) {
                isInBlacklistedApp()  // Updates isCurrentlyInBlacklistedApp
            }
            
            // Determine current state based on priority
            val currentState = when {
                // User interaction (highest priority)
                isExpanded && currentMedia != null -> "media_expanded"
                isExpanded -> "expanded"
                
                // Active transient state (5 second display) - Higher priority for disconnects/animations
                activeState != null && System.currentTimeMillis() < activeState!!.expiresAt -> "active_transient"
                
                // Active notification (5 second display)
                currentNotification != null -> "active_notification"
                
                // Continuous states (can't be interrupted)
                currentCall != null -> "call"
                currentAlarm != null -> "alarm"
                
                // Idle states (persistent, priority order: media > notifications > charging > connection)
                currentMedia != null -> "media"
                notificationQueue.isNotEmpty() -> "notification_icons"
                isCharging -> "charging"
                !isConnected -> "disconnected"
                else -> "idle"
            }
            
            Log.d(TAG, "updateState: state=$currentState, autoHide=$autoHideMode, blacklisted=$isCurrentlyInBlacklistedApp")
            
            // Check if we should hide
            val shouldHide = when (autoHideMode) {
                AUTO_HIDE_ALWAYS_SHOW -> false
                
                AUTO_HIDE_NEVER_SHOW -> {
                    // Hide only in true idle (no media, no notifications)
                    currentState == "idle" || currentState == "charging" || currentState == "disconnected"
                }
                
                AUTO_HIDE_IN_BLACKLIST -> {
                    shouldHideForBlacklist(currentState)
                }
                
                else -> false
            }
            
            Log.d(TAG, "shouldHide=$shouldHide")
            
            if (shouldHide) {
                overlayView.showIdleState(currentTransport)
                hideOverlayAnimated()
                return@post
            }
            
            showOverlayAnimated()
            
            // Display the appropriate state
            when (currentState) {
                "media_expanded" -> {
                    overlayView.expandWithMedia(
                        currentMedia!!.title,
                        currentMedia!!.artist,
                        currentMedia!!.isPlaying,
                        currentMedia!!.albumArtBase64,
                        currentMedia!!.position,
                        currentMedia!!.duration,
                        currentMedia!!.volume
                    )
                }
                
                "expanded", "active_notification" -> {
                    // Already showing, don't change
                    return@post
                }
                
                "call" -> {
                    overlayView.showPhoneCall(
                        currentCall!!.number,
                        currentCall!!.contactName,
                        currentCall!!.isRinging
                    )
                }
                
                "alarm" -> {
                    overlayView.showAlarm(currentAlarm!!.label, getString(R.string.di_alarm_triggered))
                }
                
                "active_transient" -> {
                    when (activeState!!.type) {
                        TransientType.CONNECTION_CHANGE -> {
                            if (isConnected) {
                                overlayView.showConnectedState(currentTransport)
                            } else if (showDisconnect) {
                                overlayView.showExpandedDisconnected()
                            } else {
                                overlayView.showDisconnectedState()
                            }
                        }
                        TransientType.CHARGING_ANIMATION -> {
                            overlayView.showChargingState(batteryPercent, animate = true)
                        }
                        TransientType.NONE -> {}
                    }
                }
                
                // Idle states (priority order)
                "media" -> {
                    Log.d(TAG, "Showing media: ${currentMedia!!.title}")
                    overlayView.showMediaState(
                        currentMedia!!.title,
                        currentMedia!!.artist,
                        currentMedia!!.isPlaying,
                        currentMedia!!.albumArtBase64
                    )
                }
                
                "notification_icons" -> {
                    overlayView.collapseToIcons(notificationQueue)
                }
                
                "charging" -> {
                    overlayView.showChargingState(batteryPercent)
                }
                
                "disconnected" -> {
                if (activeState?.type == TransientType.CONNECTION_CHANGE && !isConnected) {
                    overlayView.showExpandedDisconnected()
                } else {
                    overlayView.showDisconnectedState()
                }
            }
                
                "idle" -> {
                    overlayView.showIdleState(currentTransport)
                }
            }
        }
    }
    
    private fun shouldHideForBlacklist(currentState: String): Boolean {
        if (!isCurrentlyInBlacklistedApp) {
            return false
        }
        
        // Don't hide if actively showing something important or transient animations
        if (isExpanded || currentNotification != null || currentCall != null || 
            currentAlarm != null || currentState == "active_transient") {
            return false
        }
        
        // Check hideWithActiveNotifs toggle
        return if (hideWithActiveNotifs) {
            // Hide notification icons and media when in blacklisted app
            currentState == "notification_icons" || currentState == "media" || 
            currentState == "idle" || currentState == "charging" || currentState == "disconnected"
        } else {
            // Only hide if in idle/charging/disconnected state (no notifications)
            currentState == "idle" || currentState == "charging" || currentState == "disconnected"
        }
    }

    private fun showNotification(notif: NotificationData) {
        Log.d(TAG, "Showing notification in Dynamic Island: ${notif.title}")

        // Add to queue (don't filter blacklisted apps - they should still show notifications)
        notificationQueue.removeAll { it.key == notif.key } // Remove duplicates
        notificationQueue.add(0, notif) // Add to front

        // If not currently expanded, show this one as a peek
        if (!isExpanded) {
            displayNotification(notif)
        } else {
            // Already expanded to list, just update the list content
            overlayView.expandToList(notificationQueue)
        }

        // Force visibility update to unhide if necessary
        updateState()
    }

    private fun handleAlarmTriggerBroadcast(intent: Intent) {
        if (!showAlarms) return
        
        val id = intent.getStringExtra("alarm_id") ?: "unknown"
        val label = intent.getStringExtra("label") ?: "Alarm"
        
        // Create a dummy NotificationData for the alarm UI
        val dummyNotif = NotificationData(
            packageName = packageName,
            title = label,
            text = getString(R.string.di_alarm_triggered),
            key = "alarm_$id",
            appName = getString(R.string.app_name)
        )
        
        handleAlarmNotification(dummyNotif)
    }


    private fun handleAlarmNotification(notif: NotificationData) {
        Log.d(TAG, "Handling alarm notification in Dynamic Island")
        
        // Set alarm state
        val alarmInstance = AlarmData(
            id = notif.key.removePrefix("alarm_"),
            label = notif.title
        )
        currentAlarm = alarmInstance
        
        // Priority alert: Wake screen
        wakeScreen(5000)
        
        // Schedule auto-clear after 30 seconds
        handler.postDelayed({
            if (currentAlarm === alarmInstance) {
                currentAlarm = null
                updateState()
            }
        }, 30000)
        
        updateState()
    }
    
    private fun handleAlarmCleared() {
        Log.d(TAG, "Alarm cleared, removing from Dynamic Island")
        currentAlarm = null
        updateState()
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
        val capturedNotif = notif
        collapseRunnable = Runnable { 
            if (currentNotification === capturedNotif) {
                collapseNotification() 
            }
        }
        handler.postDelayed(collapseRunnable!!, timeout)
    }

    private fun collapseNotification() {
        if (isExpanded) return // Don't auto-collapse if user opened the list

        currentNotification = null

        // Always call updateState to check if we should hide in blacklisted app
        updateState()
    }

    private fun expandToList() {
        if (notificationQueue.isEmpty()) return

        isExpanded = true
        currentNotification = null // Clear current notification peek as we are in list mode
        collapseRunnable?.let { handler.removeCallbacks(it) }

        overlayView.expandToList(notificationQueue)
    }

    private fun expandMedia() {
        if (currentMedia == null) return

        isExpanded = true
        currentNotification = null
        collapseRunnable?.let { handler.removeCallbacks(it) }

        updateState()
    }
    
    private fun handleMediaStateUpdate(intent: Intent) {
        if (!showMedia) {
            Log.d(TAG, "Media display disabled, skipping Dynamic Island display")
            return
        }
        
        val isPlaying = intent.getBooleanExtra("isPlaying", false)
        val title = intent.getStringExtra("title")
        val artist = intent.getStringExtra("artist")
        val albumArtBase64 = intent.getStringExtra("albumArtBase64")
        
        val position = intent.getLongExtra("position", 0)
        val duration = intent.getLongExtra("duration", 0)
        val volume = intent.getIntExtra("volume", 0)
        
        Log.d(TAG, "Media update: title='$title', isPlaying=$isPlaying")
        
        // Clear media if title is null, empty, or "Unknown"
        if (title.isNullOrEmpty() || title == "Unknown") {
            Log.d(TAG, "Clearing media state")
            currentMedia = null
        } else {
            // Set media state
            Log.d(TAG, "Setting media state: $title")
            currentMedia = MediaData(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                albumArtBase64 = albumArtBase64,
                position = position,
                duration = duration,
                volume = volume
            )
        }
        
        updateState()
    }
    


    private fun collapseList() {
        Log.d(TAG, "collapseList: isExpanded=$isExpanded -> false")
        isExpanded = false
        currentNotification = null // Clear any active notification state

        // Always call updateState to ensure proper state and potential hiding in blacklist
        updateState()
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
     * Updates isCurrentlyInBlacklistedApp cache
     */
    @SuppressLint("NewApi")
    private fun isInBlacklistedApp(): Boolean {
        if (blacklistedApps.isEmpty()) {
            isCurrentlyInBlacklistedApp = false
            return false
        }
        
        try {
            val currentPackage = getCurrentForegroundApp()
            
            // Update cached state only if we got a valid result
            if (currentPackage != null) {
                lastForegroundApp = currentPackage
                isCurrentlyInBlacklistedApp = blacklistedApps.contains(currentPackage)
            }
            // If getCurrentForegroundApp() returned null, keep using cached state
        } catch (e: Exception) {
            Log.e(TAG, "Error checking blacklisted app: ${e.message}")
        }
        
        return isCurrentlyInBlacklistedApp
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
            // Query events from last 10 seconds to ensure we catch the foreground app
            val usageEvents = usageStatsManager.queryEvents(currentTime - 10000, currentTime)
            
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
        if (!showPhoneCalls) return
        
        val number = intent.getStringExtra("number") ?: "Unknown"
        val contactName = intent.getStringExtra("callerId")
        
        Log.d(TAG, "Incoming call in Dynamic Island: $number ($contactName)")
        
        currentCall = PhoneCallData(
            number = number,
            contactName = contactName,
            isRinging = true
        )
        
        // Priority alert: Wake screen and repeat vibration
        wakeScreen(5000)
        startPriorityVibration()
        
        updateState()
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
                stopVibration()
                updateState()
            }
            "ACTIVE" -> {
                // Call answered - clear Dynamic Island immediately
                currentCall = null
                stopVibration()
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
