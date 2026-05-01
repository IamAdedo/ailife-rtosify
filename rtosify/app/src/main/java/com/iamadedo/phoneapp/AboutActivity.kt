package com.iamadedo.phoneapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iamadedo.phoneapp.utils.OtaManager
import com.google.android.material.materialswitch.MaterialSwitch

class AboutActivity : AppCompatActivity() {

    private lateinit var otaManager: OtaManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val nestedScrollView = findViewById<View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, nestedScrollView)

        otaManager = OtaManager(this)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val tvAppName = findViewById<TextView>(R.id.tvAppName)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val tvCompanionVersion = findViewById<TextView>(R.id.tvCompanionVersion)
        val btnOriginalProject = findViewById<Button>(R.id.btnOriginalProject)
        val switchAutoUpdate = findViewById<MaterialSwitch>(R.id.switchAutoUpdate)
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        val btnWebsite = findViewById<Button>(R.id.btnWebsite)

        // Set version info
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = getString(R.string.about_version_format, pInfo.versionName)
        } catch (e: Exception) {
            tvVersion.text = getString(R.string.about_version_unknown)
        }

        // Set companion version info
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val companionVersion = prefs.getString("companion_version", null)
        if (companionVersion != null) {
            tvCompanionVersion.text = getString(R.string.about_companion_version_format, companionVersion)
            tvCompanionVersion.visibility = android.view.View.VISIBLE
        } else {
            tvCompanionVersion.visibility = android.view.View.GONE
        }

        // Setup Auto Update Switch
        switchAutoUpdate.isChecked = otaManager.isUpdateCheckEnabled()
        switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            otaManager.setUpdateCheckEnabled(isChecked)
        }

        // Check Update Button
        btnCheckUpdate.setOnClickListener {
            otaManager.checkUpdate(silent = false)
        }

        // Website Button
        btnWebsite.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gitlab.com/ailife8881/rtosify"))
            startActivity(browserIntent)
        }

        // Original Project Button
        btnOriginalProject.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_credit_original_link)))
            startActivity(browserIntent)
        }
    }
}
