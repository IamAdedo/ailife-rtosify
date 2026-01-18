package com.ailife.rtosifycompanion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * BroadcastReceiver that handles alarm triggers
 */
class AlarmReceiver : BroadcastReceiver() {
    
    companion object {
        const val ACTION_ALARM_TRIGGER = "com.ailife.rtosifycompanion.ALARM_TRIGGER"
        const val ACTION_DISMISS_ALARM = "com.ailife.rtosifycompanion.DISMISS_ALARM"
        const val ACTION_SNOOZE_ALARM = "com.ailife.rtosifycompanion.SNOOZE_ALARM"
        
        private var ringtone: Ringtone? = null
        private var vibrator: Vibrator? = null
        
        fun stopAlarmSound() {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                    Log.d(TAG, "Alarm sound stopped")
                }
            }
            ringtone = null
        }
        
        fun dismissAlarm(context: Context, alarmId: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            Log.d(TAG, "Alarm dismissed")
        }
        
        fun snoozeAlarm(context: Context, alarmId: String, label: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val snoozeIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_ALARM_TRIGGER
                putExtra("alarm_id", alarmId)
                putExtra("alarm_label", label)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId.hashCode() + 10000,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val snoozeTime = System.currentTimeMillis() + (10 * 60 * 1000)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    snoozeTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    snoozeTime,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "Alarm snoozed for 10 minutes")
        }
        const val NOTIFICATION_ID = 9000
        const val CHANNEL_ID = "alarm_channel"
        private const val TAG = "AlarmReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM_TRIGGER -> handleAlarmTrigger(context, intent)
            ACTION_DISMISS_ALARM -> handleDismiss(context, intent)
            ACTION_SNOOZE_ALARM -> handleSnooze(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> handleBootCompleted(context)
        }
    }
    
    private fun handleAlarmTrigger(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra("alarm_id") ?: return
        val label = intent.getStringExtra("alarm_label") ?: context.getString(R.string.alarm_notification_title)
        
        Log.d(TAG, "Alarm triggered: $alarmId, label: $label")
        
        // Get alarm details for full-screen activity
        val alarmManager = WatchAlarmManager(context)
        val alarm = alarmManager.getAllAlarms().find { it.id == alarmId }
        
        // Play alarm sound
        playAlarmSound(context)
        
        // Vibrate
        vibrateDevice(context)
        
        // Launch full-screen activity only if notification style is NOT dynamic_island
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val style = prefs.getString("notification_style", "android")
        
        if (style != "dynamic_island") {
            val activityIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AlarmRingingActivity.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmRingingActivity.EXTRA_ALARM_LABEL, label)
                putExtra(AlarmRingingActivity.EXTRA_ALARM_HOUR, alarm?.hour ?: 0)
                putExtra(AlarmRingingActivity.EXTRA_ALARM_MINUTE, alarm?.minute ?: 0)
            }
            context.startActivity(activityIntent)
            
            // Also show notification as backup
            showAlarmNotification(context, alarmId, label)
        } else {
            Log.d(TAG, "Dynamic Island style enabled, routing directly")
            val directIntent = Intent("com.ailife.rtosifycompanion.ALARM_TRIGGERED")
            directIntent.setPackage(context.packageName)
            directIntent.putExtra("alarm_id", alarmId)
            directIntent.putExtra("label", label)
            directIntent.putExtra("hour", alarm?.hour ?: 0)
            directIntent.putExtra("minute", alarm?.minute ?: 0)
            context.sendBroadcast(directIntent)
        }
        
        // Reschedule if it's a repeating alarm
        rescheduleIfNeeded(context, alarmId)
    }
    
    private fun handleDismiss(context: Context, intent: Intent) {
        stopAlarmSound()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        
        // Clear Dynamic Island
        val clearIntent = Intent("com.ailife.rtosifycompanion.ALARM_CLEARED")
        clearIntent.setPackage(context.packageName)
        context.sendBroadcast(clearIntent)
        
        Log.d(TAG, "Alarm dismissed")
    }
    
    private fun handleSnooze(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra("alarm_id") ?: return
        val label = intent.getStringExtra("alarm_label") ?: context.getString(R.string.alarm_notification_title)
        
        stopAlarmSound()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        
        // Clear Dynamic Island
        val clearIntent = Intent("com.ailife.rtosifycompanion.ALARM_CLEARED")
        clearIntent.setPackage(context.packageName)
        context.sendBroadcast(clearIntent)
        
        // Schedule snooze for 10 minutes
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val snoozeIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TRIGGER
            putExtra("alarm_id", alarmId)
            putExtra("alarm_label", label)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId.hashCode() + 10000, // Different request code for snooze
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val snoozeTime = System.currentTimeMillis() + (10 * 60 * 1000) // 10 minutes
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                snoozeTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                android.app.AlarmManager.RTC_WAKEUP,
                snoozeTime,
                pendingIntent
            )
        }
        
        Log.d(TAG, "Alarm snoozed for 10 minutes")
    }
    
    private fun handleBootCompleted(context: Context) {
        // Reschedule all alarms after device reboot
        val alarmManager = WatchAlarmManager(context)
        alarmManager.rescheduleAllAlarms()
        Log.d(TAG, "Rescheduled alarms after boot")
    }
    
    private fun playAlarmSound(context: Context) {
        try {
            val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            
            ringtone = RingtoneManager.getRingtone(context, alarmUri)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            
            // Set to alarm stream
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            
            ringtone?.play()
            Log.d(TAG, "Alarm sound started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm sound: ${e.message}")
        }
    }
    
    private fun stopAlarmSound() {
        ringtone?.let {
            if (it.isPlaying) {
                it.stop()
                Log.d(TAG, "Alarm sound stopped")
            }
        }
        ringtone = null
        
        // Stop vibration
        vibrator?.cancel()
        vibrator = null
        Log.d(TAG, "Vibration stopped")
    }
    
    private fun vibrateDevice(context: Context) {
        try {
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = longArrayOf(0, 500, 500, 500, 500, 500)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val pattern = longArrayOf(0, 500, 500, 500, 500, 500)
                vibrator?.vibrate(pattern, 0)
            }
            
            Log.d(TAG, "Vibration started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate: ${e.message}")
        }
    }
    
    private fun showAlarmNotification(context: Context, alarmId: String, label: String) {
        createNotificationChannel(context)
        
        val dismissIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_DISMISS_ALARM
            putExtra("alarm_id", alarmId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId.hashCode() + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val snoozeIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_SNOOZE_ALARM
            putExtra("alarm_id", alarmId)
            putExtra("alarm_label", label)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId.hashCode() + 2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(label.ifEmpty { context.getString(R.string.alarm_notification_ringing) })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(dismissPendingIntent, true)
            .addAction(android.R.drawable.ic_delete, context.getString(R.string.alarm_dismiss), dismissPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, context.getString(R.string.alarm_snooze), snoozePendingIntent)
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        Log.d(TAG, "Alarm notification shown")
    }
    
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.alarm_channel_desc)
                setSound(null, null) // We handle sound ourselves
                enableVibration(false) // We handle vibration ourselves
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun rescheduleIfNeeded(context: Context, alarmId: String) {
        val alarmManager = WatchAlarmManager(context)
        val alarm = alarmManager.getAllAlarms().find { it.id == alarmId }
        
        if (alarm != null && alarm.enabled && alarm.daysOfWeek.isNotEmpty()) {
            // It's a repeating alarm, reschedule it
            alarmManager.scheduleAlarm(alarm)
            Log.d(TAG, "Rescheduled repeating alarm: $alarmId")
        } else if (alarm != null && alarm.daysOfWeek.isEmpty()) {
            // It's a one-time alarm, disable it
            val updatedAlarm = alarm.copy(enabled = false)
            alarmManager.updateAlarm(updatedAlarm)
            Log.d(TAG, "Disabled one-time alarm: $alarmId")
        }
    }
}
