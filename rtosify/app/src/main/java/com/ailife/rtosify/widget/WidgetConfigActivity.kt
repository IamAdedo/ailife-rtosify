package com.ailife.rtosify.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ailife.rtosify.BluetoothService
import com.ailife.rtosify.DevicePrefManager
import com.ailife.rtosify.R

class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var etUpdateInterval: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_config)

        // Handle Widget Configuration Intent
        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }

        // If launched for configuration, we must return RESULT_CANCELED if they back out
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val resultValue = Intent()
            resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_CANCELED, resultValue)
        }

        etUpdateInterval = findViewById(R.id.etUpdateInterval)
        val btnSave = findViewById<Button>(R.id.btnSaveConfig)

        // Load existing preference
        val prefs = DevicePrefManager(this).getGlobalPrefs()
        val currentInterval = prefs.getInt("widget_update_interval_mins", 30)
        etUpdateInterval.setText(currentInterval.toString())

        btnSave.setOnClickListener {
            saveConfiguration()
        }
    }

    private fun saveConfiguration() {
        val intervalStr = etUpdateInterval.text.toString()
        val interval = intervalStr.toIntOrNull()

        if (interval == null || interval < 15) {
            Toast.makeText(this, "Minimum interval is 15 minutes", Toast.LENGTH_SHORT).show()
            return
        }

        // Save to Prefs
        val prefs = DevicePrefManager(this).getGlobalPrefs()
        prefs.edit().putInt("widget_update_interval_mins", interval).apply() // Consistent key

        Toast.makeText(this, "Configuration Saved", Toast.LENGTH_SHORT).show()

        // If launched as a config activity, return success
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val resultValue = Intent()
            resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, resultValue)
            finish()
        } else {
             finish()
        }
    }
}
