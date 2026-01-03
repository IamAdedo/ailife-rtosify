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
import androidx.core.app.ActivityCompat
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

    private lateinit var prefs: SharedPreferences
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var isPhoneMode = true

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
            if (type == "PHONE") bluetoothService?.startSmartphoneLogic()
            else bluetoothService?.startWatchLogic()

            updateStatusUI(bluetoothService?.currentStatus ?: getString(R.string.status_starting), bluetoothService?.isConnected == true)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
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
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
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

        layoutWatchMode = findViewById(R.id.layoutWatchMode)
        layoutConnectionHeader = findViewById(R.id.layoutConnectionHeader)
        imgWatchStatus = findViewById(R.id.imgWatchStatus)
        tvWatchStatusBig = findViewById(R.id.tvWatchStatusBig)
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
            // Também aplicamos a verificação segura aqui
            runIfConnected {
                val newState = !currentDndState
                bluetoothService?.sendDndCommand(newState)
                updateDndUI(newState)
            }
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
            // Watch app - simplified menu without phone-only features
            MenuOption(
                getString(R.string.menu_disconnect),
                getString(R.string.menu_disconnect_desc),
                android.R.drawable.ic_menu_close_clear_cancel,
                // PERMITIDO: O usuário precisa poder parar a busca/serviço mesmo se não conectou
                {
                    bluetoothService?.stopConnectionLoopOnly()
                    updateStatusUI(getString(R.string.status_stopped), false)
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
        recyclerViewMenu.adapter = MenuAdapter(options)
    }

    private fun setupWatchMenu() {
        recyclerViewMenu.layoutManager = LinearLayoutManager(this)
        recyclerViewMenu.isNestedScrollingEnabled = false

        val options = listOf(
            MenuOption(
                getString(R.string.menu_find_phone),
                getString(R.string.menu_find_phone_desc),
                android.R.drawable.ic_menu_search,
                { runIfConnected { confirmFindPhone() } }
            ),
            MenuOption(
                getString(R.string.perm_title),
                getString(R.string.perm_shizuku_desc),
                android.R.drawable.ic_menu_manage,
                { startActivity(Intent(this, PermissionActivity::class.java)) }
            ),
            MenuOption(
                "Media Control", // Hardcoded string for now or add to strings.xml later if requested
                "Control phone playback",
                android.R.drawable.ic_media_play, // Using a standard system drawable
                { runIfConnected { startActivity(Intent(this, MediaControlActivity::class.java)) } }
            ),
            MenuOption(
                "Camera",
                "Remote Shutter",
                android.R.drawable.ic_menu_camera,
                { runIfConnected { startActivity(Intent(this, CameraRemoteActivity::class.java)) } }
            ),
            MenuOption(
                getString(R.string.menu_disconnect),
                getString(R.string.menu_disconnect_desc),
                android.R.drawable.ic_menu_close_clear_cancel,
                {
                    bluetoothService?.stopConnectionLoopOnly()
                    updateStatusUI(getString(R.string.status_stopped), false)
                }
            ),
            MenuOption(
                getString(R.string.menu_reset_all),
                getString(R.string.menu_reset_all_desc),
                android.R.drawable.ic_menu_delete,
                { resetApp() }
            )
        )
        recyclerViewMenu.adapter = MenuAdapter(options)
    }

    private fun confirmFindPhone() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_find_phone))
            .setMessage(getString(R.string.dialog_find_watch_message)) // Reuse message or add new one
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

    private fun confirmShutdownWatch() {
        // A verificação de segurança já foi feita no menu (runIfConnected),
        // mas mantemos uma verificação extra por segurança.
        if (bluetoothService?.isConnected != true) {
            Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
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
                Toast.makeText(this, getString(R.string.toast_upload_failed), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (uploadDialog?.isShowing == true) updateUploadProgress(-1)
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

    override fun onWatchStatusUpdated(batteryLevel: Int, isCharging: Boolean, wifiSsid: String, dndEnabled: Boolean) {
        runOnUiThread {
            updateWatchStatusCard(batteryLevel, isCharging, wifiSsid, dndEnabled)
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