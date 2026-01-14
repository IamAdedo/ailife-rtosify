package com.ailife.rtosify.communication

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * WebSocket client for WebRTC signaling.
 * Handles exchanging SDP offers/answers and ICE candidates via a central server.
 */
class SignalingClient(
    private val serverUrl: String,
    private val localMac: String,
    private val remoteMac: String
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep connection open
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _signalingEvents = MutableSharedFlow<SignalingEvent>()
    val signalingEvents: SharedFlow<SignalingEvent> = _signalingEvents

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    sealed class SignalingEvent {
        data class Offer(val sdp: String, val source: String) : SignalingEvent()
        data class Answer(val sdp: String, val source: String) : SignalingEvent()
        data class IceCandidate(val sdpMid: String, val sdpMLineIndex: Int, val candidate: String, val source: String) : SignalingEvent()
    }

    fun connect() {
        Log.d(TAG, "Connecting to signaling server: $serverUrl (localMac=$localMac, remoteMac=$remoteMac)")
        try {
            val request = Request.Builder().url(serverUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Connected to signaling server")
                    _connectionState.value = true
                    sendJoin()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Received message: $text")
                    handleMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Disconnected from signaling server: $reason")
                    _connectionState.value = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Signaling connection failed: ${t.message}", t)
                    _connectionState.value = false
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection", e)
            _connectionState.value = false
        }
    }

    private fun sendJoin() {
        val json = JSONObject().apply {
            put("type", "join")
            put("mac", localMac)
            put("target", remoteMac)
        }
        Log.d(TAG, "Sending join: $json")
        webSocket?.send(json.toString())
    }

    fun sendSignal(targetMac: String, payload: JSONObject) {
        val json = JSONObject().apply {
            put("type", "signal")
            put("target", targetMac)
            put("source", localMac)
            put("payload", payload)
        }
        Log.d(TAG, "Sending signal to $targetMac: ${payload.optString("type")}")
        webSocket?.send(json.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            Log.d(TAG, "Handling message type: $type")

            when (type) {
                "signal" -> {
                    val source = json.getString("source")
                    val payload = json.getJSONObject("payload")
                    val signalType = payload.optString("type")

                    Log.d(TAG, "Signal from $source, signalType=$signalType")

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
                "joined" -> {
                    Log.d(TAG, "Successfully joined signaling room")
                }
                "peer_joined" -> {
                    val peer = json.optString("mac")
                    Log.d(TAG, "Peer joined: $peer")
                }
                "error" -> {
                    val error = json.optString("message")
                    Log.e(TAG, "Signaling error: $error")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $text", e)
        }
    }

    fun close() {
        Log.d(TAG, "Closing signaling connection")
        webSocket?.close(1000, "App closed")
        webSocket = null
        _connectionState.value = false
    }
}
