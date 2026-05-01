package com.iamadedo.watchapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.iamadedo.watchapp.R
import com.iamadedo.watchapp.BluetoothService
import com.iamadedo.watchapp.MediaControlActivity

class MediaWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_MEDIA_UPDATE = "com.iamadedo.watchapp.widget.ACTION_MEDIA_UPDATE"
        
        // Actions to send TO Service/Activity
        const val ACTION_CMD_PLAY_PAUSE = "com.iamadedo.watchapp.widget.ACTION_CMD_PLAY_PAUSE"
        const val ACTION_CMD_NEXT = "com.iamadedo.watchapp.widget.ACTION_CMD_NEXT"
        const val ACTION_CMD_PREV = "com.iamadedo.watchapp.widget.ACTION_CMD_PREV"
        const val ACTION_CMD_VOL_UP = "com.iamadedo.watchapp.widget.ACTION_CMD_VOL_UP"
        const val ACTION_CMD_VOL_DOWN = "com.iamadedo.watchapp.widget.ACTION_CMD_VOL_DOWN"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_media)
            setupButtons(context, views)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_MEDIA_UPDATE) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: context.getString(R.string.media_no_media_short)
            val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
            val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)

            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, MediaWidget::class.java))
            
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_media)
                views.setTextViewText(R.id.txt_title, title)
                views.setTextViewText(R.id.txt_artist, artist)
                
                val playIcon = if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
                views.setImageViewResource(R.id.btn_play_pause, playIcon)
                
                setupButtons(context, views)
                appWidgetManager.updateAppWidget(id, views)
            }
        }
    }

    private fun setupButtons(context: Context, views: RemoteViews) {
        views.setOnClickPendingIntent(R.id.btn_play_pause, getPendingIntent(context, ACTION_CMD_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.btn_next, getPendingIntent(context, ACTION_CMD_NEXT))
        views.setOnClickPendingIntent(R.id.btn_prev, getPendingIntent(context, ACTION_CMD_PREV))
        views.setOnClickPendingIntent(R.id.btn_vol_up, getPendingIntent(context, ACTION_CMD_VOL_UP))
        views.setOnClickPendingIntent(R.id.btn_vol_down, getPendingIntent(context, ACTION_CMD_VOL_DOWN))
        
        // Open Media Activity on Title/Artist Area Click
        val intent = Intent(context, MediaControlActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.layout_title_click, pendingIntent)
    }

    private fun getPendingIntent(context: Context, action: String): PendingIntent {
        // Send broadcast to BluetoothService (needs to be registered there)
        // Or send to THIS provider and handle in onReceive if we want to delegate
        // BUT better to send explicit broadcast that Service listens to or specialized Receiver.
        // Let's send to BluetoothService via a specialized receiver or just global broadcast.
        // We'll reuse the internalReceiver pattern in BluetoothService.
        val intent = Intent(action)
        intent.setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
