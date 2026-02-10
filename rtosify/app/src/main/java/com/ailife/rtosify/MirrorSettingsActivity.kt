package com.ailife.rtosify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.ailife.rtosify.utils.DeviceActionManager

class MirrorSettingsActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var imgStatus: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnStartMirror: MaterialButton
    private lateinit var btnControlWatch: MaterialButton
    private lateinit var switchEnableTouch: MaterialSwitch
    private lateinit var switchResMatching: MaterialSwitch
    private lateinit var switchAspectMatching: MaterialSwitch

    private lateinit var devicePrefManager: DevicePrefManager
    private lateinit var globalPrefs: SharedPreferences
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private lateinit var deviceActionManager: DeviceActionManager

    private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(className: ComponentName, service: IBinder) {
                    val binder = service as BluetoothService.LocalBinder
                    bluetoothService = binder.getService()
                    bluetoothService?.let {
                        it.callback = this@MirrorSettingsActivity
                        deviceActionManager.updateUserService(it.userService)
                    }
                    isBound = true
                    updateUI(bluetoothService?.isConnected == true)
                }

                override fun onServiceDisconnected(arg0: ComponentName) {
                    isBound = false
                    bluetoothService = null
                    deviceActionManager.updateUserService(null)
                }
            }

    private val screenCaptureLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK && result.data != null) {
                    // Check if high quality mode should be enabled (LAN connected + HQ setting enabled)
                    val isLanConnected = bluetoothService?.isWifiConnected() == true
                    val hqEnabled = activePrefs.getBoolean("hq_lan_enabled", false)
                    val useHighQuality = isLanConnected && hqEnabled

                    val intent =
                            Intent(this, MirroringService::class.java).apply {
                                putExtra(MirroringService.EXTRA_RESULT_CODE, result.resultCode)
                                putExtra(MirroringService.EXTRA_DATA, result.data)
                                putExtra(MirroringService.EXTRA_HIGH_QUALITY, useHighQuality)
                            }
                    ContextCompat.startForegroundService(this, intent)

                    // Send message to watch to open MirrorActivity
                    val metrics = resources.displayMetrics
                    bluetoothService?.sendMessage(
                            ProtocolHelper.createMirrorStart(
                                    metrics.widthPixels,
                                    metrics.heightPixels,
                                    metrics.densityDpi
                            )
                    )

                    // Update button state
                    btnStartMirror.text = getString(R.string.mirror_stop)
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mirror_settings)
        val appBarLayout = findViewById<android.view.View>(R.id.appBarLayout)
        val scrollView = findViewById<android.view.View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, scrollView)

        devicePrefManager = DevicePrefManager(this)
        globalPrefs = devicePrefManager.getGlobalPrefs()
        deviceActionManager = DeviceActionManager(this, null) // Will update if needed, but accessibility doesn't need userService
        initViews()

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.menu_mirroring)

        setupListeners()
        bindToService()

        if (intent.getBooleanExtra("request_mirror", false)) {
            Log.d(
                    "MirrorSettingsActivity",
                    "Received mirror request, delaying projection permission by 1s for stability"
            )
            android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(
                            {
                                if (!MirroringService.isRunning) {
                                    startPhoneMirroring()
                                }
                            },
                            1000
                    )
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        imgStatus = findViewById(R.id.imgStatus)
        tvStatus = findViewById(R.id.tvStatus)
        btnStartMirror = findViewById(R.id.btnStartMirror)
        btnControlWatch = findViewById(R.id.btnControlWatch)
        switchEnableTouch = findViewById(R.id.switchEnableTouch)
        switchResMatching = findViewById(R.id.switchResMatching)
        switchAspectMatching = findViewById(R.id.switchAspectMatching)

        switchResMatching.isChecked = activePrefs.getBoolean("mirror_res_matching", false)
        switchAspectMatching.isChecked = activePrefs.getBoolean("mirror_aspect_matching", false)
        switchEnableTouch.isChecked = activePrefs.getBoolean("mirror_enable_touch", true)
    }

    private fun setupListeners() {
        btnStartMirror.setOnClickListener {
            runIfConnected {
                if (MirroringService.isRunning) {
                    stopPhoneMirroring()
                } else {
                    startPhoneMirroring()
                }
            }
        }

        btnControlWatch.setOnClickListener {
            runIfConnected {
                val resMatching = activePrefs.getBoolean("mirror_res_matching", false)
                val aspectMatching = activePrefs.getBoolean("mirror_aspect_matching", false)

                if (resMatching || aspectMatching) {
                    val metrics = resources.displayMetrics
                    val mode =
                            if (resMatching) ResolutionData.MODE_RESOLUTION
                            else ResolutionData.MODE_ASPECT
                    bluetoothService?.sendMessage(
                            ProtocolHelper.createMirrorStart(
                                    metrics.widthPixels,
                                    metrics.heightPixels,
                                    metrics.densityDpi,
                                    isRequest = true,
                                    mode = mode
                            )
                    )
                } else {
                    bluetoothService?.sendMessage(
                            ProtocolHelper.createMirrorStart(0, 0, 0, isRequest = true)
                    )
                }
                Toast.makeText(this, getString(R.string.mirror_requesting_watch), Toast.LENGTH_SHORT).show()
            }
        }

        switchResMatching.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("mirror_res_matching", isChecked).apply()
            if (isChecked) {
                switchAspectMatching.isChecked = false
            }
        }

        switchAspectMatching.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("mirror_aspect_matching", isChecked).apply()
            if (isChecked) {
                switchResMatching.isChecked = false
            }
        }

        switchEnableTouch.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean("mirror_enable_touch", isChecked).apply()
        }
    }

    private fun startPhoneMirroring() {
        val projectionManager =
                getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as
                        android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        
        // Automate allow dialog (Entire screen + Start now)
        deviceActionManager.automateMirroringAllow()
    }

    private fun stopPhoneMirroring() {
        val intent = Intent(this, MirroringService::class.java)
        stopService(intent)
        btnStartMirror.text = getString(R.string.mirror_start)
    }

    private fun bindToService() {
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun runIfConnected(action: () -> Unit) {
        if (bluetoothService?.isConnected == true) {
            action()
        } else {
            Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT)
                    .show()
        }
    }

    private fun updateUI(isConnected: Boolean) {
        if (isConnected) {
            tvStatus.text = getString(R.string.status_connected)
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            imgStatus.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            imgStatus.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        if (MirroringService.isRunning) {
            btnStartMirror.text = getString(R.string.mirror_stop)
        } else {
            btnStartMirror.text = getString(R.string.mirror_start)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private val mirrorStateReceiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateUI(bluetoothService?.isConnected == true)
                }
            }

    override fun onResume() {
        super.onResume()
        if (isBound) {
            bluetoothService?.callback = this
            updateUI(bluetoothService?.isConnected == true)
        }
        androidx.core.content.ContextCompat.registerReceiver(
                this,
                mirrorStateReceiver,
                android.content.IntentFilter(MirroringService.ACTION_MIRROR_STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(mirrorStateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    // Service Callbacks
    override fun onStatusChanged(status: String) {
        runOnUiThread { updateUI(bluetoothService?.isConnected == true) }
    }
    override fun onDeviceConnected(deviceName: String) {
        runOnUiThread { updateUI(true) }
    }
    override fun onDeviceDisconnected() {
        runOnUiThread { updateUI(false) }
    }
    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
    override fun onScanResult(devices: List<android.bluetooth.BluetoothDevice>) {}
    override fun onUploadProgress(progress: Int) {}
    override fun onDownloadProgress(progress: Int, file: java.io.File?) {}
    override fun onFileListReceived(path: String, filesJson: String) {}
    override fun onAppListReceived(appsJson: String) {}
    override fun onWatchStatusUpdated(
            batteryLevel: Int,
            isCharging: Boolean,
            wifiSsid: String,
            wifiEnabled: Boolean,
            dndEnabled: Boolean,
            ipAddress: String?,
            wifiState: String?
    ) {}
}
