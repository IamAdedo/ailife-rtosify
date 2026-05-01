package com.iamadedo.watchapp.servicehelper

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.iamadedo.watchapp.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File

class BatteryAppUsageHandler(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val sendMessage: (ProtocolMessage) -> Unit,
    private val isConnected: () -> Boolean,
    private val isUsingShizuku: () -> Boolean,
    private val ensureUserServiceBound: suspend () -> Unit,
    private val getUserService: () -> IUserService?
) {
    private val TAG = "BatteryAppUsageHandler"
    private var batteryHistoryJob: Job? = null
    private var lastBatteryLevel = -1
    private var lastChargingState = false

    val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                handleBatteryChanged(intent)
            }
        }
    }

    fun startBatteryHistoryTracking() {
        batteryHistoryJob?.cancel()

        val detailedEnabled = prefs.getBoolean("batt_detailed_log", false)
        val interval = if (detailedEnabled) 2 * 60 * 1000L else 30 * 60 * 1000L // 2 min vs 30 min

        Log.d(
            TAG,
            "Starting battery history tracking. Detailed: $detailedEnabled, Interval: ${interval}ms"
        )

        batteryHistoryJob =
            serviceScope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        saveBatteryHistoryPoint()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving battery history: ${e.message}")
                    }
                    delay(interval)
                }
            }
    }

    fun stopBatteryHistoryTracking() {
        batteryHistoryJob?.cancel()
        batteryHistoryJob = null
    }

    private fun saveBatteryHistoryPoint() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toInt()
        val batteryStatus: Intent? =
            IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                androidx.core.content.ContextCompat.registerReceiver(context, null, ifilter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val packageName = getForegroundApp()

        val newPoint =
            BatteryHistoryPoint(
                System.currentTimeMillis(),
                level,
                voltage,
                current,
                packageName
            )

        val currentHistoryJson = prefs.getString("battery_history", "[]")
        val type = object : TypeToken<MutableList<BatteryHistoryPoint>>() {}.type
        val history: MutableList<BatteryHistoryPoint> =
            Gson().fromJson(currentHistoryJson, type) ?: mutableListOf()

        history.add(newPoint)

        // Keep last 1000 points (approx 33 hours at 2-min interval, or 20 days at 30-min interval)
        if (history.size > 1000) {
            history.removeAt(0)
        }

        prefs.edit().putString("battery_history", Gson().toJson(history)).apply()
    }

    private fun getForegroundApp(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null

        try {
            val usageStatsManager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as
                        android.app.usage.UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 5 * 60 * 1000 // Last 5 minutes

            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = android.app.usage.UsageEvents.Event()

            var lastPackage: String? = null
            var lastTime: Long = 0

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (event.timeStamp > lastTime) {
                        lastTime = event.timeStamp
                        lastPackage = event.packageName
                    }
                }
            }
            return lastPackage
        } catch (e: Exception) {
            Log.e(TAG, "Error getting foreground app: ${e.message}")
            return null
        }
    }

    fun handleRequestBatteryLive() {
        Log.d(TAG, "Handling battery live request")
        serviceScope.launch(Dispatchers.IO) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

                val batteryStatus: Intent? =
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                        context.registerReceiver(null, ifilter)
                    }

                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct =
                    if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt()
                    else 0

                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging =
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL

                val currentNow =
                    bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toInt()
                val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

                // Estimation
                var remainingTimeMillis: Long? = null
                if (isCharging) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val chargeTime = bm.computeChargeTimeRemaining()
                        if (chargeTime > 0) remainingTimeMillis = chargeTime
                    }
                } else {
                    // Current is usually negative when discharging
                    val absCurrent = if (currentNow < 0) -currentNow.toDouble() else 0.0
                    // Heuristic: If it's > 5000, it's likely microamperes. If < 5000, it might be
                    // milliamperes.
                    val dischargeCurrentMa =
                        if (absCurrent > 5000) absCurrent / 1000.0 else absCurrent

                    if (dischargeCurrentMa > 1.0) { // More than 1mA discharge
                        var chargeCounterMah =
                            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                                .toDouble() / 1000.0

                        // Fallback if chargeCounter is unavailable (returns 0 or very small)
                        if (chargeCounterMah <= 0) {
                            // Assume a typical watch capacity of 300mAh for estimation if unknown
                            val totalCapacityMah = 300.0
                            chargeCounterMah = (batteryPct / 100.0) * totalCapacityMah
                        }

                        val hoursRemaining = chargeCounterMah / dischargeCurrentMa
                        if (hoursRemaining in 0.1..500.0) {
                            remainingTimeMillis = (hoursRemaining * 3600 * 1000).toLong()
                        }
                    }
                }

                val detail =
                    BatteryDetailData(
                        batteryLevel = batteryPct,
                        isCharging = isCharging,
                        currentNow = currentNow,
                        currentAverage =
                        bm.getLongProperty(
                            BatteryManager
                                .BATTERY_PROPERTY_CURRENT_AVERAGE
                        )
                            .toInt(),
                        voltage = voltage,
                        chargeCounter =
                        bm.getLongProperty(
                            BatteryManager
                                .BATTERY_PROPERTY_CHARGE_COUNTER
                        )
                            .toInt(),
                        energyCounter =
                        bm.getLongProperty(
                            BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER
                        ),
                        capacity = getBatteryCapacity(),
                        temperature =
                        batteryStatus?.getIntExtra(
                            BatteryManager.EXTRA_TEMPERATURE,
                            0
                        )
                            ?: 0,
                        remainingTimeMillis = remainingTimeMillis
                    )

                sendMessage(ProtocolHelper.createBatteryDetailUpdate(detail))
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting live battery data: ${e.message}")
            }
        }
    }

    fun handleRequestBatteryStatic() {
        Log.d(TAG, "Handling battery static request")
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Collect App Usage
                val appUsage = collectAppUsage()

                // Get History
                val currentHistoryJson = prefs.getString("battery_history", "[]")
                val type = object : TypeToken<List<BatteryHistoryPoint>>() {}.type
                val history: List<BatteryHistoryPoint> =
                    Gson().fromJson(currentHistoryJson, type) ?: emptyList()

                val detail =
                    BatteryDetailData(
                        batteryLevel = 0,
                        isCharging = false,
                        currentNow = 0,
                        currentAverage = 0,
                        voltage = 0,
                        chargeCounter = 0,
                        energyCounter = 0L,
                        capacity = getBatteryCapacity(),
                        appUsage = appUsage,
                        history = history
                    )

                sendMessage(ProtocolHelper.createBatteryDetailUpdate(detail))
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting static battery data: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun collectAppUsage(): List<AppUsageData> {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 24 * 60 * 60 * 1000 // Last 24 hours

        val usageStats =
            try {
                usageStatsManager.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Error querying usage stats (permission likely missing): ${e.message}"
                )
                return emptyList()
            }

        if (usageStats.isNullOrEmpty()) return emptyList()

        // Map packageName -> Pair(powerMah, drainSpeed)
        val batteryStatsMap = mutableMapOf<String, Pair<Double, Double>>()

        // -------------------------------------------------------------------------
        // METHOD 1: BatteryStatsManager via Reflection (Android 12+)
        // -------------------------------------------------------------------------
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                @SuppressLint("WrongConstant")
                val batteryStatsManager = context.getSystemService("batterystats")

                val queryClass = Class.forName("android.os.BatteryUsageStatsQuery")
                val queryBuilderClass = Class.forName("android.os.BatteryUsageStatsQuery\$Builder")

                // Find constructor (handle hidden/different signatures)
                var builderConstructor: java.lang.reflect.Constructor<*>? = null
                try {
                    val personConstructors = queryBuilderClass.declaredConstructors
                    if (personConstructors.isNotEmpty()) {
                        builderConstructor = personConstructors[0]
                    }
                } catch (e: Exception) {}

                if (builderConstructor == null) {
                    builderConstructor = queryBuilderClass.getDeclaredConstructor()
                }

                builderConstructor?.isAccessible = true
                val builder = builderConstructor?.newInstance()

                try {
                    queryBuilderClass.getMethod("includeProcessStateData").invoke(builder)
                } catch (_: Exception) {}

                val query = queryBuilderClass.getMethod("build").invoke(builder)

                val getBatteryUsageStatsMethod =
                    batteryStatsManager!!.javaClass.getMethod("getBatteryUsageStats", queryClass)
                val batteryUsageStats = getBatteryUsageStatsMethod.invoke(batteryStatsManager)

                val getUidBatteryConsumersMethod =
                    batteryUsageStats!!.javaClass.getMethod("getUidBatteryConsumers")
                val uidBatteryConsumers =
                    getUidBatteryConsumersMethod.invoke(batteryUsageStats) as? List<*>

                uidBatteryConsumers?.forEach { consumer ->
                    try {
                        if (consumer != null) {
                            val getUidMethod = consumer.javaClass.getMethod("getUid")
                            val uid = getUidMethod.invoke(consumer) as Int
                            val packageName = context.packageManager.getNameForUid(uid)

                            if (packageName != null) {
                                val getConsumedPowerMethod =
                                    consumer.javaClass.getMethod("getConsumedPower")
                                val consumedPowerMah =
                                    getConsumedPowerMethod.invoke(consumer) as Double

                                if (consumedPowerMah > 0) {
                                    val appUsageStat =
                                        usageStats.find { it.packageName == packageName }
                                    val hoursForeground =
                                        (appUsageStat?.totalTimeInForeground
                                            ?: 0L) / (1000.0 * 3600.0)
                                    val drainSpeed =
                                        if (hoursForeground > 0.01)
                                            consumedPowerMah / hoursForeground
                                        else 0.0
                                    batteryStatsMap[packageName] =
                                        Pair(consumedPowerMah, drainSpeed)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                if (batteryStatsMap.isNotEmpty())
                    Log.d(TAG, "✅ Success: Retrieved battery stats via Reflection")
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Reflective BatteryStatsManager failed, trying next method: ${e.message}"
                )
            }
        }

        // -------------------------------------------------------------------------
        // METHOD 2: SystemHealthManager (Requires BATTERY_STATS)
        // -------------------------------------------------------------------------
        if (batteryStatsMap.isEmpty()) {
            if (context.checkSelfPermission(android.Manifest.permission.BATTERY_STATS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    Log.d(TAG, "Attempting SystemHealthManager (Method 2)...")
                    val healthStatsManager =
                        context.getSystemService(android.content.Context.SYSTEM_HEALTH_SERVICE) as?
                                android.os.health.SystemHealthManager

                    if (healthStatsManager != null) {
                        // 1. Get all installed UIDs
                        val pm = context.packageManager
                        val installedPackages = pm.getInstalledPackages(0)
                        val validPackages = installedPackages.filter { it.applicationInfo != null }
                        val uids =
                            validPackages
                                .mapNotNull { it.applicationInfo?.uid }
                                .distinct()
                                .toIntArray()

                        // 2. Take Snapshot
                        Log.d(TAG, "Requesting HealthStats for ${uids.size} UIDs...")
                        val healthStats = healthStatsManager.takeUidSnapshots(uids)

                        // 3. Process Results
                        healthStats.forEachIndexed { index, stats ->
                            if (stats != null) {
                                val uid = uids[index]

                                // Direct constants for CPU Timers (Not exposed in UidHealthStats
                                // public API consistently)
                                val TIMER_CPU_USER_MS = 10061
                                val TIMER_CPU_SYSTEM_MS = 10062

                                var cpuMs = 0L

                                // Helper to safely get timer
                                fun getTime(key: Int): Long {
                                    return if (stats.hasTimer(key)) stats.getTimerTime(key) else 0L
                                }
                                fun getMeasurement(key: Int): Long {
                                    return if (stats.hasMeasurement(key)) stats.getMeasurement(key)
                                    else 0L
                                }

                                // Probe other keys
                                val TIMER_PROCESS_STATE_TOP_MS = 10038
                                val MEASUREMENT_REALTIME_BATTERY_MS = 10001

                                val fgMs = getTime(TIMER_PROCESS_STATE_TOP_MS)
                                val realTime = getMeasurement(MEASUREMENT_REALTIME_BATTERY_MS)

                                // Calculate CPU time
                                cpuMs = getTime(TIMER_CPU_USER_MS) + getTime(TIMER_CPU_SYSTEM_MS)

                                if (cpuMs > 0) {
                                    // Map UID back to Package Name
                                    val pkgName =
                                        validPackages
                                            .firstOrNull { it.applicationInfo?.uid == uid }
                                            ?.packageName
                                    if (pkgName != null) {
                                        // Calculate Power: cpuMs * AVG_CPU_mA / 3600000
                                        val AVG_CPU_mA = 150.0
                                        val powerMah = (cpuMs / 3600000.0) * AVG_CPU_mA
                                        batteryStatsMap[pkgName] = Pair(powerMah, fgMs.toDouble())
                                    }
                                }
                            }
                        }
                        if (batteryStatsMap.isEmpty()) {
                            Log.d(
                                TAG,
                                "SystemHealthManager returned valid stats but map empty after filtering."
                            )
                        } else {
                            Log.d(
                                TAG,
                                "✅ Success: Retrieved ${batteryStatsMap.size} items via SystemHealthManager"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SystemHealthManager failed: ${e.message}")
                }
            } else {
                Log.d(TAG, "Skipping Method 2: BATTERY_STATS permission not granted.")
            }
        }

        // -------------------------------------------------------------------------
        // METHOD 3: Direct Dumpsys (Legacy / Fallback)
        // -------------------------------------------------------------------------
        if (batteryStatsMap.isEmpty()) {
            if (context.checkSelfPermission(android.Manifest.permission.DUMP) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    Log.d(TAG, "Attempting direct dumpsys batterystats (Method 3)...")
                    val process = Runtime.getRuntime().exec("dumpsys batterystats --checkin")
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    val exitCode = process.waitFor()

                    if (exitCode == 0 && output.isNotEmpty()) {
                        if (output.contains("9,0,l,cpu", ignoreCase = true) ||
                            output.contains(",l,cpu,", ignoreCase = true)
                        ) {
                            parseDumpsysBatteryStats(output).forEach { (pkg, power) ->
                                batteryStatsMap[pkg] = Pair(power, 0.0)
                            }
                            if (batteryStatsMap.isNotEmpty()) {
                                Log.d(TAG, "✅ Success: Retrieved battery stats via Direct Dumpsys")
                            }
                        }
                    } else {
                        Log.d(
                            TAG,
                            "Direct Dumpsys failed or empty (Exit: $exitCode, Len: ${output.length})"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Direct dumpsys failed: ${e.message}")
                }
            } else {
                Log.d(TAG, "Skipping Method 3: DUMP permission not granted.")
            }
        }

        // -------------------------------------------------------------------------
        // METHOD 4: Shizuku Dumpsys
        // -------------------------------------------------------------------------
        if (batteryStatsMap.isEmpty()) {
            if (isUsingShizuku()) {
                try {
                    ensureUserServiceBound()
                    val dumpOutput =
                        getUserService()?.runShellCommandWithOutput("dumpsys batterystats --checkin")
                    if (!dumpOutput.isNullOrEmpty()) {
                        parseDumpsysBatteryStats(dumpOutput).forEach { (pkg, power) ->
                            batteryStatsMap[pkg] = Pair(power, 0.0)
                        }
                        if (batteryStatsMap.isNotEmpty())
                            Log.d(TAG, "✅ Success: Retrieved battery stats via Shizuku")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku dumpsys failed: ${e.message}")
                }
            } else if (Shell.getShell().isRoot) {
                try {
                    val result = Shell.cmd("dumpsys batterystats --checkin").exec()
                    if (result.isSuccess) {
                        val dumpOutput = result.out.joinToString("\n")
                        parseDumpsysBatteryStats(dumpOutput).forEach { (pkg, power) ->
                            batteryStatsMap[pkg] = Pair(power, 0.0)
                        }
                        if (batteryStatsMap.isNotEmpty())
                            Log.d(TAG, "✅ Success: Retrieved battery stats via Root")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Root dumpsys failed: ${e.message}")
                }
            }
        }

        // -------------------------------------------------------------------------
        // METHOD 4: FALLBACK (Heuristic / Fake Data)
        // -------------------------------------------------------------------------
        if (batteryStatsMap.isEmpty()) {
            Log.e(TAG, "⚠️⚠️⚠️ WARNING WARNING WARNING ⚠️⚠️⚠️")
            Log.e(TAG, "USING FAKE (SIMULATED) BATTERY DATA!")
            Log.e(
                TAG,
                "Could not retrieve real battery stats via API, Direct Dumpsys, Shizuku, or Root."
            )
            Log.e(
                TAG,
                "The values shown below are ESTIMATED based on foreground time + random variation."
            )
            Log.e(TAG, "⚠️⚠️⚠️ WARNING WARNING WARNING ⚠️⚠️⚠️")
        }

        // Final Fallback: Estimated heuristic
        val useFallback = batteryStatsMap.isEmpty()

        return usageStats
            .asSequence()
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(15)
            .map { stats ->
                var name = stats.packageName
                var iconBase64: String? = null
                try {
                    val appInfo = context.packageManager.getApplicationInfo(stats.packageName, 0)
                    name = context.packageManager.getApplicationLabel(appInfo).toString()
                    val icon = context.packageManager.getApplicationIcon(appInfo)
                    val bitmap = icon.toBitmap(48, 48)
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    iconBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                } catch (_: Exception) {}

                var powerMah = 0.0
                var drainSpeed = 0.0

                // Calculate hours for drain speed in all cases
                val hours = stats.totalTimeInForeground / (1000.0 * 3600.0)

                if (batteryStatsMap.containsKey(stats.packageName)) {
                    // Use real data
                    val s = batteryStatsMap[stats.packageName]!!
                    powerMah = s.first
                    // Calculate drain speed: Total Power / Foreground Time
                    if (hours > 0.0001) {
                        drainSpeed = powerMah / hours
                    } else {
                        drainSpeed = 0.0 // Avoid division by zero or unrealistic huge speed
                    }
                } else if (!useFallback) {
                    // Real data was found for OTHER apps, but not this one.
                    // Strict mode: Assume 0 usage for this app.
                    powerMah = 0.0
                    drainSpeed = 0.0
                } else {
                    // ⚠️ FAKE DATA GENERATION ⚠️ (Only if useFallback == true)
                    val hours = stats.totalTimeInForeground / (1000.0 * 3600.0)

                    // Deterministic "random" variation
                    val hash = stats.packageName.hashCode()
                    val variance = (hash % 50) - 25 // -25 to +25 mA
                    var baseDrainRate = 100.0 + variance // ~100mA average
                    if (baseDrainRate < 20) baseDrainRate = 20.0

                    drainSpeed = baseDrainRate
                    powerMah = drainSpeed * hours

                    Log.w(
                        TAG,
                        "USING FAKE DATA for ${stats.packageName}: DrainRate=$drainSpeed mAh/h, EstPower=$powerMah mAh"
                    )
                }

                AppUsageData(
                    packageName = stats.packageName,
                    name = name,
                    usageTimeMillis = stats.totalTimeInForeground,
                    icon = iconBase64,
                    batteryPowerMah = powerMah,
                    drainSpeed = drainSpeed
                )
            }
            .toList()
    }

    private fun parseDumpsysBatteryStats(output: String): Map<String, Double> {
        val stats = mutableMapOf<String, Double>()
        // Map uid -> cpu time
        val uidCpuTime = mutableMapOf<Int, Long>()

        output.lines().forEach { line ->
            // Format: 9,0,l,cpu,user_ms,system_ms,power_ma (fake?)
            // Real Format from checkin: 9,uid,l,cpu,user_ms,system_ms,0
            // Example: 9,10383,l,cpu,3468,1776,0

            val parts = line.split(",")
            if (parts.size >= 7 && parts[2] == "l" && parts[3] == "cpu") {
                try {
                    val uid = parts[1].toInt()
                    val userMs = parts[4].toLong()
                    val systemMs = parts[5].toLong()
                    uidCpuTime[uid] = userMs + systemMs
                } catch (_: Exception) {}
            }
        }

        if (uidCpuTime.isEmpty()) return emptyMap()

        // Estimate power from CPU time
        // Assumption: Active CPU consumes ~100-200mA average. Let's pick 150mA.
        // Power (mAh) = (Time (ms) / 3600000) * 150 mA
        val AVG_CPU_mA = 150.0

        uidCpuTime.forEach { (uid, timeMs) ->
            val powerMah = (timeMs / 3600000.0) * AVG_CPU_mA
            val pkgName = context.packageManager.getNameForUid(uid)
            if (pkgName != null && powerMah > 0.0) {
                stats[pkgName] = powerMah
            }
        }

        return stats
    }

    private fun getBatteryCapacity(): Double {
        try {
            val powerProfileClass = "com.android.internal.os.PowerProfile"
            val mPowerProfile =
                Class.forName(powerProfileClass)
                    .getConstructor(Context::class.java)
                    .newInstance(context)
            val batteryCapacity =
                Class.forName(powerProfileClass)
                    .getMethod("getBatteryCapacity")
                    .invoke(mPowerProfile) as
                        Double
            if (batteryCapacity > 0) return batteryCapacity
        } catch (e: Exception) {
            Log.w(TAG, "Error getting capacity via PowerProfile: ${e.message}")
        }

        try {
            val id = context.resources.getIdentifier("config_batteryCapacity", "dimen", "android")
            if (id > 0) {
                val capacity = context.resources.getDimension(id).toDouble()
                if (capacity > 0) return capacity
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting capacity via dimensions: ${e.message}")
        }

        return 0.0
    }

    fun handleUpdateBatterySettings(message: ProtocolMessage) {
        val settings = ProtocolHelper.extractData<BatterySettingsData>(message)
        prefs.edit()
            .apply {
                putBoolean("batt_notify_full", settings.notifyFull)
                putBoolean("batt_notify_low", settings.notifyLow)
                putInt("batt_low_threshold", settings.lowThreshold)
                putBoolean("batt_detailed_log", settings.detailedLogEnabled)
            }
            .apply()

        // Restart tracking with new settings (interval might change)
        startBatteryHistoryTracking()

        // Trigger an immediate check
        checkBatteryStateForNotification()
    }

    fun checkBatteryStateForNotification() {
        val batteryStatus: Intent? =
            IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
        if (batteryStatus != null) {
            handleBatteryChanged(batteryStatus)
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = if (scale != 0) (level * 100 / scale.toFloat()).toInt() else 0

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        // val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) // Unused?

        if (batteryPct != lastBatteryLevel || isCharging != lastChargingState) {
            lastBatteryLevel = batteryPct
            lastChargingState = isCharging

            // Notification logic
            val notifyFull = prefs.getBoolean("batt_notify_full", false)
            val notifyLow = prefs.getBoolean("batt_notify_low", false)
            val lowThreshold = prefs.getInt("batt_low_threshold", 20)

            if (notifyFull && batteryPct >= 100 && isCharging) {
                if (isConnected()) {
                    sendMessage(ProtocolHelper.createBatteryAlert("FULL", batteryPct))
                }
            } else if (notifyLow && batteryPct <= lowThreshold && !isCharging) {
                if (isConnected()) {
                    sendMessage(ProtocolHelper.createBatteryAlert("LOW", batteryPct))
                }
            }
        }
    }
}
