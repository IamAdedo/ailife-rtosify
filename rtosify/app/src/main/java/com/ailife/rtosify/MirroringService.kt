package com.ailife.rtosify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

class MirroringService : Service() {
    companion object {
        private const val TAG = "MirroringService"
        private const val CHANNEL_ID = "mirroring_channel"
        private const val NOTIFICATION_ID = 100
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"
        const val ACTION_MIRROR_STATE_CHANGED = "com.ailife.rtosify.MIRROR_STATE_CHANGED"
        
        var isRunning = false
            private set

        private var lastResultCode: Int = 0
        private var lastData: Intent? = null
        private var lastDpi: Int = 240
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var isRunning = false
    
    private val bufferInfo = MediaCodec.BufferInfo()
    private val handler = Handler(Looper.getMainLooper())

    inner class MirroringBinder : Binder() {
        fun getService(): MirroringService = this@MirroringService
    }

    private val binder = MirroringBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Mirroring",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ requires calling startForeground() almost immediately
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirroring")
            .setContentText("Mirroring screen...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val width = intent?.getIntExtra(EXTRA_WIDTH, 480) ?: 480
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 854) ?: 854
        val dpi = intent?.getIntExtra(EXTRA_DPI, 240) ?: 240

        // Register receiver for stop commands
        val filter = IntentFilter("com.ailife.rtosify.STOP_MIRROR")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.registerReceiver(this, stopReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, filter)
        }

        if (resultCode != 0 && data != null) {
            lastResultCode = resultCode
            lastData = data
            lastDpi = dpi
            startMirroring(resultCode, data, width, height, dpi)
        } else {
            stopSelf()
        }

        // Add refresh receiver
        val refreshFilter = IntentFilter("com.ailife.rtosify.REFRESH_MIRROR")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.registerReceiver(this, refreshReceiver, refreshFilter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, refreshFilter)
        }

        return START_NOT_STICKY
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received REFRESH_MIRROR, re-initializing with current screen size")
            if (lastResultCode != 0 && lastData != null) {
                // Get current actual display metrics (as changed by wm size)
                val metrics = resources.displayMetrics
                val newW = metrics.widthPixels
                val newH = metrics.heightPixels
                
                Log.d(TAG, "Re-starting mirroring with new resolution: ${newW}x${newH}")
                
                // Signal current loop to stop
                isRunning = false
                
                // Wait a bit for the loop to actually exit
                handler.postDelayed({
                    // Cleanup virtual display and codec, but keep mediaProjection
                    try {
                        virtualDisplay?.release()
                        virtualDisplay = null
                        codec?.stop()
                        codec?.release()
                        codec = null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during partial cleanup: ${e.message}")
                    }
                    
                    // Re-setup codec and virtual display
                    setupCodec(newW, newH)
                    val surface = codec?.createInputSurface()
                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "MirroringDisplay",
                        newW, newH, lastDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, handler
                    )
                    
                    codec?.start()
                    isRunning = true
                    
                    Thread {
                        drainAndSend()
                    }.start()
                    
                    Log.d(TAG, "Mirroring refreshed with new resolution: ${newW}x${newH}")
                }, 200)
            }
        }
    }

    private fun clearMirroring() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            codec?.stop()
            codec?.release()
            codec = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing mirroring: ${e.message}")
        }
    }

    private fun startMirroring(resultCode: Int, data: Intent, width: Int, height: Int, dpi: Int) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        setupCodec(width, height)
        
        val surface = codec?.createInputSurface()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MirroringDisplay",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, handler
        )

        codec?.start()
        MirroringService.isRunning = true
        sendBroadcast(Intent(ACTION_MIRROR_STATE_CHANGED).setPackage(packageName))
        
        Thread {
            drainAndSend()
        }.start()
    }

    private fun setupCodec(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 300000) // 300kbps for better BT compatibility
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 8) // Reduced from 10 to 8fps
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 2 seconds between I-frames

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        Log.d(TAG, "Codec configured: ${width}x${height}, 300kbps, 8fps")
    }

    private fun drainAndSend() {
        var frameCount = 0
        var totalBytes = 0L
        while (MirroringService.isRunning) {
            val outputBufferId = try { 
                codec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1 
            } catch (e: Exception) { 
                Log.e(TAG, "Error dequeuing output buffer: ${e.message}")
                -1 
            }

            if (outputBufferId >= 0) {
                val outputBuffer = codec?.getOutputBuffer(outputBufferId)
                if (outputBuffer != null) {
                    val bytes = ByteArray(bufferInfo.size)
                    outputBuffer.get(bytes)
                    
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    
                    frameCount++
                    totalBytes += encoded.length
                    
                    if (frameCount % 30 == 0) {
                        val avgSize = totalBytes / frameCount
                        Log.d(TAG, "Frame stats: count=$frameCount, avg_size=${avgSize}B, last_size=${encoded.length}B, keyframe=$isKeyFrame")
                    }
                    
                    if (encoded.length > 100000) {
                        Log.w(TAG, "Large frame detected: ${encoded.length}B (keyframe=$isKeyFrame)")
                    }
                    
                    // Send to BluetoothService via Broadcast
                    val intent = Intent("com.ailife.rtosify.SCREEN_DATA_AVAILABLE")
                    intent.setPackage(packageName) // Make it explicit for Android 14+
                    intent.putExtra("data", encoded)
                    intent.putExtra("isKeyFrame", isKeyFrame)
                    sendBroadcast(intent)
                }
                codec?.releaseOutputBuffer(outputBufferId, false)
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Output format changed")
            }
        }
        Log.d(TAG, "Encoding stopped. Total frames: $frameCount, avg size: ${if (frameCount > 0) totalBytes / frameCount else 0}B")
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.ailife.rtosify.STOP_MIRROR" -> {
                    Log.d(TAG, "Received STOP_MIRROR broadcast, stopping service")
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        unregisterReceiver(stopReceiver)
        try { unregisterReceiver(refreshReceiver) } catch(_: Exception) {}
        
        clearMirroring()
        
        MirroringService.isRunning = false
        val intent = Intent(ACTION_MIRROR_STATE_CHANGED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }
}
