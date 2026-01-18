package com.ailife.rtosify

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class DynamicIslandSettingsActivity : AppCompatActivity() {

    // Feature toggles
    private lateinit var switchShowPhoneCalls: SwitchMaterial
    private lateinit var switchShowAlarms: SwitchMaterial
    private lateinit var switchShowDisconnect: SwitchMaterial
    private lateinit var switchShowMedia: SwitchMaterial

    // Auto-hide settings
    private lateinit var spinnerAutoHideMode: Spinner
    private lateinit var switchHideWithActiveNotifs: SwitchMaterial
    private lateinit var layoutHideWithActiveNotifs: LinearLayout
    private lateinit var cardBlacklist: View

    // Display settings
    private lateinit var seekBarTimeout: SeekBar
    private lateinit var tvTimeoutValue: TextView
    private lateinit var seekBarY: SeekBar
    private lateinit var tvYValue: TextView
    private lateinit var seekBarWidth: SeekBar
    private lateinit var tvWidthValue: TextView
    private lateinit var seekBarHeight: SeekBar
    private lateinit var tvHeightValue: TextView

    // Text settings
    private lateinit var seekBarTextSize: SeekBar
    private lateinit var tvTextSizeValue: TextView
    private lateinit var switchLimitMessageLength: SwitchMaterial

    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private var bluetoothService: BluetoothService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dynamic_island_settings)

        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val scrollView = findViewById<View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, scrollView)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Dynamic Island Settings"

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
        setupAuto

        HideSettings()
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

        // Auto-hide
        spinnerAutoHideMode = findViewById(R.id.spinnerAutoHideMode)
        switchHideWithActiveNotifs = findViewById(R.id.switchHideWithActiveNotifs)
        layoutHideWithActiveNotifs = findViewById(R.id.layoutHideWithActiveNotifs)
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

        // Text settings
        seekBarTextSize = findViewById(R.id.seekBarTextSize)
        tvTextSizeValue = findViewById(R.id.tvTextSizeValue)
        switchLimitMessageLength = findViewById(R.id.switchLimitMessageLength)
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
    }

    private fun setupAutoHideSettings() {
        // Auto-hide mode spinner
        val autoHideModes = arrayOf("Always Show", "Never Show", "Hide in Blacklisted Apps")
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
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Hide with active notifications toggle
        switchHideWithActive

        Notifs.isChecked = activePrefs.getBoolean("di_hide_with_active_notifs", false)
        switchHideWithActiveNotifs.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("di_hide_with_active_notifs", isChecked).apply()
            syncSettings()
        }

        // Initial visibility
        val showBlacklistOptions = currentMode == 2
        cardBlacklist.visibility = if (showBlacklistOptions) View.VISIBLE else View.GONE
        layoutHideWithActiveNotifs.visibility = if (showBlacklistOptions) View.VISIBLE else View.GONE
    }

    private fun setupDisplaySettings() {
        // Timeout SeekBar
        val timeout = activePrefs.getInt("dynamic_island_timeout", 5)
        seekBarTimeout.progress = timeout - 2 // Range 2-10
        tvTimeoutValue.text = "$timeout seconds"
        seekBarTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 2
                tvTimeoutValue.text = "$value seconds"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = (seekBar?.progress ?: 0) + 2
                activePrefs.edit().putInt("dynamic_island_timeout", value).apply()
                syncSettings()
            }
        })

        // Y position SeekBar
        val y = activePrefs.getInt("dynamic_island_y", 8)
        seekBarY.progress = y
        tvYValue.text = "$y dp"
        seekBarY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvYValue.text = "$progress dp"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = seekBar?.progress ?: 8
                activePrefs.edit().putInt("dynamic_island_y", value).apply()
                syncSettings()
            }
        })

        // Width SeekBar
        val width = activePrefs.getInt("dynamic_island_width", 150)
        seekBarWidth.progress = width - 50 // Range 50-300
        tvWidthValue.text = "$width dp"
        seekBarWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 50
                tvWidthValue.text = "$value dp"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = (seekBar?.progress ?: 100) + 50
               activePrefs.edit().putInt("dynamic_island_width", value).apply()
                syncSettings()
            }
        })

        // Height SeekBar
        val height = activePrefs.getInt("dynamic_island_height", 40)
        seekBarHeight.progress = height - 20 // Range 20-100
        tvHeightValue.text = "$height dp"
        seekBarHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 20
                tvHeightValue.text = "$value dp"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = (seekBar?.progress ?: 20) + 20
                activePrefs.edit().putInt("dynamic_island_height", value).apply()
                syncSettings()
            }
        })
    }

    private fun setupTextSettings() {
        // Text size multiplier SeekBar
        val textMultiplier = activePrefs.getFloat("dynamic_island_text_multiplier", 1.0f)
        seekBarTextSize.progress = ((textMultiplier - 0.5f) * 10).toInt() // Range 0.5-2.0
        tvTextSizeValue.text = String.format("%.1fx", textMultiplier)
        seekBarTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.5f + (progress / 10f)
                tvTextSizeValue.text = String.format("%.1fx", value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = 0.5f + ((seekBar?.progress ?: 5) / 10f)
                activePrefs.edit().putFloat("dynamic_island_text_multiplier", value).apply()
                syncSettings()
            }
        })

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
