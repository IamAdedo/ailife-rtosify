package com.ailife.rtosify

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
        const val ACTION_SCREEN_DATA = "com.ailife.rtosify.SCREEN_DATA_RECEIVED"
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
        setContentView(R.layout.activity_mirror)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surfaceView)
        surfaceView.holder.addCallback(this)
        
        targetWidth = intent.getIntExtra("width", 480)
        targetHeight = intent.getIntExtra("height", 854)

        surfaceView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        findViewById<android.widget.ImageButton>(R.id.btnRemoteBack).setOnClickListener {
            sendRemoteNavigation(RemoteInputData.ACTION_NAV_BACK)
        }

        findViewById<android.widget.ImageButton>(R.id.btnRemoteHome).setOnClickListener {
            sendRemoteNavigation(RemoteInputData.ACTION_NAV_HOME)
        }

        checkResolutionMatching()
        
        surfaceView.post {
            adjustSurfaceSize()
        }
    }

    private fun adjustSurfaceSize() {
        val parent = surfaceView.parent as android.view.View
        val viewWidth = parent.width
        val viewHeight = parent.height
        
        if (viewWidth == 0 || viewHeight == 0) {
            surfaceView.post { adjustSurfaceSize() }
            return
        }

        val videoRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()

        val params = surfaceView.layoutParams
        if (videoRatio > viewRatio) {
            // Video is wider than parent (letterbox top/bottom)
            params.width = viewWidth
            params.height = (viewWidth / videoRatio).toInt()
        } else {
            // Video is taller than parent (pillarbox sides)
            params.height = viewHeight
            params.width = (viewHeight * videoRatio).toInt()
        }
        surfaceView.layoutParams = params
        Log.d(TAG, "Adjusted SurfaceView size: ${params.width}x${params.height} for video ${targetWidth}x${targetHeight}")
    }

    private fun sendRemoteNavigation(navAction: Int) {
        val intent = Intent("com.ailife.rtosify.SEND_REMOTE_INPUT")
        intent.setPackage(packageName)
        intent.putExtra("action", navAction)
        intent.putExtra("x", 0f)
        intent.putExtra("y", 0f)
        sendBroadcast(intent)
        Log.d(TAG, "Sent remote navigation action: $navAction")
    }

    private fun checkResolutionMatching() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val resMatching = prefs.getBoolean("mirror_res_matching", false)
        val aspectMatching = prefs.getBoolean("mirror_aspect_matching", false)

        if (resMatching || aspectMatching) {
            val metrics = resources.displayMetrics
            val intent = Intent("com.ailife.rtosify.UPDATE_REMOTE_RESOLUTION")
            intent.setPackage(packageName)
            intent.putExtra("width", metrics.widthPixels)
            intent.putExtra("height", metrics.heightPixels)
            intent.putExtra("density", metrics.densityDpi)
            intent.putExtra("mode", if (resMatching) ResolutionData.MODE_RESOLUTION else ResolutionData.MODE_ASPECT)
            sendBroadcast(intent)
            Log.d(TAG, "Requested remote resolution update: mode=${if (resMatching) "RES" else "ASPECT"}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)
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
        
        // Send stop command to companion's MirroringService
        val stopIntent = Intent("com.ailife.rtosify.SEND_MIRROR_STOP")
        stopIntent.setPackage(packageName)
        sendBroadcast(stopIntent)
        
        // Reset resolution if matching was enabled
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("mirror_res_matching", false)) {
            val resetIntent = Intent("com.ailife.rtosify.UPDATE_REMOTE_RESOLUTION")
            resetIntent.setPackage(packageName)
            resetIntent.putExtra("reset", true)
            sendBroadcast(resetIntent)
        }
        
        releaseDecoder()
    }

    private fun handleTouch(event: MotionEvent) {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("mirror_enable_touch", true)) return

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
        val intent = Intent("com.ailife.rtosify.SEND_REMOTE_INPUT")
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
