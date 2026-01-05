package com.ailife.rtosifycompanion

import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class UserService : IUserService.Stub() {
    companion object {
        private const val TAG = "UserService"
        private val gson = Gson()
    }

    override fun destroy() {
        Log.d(TAG, "UserService destroy called")
        System.exit(0)
    }

    override fun exit() {
        Log.d(TAG, "UserService exit called")
        System.exit(0)
    }

    override fun reboot() {
        Log.i(TAG, "Reboot command received in UserService")
        try {
            // Set system property to trigger reboot
            val process = Runtime.getRuntime().exec(arrayOf("setprop", "sys.powerctl", "reboot"))
            val exitCode = process.waitFor()
            Log.i(TAG, "Reboot command executed with exit code: $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing reboot: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun shutdown() {
        Log.i(TAG, "Shutdown command received in UserService")
        try {
            // Set system property to trigger shutdown
            val process = Runtime.getRuntime().exec(arrayOf("setprop", "sys.powerctl", "shutdown"))
            val exitCode = process.waitFor()
            Log.i(TAG, "Shutdown command executed with exit code: $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing shutdown: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun listFiles(path: String): String {
        return try {
            val dir = File(path)
            if (!dir.exists()) return "[]"
            
            var files: List<Map<String, Any>>? = null
            
            if (dir.isDirectory) {
                val stdFiles = dir.listFiles()
                if (stdFiles != null) {
                    files = stdFiles.map {
                        mapOf(
                            "name" to it.name,
                            "size" to it.length(),
                            "isDirectory" to it.isDirectory,
                            "lastModified" to it.lastModified()
                        )
                    }
                } else {
                    // Standard File.listFiles() failed, try shell fallback
                    Log.d(TAG, "File.listFiles() failed for $path, trying shell fallback")
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("ls", "-F1", path))
                        val output = process.inputStream.bufferedReader().readLines()
                        if (output.isNotEmpty()) {
                            files = output.map { line ->
                                val isDir = line.endsWith("/")
                                val name = line.removeSuffix("/")
                                mapOf(
                                    "name" to name,
                                    "size" to 0L, // Hard to get via ls without parsing more
                                    "isDirectory" to isDir,
                                    "lastModified" to System.currentTimeMillis()
                                )
                            }
                        }
                    } catch (shellEx: Exception) {
                        Log.e(TAG, "Shell fallback failed: ${shellEx.message}")
                    }
                }
            }
            
            gson.toJson(files ?: emptyList<Map<String, Any>>())
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files: ${e.message}")
            "[]"
        }
    }

    override fun deleteFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: ${e.message}")
            false
        }
    }

    override fun renameFile(oldPath: String, newPath: String): Boolean {
        return try {
            File(oldPath).renameTo(File(newPath))
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming file: ${e.message}")
            false
        }
    }

    override fun moveFile(src: String, dst: String): Boolean {
        return try {
            val srcFile = File(src)
            val dstFile = File(dst)
            if (srcFile.renameTo(dstFile)) return true
            // If rename fail (cross-mount), try copy and delete
            if (copyFile(src, dst)) {
                srcFile.deleteRecursively()
                return true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error moving file: ${e.message}")
            false
        }
    }

    override fun copyFile(src: String, dst: String): Boolean {
        return try {
            val srcFile = File(src)
            val dstFile = File(dst)
            if (srcFile.isDirectory) {
                srcFile.copyRecursively(dstFile, overwrite = true)
            } else {
                srcFile.inputStream().use { input ->
                    dstFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file: ${e.message}")
            false
        }
    }

    override fun makeDirectory(path: String): Boolean {
        return try {
            File(path).mkdirs()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating directory: ${e.message}")
            false
        }
    }

    override fun exists(path: String): Boolean {
        return try {
            File(path).exists()
        } catch (e: Exception) {
            false
        }
    }

    override fun isDirectory(path: String): Boolean {
        return try {
            File(path).isDirectory
        } catch (e: Exception) {
            false
        }
    }

    override fun getFileSize(path: String): Long {
        return try {
            File(path).length()
        } catch (e: Exception) {
            0L
        }
    }

    override fun getLastModified(path: String): Long {
        return try {
            File(path).lastModified()
        } catch (e: Exception) {
            0L
        }
    }
}
