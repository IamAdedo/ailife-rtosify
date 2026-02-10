package com.ailife.rtosify

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class FullScreenSettingsActivity : AppCompatActivity() {

    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private lateinit var switchStacking: MaterialSwitch
    private lateinit var switchCloseOnScreenOff: MaterialSwitch

    private lateinit var sliderAppNameSize: com.google.android.material.slider.Slider
    private lateinit var sliderTitleSize: com.google.android.material.slider.Slider
    private lateinit var sliderContentSize: com.google.android.material.slider.Slider
    private lateinit var switchAutoClose: MaterialSwitch
    private lateinit var sliderTimeout: com.google.android.material.slider.Slider
    private lateinit var layoutTimeout: android.view.View

    private lateinit var lblAppNameSize: android.widget.TextView
    private lateinit var lblTitleSize: android.widget.TextView
    private lateinit var lblContentSize: android.widget.TextView
    private lateinit var lblTimeout: android.widget.TextView

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
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        switchStacking = findViewById(R.id.switchStacking)
        switchCloseOnScreenOff = findViewById(R.id.switchCloseOnScreenOff)

        sliderAppNameSize = findViewById(R.id.sliderAppNameSize)
        sliderTitleSize = findViewById(R.id.sliderTitleSize)
        sliderContentSize = findViewById(R.id.sliderContentSize)
        switchAutoClose = findViewById(R.id.switchAutoClose)
        sliderTimeout = findViewById(R.id.sliderTimeout)
        layoutTimeout = findViewById(R.id.layoutTimeout)

        lblAppNameSize = findViewById(R.id.lblAppNameSize)
        lblTitleSize = findViewById(R.id.lblTitleSize)
        lblContentSize = findViewById(R.id.lblContentSize)
        lblTimeout = findViewById(R.id.lblTimeout)
    }

    private fun loadSettings() {
        switchStacking.isChecked = activePrefs.getBoolean("full_screen_stacking_enabled", false)
        switchCloseOnScreenOff.isChecked = activePrefs.getBoolean("full_screen_close_on_screen_off", false)

        val appNameSize = activePrefs.getInt("full_screen_app_name_size", 20)
        sliderAppNameSize.value = appNameSize.toFloat()
        updateLabel(lblAppNameSize, R.string.lbl_app_name_size, appNameSize)

        val titleSize = activePrefs.getInt("full_screen_title_size", 22)
        sliderTitleSize.value = titleSize.toFloat()
        updateLabel(lblTitleSize, R.string.lbl_title_size, titleSize)

        val contentSize = activePrefs.getInt("full_screen_content_size", 18)
        sliderContentSize.value = contentSize.toFloat()
        updateLabel(lblContentSize, R.string.lbl_content_size, contentSize)

        val autoClose = activePrefs.getBoolean("full_screen_auto_close_enabled", false)
        switchAutoClose.isChecked = autoClose
        layoutTimeout.visibility = if (autoClose) android.view.View.VISIBLE else android.view.View.GONE

        val timeout = activePrefs.getInt("full_screen_auto_close_timeout", 10)
        sliderTimeout.value = timeout.toFloat()
        updateTimeoutLabel(timeout)
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

        sliderAppNameSize.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            updateLabel(lblAppNameSize, R.string.lbl_app_name_size, size)
            activePrefs.edit().putInt("full_screen_app_name_size", size).apply()
            sendSettingsUpdate()
        }

        sliderTitleSize.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            updateLabel(lblTitleSize, R.string.lbl_title_size, size)
            activePrefs.edit().putInt("full_screen_title_size", size).apply()
            sendSettingsUpdate()
        }

        sliderContentSize.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            updateLabel(lblContentSize, R.string.lbl_content_size, size)
            activePrefs.edit().putInt("full_screen_content_size", size).apply()
            sendSettingsUpdate()
        }

        switchAutoClose.setOnCheckedChangeListener { _, isChecked ->
            layoutTimeout.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            activePrefs.edit().putBoolean("full_screen_auto_close_enabled", isChecked).apply()
            sendSettingsUpdate()
        }

        sliderTimeout.addOnChangeListener { _, value, _ ->
            val timeout = value.toInt()
            updateTimeoutLabel(timeout)
            activePrefs.edit().putInt("full_screen_auto_close_timeout", timeout).apply()
            sendSettingsUpdate()
        }
    }

    private fun updateLabel(textView: android.widget.TextView, labelResId: Int, size: Int) {
        val label = getString(labelResId)
        val value = getString(R.string.lbl_text_size_value, size)
        textView.text = "$label: $value"
    }

    private fun updateTimeoutLabel(timeout: Int) {
        lblTimeout.text = getString(R.string.lbl_auto_close_timeout, timeout)
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
