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

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var switchSkipScreenOn: SwitchMaterial
    private lateinit var switchNotifyDisconnect: SwitchMaterial
    private lateinit var switchForwardOngoing: SwitchMaterial
    private lateinit var switchForwardSilent: SwitchMaterial
    private lateinit var switchWakeScreen: SwitchMaterial
    private lateinit var switchVibrate: SwitchMaterial
    private lateinit var switchVibrateSilent: SwitchMaterial
    private lateinit var cardMasterSwitch: View
    private lateinit var cardGeneralSettings: View
    private lateinit var cardNotificationBehavior: View
    private lateinit var cardDynamicIsland: View
    private lateinit var layoutPermissionWarning: LinearLayout
    private lateinit var cardManageApps: View
    private lateinit var cardManageRules: View
    private lateinit var btnOpenSettings: Button
    private lateinit var spinnerNotificationStyle: Spinner
    private lateinit var seekBarTimeout: SeekBar
    private lateinit var tvTimeoutValue: TextView
    private lateinit var layoutDynamicIslandOptions: LinearLayout
    private lateinit var switchHideWhenIdle: SwitchMaterial
    private lateinit var seekBarY: SeekBar
    private lateinit var tvYValue: TextView
    private lateinit var seekBarWidth: SeekBar
    private lateinit var tvWidthValue: TextView
    private lateinit var seekBarHeight: SeekBar
    private lateinit var tvHeightValue: TextView

    private lateinit var devicePrefManager: DevicePrefManager
    private lateinit var globalPrefs: SharedPreferences
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        devicePrefManager = DevicePrefManager(this)
        globalPrefs = devicePrefManager.getGlobalPrefs()

        initViews()
        setupMasterSwitch()
        setupSkipScreenOnSwitch()
        setupNotifyDisconnectSwitch()
        setupForwardOngoingSwitch()
        setupForwardSilentSwitch()
        setupWakeScreenSwitch()
        setupVibrateSwitch()
        setupVibrateSilentSwitch()
        setupNotificationStyleSpinner()
        setupTimeoutSeekBar()
        setupHideWhenIdleSwitch()
        setupYSeekBar()
        setupWidthSeekBar()
        setupHeightSeekBar()
        setupCardClickListeners()
    }

    private fun initViews() {
        switchEnable = findViewById(R.id.switchEnableMirroring)
        switchSkipScreenOn = findViewById(R.id.switchSkipScreenOn)
        switchNotifyDisconnect = findViewById(R.id.switchNotifyDisconnect)
        switchForwardOngoing = findViewById(R.id.switchForwardOngoing)
        switchForwardSilent = findViewById(R.id.switchForwardSilent)
        switchWakeScreen = findViewById(R.id.switchWakeScreen)
        switchVibrate = findViewById(R.id.switchVibrate)
        switchVibrateSilent = findViewById(R.id.switchVibrateSilent)

        cardMasterSwitch = findViewById(R.id.cardMasterSwitch)
        cardGeneralSettings = findViewById(R.id.cardGeneralSettings)
        cardNotificationBehavior = findViewById(R.id.cardNotificationBehavior)
        cardDynamicIsland = findViewById(R.id.cardDynamicIsland)
        layoutPermissionWarning = findViewById(R.id.layoutPermissionWarning)
        cardManageApps = findViewById(R.id.cardManageApps)
        cardManageRules = findViewById(R.id.cardManageRules)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        spinnerNotificationStyle = findViewById(R.id.spinnerNotificationStyle)
        seekBarTimeout = findViewById(R.id.seekBarTimeout)
        tvTimeoutValue = findViewById(R.id.tvTimeoutValue)
        layoutDynamicIslandOptions = findViewById(R.id.layoutDynamicIslandOptions)
        switchHideWhenIdle = findViewById(R.id.switchHideWhenIdle)
        seekBarY = findViewById(R.id.seekBarY)
        tvYValue = findViewById(R.id.tvYValue)
        seekBarWidth = findViewById(R.id.seekBarWidth)
        tvWidthValue = findViewById(R.id.tvWidthValue)
        seekBarHeight = findViewById(R.id.seekBarHeight)
        tvHeightValue = findViewById(R.id.tvHeightValue)
    }

    private fun setupCardClickListeners() {
        cardManageApps.setOnClickListener {
            if (isNotificationServiceEnabled()) {
                val intent = Intent(this, NotificationAppListActivity::class.java)
                startActivity(intent)
            }
        }

        cardManageRules.setOnClickListener {
            if (isNotificationServiceEnabled()) {
                val intent = Intent(this, NotificationRulesActivity::class.java)
                startActivity(intent)
            }
        }
    }

    private fun setupSkipScreenOnSwitch() {
        val isSkipEnabled = activePrefs.getBoolean("skip_screen_on_enabled", false)
        switchSkipScreenOn.isChecked = isSkipEnabled
        switchSkipScreenOn.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("skip_screen_on_enabled", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupNotifyDisconnectSwitch() {
        val isEnabled = activePrefs.getBoolean("notify_on_disconnect", false)
        switchNotifyDisconnect.isChecked = isEnabled
        switchNotifyDisconnect.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("notify_on_disconnect", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupForwardOngoingSwitch() {
        val isEnabled = activePrefs.getBoolean("forward_ongoing_enabled", false)
        switchForwardOngoing.isChecked = isEnabled
        switchForwardOngoing.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("forward_ongoing_enabled", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupForwardSilentSwitch() {
        val isEnabled = activePrefs.getBoolean("forward_silent_enabled", false)
        switchForwardSilent.isChecked = isEnabled
        switchForwardSilent.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("forward_silent_enabled", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupWakeScreenSwitch() {
        val isEnabled = activePrefs.getBoolean("wake_screen_enabled", false)
        switchWakeScreen.isChecked = isEnabled
        switchWakeScreen.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("wake_screen_enabled", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupVibrateSwitch() {
        val isEnabled = activePrefs.getBoolean("vibrate_enabled", false)
        switchVibrate.isChecked = isEnabled
        switchVibrateSilent.isEnabled = isEnabled
        switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("vibrate_enabled", isChecked).apply()
            switchVibrateSilent.isEnabled = isChecked
            if (!isChecked) {
                switchVibrateSilent.isChecked = false
                activePrefs.edit().putBoolean("vibrate_silent_enabled", false).apply()
            }
            syncSettings()
        }
    }

    private fun setupVibrateSilentSwitch() {
        val isEnabled = activePrefs.getBoolean("vibrate_silent_enabled", false)
        switchVibrateSilent.isChecked = isEnabled
        switchVibrateSilent.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("vibrate_silent_enabled", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupNotificationStyleSpinner() {
        val styles = arrayOf(getString(R.string.notif_style_android), getString(R.string.notif_style_dynamic_island))
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, styles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNotificationStyle.adapter = adapter

        // Load saved preference
        val currentStyle = activePrefs.getString("notification_style", "android") ?: "android"
        spinnerNotificationStyle.setSelection(if (currentStyle == "dynamic_island") 1 else 0)

        spinnerNotificationStyle.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                    ) {
                        val style = if (position == 0) "android" else "dynamic_island"
                        activePrefs.edit().putString("notification_style", style).apply()
                        updateUiState()
                        syncSettings()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
    }

    private fun setupTimeoutSeekBar() {
        // Load saved timeout (default 5 seconds)
        val timeout = activePrefs.getInt("dynamic_island_timeout", 5)
        seekBarTimeout.progress = timeout - 2 // Offset by 2 since min is 2
        tvTimeoutValue.text = getString(R.string.notif_unit_seconds, timeout)

        seekBarTimeout.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        // Minimum timeout is 2 seconds, max is 10 seconds
                        val timeoutSeconds = progress + 2
                        tvTimeoutValue.text = getString(R.string.notif_unit_seconds, timeoutSeconds)
                        activePrefs.edit().putInt("dynamic_island_timeout", timeoutSeconds).apply()
                        syncSettings()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
        )
    }

    private fun setupHideWhenIdleSwitch() {
        val isEnabled = activePrefs.getBoolean("dynamic_island_hide_idle", false)
        switchHideWhenIdle.isChecked = isEnabled
        switchHideWhenIdle.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("dynamic_island_hide_idle", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupYSeekBar() {
        val y = activePrefs.getInt("dynamic_island_y", 8)
        seekBarY.progress = y
        tvYValue.text = getString(R.string.notif_unit_dp, y)

        seekBarY.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        tvYValue.text = getString(R.string.notif_unit_dp, progress)
                        activePrefs.edit().putInt("dynamic_island_y", progress).apply()
                        syncSettings()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
        )
    }

    private fun setupWidthSeekBar() {
        val width = activePrefs.getInt("dynamic_island_width", 150)
        seekBarWidth.progress = width - 50
        tvWidthValue.text = getString(R.string.notif_unit_dp, width)

        seekBarWidth.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        val value = progress + 50
                        tvWidthValue.text = getString(R.string.notif_unit_dp, value)
                        activePrefs.edit().putInt("dynamic_island_width", value).apply()
                        syncSettings()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
        )
    }

    private fun setupHeightSeekBar() {
        val height = activePrefs.getInt("dynamic_island_height", 40)
        seekBarHeight.progress = height - 20
        tvHeightValue.text = getString(R.string.notif_unit_dp, height)

        seekBarHeight.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        val value = progress + 20
                        tvHeightValue.text = getString(R.string.notif_unit_dp, value)
                        activePrefs.edit().putInt("dynamic_island_height", value).apply()
                        syncSettings()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
        )
    }

    private fun syncSettings() {
        // Sync with service if connected
        val intent = Intent("com.ailife.rtosify.ACTION_UPDATE_SETTINGS")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun setupMasterSwitch() {
        if (!activePrefs.contains("notification_mirroring_enabled")) {
            activePrefs.edit().putBoolean("notification_mirroring_enabled", false).apply()
        }

        val isEnabled = activePrefs.getBoolean("notification_mirroring_enabled", false)
        switchEnable.isChecked = isEnabled

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("notification_mirroring_enabled", isChecked).apply()
            updateUiState()
            syncSettings()
        }
    }

    private fun updateUiState() {
        val hasPermission = isNotificationServiceEnabled()

        if (hasPermission && globalPrefs.getBoolean("waiting_for_permission", false)) {
            globalPrefs.edit().remove("waiting_for_permission").apply()
            activePrefs.edit().putBoolean("notification_mirroring_enabled", true).apply()
            switchEnable.isChecked = true
        }

        val isSwitchOn = activePrefs.getBoolean("notification_mirroring_enabled", false)

        if (switchEnable.isChecked != isSwitchOn) {
            switchEnable.isChecked = isSwitchOn
        }

        if (!hasPermission) {
            switchEnable.isEnabled = false
            switchEnable.isChecked = false
            layoutPermissionWarning.visibility = View.VISIBLE
            setCardsVisibility(View.GONE)

            btnOpenSettings.setOnClickListener {
                globalPrefs.edit().putBoolean("waiting_for_permission", true).apply()
                openNotificationAccessSettings()
            }
        } else {
            switchEnable.isEnabled = true
            layoutPermissionWarning.visibility = View.GONE
            setCardsVisibility(View.VISIBLE)

            val cards =
                    listOf(
                            cardGeneralSettings,
                            cardNotificationBehavior,
                            cardDynamicIsland,
                            cardManageApps,
                            cardManageRules
                    )
            val childSwitches =
                    listOf(
                            switchSkipScreenOn,
                            switchNotifyDisconnect,
                            switchForwardOngoing,
                            switchForwardSilent,
                            switchWakeScreen,
                            switchVibrate
                    )

            if (isSwitchOn) {
                cards.forEach {
                    it.alpha = 1.0f
                    it.isEnabled = true
                }
                childSwitches.forEach { it.isEnabled = true }

                // Vibrate Silent still depends on Vibrate
                val isVibrateOn = activePrefs.getBoolean("vibrate_enabled", false)
                switchVibrateSilent.isEnabled = isVibrateOn

                // Dynamic Island options depend on selected style
                val currentStyle = activePrefs.getString("notification_style", "android")
                val isDynamicIsland = currentStyle == "dynamic_island"
                layoutDynamicIslandOptions.isEnabled = isDynamicIsland
                layoutDynamicIslandOptions.alpha = if (isDynamicIsland) 1.0f else 0.4f
                setViewGroupEnabled(layoutDynamicIslandOptions, isDynamicIsland)
            } else {
                cards.forEach {
                    it.alpha = 0.4f
                    it.isEnabled = false
                }
                childSwitches.forEach { it.isEnabled = false }
                switchVibrateSilent.isEnabled = false
                layoutDynamicIslandOptions.isEnabled = false
                layoutDynamicIslandOptions.alpha = 0.4f
                setViewGroupEnabled(layoutDynamicIslandOptions, false)
            }
        }
    }

    private fun setViewGroupEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setViewGroupEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    private fun setCardsVisibility(visibility: Int) {
        val cards =
                listOf(
                        cardGeneralSettings,
                        cardNotificationBehavior,
                        cardDynamicIsland,
                        cardManageApps,
                        cardManageRules
                )
        cards.forEach { it.visibility = visibility }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, MyNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_error_open_settings), Toast.LENGTH_SHORT)
                    .show()
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
