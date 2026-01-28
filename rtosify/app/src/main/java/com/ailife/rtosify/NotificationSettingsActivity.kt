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
    private lateinit var switchWakeScreenDnd: SwitchMaterial
    private lateinit var switchVibrate: SwitchMaterial
    private lateinit var switchVibrateSilent: SwitchMaterial
    private lateinit var switchSkipScreenOn: SwitchMaterial
    private lateinit var switchPhoneCalls: SwitchMaterial
    private lateinit var switchNotifyDisconnect: SwitchMaterial
    
    private lateinit var cardManageApps: View
    private lateinit var cardDynamicIsland: View
    private lateinit var layoutPermissionWarning: View
    private lateinit var btnGrantPermission: View
    private lateinit var btnManageDynamicIsland: View
    private lateinit var btnSimulateNotification: android.widget.Button

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
        setupDebugSettings()
    }

    override fun onResume() {
        super.onResume()
        checkPermission()
        updateUiState()
    }

    private fun initViews() {
        switchEnable = findViewById(R.id.switchEnableMirroring)
        switchWakeScreen = findViewById(R.id.switchWakeScreen)
        switchWakeScreenDnd = findViewById(R.id.switchWakeScreenDnd)
        switchVibrate = findViewById(R.id.switchVibrate)
        switchVibrateSilent = findViewById(R.id.switchVibrateSilent)
        switchSkipScreenOn = findViewById(R.id.switchSkipScreenOn)
        switchPhoneCalls = findViewById(R.id.switchPhoneCalls)
        switchNotifyDisconnect = findViewById(R.id.switchNotifyDisconnect)
        
        cardManageApps = findViewById(R.id.cardManageApps)
        cardDynamicIsland = findViewById(R.id.cardDynamicIsland)
        layoutPermissionWarning = findViewById(R.id.layoutPermissionWarning)
        btnGrantPermission = findViewById(R.id.btnOpenSettings)
        btnManageDynamicIsland = findViewById(R.id.btnManageDynamicIsland)
        btnSimulateNotification = findViewById(R.id.btnSimulateNotification)

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
        switchWakeScreenDnd.isEnabled = isEnabled
        switchVibrate.isEnabled = isEnabled
        switchVibrateSilent.isEnabled = isEnabled
        switchSkipScreenOn.isEnabled = isEnabled
        switchPhoneCalls.isEnabled = isEnabled
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

        // Phone Calls
        switchPhoneCalls.isChecked = activePrefs.getBoolean("phone_call_forwarding_enabled", true)
        switchPhoneCalls.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("phone_call_forwarding_enabled", isChecked).apply()
        }
    }

    private fun setupBehaviorSwitches() {
        switchWakeScreen.isChecked = activePrefs.getBoolean("wake_screen_enabled", false)
        switchWakeScreen.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("wake_screen_enabled", isChecked).apply()
            sendSettingsUpdate()
        }

        switchVibrate.isChecked = activePrefs.getBoolean("vibrate_enabled", false)
        switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("vibrate_enabled", isChecked).apply()
            sendSettingsUpdate()
        }

        switchVibrateSilent.isChecked = activePrefs.getBoolean("vibrate_silent_enabled", false)
        switchVibrateSilent.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("vibrate_silent_enabled", isChecked).apply()
            sendSettingsUpdate()
        }

        switchWakeScreenDnd.isChecked = activePrefs.getBoolean("wake_screen_dnd_enabled", false)
        switchWakeScreenDnd.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("wake_screen_dnd_enabled", isChecked).apply()
            sendSettingsUpdate()
        }
    }

    private fun sendSettingsUpdate() {
        val intent = android.content.Intent("com.ailife.rtosify.ACTION_UPDATE_SETTINGS")
        intent.setPackage(packageName)
        sendBroadcast(intent)
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
                sendSettingsUpdate()
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

        findViewById<View>(R.id.cardFileObserver).setOnClickListener {
            startActivity(android.content.Intent(this, FileObserverSettingsActivity::class.java))
        }
    }

    private fun setupDebugSettings() {
        btnSimulateNotification.setOnClickListener {
            sendSimulatedNotification()
        }
    }

    private fun sendSimulatedNotification() {
        try {
            // 1. Get app icon
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap 
                ?: (drawable as? android.graphics.drawable.AdaptiveIconDrawable)?.let {
                    val bmp = android.graphics.Bitmap.createBitmap(
                        it.intrinsicWidth, 
                        it.intrinsicHeight, 
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    it.setBounds(0, 0, canvas.width, canvas.height)
                    it.draw(canvas)
                    bmp
                }

            val iconBase64 = bitmap?.let { bmp ->
                val outputStream = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
                android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
            }

            // 2. Create Notification Data
            val notifData = NotificationData(
                packageName = packageName,
                title = "Test Notification",
                text = "This is a simulated notification from RTOSify settings.",
                key = "test_notif_${System.currentTimeMillis()}",
                appName =getString(R.string.app_name),
                largeIcon = iconBase64,
                smallIcon = iconBase64 // Use same for simplicity
            )

            // 3. Serialize to JSON
            val gson = com.google.gson.Gson()
            val jsonString = gson.toJson(notifData)

            // 4. Send Broadcast
            val intent = Intent(BluetoothService.ACTION_SEND_NOTIF_TO_WATCH)
            intent.putExtra(BluetoothService.EXTRA_NOTIF_JSON, jsonString)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            
            Toast.makeText(this, "Simulated notification sent", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error sending simulation: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
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
