package com.ailife.rtosify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ailife.rtosify.utils.OtaManager

class AboutActivity : AppCompatActivity() {

    private lateinit var otaManager: OtaManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        otaManager = OtaManager(this)

        val tvAppName = findViewById<TextView>(R.id.tvAppName)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val switchAutoUpdate = findViewById<Switch>(R.id.switchAutoUpdate)
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        val btnWebsite = findViewById<Button>(R.id.btnWebsite)

        // Set version info
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = getString(R.string.about_version_format, pInfo.versionName)
        } catch (e: Exception) {
            tvVersion.text = getString(R.string.about_version_unknown)
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
    }
}
