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
    private lateinit var switchDetailedLog: SwitchMaterial // New Switch
    private lateinit var chartCurrent: LineChart // New Chart
    private lateinit var tvLowThresholdTitle: TextView
    private lateinit var seekbarLowThreshold: SeekBar
    private lateinit var tvUsageEmpty: TextView
    private lateinit var rvAppUsage: RecyclerView
    private lateinit var btnSortUsage: TextView
    private var chartBaseTime = System.currentTimeMillis()

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
        switchNotifyFull = findViewById(R.id.switchNotifyFull)
        switchNotifyLow = findViewById(R.id.switchNotifyLow)
        switchDetailedLog = findViewById(R.id.switchDetailedLog) // Init New Switch
        tvLowThresholdTitle = findViewById(R.id.tvLowThresholdTitle)
        seekbarLowThreshold = findViewById(R.id.seekbarLowThreshold)
        tvUsageEmpty = findViewById(R.id.tvUsageEmpty)
        
        chartCurrent = findViewById(R.id.chartCurrent) // Init New Chart
        setupChart(chartCurrent, "Current (mA)")
        seekbarLowThreshold = findViewById(R.id.seekbarLowThreshold)
        tvUsageEmpty = findViewById(R.id.tvUsageEmpty)
        
        rvAppUsage = findViewById(R.id.rvAppUsage)
        rvAppUsage.layoutManager = LinearLayoutManager(this)
        appUsageAdapter = AppUsageAdapter()
        rvAppUsage.adapter = appUsageAdapter
        
        // Chart Styling
        setupChart(chartBattery, "Battery Level")
    }

    private fun setupChart(chart: LineChart, label: String) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setNoDataText("No history available")
        chart.setNoDataTextColor(android.graphics.Color.GRAY)

        val textColor = android.graphics.Color.WHITE
        val gridColor = android.graphics.Color.parseColor("#333333")

        chart.axisLeft.textColor = textColor
        chart.axisLeft.gridColor = gridColor
        chart.axisRight.isEnabled = false
        
        chart.xAxis.isEnabled = true
        chart.xAxis.textColor = textColor
        chart.xAxis.gridColor = gridColor
        chart.xAxis.position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
        chart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                // value is (timestamp - chartBaseTime)
                val diffMillis = (chartBaseTime + value).toLong() - System.currentTimeMillis()
                val diffHours = Math.abs(diffMillis) / (1000 * 3600)
                
                return if (Math.abs(diffMillis) < 5 * 60 * 1000) {
                    "Now"
                } else if (diffHours > 0 && diffHours % 6 == 0L) {
                    "-${diffHours}h"
                } else {
                    ""
                }
            }
        }
        
        chart.legend.isEnabled = false
    }

    private fun setupListeners() {
        switchNotifyFull.setOnCheckedChangeListener { _, isChecked ->
            saveAndSendSettings()
        }

        switchNotifyLow.setOnCheckedChangeListener { _, isChecked ->
            saveAndSendSettings()
        }

        switchDetailedLog.setOnCheckedChangeListener { _, _ ->
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
        val detailedLog = prefs.getBoolean("batt_detailed_log", false)
        val lowThreshold = prefs.getInt("batt_low_threshold", 15)

        switchNotifyFull.isChecked = notifyFull
        switchNotifyLow.isChecked = notifyLow
        switchDetailedLog.isChecked = detailedLog
        seekbarLowThreshold.progress = lowThreshold
        tvLowThresholdTitle.text = "Notify below $lowThreshold%"
    }

    private fun saveAndSendSettings() {
        val notifyFull = switchNotifyFull.isChecked
        val notifyLow = switchNotifyLow.isChecked
        val detailedLog = switchDetailedLog.isChecked
        var lowThreshold = seekbarLowThreshold.progress
        if (lowThreshold < 5) lowThreshold = 5

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("batt_notify_full", notifyFull)
            putBoolean("batt_notify_low", notifyLow)
            putBoolean("batt_detailed_log", detailedLog)
            putInt("batt_low_threshold", lowThreshold)
        }.apply()

        bluetoothService?.sendMessage(ProtocolHelper.createUpdateBatterySettings(
            BatterySettingsData(notifyFull, notifyLow, lowThreshold, detailedLog)
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
                mergedData.isCharging && mergedData.batteryLevel >= 100 -> "Full"
                mergedData.isCharging -> "Charging"
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

            if (mergedData.history.isNotEmpty()) {
                updateChart(mergedData.history)
                updateCurrentChart(mergedData.history)
            }
            
            // Re-calc drain speeds if possible
            val processedAppUsage = calculateAndMergeDrainSpeed(mergedData.appUsage, mergedData.history)

            if (processedAppUsage.isNotEmpty()) {
                appUsageAdapter.updateData(processedAppUsage)
                rvAppUsage.visibility = View.VISIBLE
                tvUsageEmpty.visibility = View.GONE
            } else {
                rvAppUsage.visibility = View.GONE
                tvUsageEmpty.visibility = View.VISIBLE
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
        val now = System.currentTimeMillis()
        chartBaseTime = now
        
        // Use relative time to 'now' to keep values small and preserve precision
        val sortedHistory = history.sortedBy { it.timestamp }
        
        sortedHistory.forEach { point ->
            val relTime = (point.timestamp - chartBaseTime).toFloat()
            entries.add(Entry(relTime, point.level.toFloat()))
        }

        val dataSet = LineDataSet(entries, "Battery Level")
        dataSet.color = android.graphics.Color.parseColor("#00E676")
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)
        dataSet.lineWidth = 2f
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.setDrawFilled(true)
        dataSet.fillColor = android.graphics.Color.parseColor("#00E676")
        dataSet.fillAlpha = 50

        // Configure Axis
        chartBattery.xAxis.axisMinimum = -(24 * 3600 * 1000).toFloat()
        chartBattery.xAxis.axisMaximum = 0f
        chartBattery.xAxis.labelCount = 5
        chartBattery.axisLeft.axisMinimum = 0f
        chartBattery.axisLeft.axisMaximum = 105f

        chartBattery.data = LineData(dataSet)
        chartBattery.invalidate()
    }
    
    private fun updateCurrentChart(history: List<BatteryHistoryPoint>) {
        if (history.isEmpty()) return
        
        val entries = mutableListOf<Entry>()
        val sortedHistory = history.sortedBy { it.timestamp }
        
        // chartBaseTime is set in updateChart, reusing it
        
        sortedHistory.forEach { point ->
            val relTime = (point.timestamp - chartBaseTime).toFloat()
            // Convert to mA for display, microamps -> milliamps
            // Assuming current is in microamperes (as per BatteryManager default)
            // But check if it's large value
            val currentMa = if (Math.abs(point.current) > 5000) point.current / 1000f else point.current.toFloat()
            entries.add(Entry(relTime, currentMa))
        }

        val dataSet = LineDataSet(entries, "Current (mA)")
        dataSet.color = android.graphics.Color.parseColor("#2196F3") // Blue
        dataSet.setDrawCircles(false)
        dataSet.setDrawValues(false)
        dataSet.lineWidth = 2f
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.setDrawFilled(true)
        dataSet.fillColor = android.graphics.Color.parseColor("#2196F3")
        dataSet.fillAlpha = 50

        // Configure Axis
        chartCurrent.xAxis.axisMinimum = -(24 * 3600 * 1000).toFloat()
        chartCurrent.xAxis.axisMaximum = 0f
        chartCurrent.xAxis.labelCount = 5
        
        // Dynamic Y axis for current
        var minCurrent = entries.minOfOrNull { it.y } ?: 0f
        var maxCurrent = entries.maxOfOrNull { it.y } ?: 0f
        // Add some padding
        val range = maxCurrent - minCurrent
        chartCurrent.axisLeft.axisMinimum = minCurrent - (range * 0.1f)
        chartCurrent.axisLeft.axisMaximum = maxCurrent + (range * 0.1f)

        chartCurrent.data = LineData(dataSet)
        chartCurrent.invalidate()
    }

    private fun calculateAndMergeDrainSpeed(
        originalAppUsage: List<AppUsageData>,
        history: List<BatteryHistoryPoint>
    ): List<AppUsageData> {
        if (history.isEmpty()) return originalAppUsage
        
        // Group history by package
        val packageCurrents = mutableMapOf<String, MutableList<Float>>()
        
        history.forEach { point ->
            val pkg = point.packageName
            if (!pkg.isNullOrEmpty()) {
                // Converting to mA. Discharge is negative usually, but we want magnitude of consumption.
                // However, drain speed implies consumption rate.
                // Protocol says currentNow: microamperes.
                // If negative (discharging): -100mA. We want 100mA drain speed.
                
                var currentMa = if (Math.abs(point.current) > 5000) point.current / 1000f else point.current.toFloat()
                
                // Only consider DISCHARGING for drain speed (negative current)
                // Or if it's positive, it means charging, which is 0 drain.
                // Let's filter for negative currents (discharge) to calculate "Drain Speed".
                if (currentMa < 0) {
                     packageCurrents.getOrPut(pkg) { mutableListOf() }.add(Math.abs(currentMa))
                }
            }
        }
        
        val calculatedSpeeds = packageCurrents.mapValues { (_, currents) ->
            if (currents.isEmpty()) 0.0 else currents.average()
        }
        
        // Merge with originalAppUsage
        // If app exists in usage, update drainSpeed.
        // If app exists in history but not usage, CREATE new entry if we have valid drain speed.
        
        val mergedMap = originalAppUsage.associateBy { it.packageName }.toMutableMap()
        
        calculatedSpeeds.forEach { (pkg, speed) ->
             if (mergedMap.containsKey(pkg)) {
                 val original = mergedMap[pkg]!!
                 mergedMap[pkg] = original.copy(drainSpeed = speed)
             } else {
                 // Try to find a name for this package if possible, or use pkg name
                 // Since we don't have icon/name easily here without PackageManager (which is on companion side for watch apps),
                 // we just use package name.
                 // NOTE: This might show ugly package names for apps not in the usage stats list.
                 // But typically if it's in foreground it SHOULD be in usage stats.
                 mergedMap[pkg] = AppUsageData(
                     packageName = pkg, 
                     name = pkg, // Fallbck
                     usageTimeMillis = 0, // Unknown
                     drainSpeed = speed
                 )
             }
        }
        
        return mergedMap.values.toList()
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
