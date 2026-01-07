package com.ailife.rtosifycompanion

import android.util.Log
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class UserService : IUserService.Stub() {
    companion object {
        private const val TAG = "UserService"
        private val gson = Gson()
    }
    
    private val clipboardManager: ClipboardManagerWrapper?
    
    init {
        // Create clipboard manager wrapper (FakeContext auto-initializes)
        clipboardManager = ClipboardManagerWrapper.create()
        
        val currentUid = android.os.Process.myUid()
        val currentPid = android.os.Process.myPid()
        Log.d(TAG, "UserService running as UID: $currentUid, PID: $currentPid (shell=2000, root=0)")
        
        if (clipboardManager == null) {
            Log.w(TAG, "ClipboardManager not available on this device")
        } else {
            Log.d(TAG, "ClipboardManager initialized successfully")
        }
    }

    override fun getPrimaryClipText(): String? {
        Log.d(TAG, "getPrimaryClipText called")
        
        if (clipboardManager == null) {
            Log.e(TAG, "ClipboardManager not available")
            return null
        }
        
        return try {
            val text = clipboardManager.getText()?.toString()
            Log.i(TAG, "Successfully read clipboard: $text")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error reading clipboard: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    override fun setPrimaryClipText(text: String?) {
        Log.d(TAG, "setPrimaryClipText called with: $text")
        
        if (clipboardManager == null) {
            Log.e(TAG, "ClipboardManager not available")
            return
        }
        
        if (text == null) {
            Log.w(TAG, "Attempted to set null clipboard text")
            return
        }
        
        try {
            val success = clipboardManager.setText(text)
            if (success) {
                Log.i(TAG, "Successfully set clipboard text")
            } else {
                Log.w(TAG, "Failed to set clipboard text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting clipboard: ${e.message}")
            e.printStackTrace()
        }
    }
    
    override fun executeCommand(command: String): String {
        Log.d(TAG, "executeCommand called with: $command")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()
            Log.d(TAG, "Command executed with exit code: $exitCode")
            exitCode.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: ${e.message}")
            e.printStackTrace()
            "-1"
        }
    }

    override fun runShellCommandWithOutput(command: String): String {
        Log.d(TAG, "runShellCommandWithOutput called with: $command")
        return try {
            // Split command by spaces, but respect quotes if needed (simple split for now as typically we pass "dumpsys batterystats ...")
            // Ideally use a proper tokenizer or pass array from AIDL if complex args needed.
            // For "dumpsys batterystats --checkin", simple split is fine.
            val parts = command.split(" ").toTypedArray()
            val process = Runtime.getRuntime().exec(parts)
            
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            
            if (process.exitValue() == 0) {
                output
            } else {
                val error = process.errorStream.bufferedReader().use { it.readText() }
                Log.e(TAG, "Command failed with code ${process.exitValue()}: $error")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command with output: ${e.message}")
            e.printStackTrace()
            ""
        }
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
            if (File(src).renameTo(File(dst))) {
                android.util.Log.d(TAG, "moveFile: renameTo success")
                return true
            }
            android.util.Log.d(TAG, "moveFile: renameTo failed, trying mv")
            if (runShellStatus("mv", src, dst)) {
                android.util.Log.d(TAG, "moveFile: mv success")
                return true
            }
            
            // Cross-mount fallback: copy then delete
            android.util.Log.d(TAG, "moveFile: mv failed, trying copy+delete")
            if (copyFile(src, dst)) {
                deleteFile(src)
                android.util.Log.d(TAG, "moveFile: copy+delete success")
                return true
            }
            android.util.Log.e(TAG, "moveFile: all methods failed for $src -> $dst")
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
            android.util.Log.d(TAG, "Standard copy failed, trying shell cp for $src -> $dst")
            val success = if (srcFile.isDirectory) {
                runShellStatus("cp", "-r", src, dst)
            } else {
                runShellStatus("cp", src, dst)
            }
            
            if (success) {
                // Ensure the copied file is readable
                runShellStatus("chmod", "-R", "777", dst)
            } else {
                android.util.Log.e(TAG, "Shell cp failed for $src -> $dst")
            }
            return success
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

    override fun installApp(apkPath: String): Boolean {
        Log.i(TAG, "Installing app from $apkPath")
        return runShellStatus("pm", "install", "-r", apkPath)
    }

    override fun uninstallApp(packageName: String): Boolean {
        Log.i(TAG, "Uninstalling app $packageName")
        return runShellStatus("pm", "uninstall", packageName)
    }

    override fun installAppFromPfd(pfd: ParcelFileDescriptor): Boolean {
        Log.i(TAG, "installAppFromPfd called")
        var tempFile: File? = null
        return try {
            tempFile = File("/data/local/tmp/temp_install_${System.currentTimeMillis()}.apk")
            Log.d(TAG, "Copying APK to ${tempFile.absolutePath}")
            
            pfd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            Log.d(TAG, "Chmod 666 ${tempFile.absolutePath}")
            runShellStatus("chmod", "666", tempFile.absolutePath)
            
            Log.d(TAG, "Running pm install -r ${tempFile.absolutePath}")
            val success = runShellStatus("pm", "install", "-r", tempFile.absolutePath)
            Log.i(TAG, "pm install result: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "installAppFromPfd failed: ${e.message}")
            e.printStackTrace()
            false
        } finally {
            try {
                tempFile?.let {
                    if (it.exists()) {
                        Log.d(TAG, "Deleting temp file ${it.absolutePath}")
                        it.delete()
                        // Try shell rm if delete fails
                        if (it.exists()) runShellStatus("rm", it.absolutePath)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete temp file: ${e.message}")
            }
        }
    }

    override fun setWifiEnabled(enabled: Boolean) {
        val cmd = if (enabled) "enable" else "disable"
        Log.i(TAG, "Setting WiFi to $cmd")
        runShellStatus("svc", "wifi", cmd)
    }

    override fun connectToWifi(ssid: String, password: String?) {
        Log.i(TAG, "Connecting to WiFi: $ssid (password provided: ${!password.isNullOrEmpty()})")

        // First, check if this network is already saved
        val savedNetworks = runShellCommand("cmd", "wifi", "list-networks")
        Log.d(TAG, "Saved networks: $savedNetworks")

        var isNetworkSaved = false
        savedNetworks?.forEach { line ->
            if (line.contains(ssid, ignoreCase = true)) {
                isNetworkSaved = true
                Log.d(TAG, "Network $ssid is already saved")
            }
        }

        // If network is saved and no new password is provided, just try to connect
        if (isNetworkSaved && password.isNullOrEmpty()) {
            Log.i(TAG, "Network already saved, attempting to connect without adding new suggestion")
            // Try to connect to saved network by re-enabling it
            runShellStatus("cmd", "wifi", "connect-network", ssid)
            return
        }

        // If password is provided or network is not saved, add/update suggestion
        if (password.isNullOrEmpty()) {
            runShellStatus("cmd", "wifi", "add-suggestion", ssid, "open")
        } else {
            runShellStatus("cmd", "wifi", "add-suggestion", ssid, "wpa2", password)
        }

        // Auto-approve suggestions from shell to trigger immediate connection
        runShellStatus("cmd", "wifi", "network-suggestions-set-user-approved", "com.android.shell", "yes")
        Log.d(TAG, "Network suggestion added/updated and approved for $ssid")
    }

    override fun getWifiScanResults(): String {
        Log.d(TAG, "Getting WiFi scan results via shell")
        val output = runShellCommand("cmd", "wifi", "list-scan-results")
        return gson.toJson(output ?: emptyList<String>())
    }

    override fun startWifiScan() {
        Log.d(TAG, "Starting WiFi scan via shell")
        runShellCommand("cmd", "wifi", "start-scan")
    }

    override fun setMobileDataEnabled(enabled: Boolean) {
        Log.d(TAG, "Setting mobile data enabled: $enabled")
        try {
            runShellCommand("svc", "data", if (enabled) "enable" else "disable")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set mobile data: ${e.message}")
        }
    }

    override fun enableBluetoothPan(enabled: Boolean) {
        // PERMANENTLY DISABLED: Bluetooth PAN tethering causes system crashes on this Android version
        // The ITetheringConnector API requires complex AIDL callback implementation that crashes
        // the network stack even with dynamic proxy. This is a limitation of the Android platform.
        // Users must manually enable Bluetooth tethering via: Settings > Network > Hotspot & tethering
        Log.w(TAG, "Auto Bluetooth PAN disabled - must be enabled manually in system settings")
    }
}
