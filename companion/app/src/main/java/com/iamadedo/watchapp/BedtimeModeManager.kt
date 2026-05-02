package com.iamadedo.watchapp

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.util.Calendar

/**
 * Bedtime Mode Manager — Wear OS / Pixel Watch platform feature.
 *
 * Automatically:
 *  - Enables Do Not Disturb when bedtime starts
 *  - Dims the watch screen to minimum brightness
 *  - Starts sleep tracking (SleepTracker + SnoringDetector)
 *  - Sends AUTO_BEDTIME_START to phone (phone mirrors DND state)
 *  - Reverses all of the above at wake time
 *  - Sends AUTO_BEDTIME_END to phone
 *
 * Can be triggered:
 *  1. Automatically — scheduled alarm at user-configured bedtime
 *  2. On demand — via BEDTIME_MODE_CONTROL protocol message
 *  3. By sleep onset detection from SleepTracker callback
 */
class BedtimeModeManager(
    private val context: Context,
    private val onSendMessage: (ProtocolMessage) -> Unit
) {
    companion object {
        private const val PREFS              = "bedtime_prefs"
        private const val KEY_BEDTIME_HOUR   = "bedtime_hour"
        private const val KEY_BEDTIME_MIN    = "bedtime_minute"
        private const val KEY_WAKE_HOUR      = "wake_hour"
        private const val KEY_WAKE_MIN       = "wake_minute"
        private const val KEY_AUTO_ENABLED   = "auto_bedtime_enabled"
        const val ACTION_BEDTIME_START       = "com.iamadedo.watchapp.BEDTIME_START"
        const val ACTION_BEDTIME_END         = "com.iamadedo.watchapp.BEDTIME_END"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private var bedtimeActive = false

    // ── Schedule management ───────────────────────────────────────────────────

    fun configure(
        bedtimeHour: Int, bedtimeMinute: Int,
        wakeHour: Int, wakeMinute: Int,
        autoEnabled: Boolean = true
    ) {
        prefs.edit()
            .putInt(KEY_BEDTIME_HOUR, bedtimeHour)
            .putInt(KEY_BEDTIME_MIN, bedtimeMinute)
            .putInt(KEY_WAKE_HOUR, wakeHour)
            .putInt(KEY_WAKE_MIN, wakeMinute)
            .putBoolean(KEY_AUTO_ENABLED, autoEnabled)
            .apply()
        if (autoEnabled) scheduleAlarms() else cancelAlarms()
    }

    fun scheduleAlarms() {
        scheduleAlarm(
            prefs.getInt(KEY_BEDTIME_HOUR, 22), prefs.getInt(KEY_BEDTIME_MIN, 30),
            ACTION_BEDTIME_START, 1001
        )
        scheduleAlarm(
            prefs.getInt(KEY_WAKE_HOUR, 7), prefs.getInt(KEY_WAKE_MIN, 0),
            ACTION_BEDTIME_END, 1002
        )
    }

    fun cancelAlarms() {
        cancelAlarm(ACTION_BEDTIME_START, 1001)
        cancelAlarm(ACTION_BEDTIME_END, 1002)
    }

    // ── Mode activation ───────────────────────────────────────────────────────

    fun enableBedtimeMode() {
        if (bedtimeActive) return
        bedtimeActive = true

        // Enable DND
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        }

        // Dim screen
        try {
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, 5)
        } catch (e: Exception) { /* WRITE_SETTINGS not granted */ }

        prefs.edit().putBoolean("bedtime_active", true).apply()
        onSendMessage(ProtocolHelper.createAutoBedtimeStart())
    }

    fun disableBedtimeMode() {
        if (!bedtimeActive) return
        bedtimeActive = false

        // Restore DND
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }

        // Restore brightness
        try {
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        } catch (e: Exception) { /* WRITE_SETTINGS not granted */ }

        prefs.edit().putBoolean("bedtime_active", false).apply()
        onSendMessage(ProtocolHelper.createAutoBedtimeEnd())
    }

    fun isActive() = bedtimeActive

    // ── Alarm helpers ─────────────────────────────────────────────────────────

    private fun scheduleAlarm(hour: Int, minute: Int, action: String, requestCode: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = pendingIntent(action, requestCode)
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis,
            AlarmManager.INTERVAL_DAY, pi)
    }

    private fun cancelAlarm(action: String, requestCode: Int) {
        alarmManager.cancel(pendingIntent(action, requestCode))
    }

    private fun pendingIntent(action: String, requestCode: Int) =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** Receives scheduled bedtime/wake alarms. */
class BedtimeModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, BluetoothService::class.java).apply {
            action = intent.action
        }
        context.startService(serviceIntent)
    }
}
