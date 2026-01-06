package com.ailife.rtosify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
    private lateinit var layoutPermissionWarning: LinearLayout
    private lateinit var cardManageApps: View
    private lateinit var cardManageRules: View
    private lateinit var btnOpenSettings: Button

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        initViews()
        setupMasterSwitch()
        setupSkipScreenOnSwitch()
        setupNotifyDisconnectSwitch()
        setupForwardOngoingSwitch()
        setupForwardSilentSwitch()
        setupWakeScreenSwitch()
        setupVibrateSwitch()
        setupVibrateSilentSwitch()
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
        layoutPermissionWarning = findViewById(R.id.layoutPermissionWarning)
        cardManageApps = findViewById(R.id.cardManageApps)
        cardManageRules = findViewById(R.id.cardManageRules)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
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
        val isSkipEnabled = prefs.getBoolean("skip_screen_on_enabled", false)
        switchSkipScreenOn.isChecked = isSkipEnabled
        switchSkipScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("skip_screen_on_enabled", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupNotifyDisconnectSwitch() {
        val isEnabled = prefs.getBoolean("notify_on_disconnect", false)
        switchNotifyDisconnect.isChecked = isEnabled
        switchNotifyDisconnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notify_on_disconnect", isChecked).apply()

            // Sync with service if connected
            val intent = Intent("com.ailife.rtosify.ACTION_UPDATE_SETTINGS")
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    private fun setupForwardOngoingSwitch() {
        val isEnabled = prefs.getBoolean("forward_ongoing_enabled", false)
        switchForwardOngoing.isChecked = isEnabled
        switchForwardOngoing.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("forward_ongoing_enabled", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupForwardSilentSwitch() {
        val isEnabled = prefs.getBoolean("forward_silent_enabled", false)
        switchForwardSilent.isChecked = isEnabled
        switchForwardSilent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("forward_silent_enabled", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupWakeScreenSwitch() {
        val isEnabled = prefs.getBoolean("wake_screen_enabled", false)
        switchWakeScreen.isChecked = isEnabled
        switchWakeScreen.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wake_screen_enabled", isChecked).apply()
            syncSettings()
        }
    }

    private fun setupVibrateSwitch() {
        val isEnabled = prefs.getBoolean("vibrate_enabled", false)
        switchVibrate.isChecked = isEnabled
        switchVibrateSilent.isEnabled = isEnabled
        switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("vibrate_enabled", isChecked).apply()
            switchVibrateSilent.isEnabled = isChecked
            if (!isChecked) {
                switchVibrateSilent.isChecked = false
                prefs.edit().putBoolean("vibrate_silent_enabled", false).apply()
            }
            syncSettings()
        }
    }

    private fun setupVibrateSilentSwitch() {
        val isEnabled = prefs.getBoolean("vibrate_silent_enabled", false)
        switchVibrateSilent.isChecked = isEnabled
        switchVibrateSilent.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("vibrate_silent_enabled", isChecked).apply()
            syncSettings()
        }
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
        if (!prefs.contains("notification_mirroring_enabled")) {
            prefs.edit().putBoolean("notification_mirroring_enabled", false).apply()
        }

        val isEnabled = prefs.getBoolean("notification_mirroring_enabled", false)
        switchEnable.isChecked = isEnabled

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notification_mirroring_enabled", isChecked).apply()
            updateUiState()
            syncSettings()
        }
    }

    private fun updateUiState() {
        val hasPermission = isNotificationServiceEnabled()

        if (hasPermission && prefs.getBoolean("waiting_for_permission", false)) {
            prefs.edit()
                .remove("waiting_for_permission")
                .putBoolean("notification_mirroring_enabled", true)
                .apply()
            switchEnable.isChecked = true
        }

        val isSwitchOn = prefs.getBoolean("notification_mirroring_enabled", false)

        if (switchEnable.isChecked != isSwitchOn) {
            switchEnable.isChecked = isSwitchOn
        }

        if (!hasPermission) {
            switchEnable.isEnabled = false
            switchEnable.isChecked = false
            layoutPermissionWarning.visibility = View.VISIBLE
            setCardsVisibility(View.GONE)

            btnOpenSettings.setOnClickListener {
                prefs.edit().putBoolean("waiting_for_permission", true).apply()
                openNotificationAccessSettings()
            }
        } else {
            switchEnable.isEnabled = true
            layoutPermissionWarning.visibility = View.GONE
            setCardsVisibility(View.VISIBLE)

            val cards = listOf(cardGeneralSettings, cardNotificationBehavior, cardManageApps, cardManageRules)
            val childSwitches = listOf(switchSkipScreenOn, switchNotifyDisconnect, switchForwardOngoing, switchForwardSilent, switchWakeScreen, switchVibrate)
            
            if (isSwitchOn) {
                cards.forEach {
                    it.alpha = 1.0f
                    it.isEnabled = true
                }
                childSwitches.forEach { it.isEnabled = true }
                
                // Vibrate Silent still depends on Vibrate
                val isVibrateOn = prefs.getBoolean("vibrate_enabled", false)
                switchVibrateSilent.isEnabled = isVibrateOn
            } else {
                cards.forEach {
                    it.alpha = 0.4f
                    it.isEnabled = false
                }
                childSwitches.forEach { it.isEnabled = false }
                switchVibrateSilent.isEnabled = false
            }
        }
    }

    private fun setCardsVisibility(visibility: Int) {
        val cards = listOf(cardGeneralSettings, cardNotificationBehavior, cardManageApps, cardManageRules)
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
            Toast.makeText(this, getString(R.string.toast_error_open_settings), Toast.LENGTH_SHORT).show()
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