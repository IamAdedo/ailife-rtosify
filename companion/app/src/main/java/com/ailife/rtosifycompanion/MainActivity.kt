package com.ailife.rtosifycompanion

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
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    // UI References
    private lateinit var tvHeaderDeviceName: TextView
    private lateinit var tvLocalBtName: TextView
    private lateinit var tvLocalBtNameWatch: TextView
    private lateinit var tvHeaderStatus: TextView
    private lateinit var progressBarMain: ProgressBar
    private lateinit var recyclerViewMenu: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var mainContentScrollView: NestedScrollView
    private lateinit var switchService: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var switchServiceWatch:
            com.google.android.material.materialswitch.MaterialSwitch

    // Watch Status UI
    private lateinit var cardWatchStatus: MaterialCardView
    private lateinit var tvBatteryPercent: TextView
    private lateinit var imgBatteryIcon: ImageView
    private lateinit var tvWifiSsid: TextView
    private lateinit var imgWifiIcon: ImageView
    private lateinit var layoutDndAction: LinearLayout
    private lateinit var tvDndStatus: TextView
    private lateinit var imgDndIcon: ImageView

    // Watch Mode UI
    private lateinit var layoutWatchMode: LinearLayout
    private lateinit var layoutConnectionHeader: LinearLayout
    private lateinit var imgWatchStatus: ImageView
    private lateinit var tvWatchStatusBig: TextView
    private lateinit var tvPhoneBattery: TextView // New UI for Phone Battery
    private lateinit var imgPhoneBattery: ImageView

    private lateinit var prefs: SharedPreferences
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var isPhoneMode = true
    private var menuAdapter: MenuAdapter? = null

    // Local DND state
    private var currentDndState = false

    // Upload Dialog
    private var uploadDialog: AlertDialog? = null
    private var uploadProgressBar: ProgressBar? = null
    private var uploadPercentageText: TextView? = null
    private var uploadDescriptionText: TextView? = null
    private var uploadTitleText: TextView? = null
    private var uploadIconView: ImageView? = null
    private var uploadOkButton: Button? = null

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
                        if (type == "PHONE") bluetoothService?.startSmartphoneLogic()
                        else bluetoothService?.startWatchLogic()
                    } else {
                        bluetoothService?.stopConnectionLoopOnly()
                        updateStatusUI(getString(R.string.status_stopped), false)
                    }

                    updateStatusUI(
                            bluetoothService?.currentStatus ?: getString(R.string.status_starting),
                            bluetoothService?.isConnected == true
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

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_title)

        isPhoneMode = prefs.getString("device_type", "PHONE") == "PHONE"
        setupLayoutMode()
        setupDndClickListener()

        bindToService()

        setupServiceToggle()

        if (intent?.getBooleanExtra("request_mirror", false) == true) {
            startWatchMirroring()
        }
    }

    override fun onNewIntent(intent: Intent?) {
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
                    bluetoothService?.isConnected == true
            )
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
        toolbar = findViewById(R.id.toolbar)
        appBarLayout = findViewById(R.id.appBarLayout)
        mainContentScrollView = findViewById(R.id.mainContentScrollView)

        tvHeaderDeviceName = findViewById(R.id.tvHeaderDeviceName)
        tvLocalBtName = findViewById(R.id.tvLocalBtName)
        tvLocalBtNameWatch = findViewById(R.id.tvLocalBtNameWatch)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        progressBarMain = findViewById(R.id.progressBarMain)
        recyclerViewMenu = findViewById(R.id.recyclerViewMenu)

        // Views Status Watch
        cardWatchStatus = findViewById(R.id.cardWatchStatus)
        tvBatteryPercent = findViewById(R.id.tvBatteryPercent)
        imgBatteryIcon = findViewById(R.id.imgBatteryIcon)
        tvWifiSsid = findViewById(R.id.tvWifiSsid)
        imgWifiIcon = findViewById(R.id.imgWifiIcon)
        layoutDndAction = findViewById(R.id.layoutDndAction)
        tvDndStatus = findViewById(R.id.tvDndStatus)
        imgDndIcon = findViewById(R.id.imgDndIcon)

        layoutWatchMode = findViewById(R.id.layoutWatchMode)
        layoutConnectionHeader = findViewById(R.id.layoutConnectionHeader)
        imgWatchStatus = findViewById(R.id.imgWatchStatus)
        tvWatchStatusBig = findViewById(R.id.tvWatchStatusBig)
        tvWatchStatusBig = findViewById(R.id.tvWatchStatusBig)
        tvPhoneBattery = findViewById(R.id.tvPhoneBattery)
        imgPhoneBattery = findViewById(R.id.imgPhoneBattery)
        switchService = findViewById(R.id.switchService)
        switchServiceWatch = findViewById(R.id.switchServiceWatch)

        updateLocalBtName()
    }

    private fun setupLayoutMode() {
        if (isPhoneMode) {
            layoutWatchMode.visibility = View.GONE
            layoutConnectionHeader.visibility = View.VISIBLE
            appBarLayout.visibility = View.VISIBLE
            mainContentScrollView.visibility = View.VISIBLE
            setupPhoneMenu()
        } else {
            layoutWatchMode.visibility = View.VISIBLE
            layoutConnectionHeader.visibility = View.GONE
            appBarLayout.visibility = View.GONE
            mainContentScrollView.visibility = View.VISIBLE
            setupWatchMenu()
        }
    }

    private fun setupDndClickListener() {
        layoutDndAction.setOnClickListener {
            // We also apply the secure check here
            runIfConnected {
                val newState = !currentDndState
                bluetoothService?.sendDndCommand(newState)
                updateDndUI(newState)
            }
        }
    }

    private fun setupServiceToggle() {
        val isEnabled = prefs.getBoolean("service_enabled", true)
        switchService.isChecked = isEnabled
        switchServiceWatch.isChecked = isEnabled

        val listener =
                android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
                    // Prevent recursive updates if we were to sync them, but here we just update
                    // pref and logic
                    if (prefs.getBoolean("service_enabled", true) == isChecked)
                            return@OnCheckedChangeListener

                    prefs.edit().putBoolean("service_enabled", isChecked).apply()

                    // Sync visual state of the other switch just in case
                    switchService.isChecked = isChecked
                    switchServiceWatch.isChecked = isChecked

                    if (isChecked) {
                        // Ensure service is started and bound
                        val intent = Intent(this@MainActivity, BluetoothService::class.java)
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                        if (!isBound) {
                            bindService(intent, connection, BIND_AUTO_CREATE)
                        }

                        if (isPhoneMode) bluetoothService?.startSmartphoneLogic()
                        else bluetoothService?.startWatchLogic()

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

        switchService.setOnCheckedChangeListener(listener)
        switchServiceWatch.setOnCheckedChangeListener(listener)
    }

    private val screenCaptureLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK && result.data != null) {
                    val intent =
                            Intent(this, MirroringService::class.java).apply {
                                putExtra(MirroringService.EXTRA_RESULT_CODE, result.resultCode)
                                putExtra(MirroringService.EXTRA_DATA, result.data)
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
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
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

    private fun setupPhoneMenu() {
        recyclerViewMenu.layoutManager = LinearLayoutManager(this)
        recyclerViewMenu.isNestedScrollingEnabled = false

        val options =
                listOf(
                        // Watch app - simplified menu without phone-only features
                        MenuOption(
                                getString(R.string.menu_disconnect),
                                getString(R.string.menu_disconnect_desc),
                                android.R.drawable.ic_menu_close_clear_cancel,
                                // PERMITIDO: O usuário precisa poder parar a busca/serviço mesmo se
                                // não conectou
                                {
                                    // Legacy disconnect button removed functionality
                                }
                        ),
                        MenuOption(
                                getString(R.string.perm_title),
                                getString(R.string.perm_not_granted), // Placeholder description
                                android.R.drawable.ic_menu_manage,
                                { startActivity(Intent(this, PermissionActivity::class.java)) }
                        ),
                        MenuOption(
                                getString(R.string.menu_reset_all),
                                getString(R.string.menu_reset_all_desc),
                                android.R.drawable.ic_menu_delete,
                                { resetApp() }
                        )
                )
        menuAdapter = MenuAdapter(options.toMutableList())
        recyclerViewMenu.adapter = menuAdapter
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
        val isConnected = bluetoothService?.isConnected == true
        val isActive = bluetoothService?.isActive() == true

        val options = mutableListOf<MenuOption>()

        if (isPhoneMode) {
            options.add(
                    MenuOption(
                            getString(R.string.perm_title),
                            getString(R.string.perm_not_granted),
                            android.R.drawable.ic_menu_manage,
                            { startActivity(Intent(this, PermissionActivity::class.java)) }
                    )
            )
        } else {
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
        }

        // Connect/Disconnect options removed in favor of toggle switch

        if (!isPhoneMode) {
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
        }

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
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_find_phone))
                .setMessage(
                        getString(R.string.dialog_find_watch_message)
                .setPositiveButton(getString(R.string.dialog_find_watch_start)) { _, _ ->
                    bluetoothService?.sendMessage(ProtocolHelper.createFindPhone(true))
                }
                .setNeutralButton(getString(R.string.dialog_find_watch_stop)) { _, _ ->
                    bluetoothService?.sendMessage(ProtocolHelper.createFindPhone(false))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun bindToService() {
        if (!prefs.getBoolean("service_enabled", true)) return

        val intent = Intent(this, BluetoothService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun updateStatusUI(status: String, isConnected: Boolean) {
        val deviceName =
                bluetoothService?.currentDeviceName ?: getString(R.string.device_name_default)
        tvHeaderDeviceName.text =
                if (isConnected) deviceName else getString(R.string.status_waiting)
        tvHeaderStatus.text = if (isConnected) getString(R.string.status_connected) else status
        tvHeaderStatus.setTextColor(if (isConnected) Color.GREEN else Color.RED)

        // Update local BT name
        updateLocalBtName()

        progressBarMain.visibility =
                if (!isConnected && status.contains(getString(R.string.status_connecting))) View.VISIBLE else View.INVISIBLE

        if (isPhoneMode && isConnected) {
            cardWatchStatus.visibility = View.VISIBLE
        } else {
            cardWatchStatus.visibility = View.GONE
        }

        if (!isPhoneMode) {
            if (isConnected) {
                tvWatchStatusBig.text = getString(R.string.status_connected)
                tvWatchStatusBig.setTextColor(Color.GREEN)
                imgWatchStatus.setImageTintList(ColorStateList.valueOf(Color.GREEN))
            } else {
                tvWatchStatusBig.text = getString(R.string.status_disconnected)
                tvWatchStatusBig.setTextColor(Color.RED)
                imgWatchStatus.setImageTintList(ColorStateList.valueOf(Color.RED))
            }
        }

        refreshMenu()
    }

    private fun updateWatchStatusCard(
            battery: Int,
            isCharging: Boolean,
            wifi: String,
            dnd: Boolean
    ) {
        tvBatteryPercent.text = "$battery%"
        if (isCharging) {
            imgBatteryIcon.setImageResource(android.R.drawable.ic_lock_idle_charging)
        } else {
            imgBatteryIcon.setImageResource(R.drawable.ic_battery_full)
        }
        tvWifiSsid.text = wifi.ifEmpty { "---" }
        updateDndUI(dnd)
    }

    private fun updateDndUI(enabled: Boolean) {
        currentDndState = enabled
        layoutDndAction.alpha = 1.0f

        if (enabled) {
            tvDndStatus.text = getString(R.string.dnd_on)
        } else {
            tvDndStatus.text = getString(R.string.dnd_off)
        }
    }

    private fun resetApp() {
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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

        uploadOkButton?.setOnClickListener { dismissUploadDialog() }
        uploadDialog = AlertDialog.Builder(this).setView(dialogView).setCancelable(false).create()
        uploadDialog?.show()
    }

    private fun updateUploadProgress(progress: Int) {
        when (progress) {
            in 0..99 -> {
                uploadProgressBar?.progress = progress
                uploadPercentageText?.text = "$progress%"
                uploadDescriptionText?.text = getString(R.string.upload_transferring)
            }
            100 -> {
                uploadProgressBar?.progress = 100
                uploadPercentageText?.text = "100%"
                uploadTitleText?.text = getString(R.string.upload_complete_title)
                uploadDescriptionText?.text = getString(R.string.upload_complete_message)
                uploadIconView?.setImageResource(android.R.drawable.stat_sys_upload_done)
                uploadIconView?.setColorFilter(Color.GREEN)
                uploadPercentageText?.setTextColor(Color.GREEN)
                uploadProgressBar?.visibility = View.GONE
                uploadOkButton?.visibility = View.VISIBLE
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
    }

    private fun updateLocalBtName() {
        try {
            val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            val name = btAdapter?.name ?: "Unknown"
            val text = getString(R.string.bt_name_format, name)

            tvLocalBtName.text = text
            tvLocalBtName.visibility = View.VISIBLE

            tvLocalBtNameWatch.text = text
            tvLocalBtNameWatch.visibility = View.VISIBLE
        } catch (e: Exception) {
            tvLocalBtName.visibility = View.GONE
            tvLocalBtNameWatch.visibility = View.GONE
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread { updateStatusUI(status, bluetoothService?.isConnected == true) }
    }
    override fun onDeviceConnected(deviceName: String) {
        runOnUiThread { updateStatusUI(getString(R.string.status_connected), true) }
    }
    override fun onDeviceDisconnected() {
        runOnUiThread {
            updateStatusUI(getString(R.string.status_disconnected), false)
            if (uploadDialog?.isShowing == true) {
                // If the progress is already 100%, do not treat the disconnection as a failure.
                if (uploadProgressBar?.progress != 100) {
                    updateUploadProgress(-1)
                }
            }
        }
    }

    override fun onPhoneBatteryUpdated(battery: Int, isCharging: Boolean) {
        runOnUiThread {
            tvPhoneBattery.text = "$battery%"
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
        if (isPhoneMode) return // Don't poll if we are the phone

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
        runOnUiThread {
            if (devices.isEmpty()) return@runOnUiThread
            if (isPhoneMode) {
                val names =
                        devices
                                .map {
                                    "${it.name ?: getString(R.string.device_no_name)} (${it.address})"
                                }
                                .toTypedArray()
                AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_select_watch_title))
                        .setItems(names) { _, which ->
                            bluetoothService?.connectToDevice(devices[which])
                        }
                        .show()
            }
        }
    }
    override fun onUploadProgress(progress: Int) {
        runOnUiThread { if (uploadDialog?.isShowing == true) updateUploadProgress(progress) }
    }
    override fun onDownloadProgress(progress: Int) {}
    override fun onFileListReceived(path: String, filesJson: String) {}
    override fun onAppListReceived(appsJson: String) {}

    override fun onWatchStatusUpdated(
            batteryLevel: Int,
            isCharging: Boolean,
            wifiSsid: String,
            wifiEnabled: Boolean,
            dndEnabled: Boolean
    ) {
        runOnUiThread { updateWatchStatusCard(batteryLevel, isCharging, wifiSsid, dndEnabled) }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissUploadDialog()
        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }

    private fun syncDynamicIslandService() {
        val isServiceEnabled = prefs.getBoolean("service_enabled", true)
        val isDynamicIsland = prefs.getString("notification_style", "android") == "dynamic_island"

        if (isServiceEnabled && isDynamicIsland) {
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
