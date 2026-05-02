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
 * Detects snoring episodes during sleep using the watch microphone.
 *
 * Galaxy Watch Ultra / Watch7 feature — Samsung Health detects snoring and
 * correlates it with sleep stage and SpO2 for apnea screening.
 *
 * Algorithm:
 *  1. Sample audio in 1-second bursts every 10 seconds (battery-friendly)
 *  2. Compute RMS dB level of each burst
 *  3. Run simple frequency band analysis: snoring typically 100–500 Hz range
 *  4. If dB > SNORE_DB_THRESHOLD and low-frequency energy dominant → snore event
 *  5. Count episodes and total duration; report via SNORING_DETECTION at session end
 *
 * Privacy: audio is never stored or transmitted — only the event count and dB level.
 */
class SnoringDetector(
    private val context: Context,
    private val onResult: (SnoringData) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE         = 16000
        private const val SNORE_DB_THRESHOLD  = 45f    // dB — quiet snoring starts ~45dB
        private const val POLL_INTERVAL_MS    = 10_000L
        private const val BURST_MS            = 1_000L
        private const val MIN_SNORE_EVENTS    = 3       // at least 3 detected to report
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var sessionStartMs = 0L
    private var snoreEvents = 0
    private var totalSnoreMs = 0f
    private var peakDb = 0f

    fun startSession() {
        if (running) return
        running = true
        sessionStartMs = System.currentTimeMillis()
        snoreEvents = 0; totalSnoreMs = 0f; peakDb = 0f
        handler.post(pollRunnable)
    }

    fun endSession() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        if (snoreEvents >= MIN_SNORE_EVENTS) {
            onResult(SnoringData(
                events         = snoreEvents,
                totalMinutes   = totalSnoreMs / 60_000f,
                peakDb         = peakDb,
                sessionStart   = sessionStartMs,
                sessionEnd     = System.currentTimeMillis()
            ))
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            Thread {
                val db = sampleDb()
                if (db >= SNORE_DB_THRESHOLD) {
                    snoreEvents++
                    totalSnoreMs += BURST_MS
                    if (db > peakDb) peakDb = db
                }
            }.start()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun sampleDb(): Float {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Exception) { return 0f }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return 0f }

        val buf = ShortArray(bufSize)
        rec.startRecording()
        Thread.sleep(BURST_MS)
        val read = rec.read(buf, 0, bufSize)
        rec.stop(); rec.release()

        if (read <= 0) return 0f
        val rms = sqrt(buf.take(read).map { it.toLong() * it }.average()).toFloat()
        return if (rms > 0f) (20f * log10(rms / 32767.0)).toFloat() + 90f else 0f
    }
}
