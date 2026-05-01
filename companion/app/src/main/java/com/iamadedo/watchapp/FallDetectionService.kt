package com.iamadedo.watchapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

/**
 * Foreground service that monitors the watch accelerometer for fall signatures.
 *
 * Detection algorithm:
 *  1. Freefall phase  — magnitude |a| < FREEFALL_THRESHOLD for > FREEFALL_DURATION_MS
 *  2. Impact phase    — magnitude |a| > IMPACT_THRESHOLD within IMPACT_WINDOW_MS after freefall
 *
 * If both phases are detected the user has CONFIRM_TIMEOUT_MS to dismiss the alert before
 * the SOS is forwarded to BluetoothService and then to the paired phone.
 */
class FallDetectionService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "fall_detection"
        const val NOTIF_ID = 8821
        const val ACTION_DISMISS_FALL = "com.iamadedo.watchapp.DISMISS_FALL"
        const val CONFIRM_TIMEOUT_MS = 30_000L

        private const val FREEFALL_THRESHOLD = 3.0f    // m/s² — near-weightless
        private const val FREEFALL_DURATION_MS = 80L
        private const val IMPACT_THRESHOLD = 25.0f     // m/s² — hard landing
        private const val IMPACT_WINDOW_MS = 1500L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FallDetectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FallDetectionService::class.java))
        }
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator

    private var freefallStartMs = 0L
    private var inFreefall = false
    private var impactWindowEnd = 0L
    private var alertCountdown: java.util.Timer? = null
    private var alertActive = false

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        createChannel()
        startForeground(NOTIF_ID, buildIdleNotification())
        registerAccelerometer()
    }

    private fun registerAccelerometer() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        val nowMs = System.currentTimeMillis()

        when {
            magnitude < FREEFALL_THRESHOLD -> {
                if (!inFreefall) {
                    inFreefall = true
                    freefallStartMs = nowMs
                }
            }
            inFreefall -> {
                inFreefall = false
                val freefallDuration = nowMs - freefallStartMs
                if (freefallDuration >= FREEFALL_DURATION_MS) {
                    // Freefall confirmed — open impact window
                    impactWindowEnd = nowMs + IMPACT_WINDOW_MS
                }
            }
        }

        // Check impact within open window
        if (!alertActive && nowMs < impactWindowEnd && magnitude > IMPACT_THRESHOLD) {
            impactWindowEnd = 0L
            onFallDetected(magnitude)
        }
    }

    private fun onFallDetected(impactMagnitude: Float) {
        alertActive = true
        val confidence = (impactMagnitude / 50f).coerceIn(0f, 1f)

        // Vibrate in urgent pattern
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))

        // Show dismissible notification
        showAlertNotification()

        // Auto-escalate after timeout
        alertCountdown = java.util.Timer()
        alertCountdown?.schedule(object : java.util.TimerTask() {
            override fun run() {
                if (alertActive) escalateToPhone(confidence)
            }
        }, CONFIRM_TIMEOUT_MS)
    }

    private fun escalateToPhone(confidence: Float) {
        alertActive = false
        val intent = Intent(this, BluetoothService::class.java).apply {
            action = "com.iamadedo.watchapp.SEND_FALL_ALERT"
            putExtra("confidence", confidence)
        }
        startService(intent)
        restoreIdleNotification()
    }

    private fun dismiss() {
        alertActive = false
        alertCountdown?.cancel()
        alertCountdown = null
        restoreIdleNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS_FALL) dismiss()
        return START_STICKY
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Fall Detection",
            NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildIdleNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fall_detection_active))
            .setSmallIcon(R.drawable.ic_smartwatch)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun showAlertNotification() {
        val dismissIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FallDetectionService::class.java).apply { action = ACTION_DISMISS_FALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fall_detected_title))
            .setContentText(getString(R.string.fall_detected_body))
            .setSmallIcon(R.drawable.ic_smartwatch)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(0, getString(R.string.fall_im_ok), dismissIntent)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun restoreIdleNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildIdleNotification())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        alertCountdown?.cancel()
        super.onDestroy()
    }
}
