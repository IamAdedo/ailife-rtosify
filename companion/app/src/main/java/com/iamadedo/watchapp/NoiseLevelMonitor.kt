package com.iamadedo.watchapp

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Samples ambient noise via the microphone in short bursts (non-continuous recording)
 * to avoid battery drain. If sustained exposure above the threshold is detected
 * a NOISE_ALERT is sent to the phone via BluetoothService.
 *
 * Usage:
 *   val monitor = NoiseLevelMonitor(context) { peakDb, durationSeconds ->
 *       // handle alert
 *   }
 *   monitor.start()
 *   monitor.stop()
 */
class NoiseLevelMonitor(
    private val context: Context,
    private val onAlert: (peakDb: Float, durationSeconds: Int) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 22050
        private const val ALERT_THRESHOLD_DB = 85f      // WHO hearing damage threshold
        private const val ALERT_SUSTAIN_SECONDS = 30    // seconds above threshold before alert
        private const val BURST_DURATION_MS = 1_000L    // duration of each microphone burst
        private const val POLL_INTERVAL_MS = 5_000L     // how often to sample

        // Prevent repeated alerts within cooldown window
        private const val ALERT_COOLDOWN_MS = 5 * 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var secondsAboveThreshold = 0
    private var peakDbInWindow = 0f
    private var lastAlertMs = 0L

    fun start() {
        if (running) return
        running = true
        handler.post(sampleRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        secondsAboveThreshold = 0
        peakDbInWindow = 0f
    }

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val db = sampleAmbientDb()
            if (db >= ALERT_THRESHOLD_DB) {
                secondsAboveThreshold += (POLL_INTERVAL_MS / 1000).toInt()
                peakDbInWindow = maxOf(peakDbInWindow, db)
                if (secondsAboveThreshold >= ALERT_SUSTAIN_SECONDS) {
                    val now = System.currentTimeMillis()
                    if (now - lastAlertMs > ALERT_COOLDOWN_MS) {
                        lastAlertMs = now
                        onAlert(peakDbInWindow, secondsAboveThreshold)
                        sendAlertToService(peakDbInWindow, secondsAboveThreshold)
                    }
                    secondsAboveThreshold = 0
                    peakDbInWindow = 0f
                }
            } else {
                secondsAboveThreshold = (secondsAboveThreshold - 2).coerceAtLeast(0)
                if (secondsAboveThreshold == 0) peakDbInWindow = 0f
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * Records a brief burst and returns the RMS dB level.
     * Runs on the handler thread — ensure RECORD_AUDIO permission is granted.
     */
    private fun sampleAmbientDb(): Float {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            return 0f
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return 0f
        }

        val buffer = ShortArray(bufferSize)
        recorder.startRecording()
        Thread.sleep(BURST_DURATION_MS)
        val read = recorder.read(buffer, 0, bufferSize)
        recorder.stop()
        recorder.release()

        if (read <= 0) return 0f

        val rms = sqrt(buffer.take(read).map { it.toLong() * it }.average()).toFloat()
        return if (rms > 0) (20 * log10(rms / 32767.0)).toFloat() + 90f else 0f
    }

    private fun sendAlertToService(peakDb: Float, durationSeconds: Int) {
        val intent = Intent(context, BluetoothService::class.java).apply {
            action = "com.iamadedo.watchapp.SEND_NOISE_ALERT"
            putExtra("peak_db", peakDb)
            putExtra("duration_seconds", durationSeconds)
        }
        context.startService(intent)
    }
}
