package com.ailife.rtosify

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var switchWakeScreen: SwitchMaterial
    private lateinit var switchVibrate: SwitchMaterial
    private lateinit var switchSkipScreenOn: SwitchMaterial
    private lateinit var switchNotifyDisconnect: SwitchMaterial
    
    private lateinit var cardManageApps: View
    private lateinit var cardDynamicIsland: View
    private lateinit var layoutPermissionWarning: View
    private lateinit var btnGrantPermission: View
    private lateinit var btnManageDynamicIsland: View

    private lateinit var spinnerNotifStyle: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)
        setupToolbar()

        devicePrefManager = DevicePrefManager(this)
        
        initViews()
        setupMasterSwitch()
        setupGeneralSettings()
        setupBehaviorSwitches()
        setupDynamicIslandSettings()
        setupCardClickListeners()
    }

    override fun onResume() {
        super.onResume()
        checkPermission()
        updateUiState()
    }

    private fun initViews() {
        switchEnable = findViewById(R.id.switchEnableMirroring)
        switchWakeScreen = findViewById(R.id.switchWakeScreen)
        switchVibrate = findViewById(R.id.switchVibrate)
        switchSkipScreenOn = findViewById(R.id.switchSkipScreenOn)
        switchNotifyDisconnect = findViewById(R.id.switchNotifyDisconnect)
        
        cardManageApps = findViewById(R.id.cardManageApps)
        cardDynamicIsland = findViewById(R.id.cardDynamicIsland)
        layoutPermissionWarning = findViewById(R.id.layoutPermissionWarning)
        btnGrantPermission = findViewById(R.id.btnOpenSettings)
        btnManageDynamicIsland = findViewById(R.id.btnManageDynamicIsland)

        spinnerNotifStyle = findViewById(R.id.spinnerNotificationStyle)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.notif_settings_title)
    }

    private fun checkPermission() {
        val enabledListeners = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isEnabled = enabledListeners != null && enabledListeners.contains(packageName)
        
        if (isEnabled) {
            layoutPermissionWarning.visibility = View.GONE
        } else {
            layoutPermissionWarning.visibility = View.VISIBLE
            btnGrantPermission.setOnClickListener {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    private fun setupMasterSwitch() {
        switchEnable.isChecked = activePrefs.getBoolean("notification_mirroring_enabled", false)
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("notification_mirroring_enabled", isChecked).apply()
            updateUiState()
        }
    }

    private fun updateUiState() {
        val isEnabled = switchEnable.isChecked
        // General settings
        switchWakeScreen.isEnabled = isEnabled
        switchVibrate.isEnabled = isEnabled
        switchSkipScreenOn.isEnabled = isEnabled
        switchNotifyDisconnect.isEnabled = isEnabled
        
        cardManageApps.isEnabled = isEnabled
        cardManageApps.alpha = if (isEnabled) 1.0f else 0.5f
        
        cardDynamicIsland.isEnabled = isEnabled
        cardDynamicIsland.alpha = if (isEnabled) 1.0f else 0.5f
        
        spinnerNotifStyle.isEnabled = isEnabled
    }
    
    private fun setupGeneralSettings() {
        // Skip Screen On
        switchSkipScreenOn.isChecked = activePrefs.getBoolean("notif_skip_screen_on", false)
        switchSkipScreenOn.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("notif_skip_screen_on", isChecked).apply()
        }
        
        // Notify Disconnect
        switchNotifyDisconnect.isChecked = activePrefs.getBoolean("notify_on_disconnect", false)
        switchNotifyDisconnect.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("notify_on_disconnect", isChecked).apply()
        }
    }

    private fun setupBehaviorSwitches() {
        switchWakeScreen.isChecked = activePrefs.getBoolean("wake_screen_enabled", false)
        switchWakeScreen.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("wake_screen_enabled", isChecked).apply()
        }

        switchVibrate.isChecked = activePrefs.getBoolean("vibrate_enabled", false)
        switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("vibrate_enabled", isChecked).apply()
        }
    }

    private fun setupDynamicIslandSettings() {
        // Style Spinner
        val adapterStyle = ArrayAdapter(this, android.R.layout.simple_spinner_item, 
            arrayOf(getString(R.string.notif_style_android), getString(R.string.notif_style_dynamic_island)))
        adapterStyle.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNotifStyle.adapter = adapterStyle
        
        val savedStyle = activePrefs.getString("notification_style", "android")
        spinnerNotifStyle.setSelection(if (savedStyle == "dynamic_island") 1 else 0)

        spinnerNotifStyle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val style = if (position == 1) "dynamic_island" else "android"
                activePrefs.edit().putString("notification_style", style).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        btnManageDynamicIsland.setOnClickListener {
             startActivity(android.content.Intent(this, DynamicIslandSettingsActivity::class.java))
        }
    }
    


    private fun setupCardClickListeners() {
        findViewById<View>(R.id.cardManageNavigation).setOnClickListener {
            startActivity(android.content.Intent(this, NavigationSettingsActivity::class.java))
        }

        cardManageApps.setOnClickListener {
            startActivity(android.content.Intent(this, NotificationAppListActivity::class.java))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
