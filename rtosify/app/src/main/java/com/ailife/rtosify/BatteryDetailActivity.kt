package com.ailife.rtosify

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.SeekBar

class BatteryDetailActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private lateinit var appUsageAdapter: AppUsageAdapter

    // UI
    private lateinit var tvBatteryPercentLarge: TextView
    private lateinit var tvChargingStatus: TextView
    private lateinit var progressBattery: ProgressBar
    private lateinit var tvCurrentNow: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var chartBattery: LineChart
    private lateinit var switchNotifyFull: SwitchMaterial
    private lateinit var switchNotifyLow: SwitchMaterial
    private lateinit var tvLowThresholdTitle: TextView
    private lateinit var seekbarLowThreshold: SeekBar
    private lateinit var tvUsageEmpty: TextView
    private lateinit var rvAppUsage: RecyclerView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@BatteryDetailActivity
            isBound = true
            
            // Initial Request
            bluetoothService?.sendMessage(ProtocolHelper.createRequestBatteryDetail())
            
            loadSettings()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery_detail)

        initViews()
        setupListeners()
        
        Intent(this, BluetoothService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (isBound) {
             bluetoothService?.callback = this
             bluetoothService?.sendMessage(ProtocolHelper.createRequestBatteryDetail())
        }
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        
        tvBatteryPercentLarge = findViewById(R.id.tvBatteryPercentLarge)
        tvChargingStatus = findViewById(R.id.tvChargingStatus)
        progressBattery = findViewById(R.id.progressBattery)
        tvCurrentNow = findViewById(R.id.tvCurrentNow)
        tvVoltage = findViewById(R.id.tvVoltage)
        chartBattery = findViewById(R.id.chartBattery)
        switchNotifyFull = findViewById(R.id.switchNotifyFull)
        switchNotifyLow = findViewById(R.id.switchNotifyLow)
        tvLowThresholdTitle = findViewById(R.id.tvLowThresholdTitle)
        seekbarLowThreshold = findViewById(R.id.seekbarLowThreshold)
        tvUsageEmpty = findViewById(R.id.tvUsageEmpty)
        
        rvAppUsage = findViewById(R.id.rvAppUsage)
        rvAppUsage.layoutManager = LinearLayoutManager(this)
        appUsageAdapter = AppUsageAdapter()
        rvAppUsage.adapter = appUsageAdapter
        
        // Chart Styling
        chartBattery.description.isEnabled = false
        chartBattery.setTouchEnabled(false)
        chartBattery.axisRight.isEnabled = false
        chartBattery.xAxis.isEnabled = false
        chartBattery.legend.isEnabled = false
        chartBattery.setNoDataText("No battery history available")
        chartBattery.setNoDataTextColor(android.graphics.Color.GRAY)
    }

    private fun setupListeners() {
        switchNotifyFull.setOnCheckedChangeListener { _, isChecked ->
            saveAndSendSettings()
        }

        switchNotifyLow.setOnCheckedChangeListener { _, isChecked ->
            saveAndSendSettings()
        }

        seekbarLowThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = if (progress < 5) 5 else progress
                    tvLowThresholdTitle.text = "Notify below $value%"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveAndSendSettings()
            }
        })
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val notifyFull = prefs.getBoolean("batt_notify_full", false)
        val notifyLow = prefs.getBoolean("batt_notify_low", false)
        val lowThreshold = prefs.getInt("batt_low_threshold", 15)

        switchNotifyFull.isChecked = notifyFull
        switchNotifyLow.isChecked = notifyLow
        seekbarLowThreshold.progress = lowThreshold
        tvLowThresholdTitle.text = "Notify below $lowThreshold%"
    }

    private fun saveAndSendSettings() {
        val notifyFull = switchNotifyFull.isChecked
        val notifyLow = switchNotifyLow.isChecked
        var lowThreshold = seekbarLowThreshold.progress
        if (lowThreshold < 5) lowThreshold = 5

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("batt_notify_full", notifyFull)
            putBoolean("batt_notify_low", notifyLow)
            putInt("batt_low_threshold", lowThreshold)
        }.apply()

        bluetoothService?.sendMessage(ProtocolHelper.createUpdateBatterySettings(
            BatterySettingsData(notifyFull, notifyLow, lowThreshold)
        ))
    }

    // ServiceCallback
    override fun onBatteryDetailReceived(data: BatteryDetailData) {
        runOnUiThread {
            tvBatteryPercentLarge.text = "${data.batteryLevel}%"
            progressBattery.progress = data.batteryLevel
            
            val statusText = when {
                data.isCharging -> "Charging"
                data.batteryLevel == 100 -> "Full"
                else -> "Discharging"
            }
            tvChargingStatus.text = statusText

            tvCurrentNow.text = "${data.currentNow} mA"
            tvVoltage.text = "${data.voltage} mV"

            if (data.appUsage.isNotEmpty()) {
                appUsageAdapter.updateData(data.appUsage)
                rvAppUsage.visibility = View.VISIBLE
                tvUsageEmpty.visibility = View.GONE
            } else {
                rvAppUsage.visibility = View.GONE
                tvUsageEmpty.visibility = View.VISIBLE
            }
        }
    }
    
    // Stub other callbacks
    override fun onStatusChanged(status: String) {}
    override fun onDeviceConnected(deviceName: String) {}
    override fun onDeviceDisconnected() { 
        // Optional: finish() 
    }
    override fun onError(message: String) {}
    override fun onScanResult(devices: List<android.bluetooth.BluetoothDevice>) {}
    override fun onAppListReceived(appsJson: String) {}
    override fun onUploadProgress(progress: Int) {}
    override fun onDownloadProgress(progress: Int) {}
    override fun onFileListReceived(path: String, filesJson: String) {}
}
