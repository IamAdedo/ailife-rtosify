package com.ailife.rtosifycompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer

class MirrorActivity : AppCompatActivity(), SurfaceHolder.Callback {
    companion object {
        private const val TAG = "MirrorActivity"
        const val ACTION_SCREEN_DATA = "com.ailife.rtosifycompanion.SCREEN_DATA_RECEIVED"
    }

    private lateinit var surfaceView: SurfaceView
    private var codec: MediaCodec? = null
    private var isCodecReady = false
    
    private var targetWidth = 480
    private var targetHeight = 854

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SCREEN_DATA) {
                val base64Data = intent.getStringExtra("data") ?: return
                val data = Base64.decode(base64Data, Base64.DEFAULT)
                decodeFrame(data)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(this)
        
        targetWidth = intent.getIntExtra("width", 480)
        targetHeight = intent.getIntExtra("height", 854)

        surfaceView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        checkResolutionMatching()
    }

    private fun checkResolutionMatching() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("mirror_res_matching", false)) {
            val metrics = resources.displayMetrics
            val intent = Intent("com.ailife.rtosify.UPDATE_REMOTE_RESOLUTION")
            intent.putExtra("width", metrics.widthPixels)
            intent.putExtra("height", metrics.heightPixels)
            intent.putExtra("density", metrics.densityDpi)
            sendBroadcast(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            dataReceiver,
            IntentFilter(ACTION_SCREEN_DATA),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Send stop command to phone's MirroringService
        val stopIntent = Intent("com.ailife.rtosifycompanion.SEND_MIRROR_STOP")
        stopIntent.setPackage(packageName)
        sendBroadcast(stopIntent)
        
        // Reset resolution if matching was enabled
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("mirror_res_matching", false)) {
            val resetIntent = Intent("com.ailife.rtosifycompanion.UPDATE_REMOTE_RESOLUTION")
            resetIntent.setPackage(packageName)
            resetIntent.putExtra("reset", true)
            sendBroadcast(resetIntent)
        }
        
        releaseDecoder()
    }

    private fun handleTouch(event: MotionEvent) {
        val viewWidth = surfaceView.width.toFloat()
        val viewHeight = surfaceView.height.toFloat()
        val videoRatio = targetWidth.toFloat() / targetHeight
        val viewRatio = viewWidth / viewHeight

        var displayedWidth: Float
        var displayedHeight: Float
        var offsetX = 0f
        var offsetY = 0f

        if (videoRatio > viewRatio) {
            // Video is wider than view (letterboxed top/bottom)
            displayedWidth = viewWidth
            displayedHeight = viewWidth / videoRatio
            offsetY = (viewHeight - displayedHeight) / 2f
        } else {
            // Video is taller than view (pillarboxed left/right)
            displayedHeight = viewHeight
            displayedWidth = viewHeight * videoRatio
            offsetX = (viewWidth - displayedWidth) / 2f
        }

        val x = ((event.x - offsetX) / displayedWidth).coerceIn(0f, 1f)
        val y = ((event.y - offsetY) / displayedHeight).coerceIn(0f, 1f)
        
        Log.d(TAG, "Touch event: action=${event.action}, x=$x, y=$y (view: ${viewWidth}x${viewHeight}, video: ${targetWidth}x${targetHeight})")
        
        // Send touch to BluetoothService
        val intent = Intent("com.ailife.rtosifycompanion.SEND_REMOTE_INPUT")
        intent.setPackage(packageName) // Make it explicit for Android 14+
        intent.putExtra("action", event.action)
        intent.putExtra("x", x)
        intent.putExtra("y", y)
        sendBroadcast(intent)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        setupDecoder(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseDecoder()
    }

    private fun setupDecoder(holder: SurfaceHolder) {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight)
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec?.configure(format, holder.surface, null, 0)
            codec?.start()
            isCodecReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup decoder: ${e.message}")
        }
    }

    private fun decodeFrame(data: ByteArray) {
        if (!isCodecReady) return
        
        try {
            val inputBufferId = codec?.dequeueInputBuffer(10000) ?: -1
            if (inputBufferId >= 0) {
                val inputBuffer = codec?.getInputBuffer(inputBufferId)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                codec?.queueInputBuffer(inputBufferId, 0, data.size, System.nanoTime() / 1000, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferId = codec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
            while (outputBufferId >= 0) {
                codec?.releaseOutputBuffer(outputBufferId, true)
                outputBufferId = codec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame: ${e.message}")
        }
    }

    private fun releaseDecoder() {
        isCodecReady = false
        try {
            codec?.stop()
        } catch (e: IllegalStateException) {
            // Decoder wasn't started or already stopped, ignore
        }
        codec?.release()
        codec = null
    }
}
