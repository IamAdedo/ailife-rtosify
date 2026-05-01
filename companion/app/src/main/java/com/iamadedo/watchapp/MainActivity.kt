package com.iamadedo.watchapp

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.button.MaterialButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView
import org.woheller69.freeDroidWarn.FreeDroidWarn


class MainActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    // UI References

    private lateinit var progressBarMain: LinearProgressIndicator
    private lateinit var recyclerViewMenu: RecyclerView


    private lateinit var mainContentScrollView: NestedScrollView

    private lateinit var switchServiceWatch:
            com.google.android.material.materialswitch.MaterialSwitch

    // Watch Status UI



    // Watch Mode UI
    private lateinit var layoutWatchMode: LinearLayout

    private lateinit var imgWatchStatus: ImageView
    private lateinit var tvWatchStatusBig: TextView
    private lateinit var tvPhoneBattery: TextView // New UI for Phone Battery
    private lateinit var imgPhoneBattery: ImageView

    private lateinit var tvLocalBtNameWatch: TextView
    private var isLiteModeEnabled = false

    private val liteModeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == BluetoothService.ACTION_LITE_MODE_UPDATE) {
                isLiteModeEnabled = intent.getBooleanExtra("enabled", false)
                updateLiteModeUI()
            }
        }
    }

            
    private lateinit var prefs: SharedPreferences
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    // isPhoneMode removed - always Watch
    private var menuAdapter: MenuAdapter? = null

    // Local DND state
    private var currentDndState = false

    // Upload Dialog
    private var uploadDialog: AlertDialog? = null
    private var uploadProgressBar: LinearProgressIndicator? = null
    private var uploadPercentageText: TextView? = null
    private var uploadDescriptionText: TextView? = null
    private var uploadTitleText: TextView? = null
    private var uploadIconView: ImageView? = null
    private var uploadOkButton: MaterialButton? = null
    private var uploadCancelButton: MaterialButton? = null

    private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(className: ComponentName, service: IBinder) {
                    val binder = service as BluetoothService.LocalBinder
                    bluetoothService = binder.getService()
                    bluetoothService?.callback = this@MainActivity
                    isBound = true

                    val type = prefs.getString("device_type", "PHONE")
                    val isServiceEnabled = prefs.getBoolean("service_enabled", true)

                    if (isServiceEnabled) {
                        // Companion app is always watch, never phone
                        bluetoothService?.startWatchLogic()
                    } else {
                        bluetoothService?.stopConnectionLoopOnly()
                        updateStatusUI(getString(R.string.status_stopped), false)
                    }

                    updateStatusUI(
                            bluetoothService?.currentStatus ?: getString(R.string.status_starting),
                            bluetoothService?.isConnected == true,
                            bluetoothService?.currentTransportType ?: ""
                    )
                }

                override fun onServiceDisconnected(arg0: ComponentName) {
                    isBound = false
                    bluetoothService = null
                }
            }

    private val pickApkLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let { confirmApkUpload(it) }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        if (!prefs.contains("device_type") || hasMissingPermissions()) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        initViews()
        
        isLiteModeEnabled = prefs.getBoolean("lite_mode_enabled", false)
        updateLiteModeUI()
        
        EdgeToEdgeUtils.applyEdgeToEdge(this, findViewById(R.id.mainContentScrollView))



        // Always Watch Mode
        setupLayoutMode()


        bindToService()

        bindToService()

        setupServiceToggle()
        
        val filter = android.content.IntentFilter(BluetoothService.ACTION_LITE_MODE_UPDATE)
        ContextCompat.registerReceiver(this, liteModeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (intent?.getBooleanExtra("request_mirror", false) == true) {
            startWatchMirroring()
        }

        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE)
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("request_mirror", false) == true) {
            startWatchMirroring()
        }

    }



    override fun onResume() {
        super.onResume()
        // When the activity returns to focus, it is crucial to re-register as the callback
        // and update the UI with the latest service state.
        updateLocalBtName()
        if (isBound) {
            bluetoothService?.callback = this
            // Force UI update with current service data
            updateStatusUI(
                    bluetoothService?.currentStatus ?: getString(R.string.status_verifying),
                    bluetoothService?.isConnected == true,
                    bluetoothService?.currentTransportType ?: ""
            )
        }
        if (prefs.getBoolean("aggressive_keepalive_enabled", false)) {
            KeepaliveReceiver.schedule(this)
        }
        syncDynamicIslandService()
        startPhoneBatteryPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPhoneBatteryPolling()
    }

    private fun hasMissingPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initViews() {


        mainContentScrollView = findViewById(R.id.mainContentScrollView)

        tvLocalBtNameWatch = findViewById(R.id.tvLocalBtNameWatch)
        progressBarMain = findViewById<LinearProgressIndicator>(R.id.progressBarMain)
        recyclerViewMenu = findViewById(R.id.recyclerViewMenu)

        recyclerViewMenu = findViewById(R.id.recyclerViewMenu)
        recyclerViewMenu.layoutManager = LinearLayoutManager(this)
        recyclerViewMenu.isNestedScrollingEnabled = false



        layoutWatchMode = findViewById(R.id.layoutWatchMode)

        imgWatchStatus = findViewById(R.id.imgWatchStatus)
        tvWatchStatusBig = findViewById(R.id.tvWatchStatusBig)
        tvWatchStatusBig = findViewById(R.id.tvWatchStatusBig)
        tvPhoneBattery = findViewById(R.id.tvPhoneBattery)
        imgPhoneBattery = findViewById(R.id.imgPhoneBattery)

        switchServiceWatch = findViewById(R.id.switchServiceWatch)


        updateLocalBtName()
    }

    private fun setupLayoutMode() {
        // Enforce Watch Layout
        layoutWatchMode.visibility = View.VISIBLE

        mainContentScrollView.visibility = View.VISIBLE
        setupWatchMenu()
    }



    private fun setupServiceToggle() {
        val isEnabled = prefs.getBoolean("service_enabled", true)

        switchServiceWatch.isChecked = isEnabled

        val listener =
                android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
                    // Prevent recursive updates if we were to sync them, but here we just update
                    // pref and logic
                    if (prefs.getBoolean("service_enabled", true) == isChecked)
                            return@OnCheckedChangeListener

                    prefs.edit().putBoolean("service_enabled", isChecked).apply()

                    // Sync visual state of the other switch just in case

                    switchServiceWatch.isChecked = isChecked

                    if (isChecked) {
                        // Ensure service is started and bound
                        val intent = Intent(this@MainActivity, BluetoothService::class.java)
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                        if (!isBound) {
                            bindService(intent, connection, BIND_AUTO_CREATE)
                        }

                        // Companion app is always watch, never phone
                        bluetoothService?.startWatchLogic()

                        updateStatusUI(getString(R.string.status_starting), false)
                        syncDynamicIslandService()
                        Toast.makeText(this@MainActivity, getString(R.string.toast_service_started), Toast.LENGTH_SHORT)
                                .show()
                    } else {
                            bluetoothService?.stopServiceCompletely()
                        if (isBound) {
                            unbindService(connection)
                            isBound = false
                            bluetoothService = null
                        }
                        updateStatusUI(getString(R.string.status_stopped), false)
                        syncDynamicIslandService()
                        Toast.makeText(this, getString(R.string.toast_service_stopped), Toast.LENGTH_SHORT).show()
                    }
                    refreshMenu()
                }


        switchServiceWatch.setOnCheckedChangeListener(listener)
    }

    private val screenCaptureLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK && result.data != null) {
                    // Check if high quality mode should be enabled (LAN connected)
                    val isLanConnected = bluetoothService?.isLanConnected() == true
                    val hqEnabled = prefs.getBoolean("hq_lan_enabled", false)
                    val useHighQuality = isLanConnected && hqEnabled

                    val intent =
                            Intent(this, MirroringService::class.java).apply {
                                putExtra(MirroringService.EXTRA_RESULT_CODE, result.resultCode)
                                putExtra(MirroringService.EXTRA_DATA, result.data)
                                putExtra(MirroringService.EXTRA_HIGH_QUALITY, useHighQuality)
                            }
                    ContextCompat.startForegroundService(this, intent)

                    // Send message to phone to open MirrorActivity
                    val metrics = resources.displayMetrics
                    bluetoothService?.sendMessage(
                            ProtocolHelper.createMirrorStart(
                                    metrics.widthPixels,
                                    metrics.heightPixels,
                                    metrics.densityDpi
                            )
                    )
                }
            }

    private fun startWatchMirroring() {
        val projectionManager =
                getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as
                        android.media.projection.MediaProjectionManager
        try {
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to launch screen capture: ${e.message}")
            Toast.makeText(this, "Failed to start mirroring: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- NEW SECURITY FUNCTION ---
    // Checks if connected before executing the action.
    private fun runIfConnected(action: () -> Unit) {
        if (bluetoothService?.isConnected == true) {
            action()
        } else {
            Toast.makeText(this, getString(R.string.toast_phone_not_connected), Toast.LENGTH_SHORT).show()
        }
    }



    private fun setupWatchMenu() {
        recyclerViewMenu.layoutManager = LinearLayoutManager(this)
        recyclerViewMenu.isNestedScrollingEnabled = false

        val options =
                mutableListOf(
                        MenuOption(
                                getString(R.string.menu_media_control),
                                getString(R.string.menu_media_control_desc),
                                android.R
                                        .drawable
                                        .ic_media_play, // Using a standard system drawable
                                {
                                    runIfConnected {
                                        startActivity(
                                                Intent(this, MediaControlActivity::class.java)
                                        )
                                    }
                                }
                        ),
                        MenuOption(
                                getString(R.string.menu_mirroring),
                                getString(R.string.menu_mirroring_desc),
                                R.drawable.ic_cast,
                                { startActivity(Intent(this, MirrorSettingsActivity::class.java)) }
                        ),
                        MenuOption(
                                getString(R.string.menu_alarms),
                                getString(R.string.menu_alarms_desc),
                                android.R.drawable.ic_lock_idle_alarm,
                                { startActivity(Intent(this, AlarmManagementActivity::class.java)) }
                        ),
                        MenuOption(
                                getString(R.string.menu_camera),
                                getString(R.string.menu_camera_desc),
                                android.R.drawable.ic_menu_camera,
                                {
                                    runIfConnected {
                                        startActivity(
                                                Intent(this, CameraRemoteActivity::class.java)
                                        )
                                    }
                                }
                        ),
                        MenuOption(
                                getString(R.string.menu_dialer),
                                getString(R.string.menu_dialer_desc),
                                android.R.drawable.ic_menu_call,
                                {
                                    runIfConnected {
                                        startActivity(Intent(this, DialerActivity::class.java))
                                    }
                                }
                        ),
                        MenuOption(
                                getString(R.string.menu_phone_settings),
                                getString(R.string.menu_phone_settings_desc),
                                android.R.drawable.ic_lock_silent_mode,
                                {
                                    runIfConnected {
                                        startActivity(Intent(this, PhoneSettingsActivity::class.java))
                                    }
                                }
                        ),
                        MenuOption(
                                getString(R.string.menu_notification_log),
                                getString(R.string.menu_notification_log_desc),
                                android.R.drawable.ic_popup_reminder,
                                { startActivity(Intent(this, NotificationLogActivity::class.java)) }
                        ),
                        MenuOption(
                                getString(R.string.menu_video_helper),
                                getString(R.string.menu_video_helper_desc),
                                R.drawable.ic_settings_remote,
                                {
                                    runIfConnected {
                                        startActivity(Intent(this, VideoHelperActivity::class.java))
                                    }
                                }
                        )
                )

        val isConnected = bluetoothService?.isConnected == true
        val isActive = bluetoothService?.isActive() == true

        if (isActive || isConnected) {
            options.add(
                    MenuOption(
                            getString(R.string.menu_disconnect),
                            getString(R.string.menu_disconnect_desc),
                            android.R.drawable.ic_menu_close_clear_cancel,
                            {
                                bluetoothService?.stopConnectionLoopOnly()
                                updateStatusUI(getString(R.string.status_stopped), false)
                            }
                    )
            )
        } else {
            options.add(
                    MenuOption(
                            getString(R.string.menu_connect),
                            getString(R.string.menu_connect_desc),
                            android.R.drawable.ic_menu_view,
                            {
                                bluetoothService?.startWatchLogic()
                                updateStatusUI(getString(R.string.status_starting), false)
                            }
                    )
            )
        }

        options.add(
                MenuOption(
                        getString(R.string.menu_find_phone),
                        getString(R.string.menu_find_phone_desc),
                        android.R.drawable.ic_menu_search,
                        { runIfConnected { confirmFindPhone() } }
                )
        )
        options.add(
                MenuOption(
                        getString(R.string.perm_title),
                        getString(R.string.perm_shizuku_desc),
                        android.R.drawable.ic_menu_manage,
                        { startActivity(Intent(this, PermissionActivity::class.java)) }
                )
        )

        options.add(
                MenuOption(
                        getString(R.string.menu_reset_all),
                        getString(R.string.menu_reset_all_desc),
                        android.R.drawable.ic_menu_delete,
                        { resetApp() }
                )
        )

        menuAdapter = MenuAdapter(options)
        recyclerViewMenu.adapter = menuAdapter
    }

    private fun refreshMenu() {
        val options = mutableListOf<MenuOption>()

        if (isLiteModeEnabled) {
             // LITE MODE: Only show Reset All
             options.add(
                MenuOption(
                        getString(R.string.menu_reset_all),
                        getString(R.string.menu_reset_all_desc),
                        android.R.drawable.ic_menu_delete,
                        { resetApp() }
                )
             )
             menuAdapter?.updateOptions(options)
             return
        }

        // Watch Menu Options
        options.add(
                MenuOption(
                        getString(R.string.menu_media_control),
                        getString(R.string.menu_media_control_desc),
                        android.R.drawable.ic_media_play,
                        {
                            runIfConnected {
                                startActivity(Intent(this, MediaControlActivity::class.java))
                            }
                        }
                )
        )
        options.add(
                MenuOption(
                        getString(R.string.menu_mirroring),
                        getString(R.string.menu_mirroring_desc),
                        R.drawable.ic_cast,
                        { startActivity(Intent(this, MirrorSettingsActivity::class.java)) }
                )
        )
        options.add(
                MenuOption(
                        getString(R.string.menu_alarms),
                        getString(R.string.menu_alarms_desc),
                        android.R.drawable.ic_lock_idle_alarm,
                        { startActivity(Intent(this, AlarmManagementActivity::class.java)) }
                )
        )
        options.add(
                MenuOption(
                        getString(R.string.menu_camera),
                        getString(R.string.menu_camera_desc),
                        android.R.drawable.ic_menu_camera,
                        {
                            runIfConnected {
                                startActivity(Intent(this, CameraRemoteActivity::class.java))
                            }
                        }
                )
        )
        options.add(
                MenuOption(
                        getString(R.string.menu_dialer),
                        getString(R.string.menu_dialer_desc),
                        android.R.drawable.ic_menu_call,
                        {
                            runIfConnected {
                                startActivity(Intent(this, DialerActivity::class.java))
                            }
                        }
                )
        )

        options.add(
                MenuOption(
                        getString(R.string.menu_phone_settings),
                        getString(R.string.menu_phone_settings_desc),
                        android.R.drawable.ic_lock_silent_mode,
                        {
                            runIfConnected {
                                startActivity(Intent(this, PhoneSettingsActivity::class.java))
                            }
                        }
                )
        )

        options.add(
                MenuOption(
                        getString(R.string.menu_notification_log),
                        getString(R.string.menu_notification_log_desc),
                        android.R.drawable.ic_popup_reminder,
                        { startActivity(Intent(this, NotificationLogActivity::class.java)) }
                )
        )

        options.add(
                MenuOption(
                        getString(R.string.menu_video_helper),
                        getString(R.string.menu_video_helper_desc),
                        R.drawable.ic_settings_remote,
                        {
                            runIfConnected {
                                startActivity(Intent(this, VideoHelperActivity::class.java))
                            }
                        }
                )
        )

        // Additional Watch Options
        options.add(
                MenuOption(
                        getString(R.string.menu_find_phone),
                        getString(R.string.menu_find_phone_desc),
                        android.R.drawable.ic_menu_search,
                        { runIfConnected { confirmFindPhone() } }
                )
        )
        options.add(
                MenuOption(
                        getString(R.string.perm_title),
                        getString(R.string.perm_shizuku_desc),
                        android.R.drawable.ic_menu_manage,
                        { startActivity(Intent(this, PermissionActivity::class.java)) }
                )
        )

        options.add(
                MenuOption(
                        getString(R.string.menu_reset_all),
                        getString(R.string.menu_reset_all_desc),
                        android.R.drawable.ic_menu_delete,
                        { resetApp() }
                )
                )

        menuAdapter?.updateOptions(options)
    }

    private fun confirmFindPhone() {
        // Launch FindDeviceActivity (Map view)
        // This will start location sharing, but NOT the alarm/ringing.
        startActivity(Intent(this, FindDeviceActivity::class.java))
    }

    private fun bindToService() {
        if (!prefs.getBoolean("service_enabled", true)) return

        val intent = Intent(this, BluetoothService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun updateStatusUI(status: String, isConnected: Boolean, transportType: String = "") {
        // Update local BT name
        updateLocalBtName()

        progressBarMain.visibility =
                if (!isConnected && status.contains(getString(R.string.status_connecting))) View.VISIBLE else View.INVISIBLE

        // Use TransportManager's connection status string for accurate display
        var connectionStatus = bluetoothService?.getConnectionStatusString() ?: status
        if (connectionStatus.isBlank()) {
            connectionStatus = getString(R.string.status_disconnected)
        }

        if (isConnected || connectionStatus.startsWith("Connected")) {
            tvWatchStatusBig.text = connectionStatus
            tvWatchStatusBig.setTextColor(Color.GREEN)
            imgWatchStatus.setImageTintList(ColorStateList.valueOf(Color.GREEN))
        } else {
            tvWatchStatusBig.text = connectionStatus
            tvWatchStatusBig.setTextColor(Color.RED)
            imgWatchStatus.setImageTintList(ColorStateList.valueOf(Color.RED))
        }

        refreshMenu()
    }

    private fun updateWatchStatusCard(
            battery: Int,
            isCharging: Boolean,
            wifiSsid: String,
            dnd: Boolean
    ) {



        updateDndUI(dnd)
    }

    private fun updateDndUI(enabled: Boolean) {
        currentDndState = enabled


        if (enabled) {

        }
    }

    private fun resetApp() {
        MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_reset_all_title))
                .setMessage(getString(R.string.dialog_reset_all_message))
                .setPositiveButton(getString(R.string.dialog_reset_all_confirm)) { _, _ ->
                    bluetoothService?.resetApp()
                    startActivity(Intent(this, WelcomeActivity::class.java))
                    finish()
                }
                .setNegativeButton(getString(R.string.dialog_reset_all_cancel), null)
                .show()
    }

    private fun confirmApkUpload(uri: Uri) {
        MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_upload_apk_title))
                .setMessage(getString(R.string.dialog_upload_apk_message))
                .setPositiveButton(getString(R.string.dialog_upload_apk_send)) { _, _ ->
                    bluetoothService?.sendApkFile(uri)
                    showUploadDialog()
                }
                .setNegativeButton(getString(R.string.dialog_upload_apk_cancel), null)
                .show()
    }

    private fun confirmShutdownWatch() {
        // A verificação de segurança já foi feita no menu (runIfConnected),
        // mas mantemos uma verificação extra por segurança.
        if (bluetoothService?.isConnected != true) {
            Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT)
                    .show()
            return
        }
        MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_shutdown_title))
                .setMessage(getString(R.string.dialog_shutdown_message))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(getString(R.string.dialog_shutdown_confirm)) { _, _ ->
                    bluetoothService?.sendShutdownCommand()
                    Toast.makeText(this, getString(R.string.toast_command_sent), Toast.LENGTH_SHORT)
                            .show()
                }
                .setNegativeButton(getString(R.string.dialog_shutdown_cancel), null)
                .show()
    }

    private fun showUploadDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_upload_progress, null)
        uploadProgressBar = dialogView.findViewById(R.id.progressBarUpload)
        uploadPercentageText = dialogView.findViewById(R.id.tvUploadPercentage)
        uploadDescriptionText = dialogView.findViewById(R.id.tvUploadDescription)
        uploadTitleText = dialogView.findViewById(R.id.tvUploadTitle)
        uploadIconView = dialogView.findViewById(R.id.imgUploadIcon)
        uploadOkButton = dialogView.findViewById(R.id.btnUploadOk)
        uploadCancelButton = dialogView.findViewById(R.id.btnUploadCancel)

        uploadOkButton?.setOnClickListener { dismissUploadDialog() }
        uploadCancelButton?.setOnClickListener {
            bluetoothService?.cancelTransfer()
            dismissUploadDialog()
        }
        uploadDialog = MaterialAlertDialogBuilder(this).setView(dialogView).setCancelable(false).create()
        uploadDialog?.show()
    }

    private fun updateUploadProgress(progress: Int) {
        when (progress) {
            in 0..99 -> {
                uploadProgressBar?.progress = progress
                uploadPercentageText?.text = getString(R.string.percent_format, progress)
                uploadDescriptionText?.text = getString(R.string.upload_transferring)
            }
            100 -> {
                uploadProgressBar?.progress = 100
                uploadPercentageText?.text = getString(R.string.percent_100)
                uploadTitleText?.text = getString(R.string.upload_complete_title)
                uploadDescriptionText?.text = getString(R.string.upload_complete_message)
                uploadIconView?.setImageResource(android.R.drawable.stat_sys_upload_done)
                uploadIconView?.setColorFilter(Color.GREEN)
                uploadPercentageText?.setTextColor(Color.GREEN)
                uploadProgressBar?.visibility = View.GONE
                uploadOkButton?.visibility = View.VISIBLE
                uploadCancelButton?.visibility = View.GONE
            }
            -1 -> {
                dismissUploadDialog()
                Toast.makeText(this, getString(R.string.toast_upload_failed), Toast.LENGTH_LONG)
                        .show()
            }
        }
    }

    private fun dismissUploadDialog() {
        uploadDialog?.dismiss()
        uploadDialog = null
        uploadCancelButton = null
        uploadOkButton = null
    }

    private fun updateLocalBtName() {
        try {
            val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            val name = btAdapter?.name ?: "Unknown"
            val text = getString(R.string.bt_name_format, name)

            tvLocalBtNameWatch.text = text
            tvLocalBtNameWatch.visibility = View.VISIBLE
        } catch (e: Exception) {
            tvLocalBtNameWatch.visibility = View.GONE
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            updateStatusUI(status, bluetoothService?.isConnected == true)
        }
    }

    override fun onTransportStatusChanged(status: com.iamadedo.watchapp.communication.TransportManager.TransportStatus) {
        runOnUiThread {
            val statusDisplay = bluetoothService?.currentStatus ?: status.typeString
            updateStatusUI(statusDisplay, status.isConnected)
        }
    }
    override fun onDeviceConnected(deviceName: String, transportType: String) {
        runOnUiThread {
            val connectionStatus = bluetoothService?.getConnectionStatusString()
                ?: getString(R.string.status_connected)
            updateStatusUI(connectionStatus, true)
        }
    }
    override fun onDeviceDisconnected() {
        runOnUiThread {
            val connectionStatus = bluetoothService?.getConnectionStatusString()
                ?: getString(R.string.status_disconnected)
            val isConnected = connectionStatus.startsWith("Connected")
            updateStatusUI(connectionStatus, isConnected)
            if (uploadDialog?.isShowing == true) {
                // If the progress is already 100%, do not treat the disconnection as a failure.
                if (uploadProgressBar?.progress != 100) {
                    updateUploadProgress(-1)
                }
            }
        }
    }

    override fun onPhoneBatteryUpdated(battery: Int, isCharging: Boolean) {
        android.util.Log.d("CHARGING_DEBUG", "WATCH onPhoneBatteryUpdated: battery=$battery, isCharging=$isCharging")
        runOnUiThread {
            tvPhoneBattery.text = getString(R.string.percent_format, battery)
            tvPhoneBattery.visibility = View.VISIBLE
            imgPhoneBattery.visibility = View.VISIBLE

            if (isCharging) {
                 imgPhoneBattery.setImageResource(android.R.drawable.ic_lock_idle_charging)
            } else {
                 imgPhoneBattery.setImageResource(R.drawable.ic_battery_full)
            }
        }
    }

    private var phoneBatteryPollingJob: kotlinx.coroutines.Job? = null

    private fun startPhoneBatteryPolling() {
        stopPhoneBatteryPolling()


        phoneBatteryPollingJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
             while (true) {
                 if (bluetoothService?.isConnected == true) {
                     bluetoothService?.requestPhoneBattery()
                 }
                 kotlinx.coroutines.delay(2000) // Poll every 2 seconds
             }
        }
    }

    private fun stopPhoneBatteryPolling() {
        phoneBatteryPollingJob?.cancel()
        phoneBatteryPollingJob = null
    }
    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (uploadDialog?.isShowing == true) updateUploadProgress(-1)
        }
    }
    @android.annotation.SuppressLint("MissingPermission")
    override fun onScanResult(devices: List<BluetoothDevice>) {
        // Companion app is watch-only server, never scans for devices
        // This callback should never be called in watch mode
    }
    override fun onUploadProgress(progress: Int) {
        runOnUiThread {
            if (uploadDialog == null || !uploadDialog!!.isShowing) {
                showUploadDialog()
                uploadTitleText?.text = getString(R.string.upload_sending_to_phone)
            }
            updateUploadProgress(progress)
        }
    }
    override fun onDownloadProgress(progress: Int) {
        runOnUiThread {
            if (uploadDialog == null || !uploadDialog!!.isShowing) {
                showUploadDialog()
                uploadTitleText?.text = getString(R.string.upload_receiving_from_phone)
            }
            updateUploadProgress(progress)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(liteModeReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        dismissUploadDialog()
        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }

    private fun updateLiteModeUI() {
        // Toggle phone battery
        if (isLiteModeEnabled) {
            tvPhoneBattery.visibility = View.GONE
            imgPhoneBattery.visibility = View.GONE
        } else {
             // If connected, it will be shown by onPhoneBatteryUpdated, but we force visible if we have data
             if (bluetoothService?.isConnected == true && tvPhoneBattery.text.isNotEmpty()) {
                 tvPhoneBattery.visibility = View.VISIBLE
                 imgPhoneBattery.visibility = View.VISIBLE
             }
        }
        
        refreshMenu()
    }
    
    override fun onFileListReceived(path: String, filesJson: String) {}


    override fun onWatchStatusUpdated(
            batteryLevel: Int,
            isCharging: Boolean,
            wifiSsid: String,
            wifiEnabled: Boolean,
            dndEnabled: Boolean,
            ipAddress: String?,
            wifiState: String?
    ) {
        val finalDisplay = when(wifiState) {
            "CONNECTED" -> wifiSsid
            "DISCONNECTED" -> getString(R.string.status_disconnected)
            "DISABLED", "OFF" -> getString(R.string.wifi_status_disabled)
            "NO_PERMISSION" -> getString(R.string.status_no_permission)
            else -> if (wifiSsid.isNotEmpty()) wifiSsid else (wifiState ?: getString(R.string.wifi_placeholder))
        }
        
        runOnUiThread { updateWatchStatusCard(batteryLevel, isCharging, finalDisplay, dndEnabled) }
    }

    private fun syncDynamicIslandService() {
        val isDynamicIsland = prefs.getString("notification_style", "android") == "dynamic_island"

        if (isDynamicIsland) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                if (!DynamicIslandService.isRunning) {
                    val intent = Intent(this, DynamicIslandService::class.java)
                    ContextCompat.startForegroundService(this, intent)
                }
            }
        } else {
            if (DynamicIslandService.isRunning) {
                stopService(Intent(this, DynamicIslandService::class.java))
            }
        }
    }
}
