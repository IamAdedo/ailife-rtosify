package com.ailife.rtosify

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class AndroidNotificationSettingsActivity : AppCompatActivity() {

    private lateinit var switchInAppReplyDialog: SwitchMaterial
    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private var bluetoothService: BluetoothService? = null
    private var isServiceBound = false

    private val TAG = "AndroidNotifSettings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_android_notification_settings)

        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val scrollView = findViewById<View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, scrollView)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.notif_android_settings)

        devicePrefManager = DevicePrefManager(this)

        // Bind to BluetoothService
        val serviceIntent = Intent(this, BluetoothService::class.java)
        try {
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to service: ${e.message}")
        }

        initViews()
        setupSettings()
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            bluetoothService = (service as? BluetoothService.LocalBinder)?.getService()
            isServiceBound = true
            Log.d(TAG, "BluetoothService connected")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            bluetoothService = null
            isServiceBound = false
            Log.d(TAG, "BluetoothService disconnected")
        }
    }

    private fun initViews() {
        switchInAppReplyDialog = findViewById(R.id.switchInAppReplyDialog)
    }

    private fun setupSettings() {
        switchInAppReplyDialog.isChecked = activePrefs.getBoolean("in_app_reply_dialog", false)
        switchInAppReplyDialog.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("in_app_reply_dialog", isChecked).apply()
            syncSettings()
        }
    }

    private fun syncSettings() {
        if (isServiceBound) {
            bluetoothService?.syncDynamicIslandSettings()
        } else {
            Log.w(TAG, "Cannot sync settings: Service not bound")
        }
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
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
