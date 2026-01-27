package com.ailife.rtosify

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.FileObserver
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FileObserverManager(
    private val context: Context,
    private val userServiceGetter: () -> IUserService?,
    private val onFileDetected: (FileDetectedData) -> Unit
) {

    private val gson = Gson()
    private var observers = ConcurrentHashMap<String, FileObserver>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private val prefs: SharedPreferences = context.getSharedPreferences("file_observer_prefs", Context.MODE_PRIVATE)
    
    // We keep track of rules to manage polling vs standard observers
    private var currentRules: List<FileObserverRule> = emptyList()

    init {
        start()
    }

    fun start() {
        loadRules()
        setupObservers()
        startPolling()
    }

    fun stop() {
        stopObservers()
        pollingJob?.cancel()
    }

    fun reload() {
        stop()
        start()
    }

    private fun loadRules() {
        val json = prefs.getString("rules", "[]")
        val type = object : TypeToken<List<FileObserverRule>>() {}.type
        currentRules = gson.fromJson(json, type) ?: emptyList()
    }

    private fun stopObservers() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun setupObservers() {
        currentRules.forEach { rule ->
            // For standard paths, try FileObserver
            val file = File(rule.path)
            if (file.exists() && file.isDirectory && file.canRead()) {
                startRecursiveObserver(file, rule)
            }
        }
    }

    // Classic FileObserver is not recursive by default. We need to walk or just watch top level?
    // User asked for "recursive" option.
    // Implementing true recursive FileObserver is expensive (watching every subdir). 
    // Android's FileObserver (inotify) has limits.
    // For now, we will watch the top directory. If recursive is true, we might rely on Polling for subdirs 
    // or just assume new files in subdirs modify the parent dir (mostly true for some ops, but not all).
    // Actually, strict recursive FileObserver requires adding a watch for *every* directory.
    // A better approach for "monitor folders" especially for Downloads/Camera is just watching the main dir.
    // If "Recursive" is strictly needed, we will do it via Polling for robustness, or limited depth walking.
    
    // Given the constraints and reliability issues with FileObserver on modern Android,
    // combined with the need for Shizuku for restricted dirs, 
    // a Polling approach for *everything* (or at least checking lastModified) might be cleaner 
    // but less efficient power-wise.
    
    // Compromise: Use FileObserver for the explicit root. Use Polling for everything else (and recursive checks).
    
    private fun startRecursiveObserver(file: File, rule: FileObserverRule) {
        // Simple non-recursive observer for now to catch direct additions
        val observer = object : FileObserver(file.path, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                path?.let {
                    val fullPath = File(file, it)
                    // Debounce or process
                    scope.launch {
                        processFile(fullPath, rule)
                    }
                }
            }
        }
        observer.startWatching()
        observers[file.absolutePath] = observer
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            // Keep track of known files to detect new ones? 
            // Or just rely on "last modified > last check time".
            // Since we want "New File", we can track "Last Scan Time".
            var lastScanTime = System.currentTimeMillis()

            while (isActive) {
                delay(5000) // Poll every 5 seconds (tunable)
                
                val currentTime = System.currentTimeMillis()
                
                currentRules.forEach { rule ->
                    // Handle standard paths and potential Shizuku paths (if implemented via shell)
                    // For now, standard File Walk for accessible paths
                    val file = File(rule.path)
                    if (file.exists() && file.isDirectory) {
                        try {
                           file.walk()
                               .maxDepth(if (rule.isRecursive) Int.MAX_VALUE else 1)
                               .filter { it.isFile && it.lastModified() > lastScanTime }
                               .forEach { newFile ->
                                   processFile(newFile, rule)
                               }
                        } catch (e: Exception) {
                            Log.e("FileObserver", "Error walking path ${rule.path}", e)
                        }
                    } else {
                        // TODO: Shizuku logic here if path is not directly accessible
                        // callShizukuLs(rule.path, lastScanTime)
                    }
                }
                
                lastScanTime = currentTime
            }
        }
    }

    private suspend fun processFile(file: File, rule: FileObserverRule) {
        // Comprehensive extension check
        val ext = file.extension.lowercase()
        val type = when {
            ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif") -> "image"
            ext in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm") -> "video"
            ext in listOf("mp3", "wav", "aac", "ogg", "m4a", "flac", "amr") -> "audio"
            ext in listOf("txt", "log", "md", "json", "xml", "csv", "pdf") -> "text"
            else -> "other"
        }

        if (type !in rule.types) return
        
        // Prepare Data
        var thumbnail: String? = null
        var duration: Long? = null
        var fileSize = file.length()
        
        if (rule.sendToWatch) {
            // Check if we can read the file directly
            if (file.canRead()) {
                try {
                    if (type == "image") {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                        thumbnail = bmp?.let { bitmapToBase64(it) }
                    } else if (type == "video") {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        val bmp = retriever.getFrameAtTime()
                        thumbnail = bmp?.let { bitmapToBase64(it) }
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        retriever.release()
                    } else if (type == "audio") {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        retriever.release()
                    }
                } catch (e: Exception) {
                    Log.e("FileObserver", "Direct metadata failed for ${file.name}, trying via Shizuku if available", e)
                }
            }
            
            // Fallback to Shizuku for metadata if direct read failed or thumbnail still null
            if (thumbnail == null || duration == null) {
                userServiceGetter()?.let { service ->
                    try {
                        val json = service.getFileMetadata(file.absolutePath, type)
                        if (!json.isNullOrEmpty() && json != "{}") {
                            val metaType = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
                            val meta: Map<String, Any?> = gson.fromJson(json, metaType)
                            
                            if (thumbnail == null) thumbnail = meta["thumbnail"] as? String
                            if (duration == null) {
                                duration = (meta["duration"] as? Double)?.toLong() ?: (meta["duration"] as? Long)
                            }
                            // Also update size if it was 0
                            if (fileSize <= 0) {
                                fileSize = (meta["size"] as? Double)?.toLong() ?: (meta["size"] as? Long) ?: 0L
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FileObserver", "Shizuku metadata fallback failed for ${file.name}", e)
                    }
                }
            }
        }

        val data = FileDetectedData(
            name = file.name,
            path = file.absolutePath,
            size = fileSize,
            type = type,
            thumbnail = thumbnail,
            duration = duration,
            timestamp = System.currentTimeMillis(),
            largeIcon = rule.iconBase64, 
            notificationTitle = rule.notificationTitle,
            smallIconType = rule.smallIconType ?: rule.path.let { 
                // Infer small icon from path if possible (e.g. WhatsApp folder)
                if (it.contains("com.whatsapp")) "com.whatsapp" else type 
            }
        )
        
        onFileDetected(data)
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        
        // Mantain aspect ratio with max dimension of 300
        val maxDim = 300
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
