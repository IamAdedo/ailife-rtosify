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
            if (!dir.exists() || !dir.isDirectory) return "[]"
            val files = dir.listFiles()?.map {
                mapOf(
                    "name" to it.name,
                    "size" to it.length(),
                    "isDirectory" to it.isDirectory,
                    "lastModified" to it.lastModified()
                )
            } ?: emptyList()
            gson.toJson(files)
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
}
