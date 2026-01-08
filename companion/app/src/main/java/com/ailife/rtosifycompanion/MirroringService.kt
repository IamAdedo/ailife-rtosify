package com.ailife.rtosifycompanion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val width = intent?.getIntExtra(EXTRA_WIDTH, 480) ?: 480
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 854) ?: 854
        val dpi = intent?.getIntExtra(EXTRA_DPI, 240) ?: 240

        if (resultCode != 0 && data != null) {
            startMirroring(resultCode, data, width, height, dpi)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startMirroring(resultCode: Int, data: Intent, width: Int, height: Int, dpi: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirroring")
            .setContentText("Mirroring screen to phone...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        setupCodec(width, height)
        
        val surface = codec?.createInputSurface()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MirroringDisplay",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        codec?.start()
        isRunning = true
        
        Thread {
            drainAndSend()
        }.start()
    }

    private fun setupCodec(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 300000) // Lower bitrate for watch
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 5) // Lower FPS for watch
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun drainAndSend() {
        while (isRunning) {
            val outputBufferId = try { 
                codec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1 
            } catch (e: Exception) { -1 }

            if (outputBufferId >= 0) {
                val outputBuffer = codec?.getOutputBuffer(outputBufferId)
                if (outputBuffer != null) {
                    val bytes = ByteArray(bufferInfo.size)
                    outputBuffer.get(bytes)
                    
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    
                    // Send to BluetoothService via Broadcast
                    val intent = Intent("com.ailife.rtosifycompanion.SCREEN_DATA_AVAILABLE")
                    intent.putExtra("data", encoded)
                    intent.putExtra("isKeyFrame", isKeyFrame)
                    sendBroadcast(intent)
                }
                codec?.releaseOutputBuffer(outputBufferId, false)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        virtualDisplay?.release()
        mediaProjection?.stop()
        codec?.stop()
        codec?.release()
        super.onDestroy()
    }
}
