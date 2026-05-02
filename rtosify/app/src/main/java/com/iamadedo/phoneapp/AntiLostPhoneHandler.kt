package com.iamadedo.phoneapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

/**
 * Anti-Lost Phone Handler — Phone Side
 *
 * Complements AntiLostManager on the watch. The phone receives:
 *  - ANTI_LOST_RSSI_UPDATE  : periodic RSSI readings from the watch
 *  - ANTI_LOST_PHONE_ALERT  : watch has confirmed phone is out of range
 *  - ANTI_LOST_DISMISSED    : user dismissed alert on watch
 *
 * The phone also independently evaluates RSSI updates so it can alert
 * the user if THEY have walked away from their phone and left the watch
 * somewhere (e.g., left phone on desk and wore watch to a meeting).
 *
 * Alert on phone:
 *  - Full-volume notification with custom sound
 *  - Vibration pattern
 *  - Persistent alert until RSSI recovers or user dismisses
 *
 * Additionally the phone sends ANTI_LOST_WATCH_ALERT back to the watch when
 * IT detects the watch going out of range (phone perspective), which causes
 * the watch to alert the person wearing it that their phone is left behind.
 */
class AntiLostPhoneHandler(private val context: Context) {

    companion object {
        private const val PREFS             = "anti_lost_phone_prefs"
        private const val KEY_ENABLED       = "enabled"
        private const val KEY_THRESHOLD     = "rssi_threshold"
        private const val DEFAULT_THRESHOLD = -75
        private const val CONFIRM_READINGS  = 3
        private const val ALERT_REPEAT_MS   = 8_000L
        private const val ALERT_COOLDOWN_MS = 30_000L
        private const val CHANNEL_ID        = "anti_lost_phone"
        private const val NOTIF_ID          = 9002
        const val ACTION_DISMISS            = "com.iamadedo.phoneapp.ANTI_LOST_DISMISS"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler  = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val nm       = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var enabled       = prefs.getBoolean(KEY_ENABLED, false)
    private var rssiThreshold = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
    private var alertActive   = false
    private var weakCount     = 0
    private var lastAlertMs   = 0L

    init { createChannel() }

    // ── Configuration ─────────────────────────────────────────────────────────

    fun configure(enabled: Boolean, rssiThreshold: Int = DEFAULT_THRESHOLD) {
        this.enabled       = enabled
        this.rssiThreshold = rssiThreshold
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_THRESHOLD, rssiThreshold)
            .apply()
        if (!enabled) clearAlert()
    }

    // ── Called by BluetoothService when ANTI_LOST_RSSI_UPDATE arrives ─────────

    fun onRssiUpdate(rssi: Int): ProtocolMessage? {
        if (!enabled) return null
        return when {
            rssi < rssiThreshold -> {
                weakCount++
                if (weakCount >= CONFIRM_READINGS) {
                    val now = System.currentTimeMillis()
                    if (!alertActive && now - lastAlertMs > ALERT_COOLDOWN_MS) {
                        triggerPhoneAlert()
                        // Tell watch to alert its wearer too
                        return ProtocolHelper.createAntiLostWatchAlert(rssi)
                    }
                }
                null
            }
            else -> {
                weakCount = 0
                if (alertActive) clearAlert()
                null
            }
        }
    }

    // ── Called by BluetoothService when ANTI_LOST_PHONE_ALERT arrives ─────────
    // (watch confirmed phone is out of range from its perspective)

    fun onWatchConfirmedPhoneAlert() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (!alertActive && now - lastAlertMs > ALERT_COOLDOWN_MS) {
            triggerPhoneAlert()
        }
    }

    // ── Called when user dismisses either end ────────────────────────────────

    fun onDismissed() {
        clearAlert()
        weakCount = 0
    }

    // ── Alert ─────────────────────────────────────────────────────────────────

    private fun triggerPhoneAlert() {
        alertActive = true
        lastAlertMs = System.currentTimeMillis()
        vibrate()
        showNotification()
        handler.postDelayed(repeatRunnable, ALERT_REPEAT_MS)
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!alertActive) return
            vibrate()
            handler.postDelayed(this, ALERT_REPEAT_MS)
        }
    }

    private fun vibrate() {
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 500, 200, 500, 200, 1000), -1
            )
        )
    }

    private fun clearAlert() {
        alertActive = false
        handler.removeCallbacks(repeatRunnable)
        vibrator.cancel()
        nm.cancel(NOTIF_ID)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun showNotification() {
        val dismissPi = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.anti_lost_phone_alert_title))
            .setContentText(context.getString(R.string.anti_lost_phone_alert_body))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .addAction(0, context.getString(R.string.anti_lost_dismiss), dismissPi)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun createChannel() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Anti-Lost — Phone Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when your watch/wrist leaves your phone behind"
            setSound(alarmUri, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build())
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }
}
