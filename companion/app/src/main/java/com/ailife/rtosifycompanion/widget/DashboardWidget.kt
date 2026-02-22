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
        const val ACTION_CMD_CYCLE_RINGER = "com.ailife.rtosifycompanion.widget.ACTION_CMD_CYCLE_RINGER"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PHONE_BATTERY = "phone_battery"
        const val EXTRA_WATCH_BATTERY = "watch_battery"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_RINGER_MODE = "ringer_mode"
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
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, DashboardWidget::class.java)
            )
            val status = intent.getStringExtra(EXTRA_STATUS) ?: context.getString(R.string.status_disconnected)
            val phoneBattery = intent.getIntExtra(EXTRA_PHONE_BATTERY, -1)
            val watchBattery = intent.getIntExtra(EXTRA_WATCH_BATTERY, -1)
            val transport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "BT"
            val ringerMode = intent.getIntExtra(EXTRA_RINGER_MODE, 2)

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, status, phoneBattery, watchBattery, transport, ringerMode)
            }
        } else if (intent.action == ACTION_CMD_CYCLE_RINGER) {
            // Forward back to BluetoothService
            val serviceIntent = Intent(context, com.ailife.rtosifycompanion.BluetoothService::class.java).apply {
                action = ACTION_CMD_CYCLE_RINGER
            }
            context.startForegroundService(serviceIntent)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        status: String? = null,
        phoneBattery: Int = -1,
        watchBattery: Int = -1,
        transport: String = "BT",
        ringerMode: Int = 2
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
        
        // Actions are now tinted in XML via android:tint="@color/widget_text_primary"
        // so we don't need to force white here, allowing adaptive theme colors.
        
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

        // Ringer Mode Cycle
        val ringerIcon = when (ringerMode) {
            1 -> R.drawable.ic_ringer_vibrate
            0 -> R.drawable.ic_ringer_silent
            else -> R.drawable.ic_ringer_normal
        }
        val ringerLabel = when (ringerMode) {
            1 -> context.getString(R.string.ringer_mode_vibrate)
            0 -> context.getString(R.string.ringer_mode_silent)
            else -> context.getString(R.string.ringer_mode_normal)
        }
        views.setImageViewResource(R.id.imgBtnRinger, ringerIcon)
        views.setTextViewText(R.id.txtRinger, ringerLabel)

        val cycleRingerIntent = Intent(context, DashboardWidget::class.java).apply {
            action = ACTION_CMD_CYCLE_RINGER
        }
        val cycleRingerPendingIntent = PendingIntent.getBroadcast(context, 3, cycleRingerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btn_ringer_cycle, cycleRingerPendingIntent)

        // Volume Settings Shortcut
        val settingsIntent = Intent(context, com.ailife.rtosifycompanion.PhoneSettingsActivity::class.java)
        val settingsPendingIntent = PendingIntent.getActivity(context, 4, settingsIntent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btn_volume, settingsPendingIntent)

        // Instruct the widget manager to update the widget
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
