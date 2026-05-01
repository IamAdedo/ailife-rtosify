package com.iamadedo.phoneapp

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
// No subpackage imports needed as they are in the same package

class DynamicIslandSettingsActivity : AppCompatActivity() {

    // Feature toggles
    private lateinit var switchShowPhoneCalls: MaterialSwitch
    private lateinit var switchShowAlarms: MaterialSwitch
    private lateinit var switchShowDisconnect: MaterialSwitch
    private lateinit var switchShowMedia: MaterialSwitch
    private lateinit var switchFollowDnd: MaterialSwitch

    // Auto-hide settings
    private lateinit var spinnerAutoHideMode: Spinner
    private lateinit var switchHideWithActiveNotifs: MaterialSwitch
    private lateinit var layoutHideWithActiveNotifs: LinearLayout
    private lateinit var switchBlacklistHidePeak: MaterialSwitch
    private lateinit var layoutBlacklistHidePeak: LinearLayout
    private lateinit var cardBlacklist: View

    // Display settings
    private lateinit var seekBarTimeout: Slider
    private lateinit var tvTimeoutValue: TextView
    private lateinit var seekBarY: Slider
    private lateinit var tvYValue: TextView
    private lateinit var seekBarWidth: Slider
    private lateinit var tvWidthValue: TextView
    private lateinit var seekBarHeight: Slider
    private lateinit var tvHeightValue: TextView
    private lateinit var seekBarGlobalOpacity: Slider
    private lateinit var tvGlobalOpacityValue: TextView

