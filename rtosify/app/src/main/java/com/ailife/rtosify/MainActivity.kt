package com.ailife.rtosify

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.DialogInterface
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
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.woheller69.freeDroidWarn.FreeDroidWarn


class MainActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    // UI References
    private lateinit var tvHeaderDeviceName: TextView
    private lateinit var tvHeaderStatus: TextView
    private lateinit var progressBarMain: LinearProgressIndicator
    private lateinit var recyclerViewMenu: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var mainContentScrollView: NestedScrollView
    private lateinit var switchService: com.google.android.material.materialswitch.MaterialSwitch

    // Device Info UI
    private lateinit var cardHeader: MaterialCardView
    private lateinit var tvDeviceInfoModel: TextView
    private lateinit var tvDeviceInfoAndroid: TextView
    private lateinit var tvDeviceInfoRam: TextView
    private lateinit var tvDeviceInfoStorage: TextView
    private lateinit var tvDeviceInfoProcessor: TextView
    private lateinit var tvDeviceInfoCpu: TextView

    // Watch Status UI
    private lateinit var cardWatchStatus: MaterialCardView
    private lateinit var tvBatteryPercent: TextView
    private lateinit var imgBatteryIcon: ImageView
    private lateinit var tvWifiSsid: TextView
    private lateinit var imgWifiIcon: ImageView
    private lateinit var layoutWifiAction: LinearLayout
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
    private lateinit var layoutHealthDataContent: LinearLayout
    private lateinit var layoutHealthAppNotInstalled: LinearLayout



    private lateinit var prefs: SharedPreferences
    // Activity launchers
    private val pairNewDeviceLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                android.util.Log.d("MainActivity", "Pairing activity finished. Result: ${result.resultCode}")
                
                if (result.resultCode == RESULT_OK) {
                    val newDeviceMac = result.data?.getStringExtra("NEW_DEVICE_MAC")
                    if (newDeviceMac != null) {
                        android.util.Log.d("MainActivity", "New device paired: $newDeviceMac")
                        // Set as selected device to ensure connection to the new device
                        devicePrefManager.setSelectedDeviceMac(newDeviceMac)
                    } else {
                         android.util.Log.w("MainActivity", "Result OK but no NEW_DEVICE_MAC extra")
                    }
                } else {
                    android.util.Log.w("MainActivity", "Pairing cancelled or failed")
                }
                
                // Always restart service to ensure consistent state
                bluetoothService?.reconnect()
            }

    private lateinit var devicePrefManager: DevicePrefManager
    private lateinit var layoutConnectionHeader: LinearLayout
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    // isPhoneMode removed - always Phone
    private var menuAdapter: MenuAdapter? = null

    private var currentDndState = false
    private var currentWifiState = true
    private var currentWifiSsid = ""

    // WiFi Selection Dialog
    private var wifiSelectionDialog: AlertDialog? = null
    private var wifiAdapter: WifiResultAdapter? = null
    private var wifiLoadingLayout: LinearLayout? = null

    // Diálogo Upload
    private var uploadDialog: AlertDialog? = null
    private var uploadProgressBar: LinearProgressIndicator? = null
    private var uploadPercentageText: TextView? = null
    private var uploadDescriptionText: TextView? = null
    private var uploadTitleText: TextView? = null
    private var uploadIconView: ImageView? = null
    private var uploadOkButton: MaterialButton? = null

    // WiFi Dialog UI References
    private var layoutCurrentWifi: LinearLayout? = null
    private var containerCurrentWifi: FrameLayout? = null
    private var dividerWifi: View? = null
    private var tvAvailableTitle: TextView? = null
    private var swWifiToggle: MaterialSwitch? = null
    private var swipeRefreshWifi: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null

    private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(className: ComponentName, service: IBinder) {
                    val binder = service as BluetoothService.LocalBinder
                    bluetoothService = binder.getService()
                    bluetoothService?.callback = this@MainActivity
                    isBound = true

                    // RTOSify is phone-only, always start smartphone logic
                    val isServiceEnabled = prefs.getBoolean("service_enabled", true)

                    if (isServiceEnabled) {
                        bluetoothService?.startSmartphoneLogic()
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

    private val otaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.ailife.rtosify.ACTION_SEND_OTA") {
                val path = intent.getStringExtra("file_path") ?: return
                val file = java.io.File(path)
                if (file.exists()) {
                     if (bluetoothService?.isConnected == true) {
                         Toast.makeText(context, R.string.ota_sending_to_watch, Toast.LENGTH_SHORT).show()
                         bluetoothService?.sendFile(file, "APK")
                     } else {
                         Toast.makeText(context, R.string.toast_watch_not_connected, Toast.LENGTH_SHORT).show()
                     }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicePrefManager = DevicePrefManager(this)
        prefs = devicePrefManager.getGlobalPrefs()

        // RTOSify is phone-only - only check permissions
        if (hasMissingPermissions()) {
            Toast.makeText(
                            this,
                            R.string.toast_setup_redirect_missing_perms,
                            Toast.LENGTH_SHORT
                    )
                    .show()
            android.util.Log.d(
                    "MainActivity",
                    "Missing permissions, redirecting to WelcomeActivity"
            )
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        initViews()
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, mainContentScrollView)

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_title)

        // Always Phone Mode
        setupLayoutMode()
        setupDndClickListener()
        setupWifiClickListener()
        setupHealthClickListeners()
        setupBatteryClickListener()
        setupHeaderClickListener()

        // OTA Update Check & Receiver
        val otaFilter = IntentFilter("com.ailife.rtosify.ACTION_SEND_OTA")
        ContextCompat.registerReceiver(this, otaReceiver, otaFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        com.ailife.rtosify.utils.OtaManager(this).checkUpdate(silent = true)

        bindToService()

        setupServiceToggle()

        if (intent?.getBooleanExtra("request_mirror", false) == true) {
            startPhoneMirroring()
        }

        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE)
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("request_mirror", false) == true) {
            startPhoneMirroring()
        }
    }



    override fun onResume() {
        super.onResume()
        if (isBound) {
            bluetoothService?.callback = this
            // Força a atualização da UI com os dados atuais do serviço
            updateStatusUI(
                    bluetoothService?.currentStatus ?: getString(R.string.status_verifying),
                    bluetoothService?.isConnected == true
            )
        }
        startWatchStatusPolling()
        
        // Trigger WiFi connection if MainActivity rule is set
        val wifiRule = prefs.getInt("wifi_activation_rule", 0) // Default disabled
        if ((wifiRule and BluetoothService.WIFI_RULE_MAINACTIVITY) != 0 ||
            (wifiRule and BluetoothService.WIFI_RULE_BT_OR_APP) != 0) {
            bluetoothService?.triggerWifiConnectionForMainActivity()
        }
    }

    override fun onPause() {
        super.onPause()
        stopWatchStatusPolling()
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
        layoutWifiAction = findViewById(R.id.layoutWifiAction)
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
        layoutHealthDataContent = findViewById(R.id.layoutHealthDataContent)
        layoutHealthAppNotInstalled = findViewById(R.id.layoutHealthAppNotInstalled)



        switchService = findViewById(R.id.switchService)

        // Device Info Views
        cardHeader = findViewById(R.id.cardHeader)
        tvDeviceInfoModel = findViewById(R.id.tvDeviceInfoModel)
        tvDeviceInfoAndroid = findViewById(R.id.tvDeviceInfoAndroid)
        tvDeviceInfoRam = findViewById(R.id.tvDeviceInfoRam)
        tvDeviceInfoStorage = findViewById(R.id.tvDeviceInfoStorage)
        tvDeviceInfoProcessor = findViewById(R.id.tvDeviceInfoProcessor)
        tvDeviceInfoCpu = findViewById(R.id.tvDeviceInfoCpu)

        layoutConnectionHeader = findViewById(R.id.layoutConnectionHeader)
    }

    private fun setupLayoutMode() {
        // Enforce Phone Layout

        appBarLayout.visibility = View.VISIBLE
        mainContentScrollView.visibility = View.VISIBLE
        setupPhoneMenu()

        // Initial health UI state
        val healthInstalled = getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
            .getBoolean("health_app_installed", true)
        if (healthInstalled) {
            layoutHealthDataContent.visibility = View.VISIBLE
            layoutHealthAppNotInstalled.visibility = View.GONE
        } else {
            layoutHealthDataContent.visibility = View.GONE
            layoutHealthAppNotInstalled.visibility = View.VISIBLE
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

        layoutDndAction.setOnLongClickListener {
            runIfConnected { showDndOptionsDialog() }
            true
        }
    }

    private fun showDndOptionsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_dnd_options, null)
        val swDndSchedule =
                dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                        R.id.swDndSchedule
                )
        val tvStartTime = dialogView.findViewById<TextView>(R.id.tvStartTime)
        val tvEndTime = dialogView.findViewById<TextView>(R.id.tvEndTime)
        val btnStartTime = dialogView.findViewById<LinearLayout>(R.id.btnStartTime)
        val btnEndTime = dialogView.findViewById<LinearLayout>(R.id.btnEndTime)
        val btn1h = dialogView.findViewById<MaterialButton>(R.id.btnQuick1h)
        val btn2h = dialogView.findViewById<MaterialButton>(R.id.btnQuick2h)
        val btnCustom = dialogView.findViewById<MaterialButton>(R.id.btnQuickCustom)

        // Load existing settings if any (could be from SharedPreferences)
        val scheduleEnabled = prefs.getBoolean("dnd_schedule_enabled", false)
        val startTime = prefs.getString("dnd_start_time", "22:00") ?: "22:00"
        val endTime = prefs.getString("dnd_end_time", "07:00") ?: "07:00"

        swDndSchedule.isChecked = scheduleEnabled
        tvStartTime.text = startTime
        tvEndTime.text = endTime

        btnStartTime.setOnClickListener {
            val parts = tvStartTime.text.split(":")
            android.app.TimePickerDialog(
                            this,
                            { _, h, m ->
                                val time = String.format("%02d:%02d", h, m)
                                tvStartTime.text = time
                            },
                            parts[0].toInt(),
                            parts[1].toInt(),
                            true
                    )
                    .show()
        }

        btnEndTime.setOnClickListener {
            val parts = tvEndTime.text.split(":")
            android.app.TimePickerDialog(
                            this,
                            { _, h, m ->
                                val time = String.format("%02d:%02d", h, m)
                                tvEndTime.text = time
                            },
                            parts[0].toInt(),
                            parts[1].toInt(),
                            true
                    )
                    .show()
        }

        val dialog =
                MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dnd_dialog_title)
                        .setView(dialogView)
                        .setPositiveButton(R.string.dnd_save_settings) { _, _ ->
                            val newEnabled = swDndSchedule.isChecked
                            val newStart = tvStartTime.text.toString()
                            val newEnd = tvEndTime.text.toString()

                            prefs.edit()
                                    .putBoolean("dnd_schedule_enabled", newEnabled)
                                    .putString("dnd_start_time", newStart)
                                    .putString("dnd_end_time", newEnd)
                                    .apply()

                            bluetoothService?.updateDndSettings(
                                    DndSettingsData(
                                            scheduleEnabled = newEnabled,
                                            startTime = newStart,
                                            endTime = newEnd
                                    )
                            )
                            Toast.makeText(this, R.string.toast_command_sent, Toast.LENGTH_SHORT)
                                    .show()
                        }
                        .setNegativeButton(R.string.wifi_cancel, null)
                        .create()

        btn1h.setOnClickListener {
            bluetoothService?.updateDndSettings(
                    DndSettingsData(
                            scheduleEnabled = swDndSchedule.isChecked,
                            startTime = tvStartTime.text.toString(),
                            endTime = tvEndTime.text.toString(),
                            quickDurationMinutes = 60
                    )
            )
            Toast.makeText(this, getString(R.string.toast_dnd_sent_format, 1, getString(R.string.unit_hour)), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btn2h.setOnClickListener {
            bluetoothService?.updateDndSettings(
                    DndSettingsData(
                            scheduleEnabled = swDndSchedule.isChecked,
                            startTime = tvStartTime.text.toString(),
                            endTime = tvEndTime.text.toString(),
                            quickDurationMinutes = 120
                    )
            )
            Toast.makeText(this, getString(R.string.toast_dnd_sent_format, 2, getString(R.string.unit_hours)), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnCustom.setOnClickListener {
            showCustomDurationDialog(
                    dialog,
                    swDndSchedule.isChecked,
                    tvStartTime.text.toString(),
                    tvEndTime.text.toString()
            )
        }

        dialog.show()
    }

    private fun showCustomDurationDialog(
            parentDialog: AlertDialog,
            scheduleEnabled: Boolean,
            startTime: String,
            endTime: String
    ) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.dnd_custom_duration_hint)

        MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dnd_custom_duration_title)
                .setMessage(R.string.dnd_custom_duration_message)
                .setView(input)
                .setPositiveButton(R.string.btn_ok) { _, _ ->
                    val mins = input.text.toString().toIntOrNull()
                    if (mins != null && mins > 0) {
                        bluetoothService?.updateDndSettings(
                                DndSettingsData(
                                        scheduleEnabled = scheduleEnabled,
                                        startTime = startTime,
                                        endTime = endTime,
                                        quickDurationMinutes = mins
                                )
                        )
                        Toast.makeText(this, getString(R.string.toast_dnd_mins_sent_format, mins), Toast.LENGTH_SHORT)
                                .show()
                        parentDialog.dismiss()
                    }
                }
                .setNegativeButton(R.string.wifi_cancel, null)
                .show()
    }

    private fun setupWifiClickListener() {
        layoutWifiAction.setOnClickListener {
            runIfConnected {
                val newState = !currentWifiState
                bluetoothService?.sendWifiCommand(newState)
                updateWifiUI(newState)
            }
        }

        layoutWifiAction.setOnLongClickListener {
            runIfConnected {
                showWifiSelectionDialog()
                bluetoothService?.requestWifiScan()
            }
            true
        }
    }

    private fun showWifiSelectionDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wifi_selection, null)
        val rvWifiList = dialogView.findViewById<RecyclerView>(R.id.rvWifiList)
        wifiLoadingLayout = dialogView.findViewById<LinearLayout>(R.id.layoutWifiLoading)
        layoutCurrentWifi = dialogView.findViewById<LinearLayout>(R.id.layoutCurrentWifi)
        containerCurrentWifi = dialogView.findViewById<FrameLayout>(R.id.containerCurrentWifi)
        dividerWifi = dialogView.findViewById<View>(R.id.dividerWifi)
        tvAvailableTitle = dialogView.findViewById<TextView>(R.id.tvAvailableTitle)
        swWifiToggle =
                dialogView.findViewById<MaterialSwitch>(R.id.swWifiToggle)
        swipeRefreshWifi =
                dialogView.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
                        R.id.swipeRefreshWifi
                )

        // Set up toggle
        swWifiToggle?.isChecked = currentWifiState
        swWifiToggle?.setOnCheckedChangeListener { _, isChecked ->
            runIfConnected {
                bluetoothService?.sendWifiCommand(isChecked)
                updateWifiUI(isChecked)
                updateDialogState(isChecked)
            }
        }

        // Set up pull-to-refresh
        swipeRefreshWifi?.setOnRefreshListener {
            runIfConnected { bluetoothService?.requestWifiScan() }
        }

        updateDialogState(currentWifiState)

        wifiAdapter = WifiResultAdapter { result ->
            // Check if this is the currently connected network
            if (result.ssid == currentWifiSsid && currentWifiState) {
                Toast.makeText(
                                this,
                                getString(R.string.wifi_already_connected, result.ssid),
                                Toast.LENGTH_SHORT
                        )
                        .show()
                return@WifiResultAdapter
            }

            // For secure networks, ask for password only if not already saved
            if (result.isSecure) {
                // Show password dialog
                showWifiPasswordDialog(result.ssid)
            } else {
                // Open network, connect directly
                bluetoothService?.connectToWifi(result.ssid, null)
                Toast.makeText(
                                this,
                                getString(R.string.wifi_connecting, result.ssid),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
        }
        rvWifiList.layoutManager = LinearLayoutManager(this)
        rvWifiList.adapter = wifiAdapter

        wifiSelectionDialog =
                MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.wifi_dialog_title)
                        .setView(dialogView)
                        .setNegativeButton(R.string.wifi_cancel) { _, _ ->
                            wifiSelectionDialog = null
                            clearWifiDialogRefs()
                        }
                        .setOnDismissListener {
                            wifiSelectionDialog = null
                            clearWifiDialogRefs()
                        }
                        .show()
    }

    private fun clearWifiDialogRefs() {
        layoutCurrentWifi = null
        containerCurrentWifi = null
        dividerWifi = null
        tvAvailableTitle = null
        swWifiToggle = null
        wifiLoadingLayout = null
        swipeRefreshWifi = null
    }

    private fun updateCurrentWifiView() {
        val container = containerCurrentWifi ?: return
        val layout = layoutCurrentWifi ?: return
        val divider = dividerWifi ?: return

        // Filter out invalid/temporary states
        val invalidStates =
                setOf(
                        "Desconectado",
                        "Disconnected",
                        "Desativado",
                        "Off",
                        "WiFi Disabled",
                        "Disabled",
                        "None",
                        "N/A",
                        ""
                )

        val isValidConnection =
                currentWifiState &&
                        currentWifiSsid.isNotEmpty() &&
                        !invalidStates.contains(currentWifiSsid)

        if (isValidConnection) {
            layout.visibility = View.VISIBLE
            divider.visibility = View.VISIBLE

            container.removeAllViews()
            val itemView =
                    LayoutInflater.from(this)
                            .inflate(R.layout.item_wifi_selection, container, false)
            val tvSsid = itemView.findViewById<TextView>(R.id.tvWifiSsid)
            val tvSecurity = itemView.findViewById<TextView>(R.id.tvWifiSecurity)
            val imgSignal = itemView.findViewById<ImageView>(R.id.imgWifiSignal)
            val imgLock = itemView.findViewById<ImageView>(R.id.imgWifiLock)
            val layoutItem = itemView.findViewById<LinearLayout>(R.id.layoutWifiItem)

            tvSsid.text = currentWifiSsid
            tvSecurity.text = getString(R.string.wifi_connected)
            imgSignal.setImageResource(R.drawable.ic_wifi)
            imgLock.visibility = View.GONE

            // Highlight blue as requested
            layoutItem.setBackgroundColor(Color.parseColor("#332196F3")) // Light blue

            container.addView(itemView)
        } else {
            layout.visibility = View.GONE
            divider.visibility = View.GONE
        }
    }

    private fun updateDialogState(isWifiOn: Boolean) {
        val title = tvAvailableTitle ?: return
        if (isWifiOn) {
            title.text = getString(R.string.wifi_available_networks)
            wifiLoadingLayout?.visibility = View.VISIBLE
            updateCurrentWifiView()
            bluetoothService?.requestWifiScan()
        } else {
            layoutCurrentWifi?.visibility = View.GONE
            dividerWifi?.visibility = View.GONE
            title.text = getString(R.string.wifi_activate_message)
            wifiLoadingLayout?.visibility = View.GONE
            wifiAdapter?.setResults(emptyList())
        }
    }

    private fun showWifiPasswordDialog(ssid: String) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = getString(R.string.wifi_password_hint)

        MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.wifi_password_dialog_title, ssid))
                .setMessage(R.string.wifi_password_message)
                .setView(input)
                .setPositiveButton(R.string.wifi_connect_with_password) { _, _ ->
                    val password = input.text.toString()
                    if (password.isEmpty()) {
                        Toast.makeText(this, R.string.wifi_password_empty_error, Toast.LENGTH_SHORT)
                                .show()
                    } else {
                        bluetoothService?.connectToWifi(ssid, password)
                        Toast.makeText(
                                        this,
                                        getString(R.string.wifi_connecting, ssid),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                }
                .setNeutralButton(R.string.wifi_connect_saved) { _, _ ->
                    // Connect without password (use saved credentials)
                    bluetoothService?.connectToWifi(ssid, null)
                    Toast.makeText(
                                    this,
                                    getString(R.string.wifi_connecting, ssid),
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
                .setNegativeButton(R.string.wifi_cancel, null)
                .show()
    }

    private fun setupHealthClickListeners() {
        layoutStepsAction.setOnClickListener {
            runIfConnected {
                startActivity(
                        Intent(this, HealthDetailActivity::class.java).apply {
                            putExtra("HEALTH_TYPE", "STEPS")
                        }
                )
            }
        }

        layoutHeartRateAction.setOnClickListener {
            runIfConnected {
                startActivity(
                        Intent(this, HealthDetailActivity::class.java).apply {
                            putExtra("HEALTH_TYPE", "HEART_RATE")
                        }
                )
            }
        }

        layoutOxygenAction.setOnClickListener {
            runIfConnected {
                startActivity(
                        Intent(this, HealthDetailActivity::class.java).apply {
                            putExtra("HEALTH_TYPE", "OXYGEN")
                        }
                )
            }
        }
    }

    private fun setupBatteryClickListener() {
        val listener =
                View.OnClickListener {
                    runIfConnected {
                        startActivity(Intent(this, BatteryDetailActivity::class.java))
                    }
                }
        tvBatteryPercent.setOnClickListener(listener)
        imgBatteryIcon.setOnClickListener(listener)
    }

    private fun updateHealthDataCard(healthData: HealthDataUpdate) {
        val healthInstalled = healthData.errorState != "APP_NOT_INSTALLED"
        
        // Update visibility
        if (healthInstalled) {
            layoutHealthDataContent.visibility = View.VISIBLE
            layoutHealthAppNotInstalled.visibility = View.GONE
        } else {
            layoutHealthDataContent.visibility = View.GONE
            layoutHealthAppNotInstalled.visibility = View.VISIBLE
        }

        // Persist state
        val healthPrefs = getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
        if (healthPrefs.getBoolean("health_app_installed", true) != healthInstalled) {
            healthPrefs.edit().putBoolean("health_app_installed", healthInstalled).apply()
            
            // Update menu
            menuAdapter?.let { adapter ->
                val healthIndex = adapter.menuItems.indexOfFirst { it.titleRes == R.string.menu_health_data }
                if (healthIndex != -1) {
                    adapter.menuItems[healthIndex].isEnabled = healthInstalled
                    adapter.notifyItemChanged(healthIndex)
                }
            }
        }

        if (!healthInstalled) return

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

        // Handle other error states
        healthData.errorState?.let { error ->
            when (error) {
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

                // RTOSify is phone-only
                bluetoothService?.startSmartphoneLogic()

                updateStatusUI(getString(R.string.status_starting), false)
                Toast.makeText(this@MainActivity, R.string.toast_service_started, Toast.LENGTH_SHORT).show()
            } else {
                bluetoothService?.stopServiceCompletely()
                if (isBound) {
                    unbindService(connection)
                    isBound = false
                    bluetoothService = null
                }
                updateStatusUI(getString(R.string.status_stopped), false)
                Toast.makeText(this, R.string.toast_service_stopped, Toast.LENGTH_SHORT).show()
            }
            refreshMenu()
        }
    }

    private val screenCaptureLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK && result.data != null) {
                    // Check if high quality mode should be enabled (LAN connected + HQ setting enabled)
                    val isLanConnected = bluetoothService?.isWifiConnected() == true
                    val activePrefs = devicePrefManager.getActiveDevicePrefs()
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
                }
            }

    private fun startPhoneMirroring() {
        val projectionManager =
                getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as
                        android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
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

    private fun setupHeaderClickListener() {
        layoutConnectionHeader.setOnClickListener { showDevicePicker() }
    }

    private fun showDevicePicker() {
        val devices = devicePrefManager.getPairedDevices()
        val currentMac = devicePrefManager.getSelectedDeviceMac()

        val dialogView = layoutInflater.inflate(R.layout.dialog_device_picker, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.containerDevices)
        val btnPairNew = dialogView.findViewById<MaterialButton>(R.id.btnPairNewDevice)

        val dialog =
                MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.device_picker_title)
                        .setView(dialogView)
                        .setNegativeButton(R.string.wifi_cancel, null)
                        .create()

        if (devices.isEmpty()) {
            val emptyTv =
                    TextView(this).apply {
                        text = getString(R.string.no_devices_paired)
                        setPadding(32, 32, 32, 32)
                        gravity = android.view.Gravity.CENTER
                    }
            container.addView(emptyTv)
        } else {
            devices.forEach { device ->
                val itemView = layoutInflater.inflate(R.layout.item_paired_device, container, false)
                val tvName = itemView.findViewById<TextView>(R.id.tvDeviceName)
                val tvMac = itemView.findViewById<TextView>(R.id.tvDeviceMac)
                val imgCheck = itemView.findViewById<ImageView>(R.id.imgSelected)

                tvName.text = device.name
                tvMac.text = device.mac
                imgCheck.visibility = if (device.mac == currentMac) View.VISIBLE else View.GONE

                itemView.setOnClickListener {
                    if (device.mac != currentMac) {
                        devicePrefManager.setSelectedDeviceMac(device.mac)
                        Toast.makeText(
                                        this,
                                        getString(R.string.device_selected, device.name),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                         updateStatusUI(getString(R.string.status_switching), false)
                         // Trigger reconnect to the new device via service
                         bluetoothService?.reconnect()
                         dialog.dismiss()
                    }
                }

                itemView.setOnLongClickListener {
                    val builder = MaterialAlertDialogBuilder(this@MainActivity)
                    builder.setTitle(R.string.dialog_remove_device_title)
                    builder.setMessage(getString(R.string.dialog_remove_device_message, device.name))
                    builder.setPositiveButton(R.string.dialog_remove_device_confirm, object : DialogInterface.OnClickListener {
                        override fun onClick(d: DialogInterface?, which: Int) {
                            val wasSelected = (devicePrefManager.getSelectedDeviceMac() == device.mac)
                            devicePrefManager.removePairedDevice(device.mac)
                            
                            // If we deleted the currently selected device, switch to the next available device
                            if (wasSelected) {
                                val remainingDevices = devicePrefManager.getPairedDevices()
                                if (remainingDevices.isNotEmpty()) {
                                    // Select the first remaining device
                                    val nextDevice = remainingDevices[0]
                                    devicePrefManager.setSelectedDeviceMac(nextDevice.mac)
                                    android.util.Log.d("MainActivity", "Deleted current device, switching to: ${nextDevice.mac}")
                                    // Trigger reconnect to the new device
                                    bluetoothService?.reconnect()
                                } else {
                                    // No devices left - disable the service to prevent re-adding bonded devices
                                    android.util.Log.d("MainActivity", "No devices left after deletion, disabling service")
                                    switchService.isChecked = false
                                }
                            }
                            
                            showDevicePicker() // Refresh
                            dialog.dismiss()
                        }
                    })
                    builder.setNegativeButton(android.R.string.cancel, null)
                    builder.show()
                    true
                }

                container.addView(itemView)
            }
        }

        btnPairNew.setOnClickListener {
            dialog.dismiss()
            // Stop service before pairing new device to ensure clean state
            switchService.isChecked = false
            // Launch WelcomeActivity after brief delay to ensure service stopped
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                pairNewDeviceLauncher.launch(
                        Intent(this, WelcomeActivity::class.java).apply {
                            putExtra("FOR_NEW_DEVICE", true)
                        }
                )
            }, 300)
        }

        dialog.show()
    }

    // --- NEW SECURITY FUNCTION ---
    // Verifies if connected before executing the action.
    private fun runIfConnected(action: () -> Unit) {
        if (bluetoothService?.isConnected == true) {
            action()
        } else {
            Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT)
                    .show()
        }
    }

    private fun setupPhoneMenu() {
        recyclerViewMenu.layoutManager = LinearLayoutManager(this)
        recyclerViewMenu.isNestedScrollingEnabled = false

        val options =
                listOf(
                        MenuOption(
                                getString(R.string.menu_manage_apps),
                                getString(R.string.menu_manage_apps_desc),
                                android.R.drawable.ic_menu_sort_by_size,
                                {
                                    runIfConnected {
                                        startActivity(Intent(this, AppListActivity::class.java))
                                    }
                                },
                                null,
                                R.string.menu_manage_apps
                        ),
                        MenuOption(
                                getString(R.string.menu_watchface),
                                getString(R.string.wf_title),
                                R.drawable.ic_watch_faces,
                                {
                                    startActivity(
                                            Intent(
                                                    this,
                                                    com.ailife.rtosify.watchface
                                                                    .WatchFaceActivity::class
                                                            .java
                                            )
                                    )
                                },
                                null,
                                R.string.menu_watchface
                        ),
                        MenuOption(
                                getString(R.string.menu_notifications),
                                getString(R.string.menu_notifications_desc),
                                android.R.drawable.ic_popup_reminder,
                                {
                                    runIfConnected {
                                        startActivity(
                                                Intent(
                                                        this,
                                                        NotificationSettingsActivity::class.java
                                                )
                                        )
                                    }
                                },
                                null,
                                R.string.menu_notifications
                        ),
                        MenuOption(
                            getString(R.string.menu_health_data),
                            getString(R.string.menu_health_desc),
                            R.drawable.ic_heart,
                            {
                                runIfConnected {
                                    startActivity(
                                        Intent(this, HealthDetailActivity::class.java)
                                    )
                                }
                            },
                            null,
                            R.string.menu_health_data,
                            getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
                                .getBoolean("health_app_installed", true)
                        ),
                        MenuOption(
                                getString(R.string.menu_file_manager),
                                getString(R.string.menu_file_manager_desc),
                                android.R.drawable.ic_menu_save,
                                {
                                    runIfConnected {
                                        startActivity(Intent(this, FileManagerActivity::class.java))
                                    }
                                },
                                null,
                                R.string.menu_file_manager
                        ),
                        MenuOption(
                                getString(R.string.menu_watch_automation),
                                getString(R.string.menu_watch_automation_desc),
                                R.drawable.ic_watch_automations,
                                {
                                    startActivity(
                                            Intent(this, WatchAutomationsActivity::class.java)
                                    )
                                },
                                null,
                                R.string.menu_watch_automation
                        ),
                        MenuOption(
                                getString(R.string.menu_device_mgmt),
                                getString(R.string.menu_device_mgmt_desc),
                                android.R.drawable.ic_lock_power_off,
                                { runIfConnected { showDeviceManagementMenu() } },
                                null,
                                R.string.menu_device_mgmt
                        ),
                        MenuOption(
                                getString(R.string.menu_mirroring),
                                getString(R.string.menu_mirroring_desc),
                                R.drawable.ic_cast,
                                { startActivity(Intent(this, MirrorSettingsActivity::class.java)) },
                                null,
                                R.string.menu_mirroring
                        ),
                        MenuOption(
                                getString(R.string.menu_network_comms),
                                getString(R.string.menu_network_comms_desc),
                                R.drawable.ic_globe,
                                { startActivity(Intent(this, NetworkSettingsActivity::class.java)) },
                                null,
                                R.string.menu_network_comms
                        ),
                        MenuOption(
                                getString(R.string.menu_sync_calendar),
                                getString(R.string.menu_sync_calendar_desc),
                                android.R.drawable.ic_menu_today,
                                { runIfConnected { bluetoothService?.syncCalendar() } },
                                null,
                                R.string.menu_sync_calendar
                        ),
                        MenuOption(
                                getString(R.string.menu_sync_contacts),
                                getString(R.string.menu_sync_contacts_desc),
                                R.drawable.ic_sync_contacts,
                                { runIfConnected { bluetoothService?.syncContacts() } },
                                null,
                                R.string.menu_sync_contacts
                        ),
                        MenuOption(
                                getString(R.string.menu_disconnect),
                                getString(R.string.menu_disconnect_desc),
                                android.R.drawable.ic_menu_close_clear_cancel,
                                {
                                    // Legacy disconnect button removed
                                },
                                null,
                                R.string.menu_disconnect
                        ),
                        MenuOption(
                                getString(R.string.perm_title),
                                getString(
                                        R.string.perm_notifications_desc
                                ),
                                android.R.drawable.ic_menu_manage,
                                { startActivity(Intent(this, PermissionActivity::class.java)) },
                                null,
                                R.string.perm_title
                        ),
                        MenuOption(
                                getString(R.string.menu_about),
                                getString(R.string.menu_about_desc),
                                android.R.drawable.ic_menu_info_details,
                                { startActivity(Intent(this, AboutActivity::class.java)) },
                                null,
                                R.string.menu_about
                        ),
                        MenuOption(
                                getString(R.string.menu_reset_all),
                                getString(R.string.menu_reset_all_desc),
                                android.R.drawable.ic_menu_delete,
                                { resetApp() },
                                null,
                                R.string.menu_reset_all
                        )

                )
        menuAdapter = MenuAdapter(options.toMutableList())
        recyclerViewMenu.adapter = menuAdapter
    }

    private fun refreshMenu() {
        val isConnected = bluetoothService?.isConnected == true
        val isActive = bluetoothService?.isActive() == true

        val options =
                mutableListOf(
                        MenuOption(
                                getString(R.string.menu_manage_apps),
                                getString(R.string.menu_manage_apps_desc),
                                android.R.drawable.ic_menu_sort_by_size,
                                {
                                    runIfConnected {
                                        startActivity(Intent(this, AppListActivity::class.java))
                                    }
                                },
                                null,
                                R.string.menu_manage_apps
                        ),
                        MenuOption(
                                getString(R.string.menu_health_data),
                                getString(R.string.menu_health_desc),
                                R.drawable.ic_heart,
                                {
                                    runIfConnected {
                                        startActivity(
                                                Intent(this, HealthDetailActivity::class.java)
                                        )
                                    }
                                },
                                null,
                                R.string.menu_health_data,
                                getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
                                    .getBoolean("health_app_installed", true)
                        ),
                        MenuOption(
                                getString(R.string.menu_mirroring),
                                getString(R.string.menu_mirroring_desc),
                                R.drawable.ic_cast,
                                { startActivity(Intent(this, MirrorSettingsActivity::class.java)) },
                                null,
                                R.string.menu_mirroring
                        ),
                        MenuOption(
                                getString(R.string.menu_network_comms),
                                getString(R.string.menu_network_comms_desc),
                                R.drawable.ic_globe,
                                { startActivity(Intent(this, NetworkSettingsActivity::class.java)) },
                                null,
                                R.string.menu_network_comms
                        ),

                        MenuOption(
                                getString(R.string.menu_alarms),
                                getString(R.string.menu_alarms_desc),
                                android.R.drawable.ic_lock_idle_alarm,
                                {
                                    runIfConnected {
                                        startActivity(
                                                Intent(this, AlarmManagementActivity::class.java)
                                        )
                                    }
                                },
                                null,
                                R.string.menu_alarms
                        ),
                        MenuOption(
                                getString(R.string.menu_terminal),
                                getString(R.string.menu_terminal_desc),
                                R.drawable.ic_terminal,
                                {
                                    runIfConnected {
                                        startActivity(Intent(this, TerminalActivity::class.java))
                                    }
                                },
                                null,
                                R.string.menu_terminal
                        ),
                        MenuOption(
                                getString(R.string.menu_watchface),
                                getString(R.string.wf_title),
                                R.drawable.ic_watch_faces,
                                {
                                    startActivity(
                                            Intent(
                                                    this,
                                                    com.ailife.rtosify.watchface
                                                                    .WatchFaceActivity::class
                                                            .java
                                            )
                                    )
                                }
                        ),
                        MenuOption(
                                getString(R.string.menu_notifications),
                                getString(R.string.menu_notifications_desc),
                                android.R.drawable.ic_popup_reminder,
                                {
                                    runIfConnected {
                                        startActivity(
                                                Intent(
                                                        this,
                                                        NotificationSettingsActivity::class.java
                                                )
                                        )
                                    }
                                }
                        ),
                        MenuOption(
                                getString(R.string.menu_file_manager),
                                getString(R.string.menu_file_manager_desc),
                                android.R.drawable.ic_menu_save,
                                {
                                    runIfConnected {
                                        startActivity(Intent(this, FileManagerActivity::class.java))
                                    }
                                }
                        ),
                        MenuOption(
                                getString(R.string.menu_watch_automation),
                                getString(R.string.menu_watch_automation_desc),
                                R.drawable.ic_watch_automations,
                                {
                                    startActivity(
                                            Intent(this, WatchAutomationsActivity::class.java)
                                    )
                                }
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
                                R.drawable.ic_sync_contacts,
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
        options.add(
              options.size - 1,
              MenuOption(
                    getString(R.string.menu_about),
                    getString(R.string.menu_about_desc),
                    android.R.drawable.ic_menu_info_details,
                    { startActivity(Intent(this, AboutActivity::class.java)) },
                    null,
                    R.string.menu_about
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
        val selectedMac = devicePrefManager.getSelectedDeviceMac()
        val pairedDevices = devicePrefManager.getPairedDevices()
        val activeDevice = pairedDevices.find { it.mac == selectedMac }

        val deviceName =
                if (isConnected) {
                    bluetoothService?.currentDeviceName
                            ?: activeDevice?.name ?: getString(R.string.status_searching)
                } else {
                    activeDevice?.name ?: getString(R.string.status_searching)
                }

        tvHeaderDeviceName.text = deviceName
        
        // Use the status from BluetoothService (which handles dual transport correctly)
        tvHeaderStatus.text = status
        
        tvHeaderStatus.setTextColor(if (isConnected) Color.GREEN else Color.RED)
        progressBarMain.visibility =
                if (!isConnected && (status.contains("Conectando") || status.contains("Searching")))
                        View.VISIBLE
                else View.INVISIBLE

        if (isConnected) {
            cardWatchStatus.visibility = View.VISIBLE
            cardHealthData.visibility = View.VISIBLE
        } else {
            cardWatchStatus.visibility = View.GONE
            cardHealthData.visibility = View.GONE
        }



        refreshMenu()
    }

    private fun updateWatchStatusCard(
            battery: Int,
            isCharging: Boolean,
            wifi: String,
            wifiEnabled: Boolean,
            dnd: Boolean
    ) {
        android.util.Log.d("CHARGING_DEBUG", "PHONE updateWatchStatusCard: battery=$battery, isCharging=$isCharging")
        tvBatteryPercent.text = "$battery%"
        if (isCharging) {
            imgBatteryIcon.setImageResource(android.R.drawable.ic_lock_idle_charging)
        } else {
            imgBatteryIcon.setImageResource(R.drawable.ic_battery_full)
        }
        tvWifiSsid.text = wifi.ifEmpty { "---" }
        updateWifiUI(wifiEnabled)
        updateDndUI(dnd)
    }

    private fun updateDndUI(enabled: Boolean) {
        currentDndState = enabled
        layoutDndAction.alpha = 1.0f

        if (enabled) {
            tvDndStatus.text = getString(R.string.dnd_on)
            imgDndIcon.imageAlpha = 255
        } else {
            tvDndStatus.text = getString(R.string.dnd_off)
            imgDndIcon.imageAlpha = 100
        }
    }

    private fun updateWifiUI(enabled: Boolean) {
        currentWifiState = enabled
        layoutWifiAction.alpha = 1.0f // Assuming layoutWifiAction is a clickable view
        if (enabled) {
            imgWifiIcon.imageAlpha = 255
        } else {
            imgWifiIcon.imageAlpha = 100
        }
    }

    private fun resetApp() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.dialog_reset_all_input_hint)
            gravity = android.view.Gravity.CENTER
            setSingleLine(true)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            addView(input, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = padding
                rightMargin = padding
                topMargin = padding / 2
            })
        }

        MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_reset_all_title))
                .setMessage(getString(R.string.dialog_reset_all_message))
                .setView(container)
                .setPositiveButton(getString(R.string.dialog_reset_all_confirm)) { _, _ ->
                    if (input.text.toString().trim().lowercase() == "yes") {
                        bluetoothService?.resetApp()
                        startActivity(Intent(this, WelcomeActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this, getString(R.string.dialog_reset_all_error), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.dialog_reset_all_cancel), null)
                .show()
    }

    private fun updateDeviceInfoUI(info: DeviceInfoData) {
        tvDeviceInfoModel.text = info.model
        tvDeviceInfoAndroid.text = info.androidVersion
        tvDeviceInfoRam.text = info.ramUsage
        tvDeviceInfoStorage.text = info.storageUsage
        tvDeviceInfoProcessor.text = info.processor
        tvDeviceInfoCpu.text = getString(R.string.device_info_usage_format, info.cpuUsage)
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

    private fun showDeviceManagementMenu() {
        if (bluetoothService?.isConnected != true) {
            Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT)
                    .show()
            return
        }

        val options =
                arrayOf(
                        getString(R.string.menu_shutdown_watch),
                        getString(R.string.menu_reboot_watch),
                        getString(R.string.menu_lock_watch),
                        getString(R.string.menu_find_watch)
                )

        MaterialAlertDialogBuilder(this)
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

    private fun confirmRebootWatch() {
        MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_reboot_title))
                .setMessage(getString(R.string.dialog_reboot_message))
                .setPositiveButton(getString(R.string.dialog_reboot_confirm)) { _, _ ->
                    bluetoothService?.sendRebootCommand()
                    Toast.makeText(this, getString(R.string.toast_command_sent), Toast.LENGTH_SHORT)
                            .show()
                }
                .setNegativeButton(getString(R.string.dialog_shutdown_cancel), null)
                .show()
    }

    private fun showFindWatchDialog() {
        // Launch FindDeviceActivity (Map view)
        // This will start location sharing, but NOT the alarm/ringing.
        val intent = Intent(this, FindDeviceActivity::class.java)
        startActivity(intent)
    }

    private fun showUploadDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_upload_progress, null)
        uploadProgressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progressBarUpload)
        uploadPercentageText = dialogView.findViewById(R.id.tvUploadPercentage)
        uploadDescriptionText = dialogView.findViewById(R.id.tvUploadDescription)
        uploadTitleText = dialogView.findViewById(R.id.tvUploadTitle)
        uploadIconView = dialogView.findViewById(R.id.imgUploadIcon)
        uploadOkButton = dialogView.findViewById(R.id.btnUploadOk)

        uploadOkButton?.setOnClickListener { dismissUploadDialog() }
        uploadDialog = MaterialAlertDialogBuilder(this).setView(dialogView).setCancelable(false).create()
        uploadDialog?.show()
    }

    private fun updateUploadProgress(progress: Int) {
        when (progress) {
            in 0..99 -> {
                uploadProgressBar?.progress = progress
                uploadPercentageText?.text = getString(R.string.status_progress_format, progress)
                uploadDescriptionText?.text = getString(R.string.upload_transferring)
            }
            100 -> {
                uploadProgressBar?.progress = 100
                uploadPercentageText?.text = getString(R.string.status_progress_format, 100)
// Or just "100%" if preferred, but let's use the format if available.
// I'll check if status_progress_format exists.
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
        runOnUiThread {
            val isConnected = bluetoothService?.isConnected == true
            updateStatusUI(status, isConnected)
        }
    }

    override fun onTransportStatusChanged(status: com.ailife.rtosify.communication.TransportManager.TransportStatus) {
        runOnUiThread {
            val statusText = bluetoothService?.currentStatus ?: status.typeString
            updateStatusUI(statusText, status.isConnected)
        }
    }
    override fun onDeviceConnected(deviceName: String) {
        runOnUiThread {
            val connectionStatus = bluetoothService?.transportManager?.getConnectionStatusString()
                ?: getString(R.string.status_connected)
            updateStatusUI(connectionStatus, true)
        }
    }
    override fun onDeviceDisconnected() {
        runOnUiThread {
            val connectionStatus = bluetoothService?.transportManager?.getConnectionStatusString()
                ?: getString(R.string.status_disconnected)
            val isConnected = bluetoothService?.transportManager?.isAnyTransportConnected() == true
            updateStatusUI(connectionStatus, isConnected)
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
    @android.annotation.SuppressLint("MissingPermission")
    override fun onScanResult(devices: List<BluetoothDevice>) {
        runOnUiThread {
            if (devices.isEmpty()) return@runOnUiThread
                val names =
                        devices
                                .map {
                                    "${it.name ?: getString(R.string.device_no_name)} (${it.address})"
                                }
                                .toTypedArray()
                MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.dialog_select_watch_title))
                        .setItems(names) { _, which ->
                            bluetoothService?.connectToDevice(devices[which])
                        }
                        .show()
        }
    }
    override fun onUploadProgress(progress: Int) {
        runOnUiThread { if (uploadDialog?.isShowing == true) updateUploadProgress(progress) }
    }
    override fun onAppListReceived(appsJson: String) {}

    override fun onFileListReceived(path: String, filesJson: String) {}

    override fun onDownloadProgress(progress: Int, file: java.io.File?) {
        runOnUiThread { if (uploadDialog?.isShowing == true) updateUploadProgress(progress) }
    }

    private var hasRequestedHealthData = false

    override fun onWatchStatusUpdated(
            batteryLevel: Int,
            isCharging: Boolean,
            wifiSsid: String,
            wifiEnabled: Boolean,
            dndEnabled: Boolean,
            ipAddress: String?,
            wifiState: String?
    ) {
        // Only use actual SSID when connected, otherwise use empty to prevent status strings from showing
        currentWifiSsid = if (wifiState == "CONNECTED") wifiSsid else ""
        runOnUiThread {
            updateWatchStatusCard(batteryLevel, isCharging, wifiSsid, wifiEnabled, dndEnabled)

            // Update WiFi dialog if it's open
            if (wifiSelectionDialog?.isShowing == true) {
                swWifiToggle?.isChecked = wifiEnabled
                updateCurrentWifiView()
            }
        }

        // Request health data only once when watch status first updates
        if (!hasRequestedHealthData && bluetoothService?.isConnected == true) {
            hasRequestedHealthData = true
            val healthPrefs = getSharedPreferences("health_prefs", Context.MODE_PRIVATE)
            val useInstantSteps = healthPrefs.getBoolean("use_instant_steps", false)
            val stepType = if (useInstantSteps) "RAW" else "TODAY"
            bluetoothService?.requestHealthData(stepType)
            Log.d("MainActivity", "Requesting health data from watch with type: $stepType")
        }
    }

    override fun onWifiScanResultsReceived(results: List<WifiScanResultData>) {
        runOnUiThread {
            if (wifiSelectionDialog != null && wifiSelectionDialog?.isShowing == true) {
                wifiLoadingLayout?.visibility = View.GONE
                swipeRefreshWifi?.isRefreshing = false

                // Filter out current SSID to prevent duplicate
                val filteredResults = results.filter { it.ssid != currentWifiSsid }
                wifiAdapter?.setResults(filteredResults)

                if (filteredResults.isEmpty() && results.isNotEmpty()) {
                    // All discovered networks were hidden because they are currently connected?
                    // Or list is just empty
                } else if (results.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_wifi_no_networks), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onHealthDataUpdated(healthData: HealthDataUpdate) {
        runOnUiThread { updateHealthDataCard(healthData) }
    }

    override fun onDeviceInfoReceived(info: DeviceInfoData) {
        runOnUiThread { updateDeviceInfoUI(info) }
    }

    private inner class WifiResultAdapter(private val onItemClick: (WifiScanResultData) -> Unit) :
            androidx.recyclerview.widget.RecyclerView.Adapter<WifiResultAdapter.ViewHolder>() {

        private var results: List<WifiScanResultData> = emptyList()

        fun setResults(newResults: List<WifiScanResultData>) {
            results = newResults
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view =
                    android.view.LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_wifi_selection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = results[position]
            holder.tvSsid.text = item.ssid
            holder.tvSecurity.text = if (item.isSecure) getString(R.string.wifi_security_secured) else getString(R.string.wifi_security_open)
            holder.imgLock.visibility =
                    if (item.isSecure) android.view.View.VISIBLE else android.view.View.GONE

            holder.imgSignal.setImageResource(R.drawable.ic_wifi)

            // Standard background
            holder.layoutItem.setBackgroundResource(android.R.drawable.list_selector_background)

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = results.size

        inner class ViewHolder(view: android.view.View) :
                androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val tvSsid: android.widget.TextView = view.findViewById(R.id.tvWifiSsid)
            val tvSecurity: android.widget.TextView = view.findViewById(R.id.tvWifiSecurity)
            val imgSignal: android.widget.ImageView = view.findViewById(R.id.imgWifiSignal)
            val imgLock: android.widget.ImageView = view.findViewById(R.id.imgWifiLock)
            val layoutItem: android.widget.LinearLayout = view.findViewById(R.id.layoutWifiItem)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(otaReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        super.onDestroy()
        dismissUploadDialog()
        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }

    private var watchStatusPollingJob: Job? = null

    private fun startWatchStatusPolling() {
        stopWatchStatusPolling()
 

        watchStatusPollingJob = MainScope().launch {
             while (true) {
                 if (bluetoothService?.isConnected == true) {
                     bluetoothService?.requestWatchStatus()
                     bluetoothService?.requestDeviceInfoUpdate()
                 }
                 delay(2000) // Poll every 2 seconds
             }
        }
    }

    private fun stopWatchStatusPolling() {
        watchStatusPollingJob?.cancel()
        watchStatusPollingJob = null
    }
}
