package com.ailife.rtosifycompanion.communication

import android.content.Context
import android.util.Log
import com.ailife.rtosifycompanion.ProtocolMessage
import com.ailife.rtosifycompanion.security.EncryptionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer

/**
 * Internet transport using WebRTC Data Channels.
 * Provides P2P communication over the internet with NAT traversal.
 * Payloads are end-to-end encrypted using EncryptionManager.
 */
class WebRtcTransport(
    private val context: Context,
    private val remoteMac: String,
    private val localMac: String,
    private val deviceName: String,
    private val encryptionManager: EncryptionManager,
    private val signalingUrl: String,
    private val isInitiator: Boolean
) : CommunicationTransport {

    companion object {
        private const val TAG = "WebRtcTransport"
        private const val CONNECTION_TIMEOUT_MS = 30000L
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var signalingClient: SignalingClient? = null

    private val messageChannel = Channel<ProtocolMessage>(Channel.BUFFERED)
    private val _connectionState = MutableStateFlow(false)
    private val _dataChannelOpen = MutableStateFlow(false)
    private val _signalingConnected = MutableStateFlow(false)

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The ID to send signaling messages to. Defaults to remoteMac, but updates
    // if we receive an offer from a different source ID
    private var activeSignalingTarget: String = remoteMac

    override suspend fun connect(): Boolean {
        Log.d(TAG, "connect() called - remoteMac=$remoteMac, signalingUrl=$signalingUrl, isInitiator=$isInitiator")

        return try {
            withContext(Dispatchers.Main) {
                initializeWebRtc()
            }

            startSignaling()

            // Wait for signaling to connect
            val signalingConnected = withTimeoutOrNull(10000L) {
                _signalingConnected.first { it }
            }

            if (signalingConnected != true) {
                Log.e(TAG, "Signaling connection timeout")
                return false
            }

            withContext(Dispatchers.Main) {
                createPeerConnection()

                if (isInitiator) {
                    createDataChannel()
                    createOffer()
                }
            }

            // Wait for DataChannel to open with timeout
            val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                _dataChannelOpen.first { it }
            }

            if (connected == true) {
                Log.d(TAG, "WebRTC connection established successfully")
                _connectionState.value = true
                true
            } else {
                Log.e(TAG, "WebRTC connection timeout - DataChannel did not open")
                disconnect()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC connect failed", e)
            false
        }
    }

    private fun initializeWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext, true, true
        )
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(
            EglBase.create().eglBaseContext
        )

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }

    private fun startSignaling() {
        signalingClient = SignalingClient(signalingUrl, localMac, remoteMac)

        // Listen for connection state
        coroutineScope.launch {
            signalingClient?.connectionState?.collect { connected ->
                Log.d(TAG, "Signaling connection state: $connected")
                _signalingConnected.value = connected
            }
        }

        signalingClient?.connect()

        coroutineScope.launch {
            signalingClient?.signalingEvents?.collect { event ->
                // Guard: Ignore our own signals if they loop back
                val source = when (event) {
                    is SignalingClient.SignalingEvent.Offer -> event.source
                    is SignalingClient.SignalingEvent.Answer -> event.source
                    is SignalingClient.SignalingEvent.IceCandidate -> event.source
                }
                if (source == localMac) {
                    Log.w(TAG, "Ignoring self-loop signaling event from $source")
                    return@collect
                }

                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Received signaling event: $event")
                    when (event) {
                        is SignalingClient.SignalingEvent.Offer -> handleOffer(event.sdp, event.source)
                        is SignalingClient.SignalingEvent.Answer -> handleAnswer(event.sdp, event.source)
                        is SignalingClient.SignalingEvent.IceCandidate -> handleIceCandidate(event)
                    }
                }
            }
        }
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection State: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    _connectionState.value = true
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED || state == PeerConnection.IceConnectionState.FAILED) {
                    _connectionState.value = false
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { sendIceCandidate(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {
                Log.d(TAG, "Received remote DataChannel")
                dc?.let { setupDataChannel(it) }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
    }

    private fun createDataChannel() {
        val init = DataChannel.Init()
        dataChannel = peerConnection?.createDataChannel("rtosify_data", init)
        dataChannel?.let { setupDataChannel(it) }
    }

    private fun setupDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() {
                val state = dc.state()
                Log.d(TAG, "DataChannel State: $state")
                when (state) {
                    DataChannel.State.OPEN -> {
                        Log.i(TAG, "DataChannel is OPEN")
                        _dataChannelOpen.value = true
                        _connectionState.value = true
                    }
                    DataChannel.State.CLOSED -> {
                        Log.w(TAG, "DataChannel is CLOSED")
                        _dataChannelOpen.value = false
                        _connectionState.value = false
                    }
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                handleDataMessage(buffer)
            }
        })
    }

    private fun handleDataMessage(buffer: DataChannel.Buffer) {
        try {
            val data = ByteArray(buffer.data.remaining())
            buffer.data.get(data)

            // Decrypt
            val decryptedBytes = encryptionManager.decryptForDevice(remoteMac, data)
            if (decryptedBytes != null) {
                val json = String(decryptedBytes, Charsets.UTF_8)
                val message = ProtocolMessage.fromJson(json)
                coroutineScope.launch {
                    messageChannel.send(message)
                }
            } else {
                Log.e(TAG, "Failed to decrypt WebRTC message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling data message", e)
        }
    }

    private fun createOffer() {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                    sendSdp("offer", it.description)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(s: String?) {}
        }, MediaConstraints())
    }

    private fun handleOffer(sdp: String, source: String) {
        Log.i(TAG, "Handling Offer from: $source. Updating signaling target.")
        activeSignalingTarget = source
        
        val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d(TAG, "Set remote description (Offer) success. Creating Answer...")
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        Log.d(TAG, "Create Answer success. Type: ${desc?.type}")
                        desc?.let {
                            peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                            sendSdp("answer", it.description)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(s: String?) {
                        Log.e(TAG, "Create Answer failure: $s")
                    }
                    override fun onSetFailure(s: String?) {
                        Log.e(TAG, "Set local description (Answer) failure: $s")
                    }
                }, MediaConstraints())
            }
            override fun onSetFailure(s: String?) {
                Log.e(TAG, "Set remote description (Offer) failure: $s")
            }
        }, desc)
    }

    private fun handleAnswer(sdp: String, source: String) {
        Log.i(TAG, "Handling Answer from: $source. Updating signaling target.")
        // If we initiated, we might want to lock onto this source, 
        // though usually we know who we sent strictly to.
        activeSignalingTarget = source 
        
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.i(TAG, "Set remote description (Answer) success.")
            }
            override fun onSetFailure(s: String?) {
                Log.e(TAG, "Set remote description (Answer) failure: $s")
            }
        }, desc)
    }

    private fun handleIceCandidate(event: SignalingClient.SignalingEvent.IceCandidate) {
        val candidate = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
        peerConnection?.addIceCandidate(candidate)
    }

    private fun sendSdp(type: String, sdp: String) {
        val payload = JSONObject().apply {
            put("type", type)
            put("sdp", sdp)
        }
        Log.d(TAG, "Sending SDP ($type) to $activeSignalingTarget")
        signalingClient?.sendSignal(activeSignalingTarget, payload)
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val payload = JSONObject().apply {
            put("type", "candidate")
            put("candidate", candidate.sdp)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
        }
        signalingClient?.sendSignal(activeSignalingTarget, payload)
    }

    override suspend fun send(message: ProtocolMessage): Boolean {
        if (dataChannel?.state() != DataChannel.State.OPEN) return false

        return try {
            val jsonBytes = message.toBytes()
            val encryptedBytes = encryptionManager.encryptForDevice(remoteMac, jsonBytes) ?: return false

            val buffer = DataChannel.Buffer(ByteBuffer.wrap(encryptedBytes), true)
            dataChannel?.send(buffer) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            false
        }
    }

    override fun receive(): Flow<ProtocolMessage> = messageChannel.receiveAsFlow()

    override suspend fun disconnect() {
        Log.d(TAG, "Disconnecting WebRTC Transport")
        
        // 1. Stop all internal collectors and jobs immediately
        coroutineScope.cancel()
        
        // 2. Update states
        _connectionState.value = false
        _dataChannelOpen.value = false
        
        // 3. Close the message channel so collectors know we're done
        try {
            messageChannel.close()
        } catch (_: Exception) {}

        // 4. Close and dispose WebRTC native resources in order
        // DataChannel first, then PeerConnection, then Factory
        // Must happen on the same thread they were created (Main)
        withContext(Dispatchers.Main) {
            try {
                dataChannel?.unregisterObserver()
                dataChannel?.close()
                dataChannel?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing DataChannel: ${e.message}")
            } finally {
                dataChannel = null
            }

            try {
                peerConnection?.close()
                peerConnection?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing PeerConnection: ${e.message}")
            } finally {
                peerConnection = null
            }

            try {
                signalingClient?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing signaling client: ${e.message}")
            } finally {
                signalingClient = null
            }

            // Factory disposal is the most risky. Wait a tiny bit for threads to settle.
            try {
                factory?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing factory: ${e.message}")
            } finally {
                factory = null
            }
        }
    }

    override fun isConnected(): Boolean = _connectionState.value

    override fun getTransportType(): String = "Internet (WebRTC)"

    override fun getRemoteDeviceName(): String? = deviceName

    override fun getRemoteAddress(): String? = remoteMac

    // Simple SdpObserver implementation to reduce boilerplate
    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
