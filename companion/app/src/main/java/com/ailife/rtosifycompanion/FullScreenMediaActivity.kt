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
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
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
    private lateinit var loadingProgress: ProgressBar
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
    private var audioSeekBar: SeekBar? = null
    private var audioPlayPauseBtn: ImageButton? = null
    private var audioCurrentTime: TextView? = null
    private var audioDurationText: TextView? = null
    private val audioHandler = Handler(Looper.getMainLooper())

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
        super.onCreate(savedInstanceState)

        // Make full screen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setupUI()
        parseIntent()

        // Register for download broadcasts
        val filter = IntentFilter().apply {
            addAction(BluetoothService.ACTION_FILE_DOWNLOAD_PROGRESS)
            addAction(BluetoothService.ACTION_FILE_DOWNLOAD_COMPLETE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
        }

        // Bind to BluetoothService
        Intent(this, BluetoothService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        showContent()
    }

    private fun setupUI() {
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
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
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dpToPx(32), dpToPx(32), dpToPx(32), dpToPx(32))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Download icon
        val downloadIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.stat_sys_download)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(64), dpToPx(64)).apply {
                bottomMargin = dpToPx(24)
            }
        }
        loadingContainer.addView(downloadIcon)

        // Loading text - larger and bold
        loadingText = TextView(this).apply {
            text = getString(R.string.fullscreen_downloading)
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }
        loadingContainer.addView(loadingText)

        // Progress bar - wider and more visible
        loadingProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(280), dpToPx(12)).apply {
                topMargin = dpToPx(20)
                bottomMargin = dpToPx(8)
            }
            max = 100
            progress = 0
            progressDrawable.setColorFilter(Color.parseColor("#4CAF50"), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        loadingContainer.addView(loadingProgress)

        // Percentage text
        val percentText = TextView(this).apply {
            tag = "percent_text"
            text = "0%"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        loadingContainer.addView(percentText)

        rootContainer.addView(loadingContainer)

        // Close button
        closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.parseColor("#80000000"))
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
                setColorFilter(Color.WHITE)
            }
        }
        rootContainer.addView(artView, 0)

        // File name
        val nameText = TextView(this).apply {
            text = fileData?.name ?: "Audio"
            setTextColor(Color.WHITE)
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
                setColorFilter(Color.GRAY)
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
            setMediaController(controller)
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
                audioPlayPauseBtn?.setImageResource(android.R.drawable.ic_media_play)
                audioSeekBar?.progress = 0
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

        // SeekBar
        audioSeekBar = SeekBar(this).apply {
            max = durationMs
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        audioPlayer?.seekTo(progress)
                        audioCurrentTime?.text = formatDuration(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        audioControlsContainer!!.addView(audioSeekBar)

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
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeContainer.addView(audioCurrentTime)

        audioDurationText = TextView(this).apply {
            text = formatDuration(durationMs.toLong())
            setTextColor(Color.WHITE)
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
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16))
            setOnClickListener {
                audioPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                        setImageResource(android.R.drawable.ic_media_play)
                    } else {
                        player.start()
                        setImageResource(android.R.drawable.ic_media_pause)
                        startAudioProgressUpdater()
                    }
                }
            }
        }
        controlsRow.addView(audioPlayPauseBtn)

        // Forward button
        val forwardBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            setBackgroundColor(Color.TRANSPARENT)
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
                            audioSeekBar?.progress = pos
                            audioCurrentTime?.text = formatDuration(pos.toLong())
                            audioHandler.postDelayed(this, 500)
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
            setTextColor(Color.WHITE)
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
