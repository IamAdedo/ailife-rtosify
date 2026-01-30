package com.ailife.rtosify.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.RemoteViews
import com.ailife.rtosify.MainActivity
import com.ailife.rtosify.R

class HealthWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_HEALTH_UPDATE = "com.ailife.rtosify.widget.ACTION_WIDGET_HEALTH_UPDATE"

        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_HEART_RATE = "extra_heart_rate"
        const val EXTRA_SPO2 = "extra_spo2"
        const val EXTRA_TIMESTAMP = "extra_timestamp"
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

        if (intent.action == ACTION_WIDGET_HEALTH_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val thisAppWidget = ComponentName(context, HealthWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)

            val steps = intent.getIntExtra(EXTRA_STEPS, -1)
            val hr = intent.getIntExtra(EXTRA_HEART_RATE, -1)
            val spo2 = intent.getIntExtra(EXTRA_SPO2, -1)
            val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(
                    context,
                    appWidgetManager,
                    appWidgetId,
                    steps,
                    hr,
                    spo2,
                    timestamp
                )
            }
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        steps: Int = -1,
        hr: Int = -1,
        spo2: Int = -1,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_health)

        // Steps
        if (steps >= 0) {
            views.setTextViewText(R.id.tvWidgetSteps, steps.toString())
        } else {
            views.setTextViewText(R.id.tvWidgetSteps, "---")
        }

        // Heart Rate
        if (hr > 0) {
            views.setTextViewText(R.id.tvWidgetHr, hr.toString())
        } else {
             views.setTextViewText(R.id.tvWidgetHr, "--")
        }

        // SpO2
        if (spo2 > 0) {
             views.setTextViewText(R.id.tvWidgetSpo2, spo2.toString())
        } else {
             views.setTextViewText(R.id.tvWidgetSpo2, "--")
        }
        
        // Set white color to icons for visibility
        views.setInt(R.id.imgWidgetSteps, "setColorFilter", context.getColor(android.R.color.white))
        views.setInt(R.id.imgWidgetHeart, "setColorFilter", context.getColor(android.R.color.white))
        views.setInt(R.id.imgWidgetSpo2, "setColorFilter", context.getColor(android.R.color.white))

        // Click Handler -> Open MainActivity
        val appIntent = Intent(context, MainActivity::class.java)
        val appPendingIntent = PendingIntent.getActivity(
            context,
            0,
            appIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetHealthRoot, appPendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
