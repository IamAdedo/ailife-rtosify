package com.iamadedo.phoneapp

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

    // Readiness + Energy + Anti-lost UI
    private var layoutReadinessRow: android.view.View? = null
    private var tvReadinessScore: TextView? = null
    private var tvReadinessLabel: TextView? = null
    private var tvEnergyScore: TextView? = null
    private var tvEnergyLabel: TextView? = null
    private var cardAntiLostAlert: com.google.android.material.card.MaterialCardView? = null
    private var tvAntiLostMessage: TextView? = null

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
            if (intent.action == "com.iamadedo.phoneapp.ACTION_SEND_OTA") {
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
        val otaFilter = IntentFilter("com.iamadedo.phoneapp.ACTION_SEND_OTA")
        ContextCompat.registerReceiver(this, otaReceiver, otaFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        com.iamadedo.phoneapp.utils.OtaManager(this).checkUpdate(silent = true)

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

        // New readiness + energy + anti-lost views (nullable — added by us, may not exist in older layouts)
        layoutReadinessRow = findViewById(R.id.layoutReadinessRow)
        tvReadinessScore   = findViewById(R.id.tvReadinessScore)
        tvReadinessLabel   = findViewById(R.id.tvReadinessLabel)
        tvEnergyScore      = findViewById(R.id.tvEnergyScore)
        tvEnergyLabel      = findViewById(R.id.tvEnergyLabel)
        cardAntiLostAlert  = findViewById(R.id.cardAntiLostAlert)
        tvAntiLostMessage  = findViewById(R.id.tvAntiLostMessage)
        findViewById<android.widget.Button?>(R.id.btnAntiLostDismiss)?.setOnClickListener {
            cardAntiLostAlert?.visibility = android.view.View.GONE
        }

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

        // Update readiness + energy score tiles from SharedPreferences (written by BluetoothService)
        val healthSummary = getSharedPreferences("health_summary", Context.MODE_PRIVATE)
        val readiness = healthSummary.getInt("readiness_score", -1)
        val energy    = healthSummary.getInt("energy_score", -1)
        if (readiness >= 0 || energy >= 0) {
            layoutReadinessRow?.visibility = android.view.View.VISIBLE
        }
        if (readiness >= 0) {
            tvReadinessScore?.text = readiness.toString()
            tvReadinessLabel?.text = when {
                readiness >= 70 -> getString(R.string.readiness_push)
                readiness >= 45 -> getString(R.string.readiness_moderate)
                else            -> getString(R.string.readiness_recover)
            }
        }
        if (energy >= 0) {
            tvEnergyScore?.text = energy.toString()
            tvEnergyLabel?.text = when {
                energy >= 75 -> "High energy"
                energy >= 50 -> "Moderate"
                else         -> "Low energy"
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
                        .show()

        devices.forEach { device ->
            val itemView = layoutInflater.inflate(R.layout.item_device_picker, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvDeviceName)
            val tvMac = itemView.findViewById<TextView>(R.id.tvDeviceMac)
            val imgCheck = itemView.findViewById<ImageView>(R.id.imgSelected)

            tvName.text = device.name
            tvMac.text = device.mac

            if (device.mac == currentMac) {
                imgCheck.visibility = View.VISIBLE
            } else {
                imgCheck.visibility = View.GONE
            }

            itemView.setOnClickListener {
                devicePrefManager.setSelectedDeviceMac(device.mac)
                bluetoothService?.reconnect()
                dialog.dismiss()
            }

            container.addView(itemView)
        }

        btnPairNew.setOnClickListener {
            pairNewDeviceLauncher.launch(Intent(this, PairNewDeviceActivity::class.java))
            dialog.dismiss()
        }
    }

    // ==================== SERVICE CONNECTION ====================
    private fun bindToService() {
        val intent = Intent(this, BluetoothService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
    }

    // ==================== CALLBACKS ====================
    override fun onStatusChanged(status: String) {
        updateStatusUI(status, bluetoothService?.isConnected == true)
    }

    override fun onDeviceConnected(deviceName: String) {
        tvHeaderDeviceName.text = deviceName
        updateStatusUI(getString(R.string.status_connected), true)
        refreshMenu()
    }

    override fun onDeviceDisconnected() {
        updateStatusUI(getString(R.string.status_disconnected), false)
        refreshMenu()
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onScanResult(devices: List<BluetoothDevice>) {}

    override fun onAppListReceived(appsJson: String) {}

    override fun onUploadProgress(progress: Int) {}

    override fun onDownloadProgress(progress: Int, file: java.io.File?) {}

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
        currentDndState = dndEnabled
        currentWifiState = wifiEnabled
        currentWifiSsid = wifiSsid

        tvBatteryPercent.text = "$batteryLevel%"

        if (isCharging) {
            imgBatteryIcon.setImageResource(R.drawable.ic_battery_charging)
        } else {
            imgBatteryIcon.setImageResource(R.drawable.ic_battery)
        }

        updateWifiUI(wifiEnabled)
        updateDndUI(dndEnabled)
    }

    override fun onHealthDataUpdated(healthData: HealthDataUpdate) {
        updateHealthDataCard(healthData)
    }

    override fun onHealthHistoryReceived(historyData: HealthHistoryResponse) {}

    override fun onHealthSettingsReceived(settings: HealthSettingsUpdate) {}

    override fun onPreviewReceived(path: String, imageBase64: String?, textContent: String?) {}

    override fun onWifiScanResultsReceived(results: List<WifiScanResultData>) {
        wifiAdapter?.setResults(results)
        swipeRefreshWifi?.isRefreshing = false
    }

    override fun onBatteryDetailReceived(data: BatteryDetailData) {}

    override fun onDeviceInfoReceived(info: DeviceInfoData) {
        updateDeviceInfo(info)
    }

    override fun onShellCommandResponse(response: ShellCommandResponse) {}

    override fun onPermissionInfoReceived(info: PermissionInfoData) {}

    override fun onWifiKeyAck(success: Boolean) {}

    override fun onWifiTestAck(success: Boolean) {}

    override fun onWifiTestReceived(message: String) {}

    override fun onTransferCancelled() {}

    override fun onAntiLostAlert(message: String) {
        cardAntiLostAlert?.visibility = android.view.View.VISIBLE
        tvAntiLostMessage?.text = message
    }

    override fun onAntiLostResolved() {
        cardAntiLostAlert?.visibility = android.view.View.GONE
    }

    // ============================================
    private fun updateStatusUI(status: String, isConnected: Boolean) {
        tvHeaderStatus.text = status
        if (isConnected) {
            progressBarMain.visibility = View.GONE
        } else {
            progressBarMain.visibility = View.VISIBLE
        }
    }

    private fun updateWifiUI(wifiEnabled: Boolean) {
        imgWifiIcon.setImageResource(
                if (wifiEnabled) R.drawable.ic_wifi else R.drawable.ic_wifi_off
        )
    }

    private fun updateDndUI(dndEnabled: Boolean) {
        val color = if (dndEnabled) Color.parseColor("#FF6B6B") else Color.parseColor("#999999")
        imgDndIcon.setImageTintList(ColorStateList.valueOf(color))
    }

    private fun setupPhoneMenu() {
        val menuItems = listOf(
            MenuItem(R.string.menu_sync_clipboard, R.drawable.ic_menu_sync),
            MenuItem(R.string.menu_mirroring, R.drawable.ic_menu_mirror),
            MenuItem(R.string.menu_file_manager, R.drawable.ic_menu_files),
            MenuItem(R.string.menu_app_manager, R.drawable.ic_menu_apps),
            MenuItem(R.string.menu_settings, R.drawable.ic_menu_settings),
            MenuItem(R.string.menu_health_data, R.drawable.ic_menu_health),
        )
        menuAdapter = MenuAdapter(menuItems) { position ->
            when (position) {
                0 -> startActivity(Intent(this, ClipboardActivity::class.java))
                1 -> startPhoneMirroring()
                2 -> startActivity(Intent(this, FileManagerActivity::class.java))
                3 -> startActivity(Intent(this, AppManagerActivity::class.java))
                4 -> startActivity(Intent(this, SettingsActivity::class.java))
                5 -> startActivity(Intent(this, HealthDataActivity::class.java))
            }
        }
        recyclerViewMenu.adapter = menuAdapter
        recyclerViewMenu.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    }

    private fun refreshMenu() {
        setupPhoneMenu()
    }

    private fun runIfConnected(action: () -> Unit) {
        if (bluetoothService?.isConnected == true) {
            action()
        } else {
            Toast.makeText(
                    this,
                    R.string.toast_watch_not_connected,
                    Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startWatchStatusPolling() {
        // Implement if needed
    }

    private fun stopWatchStatusPolling() {
        // Implement if needed
    }

    private fun updateDeviceInfo(info: DeviceInfoData) {
        tvDeviceInfoModel.text = info.model
        tvDeviceInfoAndroid.text = "Android ${info.androidVersion}"
        tvDeviceInfoRam.text = "${info.ramGB}GB"
        tvDeviceInfoStorage.text = "${info.storageGB}GB"
        tvDeviceInfoProcessor.text = info.processor
        tvDeviceInfoCpu.text = info.cpuCores
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
