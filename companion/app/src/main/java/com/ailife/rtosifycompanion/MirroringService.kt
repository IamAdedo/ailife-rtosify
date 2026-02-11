package com.ailife.rtosifycompanion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Build
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
        const val EXTRA_HIGH_QUALITY = "high_quality"
        const val ACTION_MIRROR_STATE_CHANGED = "com.ailife.rtosifycompanion.MIRROR_STATE_CHANGED"
        
        var isRunning = false
            private set

        private var lastResultCode: Int = 0
        private var lastData: Intent? = null
        private var lastDpi: Int = 240
        private var lastHighQuality: Boolean = false
        
        // Direct callback to avoid Broadcast overhead
        @Volatile var frameCallback: ((String, Boolean) -> Unit)? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var highQualityMode = false

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
            getString(R.string.mirror_notif_title),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ requires calling startForeground() almost immediately
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mirror_notif_title))
            .setContentText(getString(R.string.mirror_notif_text))
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
        val highQuality = intent?.getBooleanExtra(EXTRA_HIGH_QUALITY, false) ?: false

        // Register receivers for stop and refresh commands
        androidx.core.content.ContextCompat.registerReceiver(this, stopReceiver, IntentFilter("com.ailife.rtosifycompanion.STOP_MIRROR"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(this, refreshReceiver, IntentFilter("com.ailife.rtosifycompanion.REFRESH_MIRROR"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

        if (resultCode != 0 && data != null) {
            lastResultCode = resultCode
            lastData = data
            lastDpi = dpi
            lastHighQuality = highQuality
            highQualityMode = highQuality
            startMirroring(resultCode, data, width, height, dpi)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received REFRESH_MIRROR, re-initializing with current screen size")
            if (lastResultCode != 0 && lastData != null) {
                val metrics = resources.displayMetrics
                val newW = metrics.widthPixels
                val newH = metrics.heightPixels

                // Check if HQ mode should be updated
                highQualityMode = lastHighQuality

                Log.d(TAG, "Re-starting mirroring with new resolution: ${newW}x${newH}, HQ=$highQualityMode")

                // Signal current loop to stop
                isRunning = false

                // Wait a bit for the loop to actually exit
                handler.postDelayed({
                    // Cleanup virtual display and codec, but keep mediaProjection
                    try {
                        virtualDisplay?.release()
                        virtualDisplay = null
                        
                        val currentCodec = codec
                        codec = null
                        currentCodec?.stop()
                        currentCodec?.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during partial cleanup: ${e.message}")
                    }

                    // Re-setup codec and virtual display
                    setupCodec(newW, newH)
                    val currentCodec = codec
                    if (currentCodec == null) {
                        Log.e(TAG, "Failed to setup codec during refresh")
                        return@postDelayed
                    }
                    val surface = currentCodec.createInputSurface()
                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "MirroringDisplay",
                        newW, newH, lastDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, handler
                    )

                    currentCodec.start()
                    isRunning = true // Set static isRunning to true
                    Thread {
                        drainAndSend()
                    }.start()

                    Log.d(TAG, "Mirroring refreshed with new resolution: ${newW}x${newH}")
                }, 300)
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
        
        Log.d(TAG, "Starting encoding thread, codec started")
        Thread {
            Log.d(TAG, "Encoding thread started, entering drainAndSend loop")
            drainAndSend()
            Log.d(TAG, "Encoding thread finished")
        }.start()
    }

    private fun setupCodec(width: Int, height: Int) {
        // Dynamic scaling: Target ~360px on the shortest side for watch performance (or higher for HQ)
        val targetShortSide = if (highQualityMode) 480 else 360
        val isPortrait = height > width
        val shortSide = if (isPortrait) width else height

        var scaledWidth = width
        var scaledHeight = height

        if (shortSide > targetShortSide) {
            val scale = targetShortSide.toFloat() / shortSide.toFloat()
            scaledWidth = (width * scale).toInt()
            scaledHeight = (height * scale).toInt()
        }

        // Ensure even dimensions (required for some encoders)
        if (scaledWidth % 2 != 0) scaledWidth--
        if (scaledHeight % 2 != 0) scaledHeight--

        Log.d(TAG, "Setting up codec: Input=${width}x${height}, Scaled=${scaledWidth}x${scaledHeight}, HQ=$highQualityMode")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, scaledWidth, scaledHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        // Use higher quality settings when on LAN with HQ mode enabled
        val bitrate = if (highQualityMode) 1000000 else 250000 // 1Mbps vs 250kbps
        val frameRate = if (highQualityMode) 20 else 10 // 20fps vs 10fps

        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 2 seconds between I-frames
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Log.d(TAG, "Codec configured: ${scaledWidth}x${scaledHeight}, ${bitrate/1000}kbps, ${frameRate}fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure codec: ${e.message}")
            // Fallback to safe default if scaling fails
            fallbackSetupCodec()
        }
    }

    private fun fallbackSetupCodec() {
        Log.w(TAG, "Using fallback safe resolution (320x568)")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 568)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 200000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 5)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun drainAndSend() {
        val currentCodec = codec ?: return
        Log.d(TAG, "drainAndSend: Starting encoding loop, isRunning=$isRunning")
        var frameCount = 0
        try {
            while (isRunning) {
                val outputBufferId = try { 
                    currentCodec.dequeueOutputBuffer(bufferInfo, 10000) 
                } catch (e: Exception) { 
                    Log.e(TAG, "Error dequeuing buffer: ${e.message}")
                    -1 
                }

                if (outputBufferId >= 0) {
                    val outputBuffer = currentCodec.getOutputBuffer(outputBufferId)
                    if (outputBuffer != null) {
                        val bytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(bytes)
                        
                        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        
                        frameCount++
                        if (frameCount % 30 == 0 || isKeyFrame) {
                            Log.d(TAG, "Frame #$frameCount: size=${encoded.length}B, keyframe=$isKeyFrame")
                        }
                        
                        // Optimization: Use direct callback if available to avoid Broadcast overhead (lag in background)
                        val callback = frameCallback
                        if (callback != null) {
                            callback(encoded, isKeyFrame)
                        } else {
                            // Fallback: Send to BluetoothService via Broadcast
                            val intent = Intent("com.ailife.rtosifycompanion.SCREEN_DATA_AVAILABLE")
                            intent.setPackage(packageName) // Make it explicit for Android 14+
                            intent.putExtra("data", encoded)
                            intent.putExtra("isKeyFrame", isKeyFrame)
                            sendBroadcast(intent)
                        }
                    }
                    try {
                        currentCodec.releaseOutputBuffer(outputBufferId, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing output buffer: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in drainAndSend loop: ${e.message}")
        } finally {
            Log.d(TAG, "drainAndSend: Encoding loop finished, total frames=$frameCount")
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.ailife.rtosifycompanion.STOP_MIRROR" -> {
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
