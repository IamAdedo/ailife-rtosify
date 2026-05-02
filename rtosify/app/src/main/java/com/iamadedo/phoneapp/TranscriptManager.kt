package com.iamadedo.phoneapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Live Transcript Manager — WearOS / Pixel Watch feature.
 *
 * When TRANSCRIPT_REQUEST arrives from the watch, the phone starts listening
 * via Android SpeechRecognizer (uses Google's on-device speech engine).
 * Partial and final results are streamed to the watch as TRANSCRIPT_RESULT
 * messages so the user can read the conversation on their wrist without
 * looking at their phone — useful in meetings, loud environments, or for
 * accessibility.
 *
 * Sessions auto-stop after MAX_SESSION_MS or when the watch sends a
 * second TRANSCRIPT_REQUEST (toggle behaviour).
 *
 * Requires: RECORD_AUDIO permission on phone.
 */
class TranscriptManager(
    private val context: Context,
    private val onSendMessage: (ProtocolMessage) -> Unit
) {
    companion object {
        private const val TAG             = "TranscriptManager"
        private const val MAX_SESSION_MS  = 5 * 60_000L   // 5-minute auto-stop
        private const val PARTIAL_PREFIX  = "…"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope   = CoroutineScope(Dispatchers.Main)
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private val transcript = StringBuilder()

    fun toggle() {
        if (active) stop() else start()
    }

    fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available")
            return
        }
        active = true
        transcript.clear()
        handler.postDelayed(::stop, MAX_SESSION_MS)
        listenContinuously()
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun listenContinuously() {
        if (!active) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?)   = Unit
            override fun onBeginningOfSpeech()           = Unit
            override fun onRmsChanged(rms: Float)        = Unit
            override fun onBufferReceived(buf: ByteArray?) = Unit
            override fun onEndOfSpeech()                 = Unit
            override fun onEvent(t: Int, p: Bundle?)     = Unit

            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                sendToWatch(PARTIAL_PREFIX + text)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                transcript.append(text).append(" ")
                sendToWatch(transcript.toString().trim())
                // Restart for continuous listening
                if (active) handler.postDelayed(::listenContinuously, 300)
            }

            override fun onError(error: Int) {
                Log.d(TAG, "Recognition error: $error")
                // Restart on recoverable errors
                if (active && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    handler.postDelayed(::listenContinuously, 1_000)
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    private fun sendToWatch(text: String) {
        scope.launch {
            onSendMessage(ProtocolHelper.createTranscriptResult(text))
        }
    }

    fun isActive() = active
}
