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
    private lateinit var tvRemainingTime: TextView
    private lateinit var chartBattery: LineChart
    private lateinit var switchNotifyFull: SwitchMaterial
    private lateinit var switchNotifyLow: SwitchMaterial
    private lateinit var tvLowThresholdTitle: TextView
    private lateinit var seekbarLowThreshold: SeekBar
    private lateinit var tvUsageEmpty: TextView
    private lateinit var rvAppUsage: RecyclerView
    private lateinit var btnSortUsage: TextView

    private var currentBatteryData: BatteryDetailData? = null
    private val timeHistory = mutableListOf<Long>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@BatteryDetailActivity
            isBound = true
            
            // Initial Request
            bluetoothService?.sendMessage(ProtocolHelper.createRequestBatteryStatic())
            startPolling()
            
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
             bluetoothService?.sendMessage(ProtocolHelper.createRequestBatteryStatic())
             startPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (isBound && bluetoothService?.isConnected == true) {
                bluetoothService?.sendMessage(ProtocolHelper.createRequestBatteryLive())
            }
            mainHandler.postDelayed(this, 2000)
        }
    }

    private fun startPolling() {
        stopPolling()
        mainHandler.post(pollingRunnable)
    }

    private fun stopPolling() {
        mainHandler.removeCallbacks(pollingRunnable)
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        
        tvBatteryPercentLarge = findViewById(R.id.tvBatteryPercentLarge)
        tvChargingStatus = findViewById(R.id.tvChargingStatus)
        progressBattery = findViewById(R.id.progressBattery)
        tvCurrentNow = findViewById(R.id.tvCurrentNow)
        tvVoltage = findViewById(R.id.tvVoltage)
        tvRemainingTime = findViewById(R.id.tvRemainingTime)
        chartBattery = findViewById(R.id.chartBattery)
        btnSortUsage = findViewById(R.id.btnSortUsage)
        btnSortUsage.setOnClickListener { showSortMenu() }
        
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
        chartBattery.setNoDataText("No battery history available")
        chartBattery.setNoDataTextColor(android.graphics.Color.GRAY)

        val textColor = android.graphics.Color.WHITE
        val gridColor = android.graphics.Color.parseColor("#333333")

        chartBattery.axisLeft.textColor = textColor
        chartBattery.axisLeft.gridColor = gridColor
        chartBattery.axisRight.isEnabled = false
        
        chartBattery.xAxis.isEnabled = true
        chartBattery.xAxis.textColor = textColor
        chartBattery.xAxis.gridColor = gridColor
        chartBattery.xAxis.position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
        chartBattery.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                // Show -24h, -12h, Now for example
                val size = currentBatteryData?.history?.size ?: 0
                if (size == 0) return ""
                val percent = value / (size - 1)
                return when {
                    percent < 0.1f -> "-24h"
                    percent > 0.45f && percent < 0.55f -> "-12h"
                    percent > 0.9f -> "Now"
                    else -> ""
                }
            }
        }
        
        chartBattery.legend.isEnabled = false
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
        // Merge state: keep old appUsage/history if new data doesn't have it
        val mergedData = if (data.appUsage.isEmpty() && currentBatteryData != null) {
            data.copy(appUsage = currentBatteryData!!.appUsage, history = currentBatteryData!!.history)
        } else {
            data
        }
        currentBatteryData = mergedData

        runOnUiThread {
            tvBatteryPercentLarge.text = "${mergedData.batteryLevel}%"
            progressBattery.progress = mergedData.batteryLevel
            
            val statusText = when {
                data.isCharging -> "Charging"
                data.batteryLevel == 100 -> "Full"
                else -> "Discharging"
            }
            tvChargingStatus.text = statusText

            mergedData.remainingTimeMillis?.let { millis ->
                timeHistory.add(millis)
                if (timeHistory.size > 10) timeHistory.removeAt(0)
                
                if (timeHistory.size < 10) {
                    tvRemainingTime.text = "Calculating..."
                } else {
                    val avgMillis = timeHistory.average().toLong()
                    val hours = avgMillis / (1000 * 60 * 60)
                    val mins = (avgMillis / (1000 * 60)) % 60
                    val timeStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                    tvRemainingTime.text = if (mergedData.isCharging) "$timeStr until full" else "$timeStr remaining"
                }
                tvRemainingTime.visibility = View.VISIBLE
            } ?: run {
                timeHistory.clear()
                tvRemainingTime.visibility = View.GONE
            }

            // Units and values
            // Detection heuristic: if abs(current) > 10000, it's almost certainly microamperes.
            val currentVal = mergedData.currentNow
            val displayCurrent = if (Math.abs(currentVal) > 5000) {
                currentVal / 1000.0
            } else {
                currentVal.toDouble()
            }
            tvCurrentNow.text = String.format("%.1f mA", displayCurrent)
            tvVoltage.text = "${mergedData.voltage} mV"

            if (mergedData.appUsage.isNotEmpty()) {
                appUsageAdapter.updateData(mergedData.appUsage)
                rvAppUsage.visibility = View.VISIBLE
                tvUsageEmpty.visibility = View.GONE
            }

            if (mergedData.history.isNotEmpty()) {
                updateChart(mergedData.history)
            }
        }
    }

    private fun showSortMenu() {
        val popup = android.widget.PopupMenu(this, btnSortUsage)
        popup.menu.add("Usage Time")
        popup.menu.add("Battery Used (mAh)")
        popup.menu.add("Drain Speed (mAh/h)")
        
        popup.setOnMenuItemClickListener { item ->
            val order = when (item.title) {
                "Usage Time" -> AppUsageAdapter.SortOrder.TIME
                "Battery Used (mAh)" -> AppUsageAdapter.SortOrder.POWER
                "Drain Speed (mAh/h)" -> AppUsageAdapter.SortOrder.SPEED
                else -> AppUsageAdapter.SortOrder.TIME
            }
            btnSortUsage.text = "Sort by ${item.title}"
            currentBatteryData?.appUsage?.let {
                appUsageAdapter.updateData(it, order)
            }
            true
        }
        popup.show()
    }

    private fun updateChart(history: List<BatteryHistoryPoint>) {
        if (history.isEmpty()) return
        
        val entries = mutableListOf<Entry>()
        
        // Add boundary point at start with same level as first data point
        entries.add(Entry(0f, history.first().level.toFloat()))
        
        // Add actual data points
        history.forEachIndexed { index, point ->
            entries.add(Entry((index + 1).toFloat(), point.level.toFloat()))
        }
        
        // Add boundary point at end with same level as last data point
        entries.add(Entry((history.size + 1).toFloat(), history.last().level.toFloat()))

        val dataSet = LineDataSet(entries, "Battery Level")
        dataSet.color = android.graphics.Color.parseColor("#00E676")
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)
        dataSet.valueTextColor = android.graphics.Color.WHITE
        dataSet.lineWidth = 2f
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.setDrawFilled(true)
        dataSet.fillColor = android.graphics.Color.parseColor("#00E676")
        dataSet.fillAlpha = 50

        chartBattery.data = LineData(dataSet)
        chartBattery.invalidate()
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
