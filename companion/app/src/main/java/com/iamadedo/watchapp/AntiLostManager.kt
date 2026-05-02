package com.iamadedo.watchapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

/**
 * Anti-Lost Manager — Watch Side
 *
 * Strategy: the watch is always on the wrist, so it is the SENSOR side.
 * It monitors the Bluetooth RSSI of the connected phone by reading
 * [currentRssi] which BluetoothService feeds from its existing BleRssiMonitor.
 *
 * Alert conditions:
 *  1. RSSI drops below [rssiThreshold] for [CONFIRM_READINGS] consecutive readings
 *     → assume phone is being left behind → alert on watch AND notify phone
 *  2. Connection drops entirely (BluetoothService calls onConnectionLost)
 *     → immediate alert on watch (can't notify phone — it's gone)
 *
 * Alert UX on watch:
 *  - Repeating strong vibration pattern every [ALERT_REPEAT_MS]
 *  - Persistent heads-up notification with "Dismissed" action
 *  - Screen wakes (via notification importance HIGH)
 *
 * Recovery: alert clears automatically when RSSI recovers above threshold
 * or user taps "Dismiss".
 *
 * The threshold and enable state are configurable from the phone app settings
 * via ANTI_LOST_ENABLE message.
 */
class AntiLostManager(
    private val context: Context,
    private val onSendMessage: (ProtocolMessage) -> Unit
) {
    companion object {
        private const val PREFS             = "anti_lost_prefs"
        private const val KEY_ENABLED       = "enabled"
        private const val KEY_THRESHOLD     = "rssi_threshold"
        private const val DEFAULT_THRESHOLD = -75       // dBm — typical safe range boundary
        private const val POLL_INTERVAL_MS  = 3_000L    // check every 3 s
        private const val CONFIRM_READINGS  = 3         // 3 consecutive weak readings → alert
        private const val ALERT_REPEAT_MS   = 5_000L    // repeat vibration every 5 s while alerting
        private const val ALERT_COOLDOWN_MS = 30_000L   // minimum gap between repeated alerts
        private const val CHANNEL_ID        = "anti_lost"
        private const val NOTIF_ID          = 9001
        const val ACTION_DISMISS            = "com.iamadedo.watchapp.ANTI_LOST_DISMISS"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler  = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val nm       = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // State
    private var enabled        = prefs.getBoolean(KEY_ENABLED, false)
    private var rssiThreshold  = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
    private var alertActive    = false
    private var weakCount      = 0
    private var lastAlertMs    = 0L
    private var lastKnownRssi  = 0    // fed by BluetoothService

    init { createChannel() }

    // ── Public API ────────────────────────────────────────────────────────────

    fun configure(enabled: Boolean, rssiThreshold: Int = DEFAULT_THRESHOLD) {
        this.enabled       = enabled
        this.rssiThreshold = rssiThreshold
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_THRESHOLD, rssiThreshold)
            .apply()
        if (enabled) start() else stop()
    }

    fun start() {
        if (!enabled) return
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(alertRepeatRunnable)
        clearAlert()
    }

    /** Called by BluetoothService whenever a new RSSI value is read */
    fun onRssiUpdate(rssi: Int) {
        lastKnownRssi = rssi
        // Send periodic RSSI update to phone so IT can also evaluate proximity
        if (enabled) {
            onSendMessage(ProtocolHelper.createAntiLostRssiUpdate(rssi))
        }
    }

    /** Called by BluetoothService when BT connection is fully lost */
    fun onConnectionLost() {
        if (!enabled) return
        // Phone completely out of range — immediate local alert only
        triggerAlert(rssi = -100, notifyPhone = false)
    }

    /** Called when user taps Dismiss in the notification */
    fun dismiss() {
        clearAlert()
        onSendMessage(ProtocolHelper.createAntiLostDismissed())
        weakCount = 0
    }

    // ── Poll loop ─────────────────────────────────────────────────────────────

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!enabled) return
            val rssi = lastKnownRssi
            when {
                rssi == 0 -> {
                    // No reading yet — skip
                    weakCount = 0
                }
                rssi < rssiThreshold -> {
                    weakCount++
                    if (weakCount >= CONFIRM_READINGS) {
                        val now = System.currentTimeMillis()
                        if (!alertActive && now - lastAlertMs > ALERT_COOLDOWN_MS) {
                            triggerAlert(rssi, notifyPhone = true)
                        }
                    }
                }
                else -> {
                    weakCount = 0
                    if (alertActive) clearAlert()   // phone came back in range
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ── Alert ─────────────────────────────────────────────────────────────────

    private fun triggerAlert(rssi: Int, notifyPhone: Boolean) {
        alertActive = true
        lastAlertMs = System.currentTimeMillis()

        // Vibrate
        vibrate()
        // Show persistent notification
        showNotification()
        // Repeat vibration
        handler.postDelayed(alertRepeatRunnable, ALERT_REPEAT_MS)

        if (notifyPhone) {
            onSendMessage(ProtocolHelper.createAntiLostPhoneAlert(rssi))
        }
    }

    private val alertRepeatRunnable = object : Runnable {
        override fun run() {
            if (!alertActive) return
            vibrate()
            handler.postDelayed(this, ALERT_REPEAT_MS)
        }
    }

    private fun vibrate() {
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 400, 200, 400, 200, 800), -1
            )
        )
    }

    private fun clearAlert() {
        alertActive = false
        handler.removeCallbacks(alertRepeatRunnable)
        vibrator.cancel()
        nm.cancel(NOTIF_ID)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun showNotification() {
        val dismissIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.anti_lost_alert_title))
            .setContentText(context.getString(R.string.anti_lost_alert_body))
            .setSmallIcon(R.drawable.ic_smartwatch)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(0, context.getString(R.string.anti_lost_dismiss), dismissIntent)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Anti-Lost Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when your phone is left behind"
            enableVibration(false)   // we handle vibration ourselves
        }
        nm.createNotificationChannel(ch)
    }
}
