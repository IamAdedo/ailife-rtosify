package com.iamadedo.watchapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates and plays ambient sleep sounds on the watch speaker/earphone.
 *
 * Sound types (matches SLEEP_WHITE_NOISE_START soundType field):
 *  WHITE_NOISE    — broadband random noise
 *  PINK_NOISE     — 1/f noise (softer, more natural)
 *  RAIN           — simulated rainfall using filtered noise bursts
 *  OCEAN          — low-frequency sine sweep + noise
 *  BROWN_NOISE    — deeper, rumbling noise (Brownian)
 *
 * Fades out over FADE_DURATION_MS when stopped to avoid jarring cutoff.
 * Auto-stops after maxDurationMs (default 8 hours) so it doesn't drain battery.
 */
class SleepWhiteNoisePlayer(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 22050
        private const val BUFFER_FRAMES = 2048
        private const val FADE_DURATION_MS = 3_000L
        private const val DEFAULT_MAX_DURATION_MS = 8 * 60 * 60_000L
    }

    private var audioTrack: AudioTrack? = null
    private var generatorThread: Thread? = null
    private var playing = false
    private var volume = 0.5f
    private val handler = Handler(Looper.getMainLooper())

    fun play(soundType: String, maxDurationMs: Long = DEFAULT_MAX_DURATION_MS) {
        stop()
        playing = true

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(BUFFER_FRAMES * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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

        audioTrack?.setVolume(volume)
        audioTrack?.play()

        val generator = noiseGenerator(soundType)
        generatorThread = Thread {
            val buffer = FloatArray(BUFFER_FRAMES)
            val startMs = System.currentTimeMillis()
            while (playing) {
                if (System.currentTimeMillis() - startMs > maxDurationMs) { stop(); break }
                generator(buffer)
                audioTrack?.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        playing = false
        // Fade out
        val track = audioTrack ?: return
        val steps = 30
        val stepMs = FADE_DURATION_MS / steps
        Thread {
            for (i in steps downTo 0) {
                track.setVolume(volume * i / steps)
                Thread.sleep(stepMs)
            }
            track.stop()
            track.release()
        }.start()
        audioTrack = null
        generatorThread = null
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        audioTrack?.setVolume(volume)
    }

    // ── Noise generators ──────────────────────────────────────────────────────

    private fun noiseGenerator(type: String): (FloatArray) -> Unit = when (type) {
        "PINK_NOISE"   -> pinkNoiseGen()
        "BROWN_NOISE"  -> brownNoiseGen()
        "RAIN"         -> rainGen()
        "OCEAN"        -> oceanGen()
        else           -> whiteNoiseGen()   // WHITE_NOISE default
    }

    private fun whiteNoiseGen(): (FloatArray) -> Unit = { buf ->
        for (i in buf.indices) buf[i] = (Random.nextFloat() * 2f - 1f) * 0.3f
    }

    private fun pinkNoiseGen(): (FloatArray) -> Unit {
        // Paul Kellet's economy pink noise algorithm
        var b0 = 0f; var b1 = 0f; var b2 = 0f
        var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
        return { buf ->
            for (i in buf.indices) {
                val white = Random.nextFloat() * 2f - 1f
                b0 = 0.99886f * b0 + white * 0.0555179f
                b1 = 0.99332f * b1 + white * 0.0750759f
                b2 = 0.96900f * b2 + white * 0.1538520f
                b3 = 0.86650f * b3 + white * 0.3104856f
                b4 = 0.55000f * b4 + white * 0.5329522f
                b5 = -0.7616f * b5 - white * 0.0168980f
                buf[i] = ((b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f) * 0.05f)
                    .coerceIn(-1f, 1f)
                b6 = white * 0.115926f
            }
        }
    }

    private fun brownNoiseGen(): (FloatArray) -> Unit {
        var lastOut = 0f
        return { buf ->
            for (i in buf.indices) {
                val white = Random.nextFloat() * 2f - 1f
                lastOut = (lastOut + 0.02f * white) / 1.02f
                buf[i] = (lastOut * 3.5f).coerceIn(-1f, 1f)
            }
        }
    }

    private fun rainGen(): (FloatArray) -> Unit {
        val pink = pinkNoiseGen()
        var dropPhase = 0.0
        return { buf ->
            pink(buf)
            // Modulate amplitude with slow irregular envelope to simulate drops
            for (i in buf.indices) {
                dropPhase += 0.0003
                val envelope = (0.6f + 0.4f * sin(dropPhase * 7.3).toFloat() *
                                         sin(dropPhase * 3.1).toFloat()).coerceIn(0f, 1f)
                buf[i] = buf[i] * envelope
            }
        }
    }

    private fun oceanGen(): (FloatArray) -> Unit {
        val brown = brownNoiseGen()
        var wavePhase = 0.0
        return { buf ->
            brown(buf)
            for (i in buf.indices) {
                wavePhase += 2.0 * PI / (SAMPLE_RATE * 8.0)  // 8-second wave period
                val wave = sin(wavePhase).toFloat()
                val envelope = (0.4f + 0.6f * wave).coerceIn(0f, 1f)
                buf[i] = buf[i] * 0.5f * envelope
            }
        }
    }
}
