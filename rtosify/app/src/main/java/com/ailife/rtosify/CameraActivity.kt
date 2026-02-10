package com.ailife.rtosify

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.View
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.camera.video.*
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var btnShutter: MaterialButton
    private lateinit var btnRecord: MaterialButton
    private lateinit var tvTimer: TextView

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var recordStartTime = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - recordStartTime
            val seconds = (elapsed / 1000) % 60
            val minutes = (elapsed / (1000 * 60)) % 60
            tvTimer.text = String.format("%02d:%02d", minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    private val shutterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TAKE_PICTURE -> takePhoto()
                ACTION_START_VIDEO -> startVideo()
                ACTION_STOP_VIDEO -> stopVideo()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        val rootLayout = findViewById<View>(R.id.rootLayout)
        EdgeToEdgeUtils.applyEdgeToEdge(this, rootLayout)
        viewFinder = findViewById(R.id.viewFinder)
        btnShutter = findViewById(R.id.btn_shutter)
        btnRecord = findViewById(R.id.btn_record)
        tvTimer = findViewById(R.id.tv_timer)

        btnShutter.setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener {
            if (recording == null) {
                startVideo()
            } else {
                stopVideo()
            }
        }

        // Bind to BluetoothService
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        startCamera()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ACTION_TAKE_PICTURE)
            addAction(ACTION_START_VIDEO)
            addAction(ACTION_STOP_VIDEO)
        }
        ContextCompat.registerReceiver(this, shutterReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(shutterReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Check if high quality mode should be enabled (LAN connected + HQ setting enabled)
            val isLanConnected = bluetoothService?.isWifiConnected() == true
            val devicePrefManager = DevicePrefManager(this)
            val hqEnabled = devicePrefManager.getActiveDevicePrefs().getBoolean("hq_lan_enabled", false)
            val useHighQuality = isLanConnected && hqEnabled

            // Analyzer for streaming frames
            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FrameAnalyzer({ base64 ->
                         if (isBound && bluetoothService != null) {
                             bluetoothService?.sendCameraFrame(base64)
                         }
                    }, useHighQuality))
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startVideo() {
        val videoCapture = videoCapture ?: return

        btnRecord.isEnabled = false

        val name = "RTOSify-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RTOSify")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        btnRecord.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                        btnRecord.isEnabled = true
                        tvTimer.visibility = View.VISIBLE
                        recordStartTime = System.currentTimeMillis()
                        timerHandler.post(timerRunnable)
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        Toast.makeText(this, getString(R.string.camera_record_started), Toast.LENGTH_SHORT).show()
                        
                        if (isBound && bluetoothService != null) {
                            bluetoothService?.sendMessage(ProtocolHelper.createCameraRecordingStatus(true))
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        btnRecord.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF0000.toInt())
                        btnRecord.isEnabled = true
                        tvTimer.visibility = View.GONE
                        timerHandler.removeCallbacks(timerRunnable)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                        if (isBound && bluetoothService != null) {
                            bluetoothService?.sendMessage(ProtocolHelper.createCameraRecordingStatus(false))
                        }

                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    private fun stopVideo() {
        val recording = recording
        if (recording != null) {
            recording.stop()
            this.recording = null
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = "RTOSify-${System.currentTimeMillis()}.jpg"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RTOSify")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    Toast.makeText(baseContext, getString(R.string.toast_photo_capture_failed), Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    private class FrameAnalyzer(
        private val listener: (String) -> Unit,
        private val highQualityMode: Boolean = false
    ) : ImageAnalysis.Analyzer {

        private var lastFrameTime = 0L
        // High quality: 15 FPS (67ms interval), Normal: 5 FPS (200ms interval)
        private val FRAME_INTERVAL = if (highQualityMode) 67L else 200L

        override fun analyze(image: ImageProxy) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < FRAME_INTERVAL) {
                image.close()
                return
            }
            lastFrameTime = currentTime

            // Use RGBA_8888 directly
            val bitmap = imageProxyToBitmap(image)
            image.close()

            if (bitmap != null) {
                // High quality: 400x400 @ 60%, Normal: 200x200 @ 40%
                val size = if (highQualityMode) 400 else 200
                val quality = if (highQualityMode) 60 else 40
                val resized = Bitmap.createScaledBitmap(bitmap, size, size, false)
                val baos = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                listener(base64)
            }
        }

        private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
            // Since we requested RGBA_8888, planes[0] has the pixel data
            val buffer = image.planes[0].buffer
            val pixelStride = image.planes[0].pixelStride
            val rowStride = image.planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // Create bitmap
            val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop if padding exists (unlikely to affect small previews much but correct)
            // Or simpler: just create bitmap matching dimensions if padding is 0.
            // But copyPixelsFromBuffer expects buffer size to match.

            // Rotate if needed
            var finalBitmap = bitmap
            if (image.imageInfo.rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
                finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            return finalBitmap
        }
    }

    companion object {
        private const val TAG = "CameraActivity"
        const val ACTION_TAKE_PICTURE = "com.ailife.rtosify.ACTION_TAKE_PICTURE"
        const val ACTION_START_VIDEO = "com.ailife.rtosify.ACTION_START_VIDEO"
        const val ACTION_STOP_VIDEO = "com.ailife.rtosify.ACTION_STOP_VIDEO"
    }
}
