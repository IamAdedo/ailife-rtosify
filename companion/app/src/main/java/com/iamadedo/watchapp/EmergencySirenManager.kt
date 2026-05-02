package com.iamadedo.watchapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Emergency Siren — Galaxy Watch Ultra feature.
 * Emits a loud pulsing tone (targeting 85+ dB) through the watch speaker
 * to attract attention in distress situations.
 *
 * Tone: 880 Hz / 1320 Hz alternating every 500 ms (classic emergency sweep).
 * Also pulses the vibrator in sync for silent-mode awareness.
 *
 * Auto-stops after MAX_DURATION_MS (default 120 s) to prevent battery drain.
 * User can stop manually via watch button or EMERGENCY_SIREN(on=false) message.
 */
class EmergencySirenManager(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE     = 44100
        private const val MAX_DURATION_MS = 120_000L
        private const val TONE_DURATION_MS= 500L     // each half-cycle
        private const val FREQ_LOW        = 880.0    // Hz
        private const val FREQ_HIGH       = 1320.0   // Hz
    }

    private var audioTrack: AudioTrack? = null
    private var sirenThread: Thread? = null
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val handler  = Handler(Looper.getMainLooper())
    private var active   = false

    fun start() {
        if (active) return
        active = true

        // Max volume
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.setVolume(1.0f)
        audioTrack?.play()

        sirenThread = Thread {
            val startMs = System.currentTimeMillis()
            var phase = 0.0
            var useLow = true
            val samplesPerTone = (SAMPLE_RATE * TONE_DURATION_MS / 1000).toInt()
            val buffer = FloatArray(bufferSize)
            var sampleCounter = 0

            while (active && System.currentTimeMillis() - startMs < MAX_DURATION_MS) {
                val freq = if (useLow) FREQ_LOW else FREQ_HIGH
                for (i in buffer.indices) {
                    buffer[i] = sin(phase).toFloat()
                    phase += 2.0 * PI * freq / SAMPLE_RATE
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                    sampleCounter++
                    if (sampleCounter >= samplesPerTone) {
                        sampleCounter = 0
                        useLow = !useLow
                    }
                }
                audioTrack?.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
            stop()
        }.also { it.isDaemon = true; it.start() }

        // Sync vibration pulse
        vibrator.vibrate(VibrationEffect.createWaveform(
            longArrayOf(0, 400, 100, 400, 100, 400), 0  // repeat
        ))

        // Auto-stop
        handler.postDelayed(::stop, MAX_DURATION_MS)
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        vibrator.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        sirenThread = null
    }

    fun isActive() = active
}
