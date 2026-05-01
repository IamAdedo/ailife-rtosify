package com.iamadedo.watchapp

import android.content.Context
import android.content.SharedPreferences
import android.app.AlarmManager as AndroidAlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar
import java.util.UUID

/**
 * Alarm management class for the companion (watch) app.
 * Handles storing, scheduling, and managing alarms.
 */
class WatchAlarmManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("alarms_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AndroidAlarmManager
    
    companion object {
        private const val TAG = "WatchAlarmManager"
        private const val ALARM_LIST_KEY = "alarm_list"
    }
    
    /**
     * Get all alarms, sorted by time
     */
    fun getAllAlarms(): List<AlarmData> {
        val json = prefs.getString(ALARM_LIST_KEY, "[]") ?: "[]"
        val type = object : TypeToken<List<AlarmData>>() {}.type
        val alarms: List<AlarmData> = gson.fromJson(json, type)
        return alarms.sortedWith(compareBy({ it.hour }, { it.minute }))
    }
    
    /**
     * Add a new alarm
     */
    fun addAlarm(alarm: AlarmData): AlarmData {
        val alarms = getAllAlarms().toMutableList()
        
        // Generate ID if not provided
        val newAlarm = if (alarm.id.isEmpty()) {
            alarm.copy(id = UUID.randomUUID().toString())
        } else {
            alarm
        }
        
        alarms.add(newAlarm)
        saveAlarms(alarms)
        
        if (newAlarm.enabled) {
            scheduleAlarm(newAlarm)
        }
        
        Log.d(TAG, "Added alarm: ${newAlarm.hour}:${newAlarm.minute}, ID: ${newAlarm.id}")
        return newAlarm
    }
    
    /**
     * Update an existing alarm
     */
    fun updateAlarm(alarm: AlarmData) {
        val alarms = getAllAlarms().toMutableList()
        val index = alarms.indexOfFirst { it.id == alarm.id }
        
        if (index != -1) {
            alarms[index] = alarm
            saveAlarms(alarms)
            
            // Cancel old alarm and reschedule if enabled
            cancelAlarm(alarm.id)
            if (alarm.enabled) {
                scheduleAlarm(alarm)
            }
            
            Log.d(TAG, "Updated alarm: ${alarm.hour}:${alarm.minute}, ID: ${alarm.id}, enabled: ${alarm.enabled}")
        } else {
            Log.w(TAG, "Alarm not found for update: ${alarm.id}")
        }
    }
    
    /**
     * Delete an alarm
     */
    fun deleteAlarm(alarmId: String) {
        val alarms = getAllAlarms().toMutableList()
        val removed = alarms.removeIf { it.id == alarmId }
        
        if (removed) {
            saveAlarms(alarms)
            cancelAlarm(alarmId)
            Log.d(TAG, "Deleted alarm: $alarmId")
        } else {
            Log.w(TAG, "Alarm not found for deletion: $alarmId")
        }
    }
    
    /**
     * Schedule an alarm using Android AlarmManager
     */
    fun scheduleAlarm(alarm: AlarmData) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM_TRIGGER
            putExtra("alarm_id", alarm.id)
            putExtra("alarm_label", alarm.label)
        }
        
        val requestCode = alarm.id.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerTime = calculateNextTriggerTime(alarm)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AndroidAlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AndroidAlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            
            val calendar = Calendar.getInstance().apply { timeInMillis = triggerTime }
            Log.d(TAG, "Scheduled alarm ${alarm.id} for ${calendar.time}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to schedule alarm: ${e.message}")
        }
    }
    
    /**
     * Cancel a scheduled alarm
     */
    fun cancelAlarm(alarmId: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM_TRIGGER
        }
        
        val requestCode = alarmId.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Cancelled alarm: $alarmId")
        }
    }
    
    /**
     * Calculate the next trigger time for an alarm
     */
    private fun calculateNextTriggerTime(alarm: AlarmData): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        val now = System.currentTimeMillis()
        
        // If no days specified, it's a one-time alarm
        if (alarm.daysOfWeek.isEmpty()) {
            // If time has passed today, schedule for tomorrow
            if (calendar.timeInMillis <= now) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            return calendar.timeInMillis
        }
        
        // Find next occurrence based on days of week
        // daysOfWeek: 1=Monday, 7=Sunday
        // Calendar.DAY_OF_WEEK: 1=Sunday, 2=Monday, etc.
        
        var daysToAdd = 0
        val maxDays = 7
        
        while (daysToAdd < maxDays) {
            val testCalendar = calendar.clone() as Calendar
            testCalendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
            
            // Convert Calendar day (1=Sunday) to our format (1=Monday)
            val calendarDay = testCalendar.get(Calendar.DAY_OF_WEEK)
            val ourDay = if (calendarDay == 1) 7 else calendarDay - 1
            
            // Check if this day is in the daysOfWeek list and time hasn't passed
            if (alarm.daysOfWeek.contains(ourDay) && testCalendar.timeInMillis > now) {
                return testCalendar.timeInMillis
            }
            
            daysToAdd++
        }
        
        // Fallback: schedule for next week
        calendar.add(Calendar.WEEK_OF_YEAR, 1)
        return calendar.timeInMillis
    }
    
    /**
     * Save alarms to SharedPreferences
     */
    private fun saveAlarms(alarms: List<AlarmData>) {
        val json = gson.toJson(alarms)
        prefs.edit().putString(ALARM_LIST_KEY, json).apply()
    }
    
    /**
     * Reschedule all enabled alarms (e.g., after device reboot)
     */
    fun rescheduleAllAlarms() {
        val alarms = getAllAlarms().filter { it.enabled }
        alarms.forEach { scheduleAlarm(it) }
        Log.d(TAG, "Rescheduled ${alarms.size} alarms")
    }
}
