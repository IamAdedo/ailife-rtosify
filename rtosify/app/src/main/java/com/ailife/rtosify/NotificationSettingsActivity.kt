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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
// No subpackage imports needed as they are in the same package

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private lateinit var switchEnable: MaterialSwitch
    private lateinit var switchWakeScreen: MaterialSwitch
    private lateinit var switchWakeScreenDnd: MaterialSwitch
    private lateinit var switchVibrate: MaterialSwitch
    private lateinit var switchVibrateSilent: MaterialSwitch
    private lateinit var switchSkipScreenOn: MaterialSwitch
    private lateinit var switchPhoneCalls: MaterialSwitch
    private lateinit var switchNotifyDisconnect: MaterialSwitch
    private lateinit var switchNotifSound: MaterialSwitch
    private lateinit var switchCallRinging: MaterialSwitch
    
    private lateinit var rowNotifSoundSelector: View
    private lateinit var tvNotifSoundName: TextView
    
    private lateinit var cardManageApps: View
    private lateinit var cardDynamicIsland: View
    private lateinit var layoutPermissionWarning: View
    private lateinit var btnGrantPermission: View
    private lateinit var btnManageDynamicIsland: View
    private lateinit var tvManageSettings: TextView
    private lateinit var btnSimulateNotification: android.widget.Button

    private lateinit var seekVibrationStrength: Slider
    private lateinit var spinnerVibrationPattern: Spinner
    private lateinit var layoutCustomVibration: LinearLayout
    private lateinit var sliderCustomVibration: Slider
    private lateinit var tvCustomVibrationValue: TextView

    private lateinit var spinnerNotifStyle: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)
        
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val nestedScrollView = findViewById<View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, nestedScrollView)

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
        tvManageSettings = findViewById(R.id.tvManageSettings)
        btnSimulateNotification = findViewById(R.id.btnSimulateNotification)

        seekVibrationStrength = findViewById(R.id.seekVibrationStrength)
        spinnerVibrationPattern = findViewById(R.id.spinnerVibrationPattern)

        spinnerNotifStyle = findViewById(R.id.spinnerNotificationStyle)
        
        switchNotifSound = findViewById(R.id.switchNotifSound)
        switchCallRinging = findViewById(R.id.switchCallRinging)
        rowNotifSoundSelector = findViewById(R.id.rowNotifSoundSelector)
        tvNotifSoundName = findViewById(R.id.tvNotifSoundName)
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
        switchNotifSound.isEnabled = isEnabled
        switchCallRinging.isEnabled = isEnabled
        rowNotifSoundSelector.isEnabled = isEnabled
        rowNotifSoundSelector.alpha = if (isEnabled) 1.0f else 0.5f
        
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

        // Vibration Strength Slider
        val strength = activePrefs.getInt("vibration_strength", 2) // Default Medium
        seekVibrationStrength.value = strength.toFloat().coerceIn(0f, 4f)
        seekVibrationStrength.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                activePrefs.edit().putInt("vibration_strength", value.toInt()).apply()
                sendSettingsUpdate()
            }
        }

        // Vibration Pattern
        layoutCustomVibration = findViewById(R.id.layoutCustomVibration)
        sliderCustomVibration = findViewById(R.id.sliderCustomVibration)
        tvCustomVibrationValue = findViewById(R.id.tvCustomVibrationValue)

        val patterns = arrayOf(
            getString(R.string.vibration_pattern_default),
            getString(R.string.vibration_pattern_double_click),
            getString(R.string.vibration_pattern_long),
            getString(R.string.vibration_pattern_heartbeat),
            getString(R.string.vibration_pattern_tick),
            getString(R.string.vibration_pattern_custom)
        )
        val patternAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, patterns)
        patternAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVibrationPattern.adapter = patternAdapter
        
        val patternIdx = activePrefs.getInt("vibration_pattern", 0)
        spinnerVibrationPattern.setSelection(patternIdx)

        val customLength = activePrefs.getInt("vibration_custom_length", 200)
        sliderCustomVibration.value = customLength.toFloat()
        tvCustomVibrationValue.text = "${customLength}ms"
        
        updateCustomVibrationVisibility(patternIdx)
        
        spinnerVibrationPattern.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
             override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                 activePrefs.edit().putInt("vibration_pattern", position).apply()
                 updateCustomVibrationVisibility(position)
                 sendSettingsUpdate()
             }
             override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        sliderCustomVibration.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                tvCustomVibrationValue.text = "${value.toInt()}ms"
            }
        }
        sliderCustomVibration.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                activePrefs.edit().putInt("vibration_custom_length", slider.value.toInt()).apply()
                sendSettingsUpdate()
            }
        })

        // Notification Sound
        switchNotifSound.isChecked = activePrefs.getBoolean("notification_sound_enabled", false)
        switchNotifSound.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("notification_sound_enabled", isChecked).apply()
            sendSettingsUpdate()
        }

        // Call Ringing
        switchCallRinging.isChecked = activePrefs.getBoolean("phone_call_ringing_enabled", false)
        switchCallRinging.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("phone_call_ringing_enabled", isChecked).apply()
            sendSettingsUpdate()
        }

        // Sound Selector
        tvNotifSoundName.text = activePrefs.getString("notification_sound_name", getString(R.string.notif_sound_default))
        rowNotifSoundSelector.setOnClickListener {
            // Send request to watch to open ringtone picker
            val intent = Intent("com.ailife.rtosify.ACTION_REQUEST_WATCH_RINGTONE_PICKER")
            intent.setPackage(packageName)
            sendBroadcast(intent)
            Toast.makeText(this, getString(R.string.toast_requesting_ringtone_picker), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCustomVibrationVisibility(position: Int) {
        layoutCustomVibration.visibility = if (position == 5) View.VISIBLE else View.GONE
    }

    private fun sendSettingsUpdate() {
        val intent = android.content.Intent("com.ailife.rtosify.ACTION_UPDATE_SETTINGS")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun setupDynamicIslandSettings() {
        // Style Spinner
        val adapterStyle = ArrayAdapter(this, android.R.layout.simple_spinner_item, 
            arrayOf(getString(R.string.notif_style_android), getString(R.string.notif_style_dynamic_island), getString(R.string.notif_style_full_screen)))
        adapterStyle.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNotifStyle.adapter = adapterStyle
        
        val savedStyle = activePrefs.getString("notification_style", "android")
        val selection = when (savedStyle) {
            "dynamic_island" -> 1
            "full_screen" -> 2
            else -> 0
        }
        spinnerNotifStyle.setSelection(selection)

        spinnerNotifStyle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val style = when (position) {
                    1 -> "dynamic_island"
                    2 -> "full_screen"
                    else -> "android"
                }
                activePrefs.edit().putString("notification_style", style).apply()
                sendSettingsUpdate()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Button always visible, text set in XML
        
        btnManageDynamicIsland.setOnClickListener {
            val style = activePrefs.getString("notification_style", "android")
            if (style == "dynamic_island") {
                startActivity(android.content.Intent(this, DynamicIslandSettingsActivity::class.java))
            } else if (style == "full_screen") {
                startActivity(android.content.Intent(this, FullScreenSettingsActivity::class.java))
            } else {
                // Should not happen as button hidden, but fallback
                startActivity(android.content.Intent(this, AndroidNotificationSettingsActivity::class.java))
            }
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
                title = getString(R.string.notif_sim_title),
                text = getString(R.string.notif_sim_text),
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
            
            Toast.makeText(this, getString(R.string.notif_sim_sent), Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.notif_sim_error, e.message), Toast.LENGTH_SHORT).show()
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

    private val settingsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == BluetoothService.ACTION_UPDATE_SETTINGS) {
                // Refresh sound name
                tvNotifSoundName.text = activePrefs.getString("notification_sound_name", getString(R.string.notif_sound_default))
                // Refresh switches
                switchNotifSound.isChecked = activePrefs.getBoolean("notification_sound_enabled", false)
                switchCallRinging.isChecked = activePrefs.getBoolean("phone_call_ringing_enabled", false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter(BluetoothService.ACTION_UPDATE_SETTINGS)
        androidx.core.content.ContextCompat.registerReceiver(this, settingsReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(settingsReceiver)
    }
}
