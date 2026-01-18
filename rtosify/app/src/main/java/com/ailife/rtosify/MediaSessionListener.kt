package com.ailife.rtosify

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

class MediaSessionListener(
    private val context: Context,
    private val callback: MediaStateCallback
) {
    private val TAG = "MediaSessionListener"
    private var mediaSessionManager: MediaSessionManager? = null
    private var audioManager: AudioManager? = null
    private var activeController: MediaController? = null
    private var lastMediaState: MediaStateData? = null
    private var volumeObserver: ContentObserver? = null
    
    interface MediaStateCallback {
        fun onMediaStateChanged(mediaState: MediaStateData)
    }
    
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "Active sessions changed: ${controllers?.size ?: 0} controllers")
        
        // Get the first active controller (usually the most recent media session)
        val controller = controllers?.firstOrNull()
        
        if (controller != activeController) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = controller
            activeController?.registerCallback(controllerCallback)
        }
        
        updateMediaState()
    }
    
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "Playback state changed: ${state?.state}")
            updateMediaState()
        }
        
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            Log.d(TAG, "Metadata changed")
            updateMediaState()
        }
    }
    
    fun start() {
        try {
            mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Register for active session changes
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                sessionListener,
                android.content.ComponentName(context, MyNotificationListener::class.java)
            )
            
            // Get current active sessions
            val controllers = mediaSessionManager?.getActiveSessions(
                android.content.ComponentName(context, MyNotificationListener::class.java)
            )
            
            activeController = controllers?.firstOrNull()
            activeController?.registerCallback(controllerCallback)
            
            // Send initial state
            updateMediaState()
            
            // Register volume observer
            volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    Log.d(TAG, "Volume changed, updating media state")
                    updateMediaState()
                }
            }
            context.contentResolver.registerContentObserver(
                android.provider.Settings.System.CONTENT_URI,
                true,
                volumeObserver!!
            )
            
            Log.d(TAG, "MediaSessionListener started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MediaSessionListener", e)
        }
    }
    
    fun stop() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
            volumeObserver?.let {
                context.contentResolver.unregisterContentObserver(it)
            }
            volumeObserver = null
            Log.d(TAG, "MediaSessionListener stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaSessionListener", e)
        }
    }

    fun sendCommand(command: String): Boolean {
        val controller = activeController ?: return false
        try {
            when (command) {
                MediaControlData.CMD_PLAY -> controller.transportControls.play()
                MediaControlData.CMD_PAUSE -> controller.transportControls.pause()
                MediaControlData.CMD_PLAY_PAUSE -> {
                    val state = controller.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                }
                MediaControlData.CMD_NEXT -> controller.transportControls.skipToNext()
                MediaControlData.CMD_PREVIOUS -> controller.transportControls.skipToPrevious()
                else -> return false
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending media command via controller: $command", e)
            return false
        }
    }
    
    fun getCurrentState(): MediaStateData {
        return lastMediaState ?: createEmptyState()
    }
    
    private fun updateMediaState() {
        val controller = activeController
        if (controller == null) {
            // No active media session
            val emptyState = createEmptyState()
            if (lastMediaState != emptyState) {
                lastMediaState = emptyState
                callback.onMediaStateChanged(emptyState)
            }
            return
        }
        
        try {
            val metadata = controller.metadata
            val playbackState = controller.playbackState
            
            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
            val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            val album = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
            val duration = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = playbackState?.position ?: 0L
            
            // Get album art
            val albumArtBitmap = metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            val albumArtBase64 = albumArtBitmap?.let { bitmapToBase64(it) }
            
            // Get system volume (0-100)
            val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
            val volumePercent = ((currentVolume.toFloat() / maxVolume.toFloat()) * 100).toInt()
            
            val newState = MediaStateData(
                isPlaying = isPlaying,
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                position = position,
                volume = volumePercent,
                albumArtBase64 = albumArtBase64
            )
            
            // Only notify if state changed
            if (newState != lastMediaState) {
                lastMediaState = newState
                callback.onMediaStateChanged(newState)
                Log.d(TAG, "Media state updated: $title - $artist, playing=$isPlaying")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating media state", e)
        }
    }
    
    private fun createEmptyState(): MediaStateData {
        val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
        val volumePercent = ((currentVolume.toFloat() / maxVolume.toFloat()) * 100).toInt()
        
        return MediaStateData(
            isPlaying = false,
            title = null,
            artist = null,
            album = null,
            duration = 0L,
            position = 0L,
            volume = volumePercent,
            albumArtBase64 = null
        )
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        try {
            // Scale down if too large to avoid excessive data transfer
            val maxDimension = 512
            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else {
                bitmap
            }
            
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val bytes = outputStream.toByteArray()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding bitmap to base64", e)
            return ""
        }
    }
}
