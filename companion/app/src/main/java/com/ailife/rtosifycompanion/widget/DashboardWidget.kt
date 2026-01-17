package com.ailife.rtosifycompanion.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ailife.rtosifycompanion.R
import com.ailife.rtosifycompanion.MirrorSettingsActivity
import com.ailife.rtosifycompanion.CameraRemoteActivity
import com.ailife.rtosifycompanion.DialerActivity

class DashboardWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_DASHBOARD_UPDATE = "com.ailife.rtosifycompanion.widget.ACTION_DASHBOARD_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PHONE_BATTERY = "phone_battery"
        const val EXTRA_WATCH_BATTERY = "watch_battery"
        const val EXTRA_TRANSPORT = "transport"
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
        if (intent.action == ACTION_DASHBOARD_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DashboardWidget::class.java)
            )
            val status = intent.getStringExtra(EXTRA_STATUS) ?: context.getString(R.string.status_disconnected)
            val phoneBattery = intent.getIntExtra(EXTRA_PHONE_BATTERY, -1)
            val watchBattery = intent.getIntExtra(EXTRA_WATCH_BATTERY, -1)
            val transport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "BT"

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, status, phoneBattery, watchBattery, transport)
            }
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        status: String? = null,
        phoneBattery: Int = -1,
        watchBattery: Int = -1,
        transport: String = "BT"
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_dashboard)

        if (status != null) {
            views.setTextViewText(R.id.txt_status, status)
            
            // Update connection icon based on transport
            val connectionIcon = when {
                transport.contains("WiFi", ignoreCase = true) || transport.contains("LAN", ignoreCase = true) -> R.drawable.ic_wifi
                transport.contains("Internet", ignoreCase = true) -> R.drawable.ic_globe
                else -> R.drawable.ic_bluetooth
            }
            views.setImageViewResource(R.id.img_connection_icon, connectionIcon)
        }

        if (phoneBattery != -1) {
            views.setTextViewText(R.id.txt_phone_battery, "$phoneBattery%")
        }

        if (watchBattery != -1) {
             views.setTextViewText(R.id.txt_watch_battery, "$watchBattery%")
        }

        views.setTextViewText(R.id.txt_transport, transport)
        
        // Update transport icon
        val transportIcon = when {
            transport.contains("WiFi", ignoreCase = true) || transport.contains("LAN", ignoreCase = true) -> R.drawable.ic_wifi
            transport.contains("Internet", ignoreCase = true) -> R.drawable.ic_globe
            else -> R.drawable.ic_bluetooth
        }
        views.setImageViewResource(R.id.img_transport_icon, transportIcon)
        
        // Set white color to all icons for visibility
        views.setInt(R.id.img_connection_icon, "setColorFilter", android.graphics.Color.WHITE)
        views.setInt(R.id.img_transport_icon, "setColorFilter", android.graphics.Color.WHITE)
        views.setInt(R.id.imgBtnMirror, "setColorFilter", android.graphics.Color.WHITE)
        views.setInt(R.id.imgBtnCamera, "setColorFilter", android.graphics.Color.WHITE)
        views.setInt(R.id.imgBtnDialer, "setColorFilter", android.graphics.Color.WHITE)
        
        // Setup action button clicks
        val mirrorIntent = Intent(context, MirrorSettingsActivity::class.java)
        val mirrorPendingIntent = PendingIntent.getActivity(context, 0, mirrorIntent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btn_mirror, mirrorPendingIntent)
        
        val cameraIntent = Intent(context, CameraRemoteActivity::class.java)
        val cameraPendingIntent = PendingIntent.getActivity(context, 1, cameraIntent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btn_camera, cameraPendingIntent)
        
        val dialerIntent = Intent(context, DialerActivity::class.java)
        val dialerPendingIntent = PendingIntent.getActivity(context, 2, dialerIntent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btn_dialer, dialerPendingIntent)

        // Instruct the widget manager to update the widget
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
