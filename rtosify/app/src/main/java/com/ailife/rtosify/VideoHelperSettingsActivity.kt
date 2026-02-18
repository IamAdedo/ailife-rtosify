package com.ailife.rtosify

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class VideoHelperSettingsActivity : AppCompatActivity() {

    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_helper_settings)
        
        val appBarLayout = findViewById<android.view.View>(R.id.appBarLayout)
        val scrollView = findViewById<android.view.View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, scrollView)
        
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        devicePrefManager = DevicePrefManager(this)

        setupSwipeMapping()
        setupTapMapping()
        setupDoubleTapMapping()
    }

    private fun setupSwipeMapping() {
        val rgSwipeMapping = findViewById<RadioGroup>(R.id.rgSwipeMapping)
        val current = activePrefs.getString("video_helper_swipe_mapping", "dpad")
        
        when (current) {
            "dpad" -> rgSwipeMapping.check(R.id.rbSwipeDpad)
            "swipe" -> rgSwipeMapping.check(R.id.rbSwipeGesture)
            "none" -> rgSwipeMapping.check(R.id.rbSwipeNone)
        }

        rgSwipeMapping.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rbSwipeDpad -> "dpad"
                R.id.rbSwipeGesture -> "swipe"
                R.id.rbSwipeNone -> "none"
                else -> "dpad"
            }
            activePrefs.edit().putString("video_helper_swipe_mapping", value).apply()
            sendSettingsUpdate()
        }
    }

    private fun setupTapMapping() {
        val rgTapMapping = findViewById<RadioGroup>(R.id.rgTapMapping)
        val current = activePrefs.getString("video_helper_tap_mapping", "media")

        when (current) {
            "media" -> rgTapMapping.check(R.id.rbTapMedia)
            "screen" -> rgTapMapping.check(R.id.rbTapScreen)
            "none" -> rgTapMapping.check(R.id.rbTapNone)
        }

        rgTapMapping.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rbTapMedia -> "media"
                R.id.rbTapScreen -> "screen"
                R.id.rbTapNone -> "none"
                else -> "media"
            }
            activePrefs.edit().putString("video_helper_tap_mapping", value).apply()
            sendSettingsUpdate()
        }
    }

    private fun setupDoubleTapMapping() {
        val rgDoubleTapMapping = findViewById<RadioGroup>(R.id.rgDoubleTapMapping)
        val current = activePrefs.getString("video_helper_double_tap_mapping", "center")

        when (current) {
            "center" -> rgDoubleTapMapping.check(R.id.rbDoubleTapCenter)
            "none" -> rgDoubleTapMapping.check(R.id.rbDoubleTapNone)
        }

        rgDoubleTapMapping.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rbDoubleTapCenter -> "center"
                R.id.rbDoubleTapNone -> "none"
                else -> "center"
            }
            activePrefs.edit().putString("video_helper_double_tap_mapping", value).apply()
            sendSettingsUpdate()
        }
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
