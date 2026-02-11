package com.ailife.rtosifycompanion

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.view.View
import android.view.animation.ScaleAnimation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.ailife.rtosifycompanion.ui.MediaWaveSlider
import androidx.compose.material3.MaterialTheme
import com.ailife.rtosifycompanion.EdgeToEdgeUtils

class MediaControlActivity : AppCompatActivity() {

    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var vibrator: Vibrator? = null
    
    private lateinit var btnPlayPause: ImageButton
    private lateinit var albumArt: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var composeSlider: ComposeView
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var volumeLevel: TextView
    
    private var sliderValue = mutableStateOf(0f)
    private var isMediaPlaying = mutableStateOf(false)
    
    private var currentMediaState: MediaStateData? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var isProgressRunning = false
    private var localPosition = 0L
    private var localDuration = 0L
    private var isUserSeeking = false

    private val mediaStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MEDIA_STATE_UPDATE) {
                val isPlaying = intent.getBooleanExtra("isPlaying", false)
                val title = intent.getStringExtra("title")
                val artist = intent.getStringExtra("artist")
                val duration = intent.getLongExtra("duration", 0L)
                val position = intent.getLongExtra("position", 0L)
                val volume = intent.getIntExtra("volume", 0)
                val albumArtBase64 = intent.getStringExtra("albumArtBase64")
                
                val mediaState = MediaStateData(
                    isPlaying = isPlaying,
                    title = title,
                    artist = artist,
                    album = null,
                    duration = duration,
                    position = position,
                    volume = volume,
                    albumArtBase64 = albumArtBase64
                )
                
                updateMediaState(mediaState)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
            
            // Request initial media state
            requestMediaState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_control)
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val container = findViewById<View>(R.id.container)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, container)
        
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        btnPlayPause = findViewById(R.id.btn_play_pause)
        albumArt = findViewById(R.id.albumArt)
        trackTitle = findViewById(R.id.trackTitle)
        trackArtist = findViewById(R.id.trackArtist)
        composeSlider = findViewById(R.id.composeSlider)
        currentTime = findViewById(R.id.currentTime)
        totalTime = findViewById(R.id.totalTime)
        volumeLevel = findViewById(R.id.volumeLevel)

        setupComposeSlider()

        // Bind to BluetoothService
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        // Register media state receiver
        val filter = IntentFilter(ACTION_MEDIA_STATE_UPDATE)
        ContextCompat.registerReceiver(this, mediaStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        setupListeners()
        
        // Add fade-in animation
        val rootLayout = findViewById<View>(R.id.rootLayout)
        rootLayout.alpha = 0f
        rootLayout.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
            
        // Enable marquee for title
        trackTitle.isSelected = true
    }

    private fun setupListeners() {
        btnPlayPause.setOnClickListener {
            animateButton(it)
            sendCommand(MediaControlData.CMD_PLAY_PAUSE)
        }

        findViewById<ImageButton>(R.id.btn_next).setOnClickListener {
            animateButton(it)
            sendCommand(MediaControlData.CMD_NEXT)
        }

        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener {
            animateButton(it)
            sendCommand(MediaControlData.CMD_PREVIOUS)
        }

        findViewById<ImageButton>(R.id.btn_vol_up).setOnClickListener {
            animateButton(it)
            sendCommand(MediaControlData.CMD_VOL_UP)
        }

        findViewById<ImageButton>(R.id.btn_vol_down).setOnClickListener {
            animateButton(it)
            sendCommand(MediaControlData.CMD_VOL_DOWN)
        }
    }

    private fun animateButton(view: View) {
        // Haptic feedback
        if (vibrator?.hasVibrator() == true) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30)
            }
        }
        
        // Scale animation
        val scaleDown = ScaleAnimation(
            1f, 0.9f,
            1f, 0.9f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        )
        scaleDown.duration = 100
        scaleDown.fillAfter = false
        
        view.startAnimation(scaleDown)
        
        view.postDelayed({
            val scaleUp = ScaleAnimation(
                0.9f, 1f,
                0.9f, 1f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            )
            scaleUp.duration = 100
            scaleUp.fillAfter = false
            view.startAnimation(scaleUp)
        }, 100)
    }
    
    private fun requestMediaState() {
        if (isBound && bluetoothService?.isConnected == true) {
            val message = ProtocolMessage(type = MessageType.REQUEST_MEDIA_STATE)
            bluetoothService?.sendMessage(message)
        }
    }
    
    private fun updateMediaState(state: MediaStateData) {
        currentMediaState = state
        
        // Update play/pause button icon
        btnPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
        )
        isMediaPlaying.value = state.isPlaying
        
        // Update track info
        trackTitle.text = state.title ?: getString(R.string.media_no_media)
        trackArtist.text = state.artist ?: getString(R.string.media_unknown_artist)
        
        // Update progress with received state
        localPosition = state.position
        localDuration = state.duration
        updateProgressUI()
        
        // Start/stop progress animation based on playback state
        if (state.isPlaying && state.duration > 0) {
            startProgressUpdates()
        } else {
            stopProgressUpdates()
        }
        
        // Update volume
        volumeLevel.text = state.volume.toString()
        
        // Update album art
        if (state.albumArtBase64 != null) {
            try {
                val decodedBytes = Base64.decode(state.albumArtBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                albumArt.setImageBitmap(bitmap)
            } catch (e: Exception) {
                albumArt.setImageResource(0)
                albumArt.setBackgroundResource(R.drawable.bg_album_art_placeholder)
            }
        } else {
            albumArt.setImageResource(0)
            albumArt.setBackgroundResource(R.drawable.bg_album_art_placeholder)
        }
    }
    
    private fun startProgressUpdates() {
        if (isProgressRunning) return
        isProgressRunning = true
        progressHandler.post(progressUpdateRunnable)
    }
    
    private fun stopProgressUpdates() {
        isProgressRunning = false
        progressHandler.removeCallbacks(progressUpdateRunnable)
    }
    
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            if (isProgressRunning && localDuration > 0) {
                // Increment position by 1 second
                localPosition += 1000
                
                // Don't exceed duration
                if (localPosition > localDuration) {
                    localPosition = localDuration
                    stopProgressUpdates()
                }
                
                updateProgressUI()
                
                // Schedule next update in 1 second
                progressHandler.postDelayed(this, 1000)
            }
        }
    }
    
    private fun updateProgressUI() {
        if (localDuration > 0) {
            val progress = ((localPosition.toFloat() / localDuration.toFloat()) * 100)
            if (!isUserSeeking) {
                sliderValue.value = progress
                currentTime.text = formatTime(localPosition)
            }
            totalTime.text = formatTime(localDuration)
        } else {
            if (!isUserSeeking) {
                sliderValue.value = 0f
                currentTime.text = getString(R.string.media_time_placeholder)
            }
            totalTime.text = getString(R.string.media_time_placeholder)
        }
    }

    private fun setupComposeSlider() {
        composeSlider.setContent {
            MaterialTheme {
                MediaWaveSlider(
                    value = sliderValue.value,
                    onValueChange = { newValue ->
                        isUserSeeking = true
                        sliderValue.value = newValue
                        // Update current time locally while seeking
                        currentTime.text = formatTime((newValue / 100 * localDuration).toLong())
                    },
                    isPlaying = isMediaPlaying.value,
                    onValueChangeFinished = {
                        isUserSeeking = false
                        // Handle seek
                        val newPos = (sliderValue.value / 100 * localDuration).toLong()
                        bluetoothService?.sendMediaCommand(MediaControlData.CMD_SEEK, seekPosition = newPos)
                    }
                )
            }
        }
    }
    
    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000).toInt()
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", minutes, secs)
    }

    private fun sendCommand(command: String) {
        if (isBound && bluetoothService != null) {
            if (bluetoothService?.isConnected == true) {
                bluetoothService?.sendMediaCommand(command)
                
                // Request fresh state after command to sync UI
                Handler(Looper.getMainLooper()).postDelayed({
                    requestMediaState()
                }, 300)
            } else {
                Toast.makeText(this, getString(R.string.toast_not_connected), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        
        try {
            unregisterReceiver(mediaStateReceiver)
        } catch (e: Exception) {}
        
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
    
    companion object {
        const val ACTION_MEDIA_STATE_UPDATE = "com.ailife.rtosifycompanion.MEDIA_STATE_UPDATE"
    }
}