    // Text settings
    private lateinit var seekBarTextSize: Slider
    private lateinit var tvTextSizeValue: TextView
    private lateinit var switchLimitMessageLength: MaterialSwitch

    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private var bluetoothService: BluetoothService? = null
    private lateinit var toggleBgType: MaterialButtonToggleGroup
    private lateinit var btnCustomize: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dynamic_island_settings)

        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val scrollView = findViewById<View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, scrollView)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.di_settings_title)

        devicePrefManager = DevicePrefManager(this)

        // Bind to BluetoothService
        val serviceIntent = Intent(this, BluetoothService::class.java)
        bindService(serviceIntent, object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                bluetoothService = (service as? BluetoothService.LocalBinder)?.getService()
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                bluetoothService = null
            }
        }, BIND_AUTO_CREATE)

        initViews()
        setupFeatureToggles()
        setupAutoHideSettings()
        setupDisplaySettings()
        setupTextSettings()
        setupCardClickListeners()
    }

    private fun initViews() {
        // Feature toggles
        switchShowPhoneCalls = findViewById(R.id.switchShowPhoneCalls)
        switchShowAlarms = findViewById(R.id.switchShowAlarms)
        switchShowDisconnect = findViewById(R.id.switchShowDisconnect)
        switchShowMedia = findViewById(R.id.switchShowMedia)
        switchFollowDnd = findViewById(R.id.switchFollowDnd)

        // Auto-hide
        spinnerAutoHideMode = findViewById(R.id.spinnerAutoHideMode)
        switchHideWithActiveNotifs = findViewById(R.id.switchHideWithActiveNotifs)
        layoutHideWithActiveNotifs = findViewById(R.id.layoutHideWithActiveNotifs)
        switchBlacklistHidePeak = findViewById(R.id.switchBlacklistHidePeak)
        layoutBlacklistHidePeak = findViewById(R.id.layoutBlacklistHidePeak)
        cardBlacklist = findViewById(R.id.cardBlacklist)

        // Display settings
        seekBarTimeout = findViewById(R.id.seekBarTimeout)
        tvTimeoutValue = findViewById(R.id.tvTimeoutValue)
        seekBarY = findViewById(R.id.seekBarY)
        tvYValue = findViewById(R.id.tvYValue)
        seekBarWidth = findViewById(R.id.seekBarWidth)
        tvWidthValue = findViewById(R.id.tvWidthValue)
        seekBarHeight = findViewById(R.id.seekBarHeight)
        tvHeightValue = findViewById(R.id.tvHeightValue)
        seekBarGlobalOpacity = findViewById(R.id.seekBarGlobalOpacity)
        tvGlobalOpacityValue = findViewById(R.id.tvGlobalOpacityValue)

        // Text settings
        seekBarTextSize = findViewById(R.id.seekBarTextSize)
        tvTextSizeValue = findViewById(R.id.tvTextSizeValue)
        switchLimitMessageLength = findViewById(R.id.switchLimitMessageLength)
        
        // Appearance
        toggleBgType = findViewById(R.id.toggleBgType)
        btnCustomize = findViewById(R.id.btnCustomize)

        setupAppearanceSettings()
    }

    private fun setupFeatureToggles() {
        // Show Phone Calls toggle
        switchShowPhoneCalls.isChecked = activePrefs.getBoolean("di_show_phone_calls", true)
        switchShowPhoneCalls.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("di_show_phone_calls", isChecked).apply()
            syncSettings()
        }

        // Show Alarms toggle
        switchShowAlarms.isChecked = activePrefs.getBoolean("di_show_alarms", true)
        switchShowAlarms.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("di_show_alarms", isChecked).apply()
            syncSettings()
        }

        // Show Disconnect toggle
        switchShowDisconnect.isChecked = activePrefs.getBoolean("di_show_disconnect", true)
        switchShowDisconnect.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("di_show_disconnect", isChecked).apply()
            syncSettings()
        }

        // Show Media toggle
        switchShowMedia.isChecked = activePrefs.getBoolean("di_show_media", true)
        switchShowMedia.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("di_show_media", isChecked).apply()
            syncSettings()
        }

        // Follow DND toggle
        switchFollowDnd.isChecked = activePrefs.getBoolean("di_follow_dnd", false)
        switchFollowDnd.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("di_follow_dnd", isChecked).apply()
            syncSettings()
        }
    }


    private fun setupAutoHideSettings() {
        // Auto-hide mode spinner
        val autoHideModes = arrayOf(
            getString(R.string.di_auto_hide_always_show),
            getString(R.string.di_auto_hide_always_hide),
            getString(R.string.di_auto_hide_blacklisted)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, autoHideModes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAutoHideMode.adapter = adapter
        
        val currentMode = activePrefs.getInt("di_auto_hide_mode", 0)
        spinnerAutoHideMode.setSelection(currentMode)
        
        spinnerAutoHideMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                activePrefs.edit().putInt("di_auto_hide_mode", position).apply()
                syncSettings()
                
                // Show/hide blacklist options based on mode
                val showBlacklistOptions = position == 2 // Hide in Blacklisted Apps
                cardBlacklist.visibility = if (showBlacklistOptions) View.VISIBLE else View.GONE
                layoutHideWithActiveNotifs.visibility = if (showBlacklistOptions) View.VISIBLE else View.GONE
                layoutBlacklistHidePeak.visibility = if (showBlacklistOptions) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Hide with active notifications toggle
        switchHideWithActiveNotifs.isChecked = activePrefs.getBoolean("di_hide_with_active_notifs", false)
        switchHideWithActiveNotifs.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("di_hide_with_active_notifs", isChecked).apply()
            syncSettings()
        }

        // Hide peak in blacklist toggle
        switchBlacklistHidePeak.isChecked = activePrefs.getBoolean("di_blacklist_hide_peak", false)
        switchBlacklistHidePeak.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("di_blacklist_hide_peak", isChecked).apply()
            syncSettings()
        }

        // Initial visibility
        val showBlacklistOptions = currentMode == 2
        cardBlacklist.visibility = if (showBlacklistOptions) View.VISIBLE else View.GONE
        layoutHideWithActiveNotifs.visibility = if (showBlacklistOptions) View.VISIBLE else View.GONE
        layoutBlacklistHidePeak.visibility = if (showBlacklistOptions) View.VISIBLE else View.GONE
    }

    private fun setupDisplaySettings() {
        // Timeout Slider
        val timeout = activePrefs.getInt("dynamic_island_timeout", 5)
        seekBarTimeout.value = (timeout - 2).toFloat() // Range 2-10, mapped to 0-8 in layout
        tvTimeoutValue.text = getString(R.string.notif_unit_seconds, timeout)
        seekBarTimeout.addOnChangeListener { _, value, fromUser ->
            val actualValue = value.toInt() + 2
            tvTimeoutValue.text = getString(R.string.notif_unit_seconds, actualValue)
            if (fromUser) {
                activePrefs.edit().putInt("dynamic_island_timeout", actualValue).apply()
                syncSettings()
            }
        }

        // Y position Slider
        val y = activePrefs.getInt("dynamic_island_y", 8)
        seekBarY.value = y.toFloat()
        tvYValue.text = getString(R.string.notif_unit_dp, y)
        seekBarY.addOnChangeListener { _, value, fromUser ->
            val actualValue = value.toInt()
            tvYValue.text = getString(R.string.notif_unit_dp, actualValue)
            if (fromUser) {
                activePrefs.edit().putInt("dynamic_island_y", actualValue).apply()
                syncSettings()
            }
        }

        // Width Slider
        val width = activePrefs.getInt("dynamic_island_width", 150)
        seekBarWidth.value = (width - 50).toFloat() // Range 50-300, mapped to 0-250 in layout
        tvWidthValue.text = getString(R.string.notif_unit_dp, width)
        seekBarWidth.addOnChangeListener { _, value, fromUser ->
            val actualValue = value.toInt() + 50
            tvWidthValue.text = getString(R.string.notif_unit_dp, actualValue)
            if (fromUser) {
                activePrefs.edit().putInt("dynamic_island_width", actualValue).apply()
                syncSettings()
            }
        }

        // Height Slider
        val height = activePrefs.getInt("dynamic_island_height", 40)
        seekBarHeight.value = (height - 20).toFloat() // Range 20-100, mapped to 0-80 in layout
        tvHeightValue.text = getString(R.string.notif_unit_dp, height)
        seekBarHeight.addOnChangeListener { _, value, fromUser ->
            val actualValue = value.toInt() + 20
            tvHeightValue.text = getString(R.string.notif_unit_dp, actualValue)
            if (fromUser) {
                activePrefs.edit().putInt("dynamic_island_height", actualValue).apply()
                syncSettings()
            }
        }

        // Global Opacity Slider
        val globalOpacity = activePrefs.getInt("di_global_opacity", 100)
        seekBarGlobalOpacity.value = globalOpacity.toFloat()
        tvGlobalOpacityValue.text = getString(R.string.percent_format, globalOpacity)
        seekBarGlobalOpacity.addOnChangeListener { _, value, fromUser ->
            val actualValue = value.toInt()
            tvGlobalOpacityValue.text = getString(R.string.percent_format, actualValue)
            if (fromUser) {
                activePrefs.edit().putInt("di_global_opacity", actualValue).apply()
                syncSettings()
            }
        }
    }

    private fun setupAppearanceSettings() {
        val mode = activePrefs.getInt("dynamic_island_background_mode", 0) // 0: Image, 1: Color
        if (mode == 0) toggleBgType.check(R.id.btnTypeImage) else toggleBgType.check(R.id.btnTypeColor)
        
        updateCustomizeButtonText(mode)

        toggleBgType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newMode = if (checkedId == R.id.btnTypeImage) 0 else 1
                activePrefs.edit().putInt("dynamic_island_background_mode", newMode).apply()
                updateCustomizeButtonText(newMode)
                syncSettings()
            }
        }

        btnCustomize.setOnClickListener {
            val currentMode = activePrefs.getInt("dynamic_island_background_mode", 0)
            if (currentMode == 0) {
                startActivity(Intent(this, DynamicIslandBackgroundActivity::class.java))
            } else {
                startActivity(Intent(this, DynamicIslandColorPickerActivity::class.java))
            }
        }
    }

    private fun updateCustomizeButtonText(mode: Int) {
        btnCustomize.text = if (mode == 0) {
            getString(R.string.di_select_image)
        } else {
            getString(R.string.di_select_color)
        }
    }

    private fun setupTextSettings() {
        // Text size multiplier Slider
        val textMultiplier = activePrefs.getFloat("dynamic_island_text_multiplier", 1.0f)
        seekBarTextSize.value = ((textMultiplier - 0.5f) * 10).coerceIn(0f, 15f) // Range 0.5-2.0, mapped to 0-15 in layout
        tvTextSizeValue.text = String.format("%.1fx", textMultiplier)
        seekBarTextSize.addOnChangeListener { _, value, fromUser ->
            val actualValue = 0.5f + (value / 10f)
            tvTextSizeValue.text = String.format("%.1fx", actualValue)
            if (fromUser) {
                activePrefs.edit().putFloat("dynamic_island_text_multiplier", actualValue).apply()
                syncSettings()
            }
        }

        // Limit message length toggle
        switchLimitMessageLength.isChecked = activePrefs.getBoolean("dynamic_island_limit_message_length", true)
        switchLimitMessageLength.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("dynamic_island_limit_message_length", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupCardClickListeners() {
        cardBlacklist.setOnClickListener {
            startActivity(Intent(this, DIBlacklistActivity::class.java))
        }
    }

    private fun syncSettings() {
        bluetoothService?.syncDynamicIslandSettings()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind service if needed
    }
}
