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
        // Double check extension types
        val ext = file.extension.lowercase()
        val type = when {
            ext in listOf("jpg", "jpeg", "png", "webp", "gif") -> "image"
            ext in listOf("mp4", "mkv", "avi", "mov") -> "video"
            ext in listOf("mp3", "wav", "aac", "ogg") -> "audio"
            ext in listOf("txt", "log", "md", "json") -> "text"
            else -> "other"
        }

        if (type !in rule.types) return
        
        // Prepare Data
        var thumbnail: String? = null
        var duration: Long? = null
        
        if (rule.sendToWatch) {
            // Generate metadata + thumbnail
             try {
                 if (type == "image") {
                     // Load bitmap, resize, compress to base64
                     val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                     val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                     thumbnail = bmp?.let { bitmapToBase64(it) }
                 } else if (type == "video") {
                     val retriever = MediaMetadataRetriever()
                     retriever.setDataSource(file.absolutePath)
                     val bmp = retriever.getFrameAtTime()
                     thumbnail = bmp?.let { bitmapToBase64(it) }
                     val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                     duration = time?.toLongOrNull()
                     retriever.release()
                 } else if (type == "audio") {
                      val retriever = MediaMetadataRetriever()
                      retriever.setDataSource(file.absolutePath)
                      val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                      duration = time?.toLongOrNull()
                      retriever.release()
                 }
             } catch (e: Exception) {
                 Log.e("FileObserver", "Error generating metadata for ${file.name}", e)
             }
        }

        val data = FileDetectedData(
            name = file.name,
            path = file.absolutePath,
            size = file.length(),
            type = type,
            thumbnail = thumbnail,
            duration = duration,
            timestamp = System.currentTimeMillis(),
            largeIcon = rule.iconBase64 // Use the rule's icon/app icon
        )
        
        // Switch to Main thread if callback expects it? Thread safe callback is better.
        onFileDetected(data)
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Resize if too big? 
        // Create scaled bitmap if needed
        val scaled = Bitmap.createScaledBitmap(bitmap, 200, 200, true) // Small thumbnail
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
