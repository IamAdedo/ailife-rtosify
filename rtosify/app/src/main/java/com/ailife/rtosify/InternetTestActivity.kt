package com.ailife.rtosify

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ailife.rtosify.communication.SignalingClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.webrtc.*
import java.text.SimpleDateFormat
import java.util.*

class InternetTestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "InternetTestActivity"
    }

    private lateinit var tvLogs: TextView
    private lateinit var scrollLog: android.widget.ScrollView
    private lateinit var indicatorSignaling: View
    private lateinit var indicatorStun: View
    private lateinit var indicatorTurn: View
    private lateinit var btnStartTest: Button

    private var factory: PeerConnectionFactory? = null
    private val logDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_internet_test)

        tvLogs = findViewById(R.id.tvLogs)
        scrollLog = findViewById(R.id.scrollLog)
        indicatorSignaling = findViewById(R.id.indicatorSignaling)
        indicatorStun = findViewById(R.id.indicatorStun)
        indicatorTurn = findViewById(R.id.indicatorTurn)
        btnStartTest = findViewById(R.id.btnStartTest)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        btnStartTest.setOnClickListener {
            lifecycleScope.launch {
                runTests()
            }
        }
    }

    private suspend fun runTests() {
        btnStartTest.isEnabled = false
        tvLogs.text = ""
        resetIndicators()

        log("--- Starting Internet Configuration Test ---")

        val devicePrefManager = DevicePrefManager(this)
        val devicePrefs = devicePrefManager.getActiveDevicePrefs()
        
        val signalingUrl = devicePrefs.getString("internet_signaling_url", "ws://192.168.1.10:8080") ?: ""
        val stunUrl = devicePrefs.getString("internet_stun_url", "stun:stun.cloudflare.com:3478") ?: ""
        val turnUrl = devicePrefs.getString("internet_turn_url", "") ?: ""
        val turnUser = devicePrefs.getString("internet_turn_username", "") ?: ""
        val turnPass = devicePrefs.getString("internet_turn_password", "") ?: ""

        log("Target Device MAC: ${devicePrefManager.getSelectedDeviceMac() ?: "Global"}")
        
        // 1. Signaling Test
        val signalingResult = testSignaling(signalingUrl)
        updateIndicator(indicatorSignaling, signalingResult)

        // 2. STUN Test
        val stunResult = testIceServer(stunUrl, null, null, "srflx")
        updateIndicator(indicatorStun, stunResult)

        // 3. TURN Test
        if (turnUrl.isNotBlank()) {
            val turnResult = testIceServer(turnUrl, turnUser, turnPass, "relay")
            updateIndicator(indicatorTurn, turnResult)
        } else {
            log("TURN: No URL configured, skipping test.")
        }

        log("--- Test Complete ---")
        btnStartTest.isEnabled = true
    }

    private suspend fun testSignaling(url: String): Boolean {
        log(getString(R.string.internet_test_connecting_signaling, url))
        
        // Use a dummy client for testing, but with valid MAC format
        val localMac = "02:00:00:00:00:01"
        val remoteMac = "02:00:00:00:00:02"
        val testClient = SignalingClient(url, localMac, remoteMac)
        var success = false

        try {
            testClient.connect()
            // Wait for connection state or timeout
            withTimeoutOrNull(5000L) {
                testClient.connectionState.first { it }
            }?.let {
                if (it) {
                    log(getString(R.string.internet_test_signaling_ok))
                    success = true
                }
            } ?: run {
                log(getString(R.string.internet_test_signaling_fail, "Timeout (5s)"))
            }
        } catch (e: Exception) {
            log(getString(R.string.internet_test_signaling_fail, e.message))
        } finally {
            testClient.close()
        }
        return success
    }

    private suspend fun testIceServer(url: String, user: String?, pass: String?, targetType: String): Boolean {
        log(if (targetType == "srflx") getString(R.string.internet_test_stun_gathering, url) 
            else getString(R.string.internet_test_turn_gathering, url))

        return suspendCancellableCoroutine { continuation ->
            var finished = false
            
            val iceServers = mutableListOf<PeerConnection.IceServer>()
            val builder = PeerConnection.IceServer.builder(url)
            if (!user.isNullOrBlank()) builder.setUsername(user)
            if (!pass.isNullOrBlank()) builder.setPassword(pass)
            iceServers.add(builder.createIceServer())

            if (factory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(this)
                        .createInitializationOptions()
                )
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            }

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
            var pc: PeerConnection? = null

            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    log("ICE State: $state")
                }
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    log("Gathering State: $state")
                    if (state == PeerConnection.IceGatheringState.COMPLETE && !finished) {
                        finished = true
                        log(if (targetType == "srflx") "STUN: Gathering complete, no srflx candidates found."
                            else "TURN: Gathering complete, no relay candidates found.")
                        cleanup(pc)
                        continuation.resume(false) {}
                    }
                }
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        val sdp = it.sdp
                        val type = parseCandidateType(sdp)
                        log("Candidate found: type=$type, sdp=${sdp.take(40)}...")
                        
                        if (type == targetType && !finished) {
                            finished = true
                            log(if (targetType == "srflx") getString(R.string.internet_test_stun_ok)
                                else getString(R.string.internet_test_turn_ok))
                            cleanup(pc)
                            continuation.resume(true) {}
                        }
                    }
                }
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }

            pc = factory?.createPeerConnection(rtcConfig, observer)
            
            // Create a DataChannel to ensure gathering is triggered for data-only sessions
            pc?.createDataChannel("test_channel", DataChannel.Init())
            
            // Trigger gathering by creating an offer
            pc?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    pc?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, desc)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())

            // Timeout watchdog
            lifecycleScope.launch {
                delay(10000)
                if (!finished) {
                    finished = true
                    log("Test timeout (10s)")
                    cleanup(pc)
                    continuation.resume(false) {}
                }
            }
        }
    }

    private fun cleanup(pc: PeerConnection?) {
        try {
            pc?.close()
            pc?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }

    private fun resetIndicators() {
        updateIndicator(indicatorSignaling, null)
        updateIndicator(indicatorStun, null)
        updateIndicator(indicatorTurn, null)
    }

    private fun updateIndicator(view: View, success: Boolean?) {
        view.setBackgroundResource(when (success) {
            true -> R.drawable.status_indicator_on
            false -> R.drawable.status_indicator_error
            else -> R.drawable.status_indicator_off
        })
    }

    private fun log(message: String) {
        val time = logDateFormat.format(Date())
        runOnUiThread {
            tvLogs.append("[$time] $message\n")
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun parseCandidateType(sdp: String): String {
        val parts = sdp.split(" ")
        for (i in parts.indices) {
            if (parts[i] == "typ" && i + 1 < parts.size) {
                return parts[i + 1]
            }
        }
        return "unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        factory?.dispose()
        factory = null
    }
}
