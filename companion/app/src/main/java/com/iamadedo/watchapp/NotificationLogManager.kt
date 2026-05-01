package com.iamadedo.watchapp

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.ArrayList
import java.util.LinkedList

/**
 * Wrapper for notification data plus the timestamp it was received.
 */
data class NotificationLogEntry(
    val notification: NotificationData,
    val timestamp: Long = System.currentTimeMillis()
)

object NotificationLogManager {
    private const val PREFS_NAME = "notification_log_prefs"
    private const val KEY_LOG = "notification_log"
    private const val MAX_LOG_SIZE = 30
    
    private val gson = Gson()
    private val log = LinkedList<NotificationLogEntry>()
    private var isInitialized = false

    /**
     * Initializes the manager and loads stored logs if necessary.
     */
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(log) {
            if (isInitialized) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_LOG, null)
            if (json != null) {
                try {
                    val type = object : TypeToken<LinkedList<NotificationLogEntry>>() {}.type
                    val loadedLog: LinkedList<NotificationLogEntry> = gson.fromJson(json, type)
                    log.clear()
                    log.addAll(loadedLog)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            isInitialized = true
        }
    }

    private fun save(context: Context) {
        synchronized(log) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(log)
            prefs.edit().putString(KEY_LOG, json).apply()
        }
    }

    /**
     * Adds a notification to the log.
     * Keeps only the last [MAX_LOG_SIZE] notifications.
     */
    fun addLog(context: Context, notification: NotificationData) {
        init(context)
        synchronized(log) {
            // Remove existing notification with the same key if it exists, to update its position
            log.removeAll { it.notification.key == notification.key }
            
            log.addFirst(NotificationLogEntry(notification))
            while (log.size > MAX_LOG_SIZE) {
                log.removeLast()
            }
            save(context)
        }
        sendWidgetUpdateBroadcast(context)
    }

    /**
     * Returns a copy of the current notification logs.
     */
    fun getLogs(): List<NotificationLogEntry> {
        return synchronized(log) {
            ArrayList(log)
        }
    }

    /**
     * Clears all notification logs.
     */
    fun clearLogs(context: Context) {
        synchronized(log) {
            log.clear()
            save(context)
        }
        sendWidgetUpdateBroadcast(context)
    }

    private fun sendWidgetUpdateBroadcast(context: Context) {
        val intent = android.content.Intent("com.iamadedo.watchapp.widget.ACTION_NOTIFICATION_LOG_UPDATE")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
}
