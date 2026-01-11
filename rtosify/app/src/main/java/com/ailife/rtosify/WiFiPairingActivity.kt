package com.ailife.rtosify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class WiFiPairingActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "WiFiPairingActivity"
        private const val TIMEOUT_KEY_EXCHANGE = 10L
        private const val TIMEOUT_DISCOVERY = 30L
        private const val TIMEOUT_WIFI_CONNECT = 15L
        private const val TIMEOUT_TEST_ENCRYPT = 15L
    }

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button
    private lateinit var btnClose: Button

    private lateinit var layoutStep1: View
    private lateinit var layoutStep2: View
    private lateinit var layoutStep3: View
    private lateinit var layoutStep4: View

    private lateinit var imgStep1: ImageView
    private lateinit var imgStep2: ImageView
    private lateinit var imgStep3: ImageView
    private lateinit var imgStep4: ImageView

    private lateinit var tvStep1: TextView
    private lateinit var tvStep2: TextView
    private lateinit var tvStep3: TextView
    private lateinit var tvStep4: TextView
    private lateinit var tvDiscoveryDetails: TextView

    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val keyAckDeferred = CompletableDeferred<Boolean>()
    private val deviceFoundDeferred = CompletableDeferred<String>()
    private val testAckDeferred = CompletableDeferred<Boolean>()
    private val testReceivedDeferred = CompletableDeferred<String>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
            
            bluetoothService?.callback = object : BluetoothService.ServiceCallback {
                override fun onStatusChanged(status: String) {}
                override fun onDeviceConnected(deviceName: String) {}
                override fun onDeviceDisconnected() {
                    if (!isFinishing) {
                        runOnUiThread {
                            showError("Connection lost")
                        }
                    }
                }
                override fun onError(message: String) {}
                override fun onScanResult(devices: List<android.bluetooth.BluetoothDevice>) {}
                override fun onAppListReceived(appsJson: String) {}
                override fun onUploadProgress(progress: Int) {}
                override fun onDownloadProgress(progress: Int, file: java.io.File?) {}
                override fun onFileListReceived(path: String, filesJson: String) {}
                
                override fun onWifiKeyAck(success: Boolean) {
                    keyAckDeferred.complete(success)
                }
                
                override fun onWifiTestAck(success: Boolean) {
                    testAckDeferred.complete(success)
                }
                
                override fun onWifiTestReceived(message: String) {
                    testReceivedDeferred.complete(message)
                }
            }
            
            startPairingFlow()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_pairing)

        initViews()
        bindService()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvPairingStatus)
        progressBar = findViewById(R.id.pairingProgressBar)
        btnRetry = findViewById(R.id.btnPairingRetry)
        btnClose = findViewById(R.id.btnPairingClose)

        layoutStep1 = findViewById(R.id.layoutStep1)
        layoutStep2 = findViewById(R.id.layoutStep2)
        layoutStep3 = findViewById(R.id.layoutStep3)
        layoutStep4 = findViewById(R.id.layoutStep4)

        imgStep1 = findViewById(R.id.imgStep1)
        imgStep2 = findViewById(R.id.imgStep2)
        imgStep3 = findViewById(R.id.imgStep3)
        imgStep4 = findViewById(R.id.imgStep4)

        tvStep1 = findViewById(R.id.tvStep1)
        tvStep2 = findViewById(R.id.tvStep2)
        tvStep3 = findViewById(R.id.tvStep3)
        tvStep4 = findViewById(R.id.tvStep4)
        tvDiscoveryDetails = findViewById(R.id.tvDiscoveryDetails)

        btnRetry.setOnClickListener {
            resetPairingUI()
            startPairingFlow()
        }

        btnClose.setOnClickListener {
            finish()
        }
    }

    private fun bindService() {
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startPairingFlow() {
        lifecycleScope.launch {
            // Check if already paired
            val mac = bluetoothService?.getConnectedDeviceMac()
            if (mac != null && bluetoothService?.getEncryptionKeyForCurrentDevice() != null) {
                Log.d(TAG, "Device already paired, showing status")
                showAlreadyPaired()
                return@launch
            }
            
            try {
                // Step 1: Key Exchange
                setCurrentStep(1)
                val key = bluetoothService?.getEncryptionKeyForCurrentDevice()
                if (key == null) {
                    showError(getString(R.string.pairing_error_no_key))
                    return@launch
                }

                Log.d(TAG, "Step 1: Sending key exchange...")
                bluetoothService?.sendWifiKeyExchange(key)
                
                val keySuccess = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(TIMEOUT_KEY_EXCHANGE)) {
                    keyAckDeferred.await()
                } ?: false

                if (!keySuccess) {
                    showError(getString(R.string.pairing_error_key_ack))
                    return@launch
                }
                markStepComplete(1)

                // Step 2: Discovery
                Log.d(TAG, "Step 2: Starting mDNS discovery...")
                bluetoothService?.startMdnsDiscovery { host ->
                    Log.d(TAG, "Device found at $host")
                    deviceFoundDeferred.complete(host)
                }

                val deviceHost = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(TIMEOUT_DISCOVERY)) {
                    deviceFoundDeferred.await()
                }

                if (deviceHost == null) {
                    showError(getString(R.string.pairing_error_discovery))
                    return@launch
                }
                
                runOnUiThread {
                    tvDiscoveryDetails.visibility = View.VISIBLE
                    tvDiscoveryDetails.text = getString(R.string.pairing_found_device, deviceHost)
                }
                
                // Trigger connection now that we found it
                val mac = bluetoothService?.getConnectedDeviceMac()
                if (mac != null) {
                    Log.d(TAG, "Triggering WiFi connection for $mac")
                    bluetoothService?.startWifiTransport(mac)
                }
                
                markStepComplete(2)

                // Step 3: Connecting
                setCurrentStep(3)
                Log.d(TAG, "Step 3: Waiting for WiFi transport to become active...")
                // We wait for isWifiTransportActive to become true
                val wifiConnected = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(TIMEOUT_WIFI_CONNECT)) {
                    while (true) {
                        if (bluetoothService?.isWifiTransportActive() == true) return@withTimeoutOrNull true
                        delay(500)
                    }
                    false
                } ?: false

                if (!wifiConnected) {
                    showError(getString(R.string.pairing_error_wifi))
                    return@launch
                }
                markStepComplete(3)

                // Step 4: Testing
                setCurrentStep(4)
                Log.d(TAG, "Step 4: Sending encrypted test message...")
                bluetoothService?.sendWifiTestMessage("pair_test_phone")
                
                val testAck = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(TIMEOUT_TEST_ENCRYPT)) {
                    testAckDeferred.await()
                } ?: false

                if (!testAck) {
                    showError(getString(R.string.pairing_error_test))
                    return@launch
                }

                // Wait for companion's test message
                val companionTest = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(TIMEOUT_TEST_ENCRYPT)) {
                    testReceivedDeferred.await()
                }

                if (companionTest == null) {
                    showError(getString(R.string.pairing_error_test))
                    return@launch
                }
                
                markStepComplete(4)
                showSuccess()

            } catch (e: Exception) {
                Log.e(TAG, "Pairing flow error", e)
                showError("Unexpected error: ${e.message}")
            } finally {
                bluetoothService?.stopMdnsDiscovery()
            }
        }
    }

    private fun setCurrentStep(step: Int) {
        runOnUiThread {
            when (step) {
                1 -> {
                    layoutStep1.alpha = 1.0f
                    tvStatus.text = getString(R.string.pairing_step_key_exchange)
                }
                2 -> {
                    layoutStep2.alpha = 1.0f
                    tvStatus.text = getString(R.string.pairing_step_discovery)
                }
                3 -> {
                    layoutStep3.alpha = 1.0f
                    tvStatus.text = getString(R.string.pairing_step_connecting)
                }
                4 -> {
                    layoutStep4.alpha = 1.0f
                    tvStatus.text = getString(R.string.pairing_step_testing)
                }
            }
        }
    }

    private fun markStepComplete(step: Int) {
        runOnUiThread {
            val img = when (step) {
                1 -> imgStep1
                2 -> imgStep2
                3 -> imgStep3
                4 -> imgStep4
                else -> null
            }
            img?.setImageResource(R.drawable.ic_check)
            img?.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GREEN)
        }
    }

    private fun showSuccess() {
        runOnUiThread {
            tvStatus.text = getString(R.string.pairing_complete)
            tvStatus.setTextColor(android.graphics.Color.GREEN)
            progressBar.visibility = View.GONE
            btnClose.visibility = View.VISIBLE
            btnRetry.visibility = View.GONE
        }
    }

    private fun showAlreadyPaired() {
        runOnUiThread {
            tvStatus.text = getString(R.string.wifi_already_paired)
            tvStatus.setTextColor(android.graphics.Color.GREEN)
            progressBar.visibility = View.GONE
            btnClose.visibility = View.VISIBLE
            btnRetry.visibility = View.VISIBLE
            btnRetry.text = getString(R.string.wifi_repair)
            
            // Show all steps as complete
            for (i in 1..4) {
                markStepComplete(i)
            }
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            tvStatus.text = message
            tvStatus.setTextColor(android.graphics.Color.RED)
            progressBar.visibility = View.GONE
            btnRetry.visibility = View.VISIBLE
            btnClose.visibility = View.VISIBLE
        }
    }

    private fun resetPairingUI() {
        runOnUiThread {
            tvStatus.text = "Initializing..."
            tvStatus.setTextColor(android.graphics.Color.WHITE)
            progressBar.visibility = View.VISIBLE
            btnRetry.visibility = View.GONE
            btnClose.visibility = View.GONE
            tvDiscoveryDetails.visibility = View.GONE
            
            val steps = listOf(layoutStep1, layoutStep2, layoutStep3, layoutStep4)
            val imgs = listOf(imgStep1, imgStep2, imgStep3, imgStep4)
            val icons = listOf(R.drawable.ic_sync, R.drawable.ic_search, R.drawable.ic_wifi, R.drawable.ic_lock)
            
            steps.forEach { it.alpha = 0.5f }
            imgs.forEachIndexed { i, img -> 
                img.setImageResource(icons[i])
                img.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
