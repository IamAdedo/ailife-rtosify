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


    private fun runShellCommand(vararg command: String): List<String>? {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val output = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: ${command.joinToString(" ")} - ${e.message}")
            null
        }
    }

    private fun runShellStatus(vararg command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(command)
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun listFiles(path: String): String {
        return try {
            val dir = File(path)
            
            var files: List<Map<String, Any>>? = null
            
            // Try standard API first
            if (dir.exists() && dir.isDirectory) {
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
                }
            }

            // Fallback to Shell (ls -F1)
            if (files == null) {
                Log.d(TAG, "Standard listFiles failed for $path, trying shell fallback")
                val cmdPath = if (path.endsWith("/")) path else "$path/"
                val output = runShellCommand("ls", "-F1L", cmdPath)
                if (output != null) {
                    files = output.map { line ->
                        // ls -F markers: / folder, @ link, * executable, | FIFO, = socket, > door
                        val isDir = line.endsWith("/")
                        val name = line.trimEnd('/', '@', '*', '|', '=', '>')
                        mapOf(
                            "name" to name,
                            "size" to 0L,
                            "isDirectory" to isDir,
                            "lastModified" to System.currentTimeMillis()
                        )
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
            if (file.isDirectory) {
                if (file.deleteRecursively()) return true
            } else {
                if (file.delete()) return true
            }
            // Shell fallback
            runShellStatus("rm", "-rf", path)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: ${e.message}")
            runShellStatus("rm", "-rf", path)
        }
    }

    override fun renameFile(oldPath: String, newPath: String): Boolean {
        return try {
            if (File(oldPath).renameTo(File(newPath))) return true
            runShellStatus("mv", oldPath, newPath)
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming file: ${e.message}")
            runShellStatus("mv", oldPath, newPath)
        }
    }

    override fun moveFile(src: String, dst: String): Boolean {
        return try {
            if (File(src).renameTo(File(dst))) return true
            if (runShellStatus("mv", src, dst)) return true
            
            // Cross-mount fallback: copy then delete
            if (copyFile(src, dst)) {
                deleteFile(src)
                return true
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error moving file: ${e.message}")
            runShellStatus("mv", src, dst)
        }
    }

    override fun copyFile(src: String, dst: String): Boolean {
        return try {
            val srcFile = File(src)
            val dstFile = File(dst)
            
            // Standard copy
            try {
                if (srcFile.isDirectory) {
                    srcFile.copyRecursively(dstFile, overwrite = true)
                } else {
                    srcFile.inputStream().use { input ->
                        dstFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                return true
            } catch (e: Exception) {
                // Ignore and try shell fallback
            }

            // Shell fallback
            if (srcFile.isDirectory) {
                runShellStatus("cp", "-pr", src, dst)
            } else {
                // If destination is in a restricted area, we might need 'cat' + redirection, 
                // but runShellStatus uses exec which doesn't handle redirection.
                // Shizuku shell user can usually 'cp' between app data dirs.
                runShellStatus("cp", "-p", src, dst) || runShellStatus("sh", "-c", "cat \"$src\" > \"$dst\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file: ${e.message}")
            false
        }
    }

    override fun makeDirectory(path: String): Boolean {
        return try {
            if (File(path).mkdirs()) return true
            runShellStatus("mkdir", "-p", path)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating directory: ${e.message}")
            runShellStatus("mkdir", "-p", path)
        }
    }

    override fun exists(path: String): Boolean {
        return try {
            if (File(path).exists()) return true
            runShellStatus("test", "-e", path)
        } catch (e: Exception) {
            runShellStatus("test", "-e", path)
        }
    }

    override fun isDirectory(path: String): Boolean {
        return try {
            if (File(path).isDirectory) return true
            runShellStatus("test", "-d", path)
        } catch (e: Exception) {
            runShellStatus("test", "-d", path)
        }
    }

    override fun getFileSize(path: String): Long {
        return try {
            val length = File(path).length()
            if (length > 0) return length
            
            // Shell fallback (stat -c%s)
            val output = runShellCommand("stat", "-c%s", path)
            output?.firstOrNull()?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun getLastModified(path: String): Long {
        return try {
            val time = File(path).lastModified()
            if (time > 0) return time
            
            // Shell fallback (stat -c%Y)
            val output = runShellCommand("stat", "-c%Y", path)
            // stat -c%Y returns seconds, Java needs milliseconds
            (output?.firstOrNull()?.toLongOrNull() ?: 0L) * 1000L
        } catch (e: Exception) {
            0L
        }
    }
}
