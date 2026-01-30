package com.ailife.rtosify.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.ailife.rtosify.BluetoothService
import com.ailife.rtosify.MainActivity
import com.ailife.rtosify.R

class StatusWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_UPDATE = "com.ailife.rtosify.widget.ACTION_WIDGET_UPDATE"
        const val ACTION_TOGGLE_DND = "com.ailife.rtosify.widget.ACTION_TOGGLE_DND"
        const val ACTION_TOGGLE_MIRROR = "com.ailife.rtosify.widget.ACTION_TOGGLE_MIRROR"

        // Extras for Update Intent
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_CONNECTION_STATUS = "extra_connection_status"
        const val EXTRA_BATTERY_LEVEL = "extra_battery_level"
        const val EXTRA_WIFI_SSID = "extra_wifi_ssid"
        const val EXTRA_WIDGET_DND_STATE = "extra_dnd_state" // Renamed to avoid collision
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_WIDGET_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val thisAppWidget = ComponentName(context, StatusWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)

            val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Watch"
            val status = intent.getStringExtra(EXTRA_CONNECTION_STATUS) ?: "Disconnected"
            val battery = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
            val wifiSsid = intent.getStringExtra(EXTRA_WIFI_SSID) ?: "Not Connected"
            // We might need DND state to update the icon color/tint
            val dndEnabled = intent.getBooleanExtra(EXTRA_WIDGET_DND_STATE, false)

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    deviceName,
                    status,
                    battery,
                    wifiSsid,
                    dndEnabled
                )
            }
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        deviceName: String = "Watch",
        status: String = "Disconnected",
        battery: Int = -1,
        wifiSsid: String = "Not Connected",
        dndEnabled: Boolean = false
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_status)

        // Update Texts
        views.setTextViewText(R.id.tvWidgetDeviceName, deviceName)
        views.setTextViewText(R.id.tvWidgetStatus, status)
        
        if (battery != -1) {
            views.setTextViewText(R.id.tvWidgetBattery, "$battery%")
        } else {
            views.setTextViewText(R.id.tvWidgetBattery, "--%")
        }

        views.setTextViewText(R.id.tvWidgetWifiSsid, wifiSsid)

        // Update Colors/Icons based on state
        // Status Color
        if (status.equals("Connected", ignoreCase = true) || status.contains("Connected")) {
            views.setTextColor(R.id.tvWidgetStatus, context.getColor(android.R.color.holo_green_light))
        } else {
            views.setTextColor(R.id.tvWidgetStatus, context.getColor(android.R.color.darker_gray))
        }
        
        // Set white color to all icons for visibility
        views.setInt(R.id.imgDeviceIcon, "setColorFilter", context.getColor(android.R.color.white))
        views.setInt(R.id.imgWidgetBattery, "setColorFilter", context.getColor(android.R.color.white))
        views.setInt(R.id.imgWidgetWifi, "setColorFilter", context.getColor(android.R.color.white))
        views.setInt(R.id.imgWidgetMirror, "setColorFilter", context.getColor(android.R.color.white))
        
        // DND Icon Color
        if (dndEnabled) {
            views.setInt(R.id.imgWidgetDnd, "setColorFilter", context.getColor(android.R.color.holo_blue_light))
        } else {
             views.setInt(R.id.imgWidgetDnd, "setColorFilter", context.getColor(android.R.color.white))
        }


        // Click Handlers
        // Mirror Button -> Send Broadcast to Service to Start Mirroring
        val mirrorIntent = Intent(context, BluetoothService::class.java).apply {
             action = ACTION_TOGGLE_MIRROR
        }
        val mirrorPendingIntent = PendingIntent.getService(
            context,
            0,
            mirrorIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.layoutWidgetMirror, mirrorPendingIntent)

        // DND Button -> Send Broadcast to Service to Toggle DND
        val dndIntent = Intent(context, BluetoothService::class.java).apply {
            action = ACTION_TOGGLE_DND
        }
        val dndPendingIntent = PendingIntent.getService(
            context,
            1,
            dndIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.layoutWidgetDnd, dndPendingIntent)
        
        // Open App on Click Header
        val appIntent = Intent(context, MainActivity::class.java)
        val appPendingIntent = PendingIntent.getActivity(
            context, 
            2, 
            appIntent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.imgDeviceIcon, appPendingIntent)


        // Instruct the widget manager to update the widget
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
