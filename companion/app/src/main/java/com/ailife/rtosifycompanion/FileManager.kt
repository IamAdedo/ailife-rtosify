package com.ailife.rtosifycompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.topjohnwu.superuser.Shell
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Enumeration
import java.util.zip.ZipFile

class FileManager(
    private val context: Context,
    private val userServiceGetter: suspend () -> IUserService?
) {
    private val TAG = "FileManager"
    private val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private fun getBypassedPath(path: String): String {
        if (!prefs.getBoolean("native_access_bypass", true)) return path

        var newPath = path
        if (newPath.contains("/Android/data")) {
            newPath = newPath.replace("/Android/data", "/Android/data\u200d")
        }
        if (newPath.contains("/Android/obb")) {
            newPath = newPath.replace("/Android/obb", "/Android/obb\u200d")
        }
        return newPath
    }

    fun toAbsolutePath(path: String): String {
        val root = android.os.Environment.getExternalStorageDirectory()
        return when {
            path == "/" -> root.absolutePath
            path.startsWith("/") -> {
                // Check if it's already absolute or relative to root
                if (path.startsWith(root.absolutePath)) {
                    path
                } else if (
                        path.startsWith("/storage/") ||
                                path.startsWith("/sdcard/") ||
                                path.startsWith("/data/")
                ) {
                    path
                } else {
                    File(root, path.substring(1)).absolutePath
                }
            }
            else -> File(root, path).absolutePath
        }
    }

    suspend fun listFiles(path: String): List<FileInfo> = withContext(Dispatchers.IO) {
        val absolutePath = toAbsolutePath(path)
        val files = mutableListOf<FileInfo>()

        // 1. Try Standard Native Access
        val stdDir = File(absolutePath)
        if (stdDir.exists() && stdDir.isDirectory && stdDir.canRead()) {
             val list = stdDir.listFiles()
             if (list != null) {
                 files.addAll(list.map {
                     FileInfo(it.name, it.length(), it.isDirectory, it.lastModified())
                 })
                 Log.d(TAG, "Standard native access success for $absolutePath")
                 return@withContext files
             }
        }

        // 2. Try Native Access with Bypass (if enabled and different)
        val bypassedPath = getBypassedPath(absolutePath)
        if (bypassedPath != absolutePath) {
            val dir = File(bypassedPath)
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                 val list = dir.listFiles()
                 if (list != null) {
                     files.addAll(list.map {
                         // Clean up name if it has ZWJ
                         val name = it.name.replace("\u200d", "")
                         FileInfo(name, it.length(), it.isDirectory, it.lastModified())
                     })
                     Log.d(TAG, "Bypassed native access success for $absolutePath")
                     return@withContext files
                 }
            }
        }

        // 2. Try Shizuku (UserService)
        if (isUsingShizuku()) {
            try {
                val service = userServiceGetter()
                if (service != null && service.asBinder().pingBinder()) {
                    val json = service.listFiles(absolutePath)
                    if (json != null && json != "[]" && json != "<ERROR>") {
                        val type = object : TypeToken<List<FileInfo>>() {}.type
                        val shizukuFiles: List<FileInfo> = gson.fromJson(json, type)
                        files.addAll(shizukuFiles)
                        Log.d(TAG, "Shizuku listFiles success for $absolutePath")
                        return@withContext files
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku listFiles failed: ${e.message}")
            }
        }

        // 3. Try Root (libsu)
        if (Shell.getShell().isRoot) {
            val rootPath = translatePathForRoot(absolutePath)
            try {
                val cmdPath = if (rootPath.endsWith("/")) rootPath else "$rootPath/"
                val result = Shell.cmd("ls -F1L \"$cmdPath\"").exec()
                if (result.isSuccess && result.out.isNotEmpty()) {
                    files.addAll(result.out.map { line ->
                        val isDir = line.endsWith("/")
                        val cleanName = line.trimEnd('/', '@', '*', '|', '=', '>')
                        FileInfo(cleanName, 0, isDir, System.currentTimeMillis())
                    })
                    return@withContext files
                }
            } catch (e: Exception) {
                Log.e(TAG, "Root listFiles failed: ${e.message}")
            }
        }

        // 4. Try SAF (Storage Access Framework)
        val relPath = getRelPath(absolutePath)
        val doc = getDocumentFileForPath(relPath, false)
        if (doc != null && doc.isDirectory) {
             val list = doc.listFiles()
             files.addAll(list.map {
                 FileInfo(it.name ?: "", it.length(), it.isDirectory, it.lastModified())
             })
             Log.d(TAG, "SAF listFiles success for $absolutePath")
             return@withContext files
        }
        
        // 4. Fallback: Inject special folder for Android/data if needed
        val storageRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (absolutePath == File(storageRoot, "Android/data").absolutePath) {
             // If we failed to list anything but we know we are in Android/data, maybe show the specific package folder if useful?
             // Or at least our own package path?
             if (files.none { it.name == "com.ailife.ClockSkinCoco" }) {
                files.add(FileInfo("com.ailife.ClockSkinCoco", 0, true, System.currentTimeMillis()))
             }
        }

        return@withContext files
    }

    suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val absPath = toAbsolutePath(path)
        val bypassedPath = getBypassedPath(absPath)
        // 1. Try Standard Native Delete
        val stdFile = File(absPath)
        if (stdFile.exists() && stdFile.deleteRecursively()) {
            Log.d(TAG, "Standard native delete success: $path")
            return@withContext true
        }

        // 2. Try Bypassed Native Delete
        if (bypassedPath != absPath) {
            val file = File(bypassedPath)
            if (file.exists() && file.deleteRecursively()) {
                Log.d(TAG, "Bypassed native delete success: $path")
                return@withContext true
            }
        }
        
        // Fallback Shizuku
        if (isUsingShizuku()) {
             try {
                val service = userServiceGetter()
                if (service?.deleteFile(absPath) == true) return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku delete failed: ${e.message}")
            }
        }
        
        // Fallback Root
        if (Shell.getShell().isRoot) {
             val rootPath = translatePathForRoot(absPath)
             val result = Shell.cmd("rm -rf \"$rootPath\"").exec()
             if (result.isSuccess) return@withContext true
        }

        // Fallback SAF
        val relPath = getRelPath(absPath)
        val doc = getDocumentFileForPath(relPath, false)
        if (doc != null && doc.exists()) {
             if (doc.delete()) {
                 Log.d(TAG, "SAF delete success: $path")
                 return@withContext true
             }
        }

        return@withContext false
    }

    suspend fun copyFile(srcPath: String, dstPath: String): Boolean = withContext(Dispatchers.IO) {
        val absSrc = toAbsolutePath(srcPath)
        val absDst = toAbsolutePath(dstPath)
        
        val bypassedSrc = getBypassedPath(absSrc)
        val bypassedDst = getBypassedPath(absDst)
        
        // 1. Try Standard Native Copy
        val stdSrc = File(absSrc)
        val stdDst = File(absDst)
        if (stdSrc.exists() && stdSrc.canRead()) {
             try {
                 if (stdSrc.isDirectory) {
                     stdSrc.copyRecursively(stdDst, overwrite = true)
                 } else {
                     stdSrc.copyTo(stdDst, overwrite = true)
                 }
                 Log.d(TAG, "Standard native copy success")
                 return@withContext true
             } catch (e: Exception) {
                 // Ignore and try bypass/fallback
             }
        }

        // 2. Try Bypassed Native Copy
        if (bypassedSrc != absSrc || bypassedDst != absDst) {
            val srcFile = File(bypassedSrc)
            val dstFile = File(bypassedDst)
            if (srcFile.exists() && srcFile.canRead()) {
                 try {
                     if (srcFile.isDirectory) {
                         srcFile.copyRecursively(dstFile, overwrite = true)
                     } else {
                         srcFile.copyTo(dstFile, overwrite = true)
                     }
                     Log.d(TAG, "Bypassed native copy success")
                     return@withContext true
                 } catch (e: Exception) {
                     Log.e(TAG, "Native copy failed: ${e.message}")
                 }
            }
        }
        
        // Fallback Shizuku
        if (isUsingShizuku()) {
            try {
                if (userServiceGetter()?.copyFile(absSrc, absDst) == true) return@withContext true
            } catch (e: Exception) {}
        }
        
        // Fallback Root
        if (Shell.getShell().isRoot) {
            val rSrc = translatePathForRoot(absSrc)
            val rDst = translatePathForRoot(absDst)
            val result = Shell.cmd("cp -r \"$rSrc\" \"$rDst\"").exec()
            if (result.isSuccess) return@withContext true
        }

        // Fallback SAF (Copying strictly via SAF is hard without full implementation, but we can try if dest is SAF)
        // For now, we only support copying TO SAF if source is readable
        val srcRel = getRelPath(absSrc)
        val dstRel = getRelPath(absDst)
        
        // If destination is in our SAF tree
        if (getDocumentFileForPath(dstRel, false) != null || getDocumentFileForPath(dstRel.substringBeforeLast("/"), false) != null) {
             val targetName = absDst.substringAfterLast("/")
             val parentFolder = getDocumentFileForPath(dstRel.substringBeforeLast("/"), false)
             
             if (parentFolder != null && parentFolder.isDirectory) {
                 try {
                     // Check if src is readable (standard or bypassed)
                     var inputStream = if (File(absSrc).canRead()) File(absSrc).inputStream() else null
                     if (inputStream == null && File(bypassedSrc).canRead()) inputStream = File(bypassedSrc).inputStream()
                     
                     if (inputStream != null) {
                         val newFile = parentFolder.createFile("", targetName)
                         if (newFile != null) {
                             context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                                 inputStream.use { input -> input.copyTo(output) }
                             }
                             return@withContext true
                         }
                     }
                 } catch (e: Exception) {
                     Log.e(TAG, "SAF copy failed: ${e.message}")
                 }
             }
        }

        return@withContext false
    }

    suspend fun renameFile(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val absOld = toAbsolutePath(oldPath)
        val absNew = toAbsolutePath(newPath)
        
        val bypassedOld = getBypassedPath(absOld)
        val bypassedNew = getBypassedPath(absNew)
        
        // 1. Try Standard Native Rename
        val stdOld = File(absOld)
        val stdNew = File(absNew)
        if (stdOld.exists() && stdOld.renameTo(stdNew)) {
            Log.d(TAG, "Standard native rename success")
            return@withContext true
        }

        // 2. Try Bypassed Native Rename
        if (bypassedOld != absOld || bypassedNew != absNew) {
            val oldFile = File(bypassedOld)
            val newFile = File(bypassedNew)
            if (oldFile.exists() && oldFile.renameTo(newFile)) {
                Log.d(TAG, "Bypassed native rename success")
                return@withContext true
            }
        }
        
        if (isUsingShizuku()) {
             try {
                if (userServiceGetter()?.renameFile(absOld, absNew) == true) return@withContext true
             } catch (e: Exception) {}
        }
        
        if (Shell.getShell().isRoot) {
             val rOld = translatePathForRoot(absOld)
             val rNew = translatePathForRoot(absNew)
             val result = Shell.cmd("mv \"$rOld\" \"$rNew\"").exec()
             if (result.isSuccess) return@withContext true
        }

        // Fallback SAF
        val oldRel = getRelPath(absOld)
        val doc = getDocumentFileForPath(oldRel, false)
        if (doc != null && doc.exists()) {
             val newName = absNew.substringAfterLast("/")
             if (doc.renameTo(newName)) return@withContext true
        }

        // Final Fallback: Copy and Delete
        // Useful for cross-filesystem moves or when SAF rename fails but write works
        if (copyFile(oldPath, newPath)) {
            if (deleteFile(oldPath)) {
                Log.d(TAG, "Fallback rename (copy-delete) success")
                return@withContext true
            }
        }
        
        return@withContext false
    }
    
    suspend fun createFolder(path: String): Boolean = withContext(Dispatchers.IO) {
        val absPath = toAbsolutePath(path)
        val bypassedPath = getBypassedPath(absPath)
        // 1. Try Standard Native Mkdir
        val stdFile = File(absPath)
        if (stdFile.exists() && stdFile.isDirectory) return@withContext true
        if (stdFile.mkdirs()) {
            Log.d(TAG, "Standard native mkdir success")
            return@withContext true
        }

        // 2. Try Bypassed Native Mkdir
        if (bypassedPath != absPath) {
            val file = File(bypassedPath)
            if (file.exists() && file.isDirectory) return@withContext true
            if (file.mkdirs()) {
                Log.d(TAG, "Bypassed native mkdir success")
                return@withContext true
            }
        }
        
        if (isUsingShizuku()) {
            try {
                if (userServiceGetter()?.makeDirectory(absPath) == true) return@withContext true
            } catch (e: Exception) {}
        }
        
        if (Shell.getShell().isRoot) {
            val rPath = translatePathForRoot(absPath)
            if (Shell.cmd("mkdir -p \"$rPath\"").exec().isSuccess) return@withContext true
        }

        // Fallback SAF
        val relPath = getRelPath(absPath)
        val doc = getDocumentFileForPath(relPath, true)
        if (doc != null && doc.isDirectory) return@withContext true
        
        return@withContext false
    }

    suspend fun getFilePreview(path: String): PreviewData = withContext(Dispatchers.IO) {
        val absPath = toAbsolutePath(path)
        val bypassedPath = getBypassedPath(absPath)
        
        var bitmap: Bitmap? = null
        var textContent: String? = null
        val extension = File(absPath).extension.lowercase()
        
        // 1. Try Native Read (Images)
        // Check normal file IO with bypass
        // 1. Try Native Read (Images/Text) - Standard
        var file = File(absPath)
        var canRead = file.exists() && file.isFile && file.canRead()
        var usedBypass = false

        if (!canRead && bypassedPath != absPath) {
             file = File(bypassedPath)
             canRead = file.exists() && file.isFile && file.canRead()
             usedBypass = true
        }

        if (canRead) {
            if (extension in listOf("jpg", "jpeg", "png", "webp", "bmp")) {
                 val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                 bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            } else if (extension in listOf("txt", "log", "xml", "json", "md", "properties", "gradle", "kt", "java")) {
                 try {
                     textContent = file.readText().take(1024)
                 } catch (e: Exception) {}
            }
            if (bitmap != null || textContent != null) {
                Log.d(TAG, "Native preview success (bypass=$usedBypass)")
            }
        }
        // 3. Try SAF Preview
        if (bitmap == null && textContent == null) {
             val relPath = getRelPath(absPath)
             val doc = getDocumentFileForPath(relPath, false)
             if (doc != null && doc.exists() && !doc.isDirectory) {
                 try {
                     if (extension in listOf("jpg", "jpeg", "png", "webp", "bmp")) {
                         context.contentResolver.openInputStream(doc.uri)?.use { input ->
                             val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                             bitmap = BitmapFactory.decodeStream(input, null, opts)
                         }
                     } else if (extension in listOf("txt", "log", "xml", "json", "md", "properties", "gradle", "kt", "java")) {
                         context.contentResolver.openInputStream(doc.uri)?.use { input ->
                             val buffer = ByteArray(2048)
                             val read = input.read(buffer)
                             if (read > 0) textContent = String(buffer, 0, read)
                         }
                     }
                 } catch (e: Exception) {
                     Log.e(TAG, "SAF preview failed: ${e.message}")
                 }
             }
        }
        
        // 2. Fallbacks (Shizuku/Root) for restricted files if native failed
        // If native read failed, we might need to copy to temp to read bitmap or unzip
        
        if (bitmap == null && textContent == null) {
             // Check if it's a directory
             val isDir = isDirectory(absPath)
             if (isDir) {
                 val candidates = listOf("preview.png", "img_gear_0.png", "preview.jpg")
                 for (c in candidates) {
                     val imgPath = if (absPath.endsWith("/")) "$absPath$c" else "$absPath/$c"
                     // Try loading this candidate
                     // We recurse once? or just try loading bitmap
                     // Simple check:
                     val imgFile = File(getBypassedPath(imgPath))
                     if (imgFile.exists()) {
                         val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                         bitmap = BitmapFactory.decodeFile(imgFile.absolutePath, opts)
                         if (bitmap != null) break
                     }
                 }
             } else {
                 // Try ZIP preview
                 bitmap = loadPreviewFromZip(absPath)
             }
        }

        if (bitmap == null && textContent == null) {
            if (extension in listOf("mp4", "mkv", "webm", "avi", "3gp", "mov")) {
                 val retriever = android.media.MediaMetadataRetriever()
                 try {
                     // Try native path first
                     if (File(absPath).canRead()) {
                         retriever.setDataSource(absPath)
                     } else {
                         // Try SAF
                         val relPath = getRelPath(absPath)
                         val doc = getDocumentFileForPath(relPath, false)
                         if (doc != null && doc.exists()) {
                             retriever.setDataSource(context, doc.uri)
                         } else {
                              // If unreadable natively and no SAF, maybe copy to temp?
                              // Or try bypassed path
                              if (File(bypassedPath).canRead()) {
                                  retriever.setDataSource(bypassedPath)
                              }
                         }
                     }
                     bitmap = retriever.getFrameAtTime()
                 } catch (e: Exception) {
                     Log.e(TAG, "Video preview failed: ${e.message}")
                 } finally {
                     retriever.release()
                 }
            }
        }

        return@withContext PreviewData(
            base64 = bitmap?.let { bmp ->
                val output = ByteArrayOutputStream()
                // Resize for thumb
                 val maxDim = 200
                 val scale = Math.min(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
                 val w = (bmp.width * scale).toInt()
                 val h = (bmp.height * scale).toInt()
                 val thumb = if (w > 0 && h > 0) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
                 
                thumb.compress(Bitmap.CompressFormat.JPEG, 60, output)
                if (thumb != bmp) thumb.recycle()
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            },
            textContent = textContent,
            error = null
        )
    }
    
    private suspend fun isDirectory(path: String): Boolean {
        val stdFile = File(path)
        if (stdFile.exists()) return stdFile.isDirectory

        val bypassed = getBypassedPath(path)
        if (bypassed != path) {
            val file = File(bypassed)
            if (file.exists()) return file.isDirectory
        }
        
        // Fallback
         if (isUsingShizuku()) {
            try {
                return userServiceGetter()?.isDirectory(path) ?: false
            } catch (_: Exception) {}
        }
        
         if (Shell.getShell().isRoot) {
            val rPath = translatePathForRoot(path)
            val result = Shell.cmd("test -d \"$rPath\"").exec()
            if (result.isSuccess) return true
        }

        // SAF
        val relPath = getRelPath(path)
        val doc = getDocumentFileForPath(relPath, false)
        return doc?.isDirectory ?: false
        return false
    }

    private suspend fun loadPreviewFromZip(absPath: String): Bitmap? = withContext(Dispatchers.IO) {
        // For zip reading, we need random access, which is hard with Shizuku/Root streams without copy
        // So we copy to temp if not readable natively
        var zipFileToUse = File(absPath)
        var tempFile: File? = null
        
        // Check if standard path is readable
        if (!zipFileToUse.exists() || !zipFileToUse.canRead()) {
             // Try bypassed path
             val bypassed = getBypassedPath(absPath)
             if (bypassed != absPath) {
                 val bpFile = File(bypassed)
                 if (bpFile.exists() && bpFile.canRead()) {
                     zipFileToUse = bpFile
                 }
             }
        }
        
        if (!zipFileToUse.exists() || !zipFileToUse.canRead()) {
             // Copy to temp
             val cacheDir = context.externalCacheDir ?: context.cacheDir
             tempFile = File.createTempFile("pzip_", ".zip", cacheDir)
             
             var copied = false
             if (copyFile(absPath, tempFile.absolutePath)) {
                 copied = true
             } else {
                 // Try reading from SAF if copyFile failed (as it might not handle SAF Source -> Native Dest)
                 val relPath = getRelPath(absPath)
                 val doc = getDocumentFileForPath(relPath, false)
                 if (doc != null && doc.exists()) {
                     try {
                         context.contentResolver.openInputStream(doc.uri)?.use { input ->
                             java.io.FileOutputStream(tempFile).use { output ->
                                 input.copyTo(output)
                             }
                         }
                         copied = true
                     } catch(e: Exception) {
                         Log.e(TAG, "SAF zip copy failed: ${e.message}")
                     }
                 }
             }
             
             if (copied) {
                 zipFileToUse = tempFile
             } else {
                 tempFile.delete()
                 return@withContext null
             }
        }
        
        return@withContext try {
             ZipFile(zipFileToUse).use { zip ->
                val candidates = listOf("preview.png", "img_gear_0.png", "preview.jpg")
                for (c in candidates) {
                    val entry = zip.entries().asSequence().find { 
                        it.name.endsWith(c, true) && !it.isDirectory 
                    }
                    if (entry != null) {
                         zip.getInputStream(entry).use { input -> 
                             BitmapFactory.decodeStream(input) 
                         }?.let { return@withContext it }
                    }
                }
                null
             }
        } catch(e: Exception) {
            Log.e(TAG, "Zip preview failed: ${e.message}")
            null
        } finally {
            tempFile?.delete()
        }
    }

    fun isUsingShizuku(): Boolean {
        // Check if Shizuku is actually available and we have permission
        return try {
            rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }
    
    // Helpers
    private fun translatePathForRoot(path: String): String {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        // Prefer /sdcard for root operations as it proved more reliable for 'ls' on some devices
        return when {
            path.startsWith(root) -> path.replaceFirst(root, "/sdcard")
            path.startsWith("/storage/emulated/0") ->
                    path.replaceFirst("/storage/emulated/0", "/sdcard")
            path.startsWith("/data/media/0") -> path.replaceFirst("/data/media/0", "/sdcard")
            else -> path
        }
    }

    private fun getRelPath(absolutePath: String): String {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        return absolutePath.removePrefix(root).removePrefix("/")
    }

    private fun getDocumentFileForPath(
            path: String,
            createIfNotExists: Boolean
    ): androidx.documentfile.provider.DocumentFile? {
        try {
             // We typically support SAF for restricted folders we have access to (like WatchFace folder)
            val targetFolder = "Android/data/com.ailife.ClockSkinCoco"
            
            // Find permission that covers this path
            val perm = context.contentResolver.persistedUriPermissions.firstOrNull {
                 val decodedUri = Uri.decode(it.uri.toString())
                 decodedUri.contains("com.ailife.ClockSkinCoco", ignoreCase = true) &&
                         it.isWritePermission &&
                         path.startsWith(targetFolder, ignoreCase = true)
            }

            if (perm != null) {
                var docFile = DocumentFile.fromTreeUri(context, perm.uri) ?: return null

                // Determine relative path from the treeUri root to our target path
                 val decodedTreeUri = Uri.decode(perm.uri.toString())
                 val treePathPart = decodedTreeUri.substringAfterLast(":", "").removePrefix("/") 
                 
                 val normalizedTarget = path.removePrefix("/").removeSuffix("/") 

                 if (normalizedTarget.startsWith(treePathPart, ignoreCase = true)) {
                     val subPath = normalizedTarget.substring(treePathPart.length).removePrefix("/")
                     if (subPath.isEmpty()) return docFile
                     
                     val parts = subPath.split("/")
                     for (part in parts) {
                         var nextFile = docFile.listFiles().find { 
                             it.name?.equals(part, ignoreCase = true) == true 
                         }
                         
                         if (nextFile == null && createIfNotExists) {
                             try {
                                 nextFile = docFile.createDirectory(part)
                             } catch (e: Exception) {
                                 Log.e(TAG, "SAF: Create dir failed: ${e.message}")
                             }
                         }
                         
                         if (nextFile != null) {
                             docFile = nextFile
                         } else {
                             return null
                         }
                     }
                     return docFile
                 }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SAF Error for $path: ${e.message}")
        }
        return null
    }

    data class PreviewData(
        val base64: String?,
        val textContent: String?,
        val error: String?
    )
}
