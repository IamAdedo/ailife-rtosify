package com.ailife.rtosifycompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * Health Data Collector - Integrates with Better Health Tracker API
 *
 * This class encapsulates all interactions with the Better Health Tracker app,
 * including broadcast-based data requests and Content Provider queries for historical data.
 */
class HealthDataCollector(private val context: Context) {

    companion object {
        private const val TAG = "HealthDataCollector"

        // Better Health Tracker constants
        private const val BETTER_HEALTH_PACKAGE = "com.ailife.betterhealth"
        private const val GET_DATA_API = "com.ailife.betterhealth.GET_DATA_API"
        private const val DATA_REPLY = "com.ailife.betterhealth.DATA_REPLY"
        private const val SETTING_API = "com.ailife.betterhealth.SETTING_API"
        private const val SETTING_REPLY = "com.ailife.betterhealth.SETTING_REPLY"
        private const val CONTENT_AUTHORITY = "com.ailife.betterhealth.healthprovider"

        private const val BROADCAST_TIMEOUT_MS = 3000L
    }

    // Cache for last successful health data to handle API failures gracefully
    private var lastHealthData: HealthDataUpdate? = null

    /**
     * Check if Better Health Tracker app is installed
     */
    fun isHealthAppInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(BETTER_HEALTH_PACKAGE, 0)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Better Health app not installed")
            false
        }
    }

    /**
     * Collect current health data using broadcast API
     * Returns cached data if broadcast fails
     */
    suspend fun collectCurrentHealthData(): HealthDataUpdate = withContext(Dispatchers.IO) {
        if (!isHealthAppInstalled()) {
            return@withContext createErrorState("APP_NOT_INSTALLED")
        }

        try {
            // Request all current health data SEQUENTIALLY (broadcast API can't handle concurrent requests)
            val steps = requestData("STEP", "TODAY") ?: 0
            val distance = requestFloatData("DISTANCE", "TODAY") ?: 0f
            val calories = requestData("CALORIE", "TODAY") ?: 0
            val hrData = requestData("HR", "LAST")
            val oxygenData = requestData("OXYGEN", "LAST")

            // Get last measurement timestamps using Content Provider
            val (hrValue, hrTimestamp) = if (hrData != null && hrData > 0) {
                val timestamp = getLastMeasurementTimestamp("heartrate")
                Pair(hrData, timestamp)
            } else {
                Pair(null, null)
            }

            val (oxygenValue, oxygenTimestamp) = if (oxygenData != null && oxygenData > 0) {
                val timestamp = getLastMeasurementTimestamp("oxygen")
                Pair(oxygenData, timestamp)
            } else {
                Pair(null, null)
            }

            val healthData = HealthDataUpdate(
                steps = steps,
                distance = distance,
                calories = calories,
                heartRate = hrValue,
                heartRateTimestamp = hrTimestamp,
                bloodOxygen = oxygenValue,
                oxygenTimestamp = oxygenTimestamp,
                errorState = null
            )

            lastHealthData = healthData
            healthData

        } catch (e: Exception) {
            Log.e(TAG, "Error collecting health data: ${e.message}")
            lastHealthData ?: createErrorState("NO_DATA")
        }
    }

    /**
     * Measure now - forces immediate measurement for HR/Oxygen
     * Used during live measurement mode
     */
    suspend fun measureNow(type: String): HealthDataUpdate = withContext(Dispatchers.IO) {
        if (!isHealthAppInstalled()) {
            return@withContext createErrorState("APP_NOT_INSTALLED")
        }

        try {
            // Use AUTO type for live measurements (measures if no recent data)
            val measurementType = if (type == "HR" || type == "OXYGEN") "AUTO" else "TODAY"

            when (type) {
                "HR" -> {
                    val hr = requestData("HR", measurementType)
                    if (hr != null && hr > 0) {
                        val timestamp = System.currentTimeMillis()
                        HealthDataUpdate(
                            steps = lastHealthData?.steps ?: 0,
                            distance = lastHealthData?.distance ?: 0f,
                            calories = lastHealthData?.calories ?: 0,
                            heartRate = hr,
                            heartRateTimestamp = timestamp,
                            bloodOxygen = lastHealthData?.bloodOxygen,
                            oxygenTimestamp = lastHealthData?.oxygenTimestamp,
                            errorState = null
                        )
                    } else {
                        lastHealthData ?: createErrorState("NO_DATA")
                    }
                }
                "OXYGEN" -> {
                    val oxygen = requestData("OXYGEN", measurementType)
                    if (oxygen != null && oxygen > 0) {
                        val timestamp = System.currentTimeMillis()
                        HealthDataUpdate(
                            steps = lastHealthData?.steps ?: 0,
                            distance = lastHealthData?.distance ?: 0f,
                            calories = lastHealthData?.calories ?: 0,
                            heartRate = lastHealthData?.heartRate,
                            heartRateTimestamp = lastHealthData?.heartRateTimestamp,
                            bloodOxygen = oxygen,
                            oxygenTimestamp = timestamp,
                            errorState = null
                        )
                    } else {
                        lastHealthData ?: createErrorState("NO_DATA")
                    }
                }
                else -> lastHealthData ?: createErrorState("NO_DATA")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error measuring $type: ${e.message}")
            lastHealthData ?: createErrorState("NO_DATA")
        }
    }

    /**
     * Query historical data using Content Provider API
     */
    suspend fun queryHistoricalData(
        type: String,
        startTime: Long,
        endTime: Long
    ): HealthHistoryResponse = withContext(Dispatchers.IO) {
        if (!isHealthAppInstalled()) {
            return@withContext HealthHistoryResponse(
                type = type,
                dataPoints = emptyList(),
                goal = null,
                errorState = "APP_NOT_INSTALLED"
            )
        }

        try {
            val uri = Uri.parse("content://$CONTENT_AUTHORITY/records/range/$startTime/$endTime")
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)

            val dataPoints = mutableListOf<HealthDataPoint>()
            cursor?.use {
                while (it.moveToNext()) {
                    val timestamp = it.getLong(it.getColumnIndexOrThrow("unix"))
                    val value = when (type) {
                        "STEP" -> it.getInt(it.getColumnIndexOrThrow("steps")).toFloat()
                        "HR" -> it.getInt(it.getColumnIndexOrThrow("heartrate")).toFloat()
                        "OXYGEN" -> it.getInt(it.getColumnIndexOrThrow("bloodoxygen")).toFloat()
                        else -> 0f
                    }

                    if (value > 0) {
                        dataPoints.add(HealthDataPoint(timestamp, value))
                    }
                }
            }

            // Get goal if applicable
            val goal = if (type == "STEP") getStepGoal() else null

            return@withContext HealthHistoryResponse(
                type = type,
                dataPoints = dataPoints,
                goal = goal,
                errorState = null
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error querying history for $type: ${e.message}")
            return@withContext HealthHistoryResponse(
                type = type,
                dataPoints = emptyList(),
                goal = null,
                errorState = "QUERY_FAILED"
            )
        }
    }

    /**
     * Update Better Health settings via broadcast
     */
    suspend fun updateHealthSettings(settings: HealthSettingsUpdate) = withContext(Dispatchers.IO) {
        if (!isHealthAppInstalled()) return@withContext

        try {
            settings.stepGoal?.let { writeSetting("GOAL_STEP", it) }
            settings.backgroundEnabled?.let { writeSetting("BACKGROUND", it) }
            settings.monitoringTypes?.let { writeSetting("TYPE", it) }
            settings.interval?.let { writeSetting("INTERVAL", it) }

            // Body specifications - using correct Better Health API keys
            settings.age?.let { writeSetting("AGE", it) }
            settings.height?.let { writeSetting("HEIGHT", it) }
            settings.weight?.let { writeSetting("WEIGHT", it.toInt()) }

            // Gender: convert string to integer (0=Other, 1=Male, 2=Female)
            settings.gender?.let { genderStr ->
                val genderInt = when (genderStr) {
                    "Male" -> 1
                    "Female" -> 2
                    else -> 0 // Other/prefer not to say
                }
                writeSetting("GENDER", genderInt)
            }

            Log.d(TAG, "Health settings updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating settings: ${e.message}")
        }
    }

    /**
     * Request data via broadcast API with timeout (for integer values)
     */
    private suspend fun requestData(dataType: String, type: String?): Int? {
        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return

                    val error = intent.getStringExtra("ERROR")
                    val returnedDataType = intent.getStringExtra("DATA")

                    if (returnedDataType == dataType && error == "NONE") {
                        val result = intent.getIntExtra("RESULT", 0)
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    } else {
                        Log.w(TAG, "Error requesting $dataType: $error")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    try {
                        context?.unregisterReceiver(this)
                    } catch (e: Exception) {
                        // Already unregistered
                    }
                }
            }

            // Register receiver
            val filter = IntentFilter(DATA_REPLY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            // Send request
            val intent = Intent(GET_DATA_API).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                setPackage(BETTER_HEALTH_PACKAGE)
                putExtra("DATA", dataType)
                type?.let { putExtra("TYPE", it) }
            }
            context.sendBroadcast(intent)

            // Setup timeout
            CoroutineScope(Dispatchers.IO).launch {
                delay(BROADCAST_TIMEOUT_MS)
                if (continuation.isActive) {
                    continuation.resume(null)
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (e: Exception) {
                        // Already unregistered
                    }
                }
            }

            continuation.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    // Already unregistered
                }
            }
        }
    }

    /**
     * Request float data via broadcast API with timeout (for distance)
     */
    private suspend fun requestFloatData(dataType: String, type: String?): Float? {
        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return

                    val error = intent.getStringExtra("ERROR")
                    val returnedDataType = intent.getStringExtra("DATA")

                    if (returnedDataType == dataType && error == "NONE") {
                        val result = intent.getDoubleExtra("RESULT", 0.0).toFloat()
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    } else {
                        Log.w(TAG, "Error requesting $dataType: $error")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    try {
                        context?.unregisterReceiver(this)
                    } catch (e: Exception) {
                        // Already unregistered
                    }
                }
            }

            // Register receiver
            val filter = IntentFilter(DATA_REPLY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            // Send request
            val intent = Intent(GET_DATA_API).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                setPackage(BETTER_HEALTH_PACKAGE)
                putExtra("DATA", dataType)
                type?.let { putExtra("TYPE", it) }
            }
            context.sendBroadcast(intent)

            // Setup timeout
            CoroutineScope(Dispatchers.IO).launch {
                delay(BROADCAST_TIMEOUT_MS)
                if (continuation.isActive) {
                    continuation.resume(null)
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (e: Exception) {
                        // Already unregistered
                    }
                }
            }

            continuation.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    // Already unregistered
                }
            }
        }
    }

    /**
     * Write setting via broadcast API
     */
    private suspend fun writeSetting(key: String, value: Any) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val intent = Intent(SETTING_API).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                setPackage(BETTER_HEALTH_PACKAGE)
                putExtra("MODE", "WRITE")
                putExtra("KEY", key)
                when (value) {
                    is Boolean -> putExtra("VALUE", value)
                    is Int -> putExtra("VALUE", value)
                    is String -> putExtra("VALUE", value)
                }
            }
            context.sendBroadcast(intent)
            continuation.resume(Unit)
        }
    }

    /**
     * Get last measurement timestamp from Content Provider
     */
    private fun getLastMeasurementTimestamp(type: String): Long? {
        try {
            val uriString = when (type) {
                "heartrate" -> "content://$CONTENT_AUTHORITY/last/heartrate"
                "oxygen" -> "content://$CONTENT_AUTHORITY/last/oxygen"
                else -> return null
            }

            val cursor = context.contentResolver.query(Uri.parse(uriString), null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val unixSeconds = it.getLong(it.getColumnIndexOrThrow("unix"))
                    if (unixSeconds > 0) {
                        return unixSeconds * 1000 // Convert to milliseconds
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting timestamp for $type: ${e.message}")
        }
        return null
    }

    /**
     * Get step goal from Content Provider
     */
    private fun getStepGoal(): Int? {
        // Default goal for now - could be retrieved from settings if Better Health exposes it
        return 10000
    }

    /**
     * Create error state health data
     */
    private fun createErrorState(error: String): HealthDataUpdate {
        return HealthDataUpdate(
            steps = 0,
            distance = 0f,
            calories = 0,
            heartRate = null,
            heartRateTimestamp = null,
            bloodOxygen = null,
            oxygenTimestamp = null,
            errorState = error
        )
    }
}
