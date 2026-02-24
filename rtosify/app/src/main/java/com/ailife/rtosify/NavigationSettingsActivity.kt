package com.ailife.rtosify

import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class NavigationSettingsActivity : AppCompatActivity() {

    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private lateinit var spinnerImageType: Spinner
    private lateinit var spinnerTextType: Spinner
    private lateinit var switchKeepScreenOn: MaterialSwitch
    private lateinit var switchForceGreyBg: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        devicePrefManager = DevicePrefManager(this)

        initViews()
        setupSpinners()
        setupSwitch()
    }

    private fun initViews() {
        spinnerImageType = findViewById(R.id.spinnerImageType)
        spinnerTextType = findViewById(R.id.spinnerTextType)
        switchKeepScreenOn = findViewById(R.id.switchKeepScreenOn)
        switchForceGreyBg = findViewById(R.id.switchForceGreyBg)
    }

    private fun setupSpinners() {
        // Image Type
        // 0: None, 1: Large Icon, 2: Big Picture
        val imageTypes = arrayOf(
            getString(R.string.nav_image_none),
            getString(R.string.nav_image_large_icon),
            getString(R.string.nav_image_big_picture)
        )
        val imageAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, imageTypes)
        imageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerImageType.adapter = imageAdapter

        val savedImageType = activePrefs.getInt("nav_image_type", 1) // Default Large Icon
        spinnerImageType.setSelection(savedImageType)

        spinnerImageType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                activePrefs.edit().putInt("nav_image_type", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Text Type
        // 0: Title, 1: Content, 2: Both
        val textTypes = arrayOf(
            getString(R.string.nav_text_title),
            getString(R.string.nav_text_content),
            getString(R.string.nav_text_both)
        )
        val textAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, textTypes)
        textAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTextType.adapter = textAdapter

        val savedTextType = activePrefs.getInt("nav_text_type", 2) // Default Both
        spinnerTextType.setSelection(savedTextType)

        spinnerTextType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                activePrefs.edit().putInt("nav_text_type", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSwitch() {
        switchKeepScreenOn.isChecked = activePrefs.getBoolean("nav_keep_screen_on", true)
        switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("nav_keep_screen_on", isChecked).apply()
        }

        switchForceGreyBg.isChecked = activePrefs.getBoolean("nav_use_grey_background", false)
        switchForceGreyBg.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("nav_use_grey_background", isChecked).apply()
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
