package com.iamadedo.watchapp

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detects handwashing by combining two signals:
 *  1. Wrist rubbing pattern — accelerometer shows rhythmic 2-4 Hz oscillation
 *  2. Water sound           — microphone ambient level rises (running water ~55-70 dB)
 *
 * When both are detected simultaneously for DETECTION_WINDOW_MS, a 20-second
 * countdown begins with haptic pulses every 5 s to guide proper handwashing duration.
 *
 * On completion a HANDWASH_EVENT is sent to the phone.
 */
class HandwashDetector(private val context: Context) : SensorEventListener {

    companion object {
        private const val DETECTION_WINDOW_MS = 1_500L
        private const val WASH_DURATION_MS    = 20_000L
        private const val ACCEL_THRESHOLD     = 4.0f    // m/s² peak variation
        private const val WATER_SOUND_DB      = 50f     // ambient dB threshold
        private const val COOLDOWN_MS         = 120_000L // 2 min between detections
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler = Handler(Looper.getMainLooper())

    private val accelBuffer = ArrayDeque<Float>()
    private var rubbingDetected = false
    private var waterDetected = false
    private var detectionStartMs = 0L
    private var timerActive = false
    private var lastCompleteMs = 0L
    private var running = false

    fun start() {
        if (running) return
        running = true
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        handler.post(soundCheckRunnable)
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    // ── Accelerometer: detect wrist rubbing ───────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val mag = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        accelBuffer.addLast(mag)
        if (accelBuffer.size > 60) accelBuffer.removeFirst()

        // Peak-to-peak amplitude check
        val peakToPeak = (accelBuffer.maxOrNull() ?: 0f) - (accelBuffer.minOrNull() ?: 0f)
        rubbingDetected = peakToPeak > ACCEL_THRESHOLD
        checkBothSignals()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── Microphone: detect water sound ────────────────────────────────────────

    private val soundCheckRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            waterDetected = sampleAmbientDb() >= WATER_SOUND_DB
            checkBothSignals()
            handler.postDelayed(this, 500)
        }
    }

    private fun sampleAmbientDb(): Float {
        val bufferSize = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        } catch (e: Exception) { return 0f }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); return 0f }
        val buffer = ShortArray(bufferSize)
        recorder.startRecording()
        Thread.sleep(250)
        val read = recorder.read(buffer, 0, bufferSize)
        recorder.stop(); recorder.release()
        if (read <= 0) return 0f
        val rms = sqrt(buffer.take(read).map { it.toLong() * it }.average()).toFloat()
        return if (rms > 0) (20 * log10(rms / 32767.0)).toFloat() + 90f else 0f
    }

    // ── Combined signal assessment ────────────────────────────────────────────

    private fun checkBothSignals() {
        if (timerActive) return
        val now = System.currentTimeMillis()
        if (now - lastCompleteMs < COOLDOWN_MS) return

        if (rubbingDetected && waterDetected) {
            if (detectionStartMs == 0L) {
                detectionStartMs = now
            } else if (now - detectionStartMs >= DETECTION_WINDOW_MS) {
                detectionStartMs = 0L
                startWashTimer()
            }
        } else {
            detectionStartMs = 0L
        }
    }

    private fun startWashTimer() {
        timerActive = true
        vibrator.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))

        // Haptic every 5 seconds
        listOf(5_000L, 10_000L, 15_000L, 20_000L).forEach { delay ->
            handler.postDelayed({
                if (timerActive) {
                    vibrator.vibrate(VibrationEffect.createOneShot(200, 100))
                }
                if (delay == 20_000L) onWashComplete()
            }, delay)
        }
    }

    private fun onWashComplete() {
        timerActive = false
        lastCompleteMs = System.currentTimeMillis()
        // Strong haptic — done!
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        // Report to phone
        val intent = Intent(context, BluetoothService::class.java).apply {
            action = "com.iamadedo.watchapp.SEND_HANDWASH_EVENT"
        }
        context.startService(intent)
    }
}
