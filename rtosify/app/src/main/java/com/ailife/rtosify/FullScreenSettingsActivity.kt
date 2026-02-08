package com.ailife.rtosify

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class FullScreenSettingsActivity : AppCompatActivity() {

    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private lateinit var switchStacking: SwitchMaterial
    private lateinit var switchCloseOnScreenOff: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_settings)
        setupToolbar()

        devicePrefManager = DevicePrefManager(this)

        initViews()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_full_screen_settings)
    }

    private fun initViews() {
        switchStacking = findViewById(R.id.switchStacking)
        switchCloseOnScreenOff = findViewById(R.id.switchCloseOnScreenOff)
    }

    private fun loadSettings() {
        switchStacking.isChecked = activePrefs.getBoolean("full_screen_stacking_enabled", false)
        switchCloseOnScreenOff.isChecked = activePrefs.getBoolean("full_screen_close_on_screen_off", false)
    }

    private fun setupListeners() {
        switchStacking.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("full_screen_stacking_enabled", isChecked).apply()
            sendSettingsUpdate()
        }

        switchCloseOnScreenOff.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("full_screen_close_on_screen_off", isChecked).apply()
            sendSettingsUpdate()
        }
    }

    private fun sendSettingsUpdate() {
        val intent = Intent("com.ailife.rtosify.ACTION_UPDATE_SETTINGS")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
