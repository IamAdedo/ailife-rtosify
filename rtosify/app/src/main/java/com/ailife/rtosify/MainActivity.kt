package com.ailife.rtosify

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
import android.os.Bundle
import android.os.Build
import android.os.IBinder
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    // UI References
    private lateinit var tvHeaderDeviceName: TextView
    private lateinit var tvHeaderStatus: TextView
    private lateinit var progressBarMain: ProgressBar
    private lateinit var recyclerViewMenu: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var mainContentScrollView: NestedScrollView
    private lateinit var switchService: com.google.android.material.materialswitch.MaterialSwitch

    // Watch Status UI
    private lateinit var cardWatchStatus: MaterialCardView
    private lateinit var tvBatteryPercent: TextView
    private lateinit var imgBatteryIcon: ImageView
    private lateinit var tvWifiSsid: TextView
    private lateinit var imgWifiIcon: ImageView
    private lateinit var layoutDndAction: LinearLayout
    private lateinit var tvDndStatus: TextView
    private lateinit var imgDndIcon: ImageView

    // Health Data UI
    private lateinit var cardHealthData: MaterialCardView
    private lateinit var tvStepsCount: TextView
    private lateinit var tvStepsDetails: TextView
    private lateinit var tvHeartRate: TextView
    private lateinit var tvHeartRateTime: TextView
    private lateinit var tvOxygenLevel: TextView
    private lateinit var tvOxygenTime: TextView
    private lateinit var layoutStepsAction: LinearLayout
    private lateinit var layoutHeartRateAction: LinearLayout
    private lateinit var layoutOxygenAction: LinearLayout

    // Watch Mode UI
    private lateinit var layoutWatchMode: LinearLayout
    private lateinit var imgWatchStatus: ImageView
    private lateinit var tvWatchStatusBig: TextView

    private lateinit var prefs: SharedPreferences
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var isPhoneMode = true
    private var menuAdapter: MenuAdapter? = null

    // Estado local do DND
    private var currentDndState = false

    // Diálogo Upload
    private var uploadDialog: AlertDialog? = null
    private var uploadProgressBar: ProgressBar? = null
    private var uploadPercentageText: TextView? = null
    private var uploadDescriptionText: TextView? = null
    private var uploadTitleText: TextView? = null
    private var uploadIconView: ImageView? = null
    private var uploadOkButton: Button? = null

    private val connection = object : ServiceConnection {
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

            updateStatusUI(bluetoothService?.currentStatus ?: getString(R.string.status_starting), bluetoothService?.isConnected == true)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        if (!prefs.contains("device_type") || hasMissingPermissions()) {
            if (hasMissingPermissions()) {
                Toast.makeText(this, "Redirecting to setup: Missing permissions", Toast.LENGTH_SHORT).show()
                android.util.Log.d("MainActivity", "Missing permissions, redirecting to WelcomeActivity")
            }
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
        setupHealthClickListeners()

        bindToService()

        setupServiceToggle()
    }

    override fun onResume() {
        super.onResume()
        // Quando a atividade volta ao foco, é crucial se registrar novamente como o callback
        // e atualizar a UI com o estado mais recente do serviço.
        if (isBound) {
            bluetoothService?.callback = this
            // Força a atualização da UI com os dados atuais do serviço
            updateStatusUI(bluetoothService?.currentStatus ?: getString(R.string.status_verifying), bluetoothService?.isConnected == true)
        }
    }

    private fun hasMissingPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        appBarLayout = findViewById(R.id.appBarLayout)
        mainContentScrollView = findViewById(R.id.mainContentScrollView)

        tvHeaderDeviceName = findViewById(R.id.tvHeaderDeviceName)
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

        // Health views
        cardHealthData = findViewById(R.id.cardHealthData)
        tvStepsCount = findViewById(R.id.tvStepsCount)
        tvStepsDetails = findViewById(R.id.tvStepsDetails)
        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvHeartRateTime = findViewById(R.id.tvHeartRateTime)
        tvOxygenLevel = findViewById(R.id.tvOxygenLevel)
        tvOxygenTime = findViewById(R.id.tvOxygenTime)
        layoutStepsAction = findViewById(R.id.layoutStepsAction)
        layoutHeartRateAction = findViewById(R.id.layoutHeartRateAction)
        layoutOxygenAction = findViewById(R.id.layoutOxygenAction)

        layoutWatchMode = findViewById(R.id.layoutWatchMode)
        imgWatchStatus = findViewById(R.id.imgWatchStatus)
        tvWatchStatusBig = findViewById(R.id.tvWatchStatusBig)
        switchService = findViewById(R.id.switchService)
    }

    private fun setupLayoutMode() {
        if (isPhoneMode) {
            layoutWatchMode.visibility = View.GONE
            appBarLayout.visibility = View.VISIBLE
            mainContentScrollView.visibility = View.VISIBLE
            setupPhoneMenu()
        } else {
            layoutWatchMode.visibility = View.VISIBLE
            appBarLayout.visibility = View.GONE
            mainContentScrollView.visibility = View.GONE
        }
    }

    private fun setupDndClickListener() {
        layoutDndAction.setOnClickListener {
            // Também aplicamos a verificação segura aqui
            runIfConnected {
                val newState = !currentDndState
                bluetoothService?.sendDndCommand(newState)
                updateDndUI(newState)
            }
        }
    }

    private fun setupHealthClickListeners() {
        layoutStepsAction.setOnClickListener {
            runIfConnected {
                startActivity(Intent(this, HealthDetailActivity::class.java).apply {
                    putExtra("HEALTH_TYPE", "STEPS")
                })
            }
        }

        layoutHeartRateAction.setOnClickListener {
            runIfConnected {
                startActivity(Intent(this, HealthDetailActivity::class.java).apply {
                    putExtra("HEALTH_TYPE", "HEART_RATE")
                })
            }
        }

        layoutOxygenAction.setOnClickListener {
            runIfConnected {
                startActivity(Intent(this, HealthDetailActivity::class.java).apply {
                    putExtra("HEALTH_TYPE", "OXYGEN")
                })
            }
        }
    }

    private fun updateHealthDataCard(healthData: HealthDataUpdate) {
        // Steps with distance and calories
        tvStepsCount.text = healthData.steps.toString()
        val distance = String.format("%.2f km", healthData.distance)
        val calories = "${healthData.calories} kcal"
        tvStepsDetails.text = "$distance • $calories"

        // Heart Rate
        if (healthData.heartRate != null && healthData.heartRateTimestamp != null) {
            tvHeartRate.text = "${healthData.heartRate} bpm"
            tvHeartRateTime.text = formatTimeAgo(healthData.heartRateTimestamp)
        } else {
            tvHeartRate.text = "--"
            tvHeartRateTime.text = getString(R.string.health_no_data)
        }

        // Blood Oxygen
        if (healthData.bloodOxygen != null && healthData.oxygenTimestamp != null) {
            tvOxygenLevel.text = "${healthData.bloodOxygen}%"
            tvOxygenTime.text = formatTimeAgo(healthData.oxygenTimestamp)
        } else {
            tvOxygenLevel.text = "--"
            tvOxygenTime.text = getString(R.string.health_no_data)
        }

        // Handle error states
        healthData.errorState?.let { error ->
            when (error) {
                "APP_NOT_INSTALLED" -> {
                    tvStepsDetails.text = getString(R.string.health_error_app_not_installed)
                }
                "API_DISABLED" -> {
                    tvStepsDetails.text = getString(R.string.health_error_api_disabled)
                }
            }
        }
    }

    private fun setupServiceToggle() {
        val isEnabled = prefs.getBoolean("service_enabled", true)
        switchService.isChecked = isEnabled
        
        switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            
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
                Toast.makeText(this@MainActivity, "Service Started", Toast.LENGTH_SHORT).show()
            } else {
                bluetoothService?.stopServiceCompletely()
                if (isBound) {
                    unbindService(connection)
                    isBound = false
                    bluetoothService = null
                }
                updateStatusUI(getString(R.string.status_stopped), false)
                Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT).show()
            }
            refreshMenu()
        }
    }

    private fun formatTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60000 -> getString(R.string.time_just_now)
            diff < 3600000 -> getString(R.string.time_minutes_ago, diff / 60000)
            diff < 86400000 -> getString(R.string.time_hours_ago, diff / 3600000)
            else -> getString(R.string.time_days_ago, diff / 86400000)
        }
    }

    // --- NOVA FUNÇÃO DE SEGURANÇA ---
    // Verifica se está conectado antes de executar a ação.
    private fun runIfConnected(action: () -> Unit) {
        if (bluetoothService?.isConnected == true) {
            action()
        } else {
            Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPhoneMenu() {
        recyclerViewMenu.layoutManager = LinearLayoutManager(this)
        recyclerViewMenu.isNestedScrollingEnabled = false

        val options = listOf(
            MenuOption(
                getString(R.string.menu_manage_apps),
                getString(R.string.menu_manage_apps_desc),
                android.R.drawable.ic_menu_sort_by_size,
                // BLOQUEADO: Precisa de conexão para buscar lista
                { runIfConnected { startActivity(Intent(this, AppListActivity::class.java)) } }
            ),
            MenuOption(
                getString(R.string.menu_watchface),
                getString(R.string.wf_title),
                android.R.drawable.ic_menu_gallery,
                { startActivity(Intent(this, com.ailife.rtosify.watchface.WatchFaceActivity::class.java)) }
            ),
            MenuOption(
                getString(R.string.menu_notifications),
                getString(R.string.menu_notifications_desc),
                android.R.drawable.ic_popup_reminder,
                // BLOQUEADO: Geralmente só útil se o watch estiver ativo
                { runIfConnected { startActivity(Intent(this, NotificationSettingsActivity::class.java)) } }
            ),
            MenuOption(
                "File Manager",
                "Browse and manage watch files",
                android.R.drawable.ic_menu_save,
                { runIfConnected { startActivity(Intent(this, FileManagerActivity::class.java)) } }
            ),
            MenuOption(
                getString(R.string.menu_device_mgmt),
                getString(R.string.menu_device_mgmt_desc),
                android.R.drawable.ic_lock_power_off,
                { runIfConnected { showDeviceManagementMenu() } }
            ),
            MenuOption(
                getString(R.string.menu_sync_calendar),
                getString(R.string.menu_sync_calendar_desc),
                android.R.drawable.ic_menu_today,
                { runIfConnected { bluetoothService?.syncCalendar() } }
            ),
            MenuOption(
                getString(R.string.menu_sync_contacts),
                getString(R.string.menu_sync_contacts_desc),
                android.R.drawable.ic_menu_myplaces,
                { runIfConnected { bluetoothService?.syncContacts() } }
            ),
            MenuOption(
                getString(R.string.menu_disconnect),
                getString(R.string.menu_disconnect_desc),
                android.R.drawable.ic_menu_close_clear_cancel,
                // PERMITIDO: O usuário precisa poder parar a busca/serviço mesmo se não conectou
                {
                   // Legacy disconnect button removed
                }
            ),
            MenuOption(
                getString(R.string.perm_title),
                getString(R.string.perm_notifications_desc), // Reusing similar desc for space
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

    private fun refreshMenu() {
        if (!isPhoneMode) {
            return
        }

        val isConnected = bluetoothService?.isConnected == true
        val isActive = bluetoothService?.isActive() == true

        val options = mutableListOf(
            MenuOption(
                getString(R.string.menu_manage_apps),
                getString(R.string.menu_manage_apps_desc),
                android.R.drawable.ic_menu_sort_by_size,
                { runIfConnected { startActivity(Intent(this, AppListActivity::class.java)) } }
            ),
            MenuOption(
                getString(R.string.menu_watchface),
                getString(R.string.wf_title),
                android.R.drawable.ic_menu_gallery,
                { startActivity(Intent(this, com.ailife.rtosify.watchface.WatchFaceActivity::class.java)) }
            ),
            MenuOption(
                getString(R.string.menu_notifications),
                getString(R.string.menu_notifications_desc),
                android.R.drawable.ic_popup_reminder,
                { runIfConnected { startActivity(Intent(this, NotificationSettingsActivity::class.java)) } }
            ),
            MenuOption(
                "File Manager",
                "Browse and manage watch files",
                android.R.drawable.ic_menu_save,
                { runIfConnected { startActivity(Intent(this, FileManagerActivity::class.java)) } }
            ),
            MenuOption(
                getString(R.string.menu_device_mgmt),
                getString(R.string.menu_device_mgmt_desc),
                android.R.drawable.ic_lock_power_off,
                { runIfConnected { showDeviceManagementMenu() } }
            ),
            MenuOption(
                getString(R.string.menu_sync_calendar),
                getString(R.string.menu_sync_calendar_desc),
                android.R.drawable.ic_menu_today,
                { runIfConnected { bluetoothService?.syncCalendar() } }
            ),
            MenuOption(
                getString(R.string.menu_sync_contacts),
                getString(R.string.menu_sync_contacts_desc),
                android.R.drawable.ic_menu_myplaces,
                { runIfConnected { bluetoothService?.syncContacts() } }
            )
        )

        // Connect/Disconnect options removed in favor of toggle switch

        options.add(
            MenuOption(
                getString(R.string.perm_title),
                getString(R.string.perm_notifications_desc),
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

    private fun bindToService() {
        if (!prefs.getBoolean("service_enabled", true)) return

        val intent = Intent(this, BluetoothService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun updateStatusUI(status: String, isConnected: Boolean) {
        val deviceName = bluetoothService?.currentDeviceName ?: getString(R.string.device_name_default)
        tvHeaderDeviceName.text = if (isConnected) deviceName else getString(R.string.status_waiting)
        tvHeaderStatus.text = if (isConnected) getString(R.string.status_connected) else status
        tvHeaderStatus.setTextColor(if (isConnected) Color.GREEN else Color.RED)
        progressBarMain.visibility = if (!isConnected && status.contains("Conectando")) View.VISIBLE else View.INVISIBLE

        if (isPhoneMode && isConnected) {
            cardWatchStatus.visibility = View.VISIBLE
            cardHealthData.visibility = View.VISIBLE
        } else {
            cardWatchStatus.visibility = View.GONE
            cardHealthData.visibility = View.GONE
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

    private fun updateWatchStatusCard(battery: Int, isCharging: Boolean, wifi: String, dnd: Boolean) {
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
        bluetoothService?.resetApp()
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
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

    private fun showDeviceManagementMenu() {
        if (bluetoothService?.isConnected != true) {
            Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            getString(R.string.menu_shutdown_watch),
            getString(R.string.menu_reboot_watch),
            getString(R.string.menu_lock_watch),
            getString(R.string.menu_find_watch)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_device_mgmt_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmShutdownWatch()
                    1 -> confirmRebootWatch()
                    2 -> bluetoothService?.sendLockDeviceCommand()
                    3 -> showFindWatchDialog()
                }
            }
            .show()
    }

    private fun confirmShutdownWatch() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_shutdown_title))
            .setMessage(getString(R.string.dialog_shutdown_message))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(getString(R.string.dialog_shutdown_confirm)) { _, _ ->
                bluetoothService?.sendShutdownCommand()
                Toast.makeText(this, getString(R.string.toast_command_sent), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.dialog_shutdown_cancel), null)
            .show()
    }

    private fun confirmRebootWatch() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_reboot_title))
            .setMessage(getString(R.string.dialog_reboot_message))
            .setPositiveButton(getString(R.string.dialog_reboot_confirm)) { _, _ ->
                bluetoothService?.sendRebootCommand()
                Toast.makeText(this, getString(R.string.toast_command_sent), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.dialog_shutdown_cancel), null)
            .show()
    }

    private fun showFindWatchDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_find_watch_title))
            .setMessage(getString(R.string.dialog_find_watch_message))
            .setPositiveButton(getString(R.string.dialog_find_watch_start)) { _, _ ->
                bluetoothService?.sendFindDeviceCommand(true)
            }
            .setNeutralButton(getString(R.string.dialog_find_watch_stop)) { _, _ ->
                bluetoothService?.sendFindDeviceCommand(false)
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
                uploadProgressBar?.visibility = View.GONE
                uploadPercentageText?.visibility = View.GONE
                uploadTitleText?.text = getString(R.string.upload_failed_title)
                uploadTitleText?.setTextColor(Color.RED)
                uploadDescriptionText?.text = getString(R.string.upload_failed_message)
                uploadIconView?.setImageResource(android.R.drawable.stat_notify_error)
                uploadIconView?.setColorFilter(Color.RED)
                uploadOkButton?.visibility = View.VISIBLE
            }
        }
    }

    private fun dismissUploadDialog() {
        uploadDialog?.dismiss()
        uploadDialog = null
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
                // Se o progresso já for 100%, não trate a desconexão como falha.
                if (uploadProgressBar?.progress != 100) {
                    updateUploadProgress(-1)
                }
            }
        }
    }
    override fun onError(message: String) {
        runOnUiThread {
            if (uploadDialog?.isShowing == true) {
                updateUploadProgress(-1)
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onScanResult(devices: List<BluetoothDevice>) {
        runOnUiThread {
            if (devices.isEmpty()) return@runOnUiThread
            if (isPhoneMode) {
                val names = devices.map { "${it.name ?: getString(R.string.device_no_name)} (${it.address})" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_select_watch_title))
                    .setItems(names) { _, which -> bluetoothService?.connectToDevice(devices[which]) }
                    .show()
            }
        }
    }
    override fun onUploadProgress(progress: Int) {
        runOnUiThread { if (uploadDialog?.isShowing == true) updateUploadProgress(progress) }
    }
    override fun onAppListReceived(appsJson: String) {}
    
    override fun onFileListReceived(path: String, filesJson: String) {}
    
    override fun onDownloadProgress(progress: Int) {
        runOnUiThread { if (uploadDialog?.isShowing == true) updateUploadProgress(progress) }
    }

    private var hasRequestedHealthData = false

    override fun onWatchStatusUpdated(batteryLevel: Int, isCharging: Boolean, wifiSsid: String, dndEnabled: Boolean) {
        runOnUiThread {
            updateWatchStatusCard(batteryLevel, isCharging, wifiSsid, dndEnabled)
        }
        // Request health data only once when watch status first updates
        if (!hasRequestedHealthData && bluetoothService?.isConnected == true) {
            hasRequestedHealthData = true
            bluetoothService?.requestHealthData()
            android.util.Log.d("MainActivity", "Requesting health data from watch")
        }
    }

    override fun onHealthDataUpdated(healthData: HealthDataUpdate) {
        android.util.Log.d("MainActivity", "Health data received: steps=${healthData.steps}, cal=${healthData.calories}, hr=${healthData.heartRate}")
        runOnUiThread {
            updateHealthDataCard(healthData)
        }
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
}