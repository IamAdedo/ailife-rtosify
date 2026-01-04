package com.ailife.rtosify

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HealthDetailActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    // UI Components
    private lateinit var toolbar: Toolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var togglePeriod: MaterialButtonToggleGroup
    private lateinit var bottomNavigation: BottomNavigationView

    // Current Value Card (HR/Oxygen)
    private lateinit var cardCurrentValue: MaterialCardView
    private lateinit var tvCurrentValue: TextView
    private lateinit var tvLastMeasured: TextView
    private lateinit var btnMeasureNow: Button

    // Total Stats Card (Steps)
    private lateinit var cardTotalStats: MaterialCardView
    private lateinit var tvTotalSteps: TextView
    private lateinit var tvTotalDistance: TextView
    private lateinit var tvTotalCalories: TextView

    // Goal Progress Card (Steps)
    private lateinit var cardGoalProgress: MaterialCardView
    private lateinit var tvGoalTitle: TextView
    private lateinit var progressGoal: ProgressBar
    private lateinit var tvGoalProgress: TextView

    // Chart and Stats
    private lateinit var chart: LineChart
    private lateinit var tvEmptyData: TextView
    private lateinit var layoutStats: LinearLayout
    private lateinit var tvMinValue: TextView
    private lateinit var tvAvgValue: TextView
    private lateinit var tvMaxValue: TextView

    private lateinit var fabMeasureNow: FloatingActionButton

    // State Variables
    private var healthType: String = "STEPS" // "STEPS", "HEART_RATE", "OXYGEN"
    private var currentPeriod: Period = Period.DAY
    private var isLiveMeasuring: Boolean = false
    private var currentHistoryData: HealthHistoryResponse? = null
    private var currentGoal: Int? = null
    private var lastHealthData: HealthDataUpdate? = null

    // Settings Dialog Views (for populating when settings are received)
    private var settingsDialogAge: EditText? = null
    private var settingsDialogGender: Spinner? = null
    private var settingsDialogHeight: EditText? = null
    private var settingsDialogWeight: EditText? = null
    private var settingsDialogStepGoal: EditText? = null
    private var settingsDialogInterval: EditText? = null
    private var settingsDialogBackground: SwitchCompat? = null

    // Service Binding
    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@HealthDetailActivity
            isBound = true

            // Request initial data
            requestHistoricalData()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    enum class Period {
        DAY, WEEK, MONTH
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_detail)

        // Get health type from intent
        healthType = intent.getStringExtra("HEALTH_TYPE") ?: "STEPS"

        initViews()
        setupToolbar()
        setupChart()
        setupPeriodToggle()
        setupSwipeRefresh()
        setupBottomNavigation()
        setupCards()

        // FIX: Initialize X-axis formatter on load
        updateChartXAxisFormatter()

        // Bind to service
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        togglePeriod = findViewById(R.id.togglePeriod)
        bottomNavigation = findViewById(R.id.bottomNavigation)

        cardCurrentValue = findViewById(R.id.cardCurrentValue)
        tvCurrentValue = findViewById(R.id.tvCurrentValue)
        tvLastMeasured = findViewById(R.id.tvLastMeasured)
        btnMeasureNow = findViewById(R.id.btnMeasureNow)

        cardTotalStats = findViewById(R.id.cardTotalStats)
        tvTotalSteps = findViewById(R.id.tvTotalSteps)
        tvTotalDistance = findViewById(R.id.tvTotalDistance)
        tvTotalCalories = findViewById(R.id.tvTotalCalories)

        cardGoalProgress = findViewById(R.id.cardGoalProgress)
        tvGoalTitle = findViewById(R.id.tvGoalTitle)
        progressGoal = findViewById(R.id.progressGoal)
        tvGoalProgress = findViewById(R.id.tvGoalProgress)

        chart = findViewById(R.id.chart)
        tvEmptyData = findViewById(R.id.tvEmptyData)
        layoutStats = findViewById(R.id.layoutStats)
        tvMinValue = findViewById(R.id.tvMinValue)
        tvAvgValue = findViewById(R.id.tvAvgValue)
        tvMaxValue = findViewById(R.id.tvMaxValue)

        fabMeasureNow = findViewById(R.id.fabMeasureNow)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        updateToolbarTitle()
    }

    private fun updateToolbarTitle() {
        supportActionBar?.title = when (healthType) {
            "STEPS" -> getString(R.string.health_steps_title)
            "HEART_RATE" -> getString(R.string.health_hr_title)
            "OXYGEN" -> getString(R.string.health_oxygen_title)
            else -> getString(R.string.health_title)
        }
    }

    private fun setupChart() {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)

            // X-axis configuration
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                granularity = 1f
                textColor = ContextCompat.getColor(this@HealthDetailActivity, android.R.color.white)
            }

            // Y-axis configuration
            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 0f
                textColor = ContextCompat.getColor(this@HealthDetailActivity, android.R.color.white)
            }
            axisRight.isEnabled = false

            // Legend
            legend.apply {
                isEnabled = true
                textColor = ContextCompat.getColor(this@HealthDetailActivity, android.R.color.white)
            }
        }
    }

    private fun updateChartXAxisFormatter() {
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            private val dateFormat = when (currentPeriod) {
                Period.DAY -> SimpleDateFormat("HH:mm", Locale.getDefault())
                Period.WEEK, Period.MONTH -> SimpleDateFormat("MM/dd", Locale.getDefault())
            }

            override fun getFormattedValue(value: Float): String {
                return dateFormat.format(Date(value.toLong() * 1000))
            }
        }
        chart.invalidate()
    }

    private fun setupPeriodToggle() {
        // Set initial checked button
        togglePeriod.check(R.id.btnDay)

        togglePeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentPeriod = when (checkedId) {
                    R.id.btnDay -> Period.DAY
                    R.id.btnWeek -> Period.WEEK
                    R.id.btnMonth -> Period.MONTH
                    else -> Period.DAY
                }
                updateChartXAxisFormatter()
                requestHistoricalData()
            }
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            requestHistoricalData()
        }
    }

    private fun setupBottomNavigation() {
        // Set initial selection
        bottomNavigation.selectedItemId = when (healthType) {
            "STEPS" -> R.id.nav_steps
            "HEART_RATE" -> R.id.nav_heart_rate
            "OXYGEN" -> R.id.nav_oxygen
            else -> R.id.nav_steps
        }

        bottomNavigation.setOnItemSelectedListener { item ->
            val newType = when (item.itemId) {
                R.id.nav_steps -> "STEPS"
                R.id.nav_heart_rate -> "HEART_RATE"
                R.id.nav_oxygen -> "OXYGEN"
                else -> "STEPS"
            }

            if (newType != healthType) {
                switchHealthType(newType)
            }
            true
        }
    }

    private fun switchHealthType(newType: String) {
        healthType = newType
        updateToolbarTitle()
        setupCards()
        requestHistoricalData()
    }

    private fun setupCards() {
        // Show appropriate cards based on health type
        when (healthType) {
            "STEPS" -> {
                cardCurrentValue.visibility = View.GONE
                cardTotalStats.visibility = View.VISIBLE
                cardGoalProgress.visibility = View.VISIBLE
                fabMeasureNow.visibility = View.GONE
            }
            "HEART_RATE", "OXYGEN" -> {
                cardCurrentValue.visibility = View.VISIBLE
                cardTotalStats.visibility = View.GONE
                cardGoalProgress.visibility = View.GONE
                fabMeasureNow.visibility = View.GONE

                btnMeasureNow.setOnClickListener {
                    if (isLiveMeasuring) {
                        stopLiveMeasurement()
                    } else {
                        startLiveMeasurement()
                    }
                }
            }
        }
    }

    private fun requestHistoricalData() {
        if (!isBound || bluetoothService == null) {
            Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT).show()
            swipeRefresh.isRefreshing = false
            return
        }

        val (startTime, endTime) = getTimeRange()
        val type = when (healthType) {
            "STEPS" -> "STEP"
            "HEART_RATE" -> "HR"
            "OXYGEN" -> "OXYGEN"
            else -> "STEP"
        }

        // Request historical data for the chart
        bluetoothService?.requestHealthHistory(type, startTime, endTime)

        // Also request current health data to populate the current value card (HR/Oxygen)
        bluetoothService?.requestHealthData()
    }

    private fun getTimeRange(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val endTime = now / 1000 // Convert to Unix seconds

        val startTime = when (currentPeriod) {
            Period.DAY -> (now - TimeUnit.HOURS.toMillis(24)) / 1000
            Period.WEEK -> (now - TimeUnit.DAYS.toMillis(7)) / 1000
            Period.MONTH -> (now - TimeUnit.DAYS.toMillis(30)) / 1000
        }

        return Pair(startTime, endTime)
    }

    private fun startLiveMeasurement() {
        if (!isBound || bluetoothService == null) return

        val type = when (healthType) {
            "HEART_RATE" -> "HR"
            "OXYGEN" -> "OXYGEN"
            else -> return
        }

        bluetoothService?.startLiveMeasurement(type)
        isLiveMeasuring = true

        // Update UI
        tvCurrentValue.text = "--"
        tvLastMeasured.text = getString(R.string.health_measuring)
        btnMeasureNow.text = "Stop"
        Toast.makeText(this, getString(R.string.health_measuring), Toast.LENGTH_SHORT).show()
    }

    private fun stopLiveMeasurement() {
        if (!isBound || bluetoothService == null) return

        bluetoothService?.stopLiveMeasurement()
        isLiveMeasuring = false

        // Restore button text
        btnMeasureNow.text = "Measure"
    }

    private fun updateChart(historyData: HealthHistoryResponse) {
        currentHistoryData = historyData
        currentGoal = historyData.goal

        if (historyData.dataPoints.isEmpty()) {
            chart.visibility = View.GONE
            tvEmptyData.visibility = View.VISIBLE
            layoutStats.visibility = View.GONE
            tvEmptyData.text = getString(R.string.health_no_data)
            return
        }

        chart.visibility = View.VISIBLE
        tvEmptyData.visibility = View.GONE
        layoutStats.visibility = View.VISIBLE

        // FIX: Add start and end points to ensure proper graph range
        val (startTime, endTime) = getTimeRange()
        val dataPoints = historyData.dataPoints.toMutableList()

        // Add start point if no data at the beginning
        if (dataPoints.isEmpty() || dataPoints.first().timestamp > startTime) {
            dataPoints.add(0, HealthDataPoint(startTime, 0f))
        }

        // Add end point if no data at the end
        if (dataPoints.isEmpty() || dataPoints.last().timestamp < endTime) {
            dataPoints.add(HealthDataPoint(endTime, 0f))
        }

        // Convert data points to chart entries
        val entries = dataPoints.map { point ->
            Entry(point.timestamp.toFloat(), point.value)
        }

        val dataSet = LineDataSet(entries, getChartLabel()).apply {
            color = getChartColor()
            setCircleColor(getChartColor())
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircleHole(false)
            valueTextSize = 0f // Hide values on points
            mode = LineDataSet.Mode.LINEAR
        }

        chart.data = LineData(dataSet)

        // Add goal line for steps
        if (healthType == "STEPS" && currentGoal != null) {
            val limitLine = LimitLine(currentGoal!!.toFloat(), getString(R.string.health_daily_goal))
            limitLine.lineWidth = 2f
            limitLine.lineColor = Color.GREEN
            limitLine.enableDashedLine(10f, 10f, 0f)
            limitLine.labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
            limitLine.textColor = Color.WHITE
            chart.axisLeft.removeAllLimitLines()
            chart.axisLeft.addLimitLine(limitLine)
        }

        chart.invalidate()

        // Update stats (exclude the added start/end points)
        updateStats(historyData.dataPoints)

        // Update type-specific cards
        when (healthType) {
            "STEPS" -> updateStepCards(historyData.dataPoints)
            "HEART_RATE", "OXYGEN" -> updateCurrentValueCard()
        }
    }

    private fun updateStats(dataPoints: List<HealthDataPoint>) {
        if (dataPoints.isEmpty()) return

        // Filter out zero values for stats calculation
        val nonZeroValues = dataPoints.filter { it.value > 0 }.map { it.value }
        if (nonZeroValues.isEmpty()) {
            tvMinValue.text = "0"
            tvAvgValue.text = "0"
            tvMaxValue.text = "0"
            return
        }

        val min = nonZeroValues.minOrNull() ?: 0f
        val max = nonZeroValues.maxOrNull() ?: 0f
        val avg = nonZeroValues.average().toFloat()

        tvMinValue.text = String.format("%.0f", min)
        tvAvgValue.text = String.format("%.0f", avg)
        tvMaxValue.text = String.format("%.0f", max)
    }

    private fun updateStepCards(dataPoints: List<HealthDataPoint>) {
        val totalSteps = dataPoints.sumOf { it.value.toInt() }

        // Calculate distance and calories (rough estimates)
        val distanceKm = (totalSteps * 0.0007f) // Approx 0.7m per step
        val calories = (totalSteps * 0.04f).toInt() // Approx 0.04 calories per step

        tvTotalSteps.text = String.format("%,d", totalSteps)
        tvTotalDistance.text = String.format("%.1f km", distanceKm)
        tvTotalCalories.text = calories.toString()

        // Update goal progress
        if (currentGoal != null) {
            val percentage = ((totalSteps.toFloat() / currentGoal!!) * 100).toInt().coerceIn(0, 100)
            progressGoal.progress = percentage
            tvGoalProgress.text = getString(R.string.health_goal_progress, percentage)
        }
    }

    private fun updateCurrentValueCard() {
        lastHealthData?.let { data ->
            val value = when (healthType) {
                "HEART_RATE" -> data.heartRate
                "OXYGEN" -> data.bloodOxygen
                else -> null
            }

            val timestamp = when (healthType) {
                "HEART_RATE" -> data.heartRateTimestamp
                "OXYGEN" -> data.oxygenTimestamp
                else -> null
            }

            if (value != null) {
                val unit = when (healthType) {
                    "HEART_RATE" -> " bpm"
                    "OXYGEN" -> "%"
                    else -> ""
                }
                tvCurrentValue.text = "$value$unit"

                if (timestamp != null) {
                    tvLastMeasured.text = formatTimeAgo(timestamp)
                }
            } else {
                tvCurrentValue.text = "--"
                tvLastMeasured.text = "No recent data"
            }
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

    private fun getChartLabel(): String {
        return when (healthType) {
            "STEPS" -> getString(R.string.health_steps)
            "HEART_RATE" -> "Heart Rate (bpm)"
            "OXYGEN" -> "Blood Oxygen (%)"
            else -> ""
        }
    }

    private fun getChartColor(): Int {
        return when (healthType) {
            "STEPS" -> Color.parseColor("#1976D2") // Blue
            "HEART_RATE" -> Color.parseColor("#D32F2F") // Red
            "OXYGEN" -> Color.parseColor("#0097A7") // Cyan
            else -> Color.BLUE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_health_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_health_settings, null)

        val etAge = dialogView.findViewById<EditText>(R.id.etAge)
        val spinnerGender = dialogView.findViewById<Spinner>(R.id.spinnerGender)
        val etHeight = dialogView.findViewById<EditText>(R.id.etHeight)
        val etWeight = dialogView.findViewById<EditText>(R.id.etWeight)
        val etStepGoal = dialogView.findViewById<EditText>(R.id.etStepGoal)
        val etInterval = dialogView.findViewById<EditText>(R.id.etMonitoringInterval)
        val switchBackground = dialogView.findViewById<SwitchCompat>(R.id.switchBackgroundMonitoring)

        // Store references for population when settings are received
        settingsDialogAge = etAge
        settingsDialogGender = spinnerGender
        settingsDialogHeight = etHeight
        settingsDialogWeight = etWeight
        settingsDialogStepGoal = etStepGoal
        settingsDialogInterval = etInterval
        settingsDialogBackground = switchBackground

        // Request current settings from watch
        bluetoothService?.requestHealthSettings()

        // Show/hide step goal based on type
        dialogView.findViewById<View>(R.id.layoutStepGoal).visibility =
            if (healthType == "STEPS") View.VISIBLE else View.GONE

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.health_settings))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val gender = spinnerGender.selectedItem.toString()
                val settings = HealthSettingsUpdate(
                    stepGoal = if (healthType == "STEPS") etStepGoal.text.toString().toIntOrNull() else null,
                    interval = etInterval.text.toString().toIntOrNull(),
                    backgroundEnabled = switchBackground.isChecked,
                    monitoringTypes = null, // Will be set by watch based on enabled types
                    age = etAge.text.toString().toIntOrNull(),
                    gender = gender,
                    height = etHeight.text.toString().toIntOrNull(),
                    weight = etWeight.text.toString().toFloatOrNull()
                )
                bluetoothService?.updateHealthSettings(settings)
                Toast.makeText(this, getString(R.string.toast_command_sent), Toast.LENGTH_SHORT).show()

                // Clear dialog view references
                clearSettingsDialogReferences()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Clear dialog view references
                clearSettingsDialogReferences()
            }
            .setOnDismissListener {
                // Clear dialog view references when dismissed
                clearSettingsDialogReferences()
            }
            .show()
    }

    private fun clearSettingsDialogReferences() {
        settingsDialogAge = null
        settingsDialogGender = null
        settingsDialogHeight = null
        settingsDialogWeight = null
        settingsDialogStepGoal = null
        settingsDialogInterval = null
        settingsDialogBackground = null
    }

    // ServiceCallback implementations
    override fun onHealthHistoryReceived(historyData: HealthHistoryResponse) {
        runOnUiThread {
            swipeRefresh.isRefreshing = false

            if (historyData.errorState != null) {
                Toast.makeText(this, historyData.errorState, Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }

            updateChart(historyData)
        }
    }

    override fun onHealthDataUpdated(healthData: HealthDataUpdate) {
        lastHealthData = healthData

        runOnUiThread {
            // Update current value card for HR/Oxygen
            if (healthType == "HEART_RATE" || healthType == "OXYGEN") {
                updateCurrentValueCard()
            }

            // Handle live measurement updates
            if (!isLiveMeasuring) return@runOnUiThread

            val value = when (healthType) {
                "HEART_RATE" -> healthData.heartRate?.toFloat()
                "OXYGEN" -> healthData.bloodOxygen?.toFloat()
                else -> null
            } ?: return@runOnUiThread

            // Stop measuring state since we received the stable value
            isLiveMeasuring = false
            btnMeasureNow.text = "Measure"

            // Add live data point to chart
            val timestamp = System.currentTimeMillis() / 1000
            val newEntry = Entry(timestamp.toFloat(), value)

            chart.data?.let { data ->
                val dataSet = data.getDataSetByIndex(0) as? LineDataSet
                dataSet?.addEntry(newEntry)
                data.notifyDataChanged()
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        }
    }

    override fun onHealthSettingsReceived(settings: HealthSettingsUpdate) {
        runOnUiThread {
            if (settings.errorState == "PERMISSION_DENIED") {
                Toast.makeText(this, "Please enable 'Settings API' in the health app settings", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }

            // Populate settings dialog if it's open
            settingsDialogAge?.setText(settings.age?.toString() ?: "")
            settingsDialogHeight?.setText(settings.height?.toString() ?: "")
            settingsDialogWeight?.setText(settings.weight?.toString() ?: "")
            settingsDialogStepGoal?.setText(settings.stepGoal?.toString() ?: "")
            settingsDialogInterval?.setText(settings.interval?.toString() ?: "")
            settingsDialogBackground?.isChecked = settings.backgroundEnabled ?: false

            // Set gender spinner selection
            settings.gender?.let { gender ->
                val genderIndex = when (gender) {
                    "Male" -> 0
                    "Female" -> 1
                    else -> 2 // Other
                }
                settingsDialogGender?.setSelection(genderIndex)
            }
        }
    }

    // Empty callback implementations
    override fun onStatusChanged(status: String) {}
    override fun onDeviceConnected(deviceName: String) {}
    override fun onDeviceDisconnected() {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.toast_watch_disconnected), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            swipeRefresh.isRefreshing = false
        }
    }
    override fun onScanResult(devices: List<BluetoothDevice>) {}
    override fun onAppListReceived(appsJson: String) {}
    override fun onUploadProgress(progress: Int) {}
    override fun onDownloadProgress(progress: Int) {}
    override fun onFileListReceived(path: String, filesJson: String) {}

    override fun onPause() {
        super.onPause()
        // Stop live measurement when activity pauses to save battery
        if (isLiveMeasuring) {
            stopLiveMeasurement()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-register callback
        if (isBound) {
            bluetoothService?.callback = this
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear chart data
        chart.clear()
        chart.data = null

        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }
}
