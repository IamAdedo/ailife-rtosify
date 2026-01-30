package com.ailife.rtosifycompanion.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ailife.rtosifycompanion.NotificationLogEntry
import com.ailife.rtosifycompanion.NotificationLogManager
import com.ailife.rtosifycompanion.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationLogRemoteViewsFactory(private val context: Context, intent: Intent) :
    RemoteViewsService.RemoteViewsFactory {

    private var logs: List<NotificationLogEntry> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate() {
        // Initialize data
        NotificationLogManager.init(context)
        logs = NotificationLogManager.getLogs()
    }

    override fun onDataSetChanged() {
        // Refresh data
        // Note: NotificationLogManager.getLogs() might need to be called on a background thread if using DB,
        // but here it's SharedPrefs/memory based so it's okay-ish, though strict mode might complain.
        // For RemoteViewsFactory, onDataSetChanged is called on a worker thread.
        logs = NotificationLogManager.getLogs()
    }

    override fun onDestroy() {
        logs = emptyList()
    }

    override fun getCount(): Int = logs.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position < 0 || position >= logs.size) return RemoteViews(context.packageName, R.layout.item_widget_notification_log)

        val entry = logs[position]
        val log = entry.notification
        val views = RemoteViews(context.packageName, R.layout.item_widget_notification_log)

        views.setTextViewText(R.id.tvAppName, log.appName ?: log.packageName)
        views.setTextViewText(R.id.tvTitle, log.title)
        views.setTextViewText(R.id.tvText, log.text)
        views.setTextViewText(R.id.tvTime, timeFormat.format(Date(entry.timestamp)))

        // Decode icon
        val iconBase64 = log.smallIcon ?: log.largeIcon
        var bitmap: Bitmap? = null
        if (iconBase64 != null) {
            try {
                val decodedString = Base64.decode(iconBase64, Base64.DEFAULT)
                bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (bitmap != null) {
            views.setImageViewBitmap(R.id.imgAppIcon, bitmap)
        } else {
            views.setImageViewResource(R.id.imgAppIcon, android.R.drawable.sym_def_app_icon)
        }

        // FillIntent for click events (if we want to open details)
        // val fillInIntent = Intent().apply { ... }
        // views.setOnClickFillInIntent(R.id.rootLayout, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
