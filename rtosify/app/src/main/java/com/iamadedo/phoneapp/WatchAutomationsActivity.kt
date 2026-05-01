package com.iamadedo.phoneapp

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class WatchAutomationsActivity : AppCompatActivity() {

    private lateinit var switchBootService: MaterialSwitch
    private lateinit var switchClipboard: MaterialSwitch
    private lateinit var switchAutoWifi: MaterialSwitch
    private lateinit var switchAutoData: MaterialSwitch
    private lateinit var switchAutoBtTether: MaterialSwitch
    private lateinit var switchForceBt: MaterialSwitch
    private lateinit var switchSharingSync: MaterialSwitch
    private lateinit var switchStarredContacts: MaterialSwitch
    private lateinit var switchAggressiveKeepalive: MaterialSwitch

    private lateinit var devicePrefManager: DevicePrefManager
    private lateinit var globalPrefs: SharedPreferences
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_automations)
        val appBarLayout = findViewById<android.view.View>(R.id.appBarLayout)
        val scrollView = findViewById<android.view.View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, scrollView)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        devicePrefManager = DevicePrefManager(this)
        globalPrefs = devicePrefManager.getGlobalPrefs()

        initViews()
        setupBootServiceSwitch()
        setupClipboardSwitch()
        setupAutoWifiSwitch()
        setupAutoDataSwitch()
        setupAutoBtTetherSwitch()
        setupForceBtSwitch()
        setupSharingSyncSwitch()
        setupStarredContactsSwitch()
        setupAggressiveKeepaliveSwitch()
        setupVideoHelperCard()
    }

    private fun initViews() {
        switchBootService = findViewById(R.id.switchBootService)
        switchClipboard = findViewById(R.id.switchClipboard)
        switchAutoWifi = findViewById(R.id.switchAutoWifi)
        switchAutoData = findViewById(R.id.switchAutoData)
        switchAutoBtTether = findViewById(R.id.switchAutoBtTether)
        switchForceBt = findViewById(R.id.switchForceBt)
        switchSharingSync = findViewById(R.id.switchSharingSync)
        switchStarredContacts = findViewById(R.id.switchStarredContacts)
        switchAggressiveKeepalive = findViewById(R.id.switchAggressiveKeepalive)
    }

    private fun setupBootServiceSwitch() {
        val isEnabled = globalPrefs.getBoolean("autostart_on_boot", true)
        switchBootService.isChecked = isEnabled
        switchBootService.setOnCheckedChangeListener { _, isChecked ->
            globalPrefs.edit().putBoolean("autostart_on_boot", isChecked).apply()
        }
    }

    private fun setupClipboardSwitch() {
        val isEnabled = activePrefs.getBoolean("clipboard_sync_enabled", false)
        switchClipboard.isChecked = isEnabled
        switchClipboard.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("clipboard_sync_enabled", isChecked).apply()
            sendAutomationUpdate()
        }
    }

    private fun setupAutoWifiSwitch() {
        val isEnabled = activePrefs.getBoolean("auto_wifi_enabled", false)
        switchAutoWifi.isChecked = isEnabled
        switchAutoWifi.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("auto_wifi_enabled", isChecked).apply()
            sendAutomationUpdate()
        }
    }

    private fun setupAutoDataSwitch() {
        val isEnabled = activePrefs.getBoolean("auto_data_enabled", false)
        switchAutoData.isChecked = isEnabled
        switchAutoData.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("auto_data_enabled", isChecked).apply()
            sendAutomationUpdate()
        }
    }

    private fun setupAutoBtTetherSwitch() {
        val isEnabled = activePrefs.getBoolean("auto_bt_tether_enabled", false)
        switchAutoBtTether.isChecked = isEnabled
        switchAutoBtTether.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("auto_bt_tether_enabled", isChecked).apply()
            sendAutomationUpdate()
        }
    }

    private fun setupForceBtSwitch() {
        val isEnabled = activePrefs.getBoolean("force_bt_enabled", false)
        switchForceBt.isChecked = isEnabled
        switchForceBt.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("force_bt_enabled", isChecked).apply()
            sendAutomationUpdate()
        }
    }

    private fun setupSharingSyncSwitch() {
        val isEnabled = activePrefs.getBoolean("sharing_sync_enabled", false)
        switchSharingSync.isChecked = isEnabled
        switchSharingSync.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("sharing_sync_enabled", isChecked).apply()
            sendAutomationUpdate()
        }
    }

    private fun setupAggressiveKeepaliveSwitch() {
        val isEnabled = activePrefs.getBoolean("aggressive_keepalive_enabled", false)
        switchAggressiveKeepalive.isChecked = isEnabled
        switchAggressiveKeepalive.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("aggressive_keepalive_enabled", isChecked).apply()
            sendAutomationUpdate()
        }
    }

    private fun setupStarredContactsSwitch() {
        val isEnabled = activePrefs.getBoolean("starred_contacts_enabled", true)
        switchStarredContacts.isChecked = isEnabled
        switchStarredContacts.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("starred_contacts_enabled", isChecked).apply()
        }
    }

    private fun setupVideoHelperCard() {
        findViewById<android.view.View>(R.id.cardVideoHelper).setOnClickListener {
            val intent = Intent(this, VideoHelperSettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun sendAutomationUpdate() {
        // Notify service to send updated settings to watch
        val intent = Intent("com.iamadedo.phoneapp.ACTION_UPDATE_SETTINGS")
        intent.setPackage(packageName)
        sendBroadcast(intent)
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
}
