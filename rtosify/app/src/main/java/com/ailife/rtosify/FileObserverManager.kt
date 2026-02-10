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
    private val userServiceGetter: suspend () -> IUserService?,
    private val onFileDetected: (FileDetectedData) -> Unit
) {

    private val gson = Gson()
    private var observers = ConcurrentHashMap<String, FileObserver>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private val prefs: SharedPreferences = context.getSharedPreferences("file_observer_prefs", Context.MODE_PRIVATE)
    private val appPrefs: SharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    
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

    private fun getBypassedPath(path: String): String {
        if (!appPrefs.getBoolean("native_access_bypass", true)) return path

        var newPath = path
        if (newPath.contains("/Android/data")) {
            newPath = newPath.replace("/Android/data", "/Android/data\u200d")
        }
        if (newPath.contains("/Android/obb")) {
            newPath = newPath.replace("/Android/obb", "/Android/obb\u200d")
        }
        return newPath
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

    // State for polling: RuleID -> Set of Filenames
    // Use KeySet which is a thread-safe implementation of a Set backed by ConcurrentHashMap
    private val monitoredFileStates = ConcurrentHashMap<String, MutableSet<String>>()

    private fun startPolling() {
        pollingJob = scope.launch {
            // Initial delay to allow system to settle? 
            while (isActive) {
                val currentTime = System.currentTimeMillis()
                
                currentRules.forEach { rule ->
                    val currentFiles = mutableSetOf<String>()
                    var success = false
                    
                    // 1. Get List of Files (Accessible + Restricted)
                    // Try with bypass first
                    val effectivePath = getBypassedPath(rule.path)
                    val file = File(effectivePath)
                    
                    if (file.exists() && file.isDirectory && file.canRead()) {
                        try {
                             file.walk()
                                .maxDepth(if (rule.isRecursive) Int.MAX_VALUE else 1)
                                .filter { it.isFile }
                                .forEach { f ->
                                    // Fix name if it contains ZWJ in relative path
                                    val relPath = f.toRelativeString(file)
                                    // We must strip \u200d from the relative path/filename if present for cleaner matching?
                                    // Actually, standard File API usually returns clean names if the path works.
                                    // But if the DIR has ZWJ, child files might not. 
                                    // Let's assume standard behavior.
                                    currentFiles.add(relPath)
                                }
                            success = true
                        } catch (e: Exception) {
                            Log.e("FileObserver", "Error walking path $effectivePath", e)
                            success = false
                        }
                    } else {
                        // Restricted path: Use Shizuku Polling
                        userServiceGetter()?.let { service ->
                            try {
                                val json = service.listFiles(rule.path)
                                if (json != "<ERROR>") {
                                    success = true
                                    if (!json.isNullOrEmpty() && json != "[]") {
                                        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                                        val list: List<Map<String, Any>> = gson.fromJson(json, type)
                                        
                                        list.forEach { fileData ->
                                            val isDirectory = fileData["isDirectory"] == true
                                            val name = fileData["name"] as? String
                                            if (!isDirectory && name != null) {
                                                 currentFiles.add(name)
                                            }
                                        }
                                    }
                                } else {
                                    Log.w("FileObserver", "Shizuku poll returned ERROR for ${rule.path}")
                                    success = false
                                }
                            } catch (e: Exception) {
                                Log.e("FileObserver", "Shizuku poll failed for ${rule.path}", e)
                                success = false
                            }
                        }
                    }
                    
                    // 2. Process Differences ONLY on success
                    if (success) {
                        // Use putIfAbsent for thread-safety during init? 
                        // Actually we need to obtain the existing set or create new safely.
                        // Since we are iterating rules, we can just use put.
                        
                        // We need to use ConcurrentHashMap.newKeySet() for thread safety of the VALUES
                        val previousState = monitoredFileStates.getOrPut(rule.id) { 
                            // First run! Just populate and don't notify
                            ConcurrentHashMap.newKeySet()
                        }
                        
                        // Check if this rule is newly initialized
                        if (!ruleInitialized.contains(rule.id)) {
                            previousState.addAll(currentFiles)
                            ruleInitialized.add(rule.id)
                        } else {
                            // Find new files
                            currentFiles.forEach { relPath ->
                                if (!previousState.contains(relPath)) {
                                    // NEW FILE!
                                    val fullPath = File(rule.path, relPath)
                                    processFile(fullPath, rule)
                                    // Note: processFile will now add it to monitoredFileStates
                                }
                            }
                            
                            // Update state: Add all current files to keep set in sync.
                            // We don't just replace the set, we update it to maintain concurrency?
                            // Actually, replacing the set is fine since we are the only writer?
                            // But processFile is a writer too! So we must NOT replace the set object.
                            // We should clear and addAll or just addAll if we don't care about deletions?
                            // If file is deleted, we should remove it? 
                            // The current logic doesn't handle deletions explicitly for notifications, 
                            // but for detecting re-additions we should remove missing files.
                            
                            // SYNC LOGIC:
                            // Remove files that verify as no longer existing (not in currentFiles)
                            previousState.retainAll(currentFiles)
                            // Add all current files (some might be new ones we just processed, some old)
                            previousState.addAll(currentFiles)
                        }
                    }
                }
                
                delay(5000) // Poll every 5 seconds
            }
        }
    }
    
    private val ruleInitialized = ConcurrentHashMap.newKeySet<String>()

    private suspend fun processFile(file: File, rule: FileObserverRule) {
        // Register this file as "seen" to prevent Polling from re-detecting it
        // We need to determine the relative path or name used by polling
        try {
            val ruleRoot = File(rule.path)
            val relPath = if (file.absolutePath.startsWith(ruleRoot.absolutePath)) {
                // Determine relative path for state tracking
                file.toRelativeString(ruleRoot)
            } else {
                file.name // Fallback
            }
            
            // Thread-safe update
            monitoredFileStates.getOrPut(rule.id) { ConcurrentHashMap.newKeySet() }.add(relPath)
        } catch (e: Exception) {
            Log.e("FileObserver", "Error updating state for ${file.name}", e)
        }
    
        // Comprehensive extension check
        val ext = file.extension.lowercase()
        val type = when {
            ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif") -> "image"
            ext in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm") -> "video"
            // ADDED: opus, oga, wma, mid
            ext in listOf("mp3", "wav", "aac", "ogg", "m4a", "flac", "amr", "opus", "oga", "wma", "mid", "midi") -> "audio"
            ext in listOf("txt", "log", "md", "json", "xml", "csv", "pdf") -> "text"
            else -> "other"
        }

        if (type !in rule.types) return
        
        // Prepare Data
        var thumbnail: String? = null
        var duration: Long? = null
        var fileSize = file.length()
        var textContent: String? = null
        
        if (rule.sendToWatch) {
            // Read text content
            if (type == "text") {
                if (file.canRead()) {
                    try {
                        textContent = file.bufferedReader().use { reader ->
                            val buffer = CharArray(500)
                            val read = reader.read(buffer, 0, 500)
                            if (read > 0) String(buffer, 0, read) else ""
                        }
                    } catch (e: Exception) {}
                }
            }

            // Check if we can read the file directly for metadata
            var canReadDirectly = file.canRead()
            var effectiveFile = file
            
            if (!canReadDirectly) {
                // Try bypass
                val bp = getBypassedPath(file.absolutePath)
                val bf = File(bp)
                if (bf.canRead()) {
                    canReadDirectly = true
                    effectiveFile = bf
                }
            }

            if (canReadDirectly) {
                try {
                    if (type == "image") {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        val bmp = BitmapFactory.decodeFile(effectiveFile.absolutePath, opts)
                        thumbnail = bmp?.let { bitmapToBase64(it) }
                    } else if (type == "video") {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(effectiveFile.absolutePath)
                        val bmp = retriever.getFrameAtTime()
                        thumbnail = bmp?.let { bitmapToBase64(it) }
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        retriever.release()
                    } else if (type == "audio") {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(effectiveFile.absolutePath)
                        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        retriever.release()
                    }
                } catch (e: Exception) {
                    Log.e("FileObserver", "Direct metadata failed for ${effectiveFile.name}, trying via Shizuku if available", e)
                }
            }
            
            // Fallback to Shizuku for metadata/text if direct read failed
            // Note: For OGG/OPUS, direct MediaMetadataRetriever might fail if path is restricted.
            if (thumbnail == null || duration == null || (type == "text" && textContent == null)) {
                userServiceGetter()?.let { service ->
                    try {
                        if (type == "text" && textContent == null) {
                            textContent = service.readTextFile(file.absolutePath, 500)
                        }

                        val json = service.getFileMetadata(file.absolutePath, type)
                        if (!json.isNullOrEmpty() && json != "{}") {
                            val metaType = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
                            val meta: Map<String, Any?> = gson.fromJson(json, metaType)
                            
                            if (thumbnail == null) thumbnail = meta["thumbnail"] as? String
                            if (duration == null) {
                                duration = (meta["duration"] as? Double)?.toLong() ?: (meta["duration"] as? Long)
                            }
                            if (fileSize <= 0) {
                                fileSize = (meta["size"] as? Double)?.toLong() ?: (meta["size"] as? Long) ?: 0L
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FileObserver", "Shizuku fallback failed for ${file.name}", e)
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
            smallIconType = rule.smallIconType ?: type, // Default to file category
            textContent = textContent
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
