package com.ailife.rtosifycompanion

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.updateLayoutParams
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.ComposeView
import com.ailife.rtosifycompanion.ui.MediaWaveSlider
import com.ailife.rtosifycompanion.ui.theme.SmartwatchTheme
import androidx.compose.material3.MaterialTheme
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.color.MaterialColors
import android.util.TypedValue
import androidx.compose.runtime.*
import com.google.gson.Gson
import java.io.File

/**
 * Full-screen media viewer for images, videos, and audio files.
 * - Images: Pinch-to-zoom with ZoomableImageView
 * - Videos: Download if needed, then play with VideoView
 * - Audio: Download if needed, then play with MediaPlayer and controls
 */
class FullScreenMediaActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FullScreenMediaActivity"

        const val EXTRA_DATA_JSON = "data_json"
        const val EXTRA_LOCAL_FILE_PATH = "local_file_path"
        const val EXTRA_BIG_PICTURE_BASE64 = "big_picture_base64"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
    }

    private var fileData: FileDetectedData? = null
    private var localFilePath: String? = null
    private var bigPictureBase64: String? = null
    private var notificationKey: String? = null

    // UI Components
    private lateinit var rootContainer: FrameLayout
    private lateinit var loadingContainer: LinearLayout
    private lateinit var loadingProgress: LinearProgressIndicator
    private lateinit var loadingText: TextView
    private lateinit var closeButton: ImageButton

    // Image viewer
    private var zoomableImageView: ZoomableImageView? = null

    // Video player
    private var videoView: VideoView? = null
    private var videoControlsContainer: LinearLayout? = null

    // Audio player
    private var audioPlayer: MediaPlayer? = null
    private var audioControlsContainer: LinearLayout? = null
    private var audioComposeSlider: ComposeView? = null
    private var sliderValue = mutableFloatStateOf(0f)
    private var isPlayingState = mutableStateOf(false)
    private var audioPlayPauseBtn: ImageButton? = null
    private var audioCurrentTime: TextView? = null
    private var audioDurationText: TextView? = null
    private val audioHandler = Handler(Looper.getMainLooper())

    // Theme colors
    private var uiBgColor: Int = Color.BLACK
    private var uiTextColor: Int = Color.WHITE
    private var uiTextColorSecondary: Int = Color.GRAY
    private var uiPrimaryColor: Int = Color.BLUE

    // Service connection
    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BluetoothService.LocalBinder
            bluetoothService = binder?.getService()
            isBound = true
            Log.d(TAG, "BluetoothService connected")

            // If we need to download, start now
            if (localFilePath == null && fileData != null) {
                requestFileDownload()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothService.ACTION_FILE_DOWNLOAD_PROGRESS -> {
                    val progress = intent.getIntExtra(BluetoothService.EXTRA_PROGRESS, 0)
                    updateDownloadProgress(progress)
                }
                BluetoothService.ACTION_FILE_DOWNLOAD_COMPLETE -> {
                    val path = intent.getStringExtra(BluetoothService.EXTRA_FILE_PATH)
                    val success = intent.getBooleanExtra(BluetoothService.EXTRA_SUCCESS, false)
                    if (success && path != null) {
                        onDownloadComplete(path)
                    } else {
                        onDownloadFailed()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Make full screen and immersive
        val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setupUI()
        parseIntent()

        // Register for download broadcasts
        val filter = IntentFilter(BluetoothService.ACTION_FILE_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Bind to BluetoothService
        Intent(this, BluetoothService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        showContent()
    }

    private fun setupUI() {
        uiBgColor = MaterialColors.getColor(this, android.R.attr.colorBackground, Color.BLACK)
        uiTextColor = MaterialColors.getColor(this, android.R.attr.textColorPrimary, Color.WHITE)
        uiTextColorSecondary = MaterialColors.getColor(this, android.R.attr.textColorSecondary, Color.GRAY)
        
        val primaryAttr = resources.getIdentifier("colorPrimary", "attr", packageName)
        uiPrimaryColor = if (primaryAttr != 0) MaterialColors.getColor(this, primaryAttr, Color.BLUE) else Color.BLUE
 
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(uiBgColor)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        // Loading container - centered with semi-transparent background
        loadingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            val surfaceAttr = resources.getIdentifier("colorSurface", "attr", packageName)
            val surfaceColor = if (surfaceAttr != 0) MaterialColors.getColor(this@FullScreenMediaActivity, surfaceAttr, uiBgColor) else uiBgColor
            setBackgroundColor(surfaceColor)
            alpha = 0.9f
            setPadding(dpToPx(32), dpToPx(32), dpToPx(32), dpToPx(32))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Download icon
        val downloadIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.stat_sys_download)
            setColorFilter(uiTextColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(64), dpToPx(64)).apply {
                bottomMargin = dpToPx(24)
            }
        }
        loadingContainer.addView(downloadIcon)

        // Loading text - larger and bold
        loadingText = TextView(this).apply {
            text = getString(R.string.fullscreen_downloading)
            setTextColor(uiTextColor)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }
        loadingContainer.addView(loadingText)

        // Progress bar - wider and more visible
        loadingProgress = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(280), dpToPx(12)).apply {
                topMargin = dpToPx(20)
                bottomMargin = dpToPx(8)
            }
            max = 100
            progress = 0
            setIndicatorColor(uiPrimaryColor)
            val trackAttr = resources.getIdentifier("colorSurfaceVariant", "attr", packageName)
            trackColor = if (trackAttr != 0) MaterialColors.getColor(this@FullScreenMediaActivity, trackAttr, Color.DKGRAY) else Color.DKGRAY
        }
        loadingContainer.addView(loadingProgress)

        // Percentage text
        val percentText = TextView(this).apply {
            tag = "percent_text"
            text = "0%"
            setTextColor(uiTextColorSecondary)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        loadingContainer.addView(percentText)

        rootContainer.addView(loadingContainer)

        // Close button
        closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            val containerAttr = resources.getIdentifier("colorSurfaceContainerHigh", "attr", packageName)
            val btnBg = if (containerAttr != 0) MaterialColors.getColor(this@FullScreenMediaActivity, containerAttr, uiBgColor) else uiBgColor
            setBackgroundColor(btnBg)
            alpha = 0.8f
            setColorFilter(uiTextColor)
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = dpToPx(16)
                marginEnd = dpToPx(16)
            }
            setOnClickListener { finish() }
        }
        rootContainer.addView(closeButton)
        
        // Handle insets for close button
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(closeButton) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = dpToPx(16) + systemBars.top
                marginEnd = dpToPx(16) + systemBars.right
            }
            insets
        }
    }

    private fun parseIntent() {
        intent.getStringExtra(EXTRA_DATA_JSON)?.let { json ->
            try {
                fileData = Gson().fromJson(json, FileDetectedData::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing file data: ${e.message}")
            }
        }
        localFilePath = intent.getStringExtra(EXTRA_LOCAL_FILE_PATH)
        bigPictureBase64 = intent.getStringExtra(EXTRA_BIG_PICTURE_BASE64)
        notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY)
    }

    private fun showContent() {
        val type = fileData?.type ?: detectTypeFromPath()

        when (type) {
            "image" -> showImage()
            "video" -> showVideo()
            "audio" -> showAudio()
            else -> {
                // For other types, just show info
                showFileInfo()
            }
        }
    }

    private fun detectTypeFromPath(): String {
        // If we have a big picture, it's an image, regardless of path
        if (bigPictureBase64 != null) return "image"

        val path = localFilePath ?: fileData?.path ?: return "other"
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> "image"
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm" -> "video"
            "mp3", "wav", "aac", "ogg", "m4a", "flac", "amr", "opus", "oga", "wma" -> "audio"
            else -> "other"
        }

    }

    private fun showImage() {
        // Try local file first, then base64, then thumbnail from fileData
        val bitmap = when {
            localFilePath != null && File(localFilePath!!).exists() -> {
                BitmapFactory.decodeFile(localFilePath)
            }
            bigPictureBase64 != null -> {
                decodeBase64ToBitmap(bigPictureBase64!!)
            }
            fileData?.thumbnail != null -> {
                decodeBase64ToBitmap(fileData!!.thumbnail!!)
            }
            else -> null
        }

        if (bitmap != null) {
            zoomableImageView = ZoomableImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setImageBitmap(bitmap)
            }
            rootContainer.addView(zoomableImageView, 0)
        } else {
            // Show placeholder and try to download
            showPlaceholderAndDownload()
        }
    }

    private fun showVideo() {
        if (localFilePath != null && File(localFilePath!!).exists()) {
            setupVideoPlayer(localFilePath!!)
        } else if (fileData != null) {
            // Show thumbnail while downloading
            fileData?.thumbnail?.let { thumb ->
                decodeBase64ToBitmap(thumb)?.let { bitmap ->
                    val thumbnailView = ImageView(this).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(bitmap)
                    }
                    rootContainer.addView(thumbnailView, 0)
                }
            }
            showLoadingAndDownload()
        } else {
            showFileInfo()
        }
    }

    private fun showAudio() {
        // Show album art or file icon
        val artBitmap = fileData?.thumbnail?.let { decodeBase64ToBitmap(it) }
            ?: fileData?.largeIcon?.let { decodeBase64ToBitmap(it) }

        val artView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(200),
                dpToPx(200)
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(80)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (artBitmap != null) {
                setImageBitmap(artBitmap)
            } else {
                setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                setColorFilter(uiTextColor)
            }
        }
        rootContainer.addView(artView, 0)

        // File name
        val nameText = TextView(this).apply {
            text = fileData?.name ?: "Audio"
            setTextColor(uiTextColor)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(300)
                marginStart = dpToPx(16)
                marginEnd = dpToPx(16)
            }
        }
        rootContainer.addView(nameText)

        if (localFilePath != null && File(localFilePath!!).exists()) {
            setupAudioPlayer(localFilePath!!)
        } else if (fileData != null) {
            showLoadingAndDownload()
        }
    }

    private fun showPlaceholderAndDownload() {
        // Try to show low-res placeholder if available
        val placeholderBitmap = fileData?.thumbnail?.let { decodeBase64ToBitmap(it) }
        
        if (placeholderBitmap != null) {
            // Use ZoomableImageView for placeholder too so it's seamless
            zoomableImageView = ZoomableImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setImageBitmap(placeholderBitmap)
            }
            rootContainer.addView(zoomableImageView, 0)
        } else {
            val placeholder = ImageView(this).apply {
                tag = "placeholder"
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(android.R.drawable.ic_menu_gallery)
                setColorFilter(uiTextColorSecondary)
            }
            rootContainer.addView(placeholder, 0)
        }

        if (fileData != null) {
            showLoadingAndDownload()
        }
    }

    private fun showLoadingAndDownload() {
        loadingContainer.visibility = View.VISIBLE
        loadingText.text = getString(R.string.fullscreen_downloading)
        loadingProgress.progress = 0

        // Download will start when service connects
    }

    private fun requestFileDownload() {
        val data = fileData ?: return
        Log.d(TAG, "Requesting file download: ${data.path}")

        val msg = ProtocolHelper.createRequestFileDownload(data.path)
        if (data.type == "video") {
            msg.data.addProperty("prepare_video", true)
        }
        bluetoothService?.sendMessage(msg)
    }

    private fun updateDownloadProgress(progress: Int) {
        loadingProgress.progress = progress
        loadingText.text = getString(R.string.fullscreen_downloading)
        loadingContainer.findViewWithTag<TextView>("percent_text")?.text = "$progress%"
    }

    private fun onDownloadComplete(path: String) {
        localFilePath = path
        loadingContainer.visibility = View.GONE

        val type = fileData?.type ?: detectTypeFromPath()
        when (type) {
            "image" -> {
                // Check for existing zoomable view (loaded from low-res)
                if (zoomableImageView != null) {
                    val bitmap = BitmapFactory.decodeFile(path)
                    if (bitmap != null) {
                        Log.d(TAG, "Replacing low-res image with high-res from $path")
                        zoomableImageView?.setImageBitmap(bitmap)
                    }
                } else {
                    // Replace placeholder with actual image
                    rootContainer.findViewWithTag<View>("placeholder")?.let {
                        rootContainer.removeView(it)
                    }
                    val bitmap = BitmapFactory.decodeFile(path)
                    if (bitmap != null) {
                        zoomableImageView = ZoomableImageView(this).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                            setImageBitmap(bitmap)
                        }
                        rootContainer.addView(zoomableImageView, 0)
                    }
                }
            }
            "video" -> setupVideoPlayer(path)
            "audio" -> setupAudioPlayer(path)
        }
    }

    private fun onDownloadFailed() {
        loadingText.text = getString(R.string.fullscreen_download_failed)
        loadingProgress.visibility = View.GONE
    }

    private fun setupVideoPlayer(path: String) {
        // Remove any thumbnail
        for (i in rootContainer.childCount - 1 downTo 0) {
            val child = rootContainer.getChildAt(i)
            if (child is ImageView && child != closeButton) {
                rootContainer.removeView(child)
            }
        }

        videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setVideoPath(path)
            setOnPreparedListener { mp ->
                mp.isLooping = false
                start()
            }
            setOnCompletionListener {
                seekTo(0)
            }

            // Add media controller
            val controller = android.widget.MediaController(this@FullScreenMediaActivity)
            controller.setAnchorView(this)
            this.setMediaController(controller)
        }
        rootContainer.addView(videoView, 0)
    }

    private fun setupAudioPlayer(path: String) {
        audioPlayer = MediaPlayer().apply {
            setDataSource(this@FullScreenMediaActivity, Uri.parse(path))
            setOnPreparedListener { mp ->
                setupAudioControls(mp.duration)
                mp.start()
                startAudioProgressUpdater()
            }
            setOnCompletionListener {
                isPlayingState.value = false
                sliderValue.floatValue = 0f
                audioCurrentTime?.text = formatDuration(0)
            }
            prepareAsync()
        }
    }

    private fun setupAudioControls(durationMs: Int) {
        audioControlsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                bottomMargin = dpToPx(60)
                marginStart = dpToPx(24)
                marginEnd = dpToPx(24)
            }
        }

        // Wavy Slider (Compose)
        audioComposeSlider = ComposeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            ).apply {
                bottomMargin = dpToPx(8)
            }
            setContent {
                SmartwatchTheme {
                    MediaWaveSlider(
                        value = sliderValue.floatValue,
                        onValueChange = { newValue ->
                            sliderValue.floatValue = newValue
                            audioCurrentTime?.text = formatDuration(newValue.toLong())
                        },
                        isPlaying = isPlayingState.value,
                        valueRange = 0f..durationMs.toFloat(),
                        onValueChangeFinished = {
                            audioPlayer?.seekTo(sliderValue.floatValue.toInt())
                        }
                    )
                }
            }
        }
        audioControlsContainer!!.addView(audioComposeSlider)

        // Time display
        val timeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
        }

        audioCurrentTime = TextView(this).apply {
            text = "0:00"
            setTextColor(uiTextColor)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeContainer.addView(audioCurrentTime)

        audioDurationText = TextView(this).apply {
            text = formatDuration(durationMs.toLong())
            setTextColor(uiTextColor)
            textSize = 12f
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeContainer.addView(audioDurationText)

        audioControlsContainer!!.addView(timeContainer)

        // Control buttons
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Rewind button
        val rewindBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(uiTextColor)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            setOnClickListener {
                audioPlayer?.let { player ->
                    val newPos = maxOf(0, player.currentPosition - 10000)
                    player.seekTo(newPos)
                }
            }
        }
        controlsRow.addView(rewindBtn)

        // Play/Pause button
        audioPlayPauseBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(uiTextColor)
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16))
            setOnClickListener {
                audioPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                        setImageResource(android.R.drawable.ic_media_play)
                        isPlayingState.value = false
                    } else {
                        player.start()
                        setImageResource(android.R.drawable.ic_media_pause)
                        isPlayingState.value = true
                        startAudioProgressUpdater()
                    }
                }
            }
        }
        isPlayingState.value = true // Initial state when prepared
        controlsRow.addView(audioPlayPauseBtn)

        // Forward button
        val forwardBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(uiTextColor)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            setOnClickListener {
                audioPlayer?.let { player ->
                    val newPos = minOf(player.duration, player.currentPosition + 10000)
                    player.seekTo(newPos)
                }
            }
        }
        controlsRow.addView(forwardBtn)

        audioControlsContainer!!.addView(controlsRow)
        rootContainer.addView(audioControlsContainer)
    }

    private fun startAudioProgressUpdater() {
        audioHandler.post(object : Runnable {
            override fun run() {
                try {
                    audioPlayer?.let { player ->
                        if (player.isPlaying) {
                            val pos = player.currentPosition
                            sliderValue.floatValue = pos.toFloat()
                            audioCurrentTime?.text = formatDuration(pos.toLong())
                            audioHandler.postDelayed(this, 500)
                        } else {
                            isPlayingState.value = false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating audio progress", e)
                }
            }
        })
    }

    private fun showFileInfo() {
        val infoText = TextView(this).apply {
            text = fileData?.let { data ->
                "${data.name}\n${android.text.format.Formatter.formatShortFileSize(this@FullScreenMediaActivity, data.size)}"
            } ?: "File"
            setTextColor(uiTextColor)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        rootContainer.addView(infoText, 0)
    }

    private fun decodeBase64ToBitmap(base64Str: String): android.graphics.Bitmap? {
        return try {
            val bytes = Base64.decode(base64Str, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64: ${e.message}")
            null
        }
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioHandler.removeCallbacksAndMessages(null)

        try {
            audioPlayer?.stop()
            audioPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio player", e)
        }
        audioPlayer = null

        try {
            videoView?.stopPlayback()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video", e)
        }
        videoView = null

        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
