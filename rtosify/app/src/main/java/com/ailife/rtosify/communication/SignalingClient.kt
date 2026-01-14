package com.ailife.rtosify.communication

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * WebSocket client for WebRTC signaling.
 * Handles exchanging SDP offers/answers and ICE candidates via a central server.
 */
class SignalingClient(
    private val serverUrl: String,
    private val localMac: String
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep connection open
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _signalingEvents = MutableSharedFlow<SignalingEvent>()
    val signalingEvents: SharedFlow<SignalingEvent> = _signalingEvents

    sealed class SignalingEvent {
        data class Offer(val sdp: String, val source: String) : SignalingEvent()
        data class Answer(val sdp: String, val source: String) : SignalingEvent()
        data class IceCandidate(val sdpMid: String, val sdpMLineIndex: Int, val candidate: String, val source: String) : SignalingEvent()
    }

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to signaling server")
                sendJoin()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Disconnected from signaling server: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Signaling connection failed", t)
            }
        })
    }

    private fun sendJoin() {
        val json = JSONObject().apply {
            put("type", "join")
            put("mac", localMac)
        }
        webSocket?.send(json.toString())
    }

    fun sendSignal(targetMac: String, payload: JSONObject) {
        val json = JSONObject().apply {
            put("type", "signal")
            put("target", targetMac)
            put("payload", payload)
        }
        webSocket?.send(json.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            if (type == "signal") {
                val source = json.getString("source")
                val payload = json.getJSONObject("payload")
                val signalType = payload.optString("type")

                when (signalType) {
                    "offer" -> {
                        val sdp = payload.getString("sdp")
                        _signalingEvents.tryEmit(SignalingEvent.Offer(sdp, source))
                    }
                    "answer" -> {
                        val sdp = payload.getString("sdp")
                        _signalingEvents.tryEmit(SignalingEvent.Answer(sdp, source))
                    }
                    "candidate" -> {
                        val candidate = payload.getString("candidate")
                        val sdpMid = payload.getString("sdpMid")
                        val sdpMLineIndex = payload.getInt("sdpMLineIndex")
                        _signalingEvents.tryEmit(SignalingEvent.IceCandidate(sdpMid, sdpMLineIndex, candidate, source))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $text", e)
        }
    }

    fun close() {
        webSocket?.close(1000, "App closed")
        webSocket = null
    }
}
