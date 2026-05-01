package com.iamadedo.watchapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.iamadedo.watchapp.NotificationLogActivity
import com.iamadedo.watchapp.R

class NotificationLogWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_NOTIFICATION_LOG_UPDATE = "com.iamadedo.watchapp.widget.ACTION_NOTIFICATION_LOG_UPDATE"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_NOTIFICATION_LOG_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val thisAppWidget = ComponentName(context, NotificationLogWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
            
            // Notify list view data changed
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lvNotificationHistory)
            
            // Also invoke updateAppWidget to refresh empty view or other header details if needed
             for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_notification_log)

        // Set up the intent that starts the NotificationLogRemoteViewsService, which will
        // provide the views for this collection.
        val intent = Intent(context, NotificationLogRemoteViewsService::class.java).apply {
            // Add appWidgetId to the intent extras to be unique
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        
        // Set up the RemoteViews object to use a RemoteViews adapter.
        // This adapter connects to a RemoteViewsService through the specified intent.
        views.setRemoteAdapter(R.id.lvNotificationHistory, intent)

        // The empty view is displayed when the collection has no items.
        // It should be a sibling of the collection view.
        views.setEmptyView(R.id.lvNotificationHistory, R.id.tvEmptyWidget)

        // Pending Intent to launch the full activity on title click
        val appIntent = Intent(context, NotificationLogActivity::class.java)
        val appPendingIntent = PendingIntent.getActivity(
            context,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.tvWidgetTitle, appPendingIntent)

        // Instruct the widget manager to update the widget
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
