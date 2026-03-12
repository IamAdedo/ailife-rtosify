package com.ailife.rtosify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider

class DynamicIslandColorPickerActivity : AppCompatActivity() {

    private lateinit var colorWheel: ColorWheelView
    private lateinit var seekBarBrightness: Slider
    private lateinit var tvBrightnessValue: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var dynamicIslandPreview: DynamicIslandView
    private lateinit var toggleMode: MaterialButtonToggleGroup

    private var selectedColor: Int = Color.parseColor("#1C1C1E")
    private var brightness: Int = 100
    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val devicePrefManager by lazy { DevicePrefManager(this) }
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            bluetoothService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dynamic_island_color_picker)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val appBarLayout = findViewById<android.view.View>(R.id.appBarLayout)
        val nestedScrollView = findViewById<android.view.View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, nestedScrollView)

        initViews()
        loadSettings()

        val serviceIntent = Intent(this, BluetoothService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun initViews() {
        colorWheel = findViewById(R.id.colorWheel)
        seekBarBrightness = findViewById(R.id.seekBarBrightness)
        tvBrightnessValue = findViewById(R.id.tvBrightnessValue)
        btnSave = findViewById(R.id.btnSave)
        dynamicIslandPreview = findViewById(R.id.dynamicIslandPreview)
        toggleMode = findViewById(R.id.toggleMode)

        colorWheel.onColorSelected = { color ->
            selectedColor = color
            updatePreview()
        }

        seekBarBrightness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                brightness = value.toInt()
                tvBrightnessValue.text = "$brightness%"
                updatePreview()
            }
        }

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnPill) {
                    dynamicIslandPreview.showIdleState()
                } else {
                    dynamicIslandPreview.showExpandedPreview()
                }
            }
        }
        toggleMode.check(R.id.btnPill)

        btnSave.setOnClickListener {
            saveAndSync()
        }
    }

    private fun loadSettings() {
        selectedColor = activePrefs.getInt("dynamic_island_solid_color", Color.parseColor("#1C1C1E"))
        brightness = activePrefs.getInt("dynamic_island_background_opacity", 100)

        colorWheel.setColor(selectedColor)
        seekBarBrightness.value = brightness.toFloat()
        tvBrightnessValue.text = "$brightness%"
        
        updatePreview()
    }

    private fun updatePreview() {
        dynamicIslandPreview.setPreviewColor(selectedColor, brightness)
    }

    private fun saveAndSync() {
        activePrefs.edit().apply {
            putInt("dynamic_island_solid_color", selectedColor)
            putInt("dynamic_island_background_opacity", brightness)
            putInt("dynamic_island_background_mode", 1) // Force Color mode
            apply()
        }

        if (isBound) {
            val alpha = (brightness * 255) / 100
            bluetoothService?.sendDynamicIslandColor(selectedColor, alpha)
            Toast.makeText(this, getString(R.string.toast_sent_to_watch), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT).show()
        }
        
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
