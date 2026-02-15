package com.ailife.rtosify.utils

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import com.ailife.rtosify.R

/**
 * OtaManager - Handles OTA updates for both Phone (Rtosify) and Watch (Companion).
 *
 * Features:
 * - GitLab Release API integration
 * - Downloads Phone APK and Watch APK
 * - Installs Phone APK locally
 * - Sends Watch APK to watch via BluetoothService
 */
class OtaManager(private val context: Context) {

    companion object {
        // GitLab Configuration
        private const val GITLAB_OWNER = "ailife8881"
        private const val GITLAB_REPO = "rtosify"
        private const val GITLAB_API_BASE = "https://gitlab.com/api/v4"
        private val GITLAB_TOKEN: String? = null // Set token if repo is private

        // File Names
        // Adjust patterns if naming conventions are reversed
        private const val APK_PHONE_PATTERN = ".*rtosify.*\\.apk$" 
        private const val APK_WATCH_PATTERN = ".*companion.*\\.apk$" 
        private const val DOWNLOAD_FILE_PHONE = "rtosify-update.apk"
        private const val DOWNLOAD_FILE_WATCH = "companion-update.apk"

        // Preferences
        private const val PREFS_NAME = "OtaManagerPrefs"
        private const val PREF_UPDATE_ENABLED = "update_check_enabled"

        private const val TAG = "OtaManager"
        private const val MIME_TYPE = "application/vnd.android.package-archive"
        private const val PROVIDER_PATH = ".provider"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestVersion: String? = null
    private var changelog: String? = null
    private var downloadUrlPhone: String? = null
    private var downloadUrlWatch: String? = null

    /**
     * Check if update checking is enabled
     */
    fun isUpdateCheckEnabled(): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_UPDATE_ENABLED, true)
    }

    /**
     * Enable or disable update checking
     */
    fun setUpdateCheckEnabled(enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_UPDATE_ENABLED, enabled)
            .apply()
        Log.d(TAG, "Update check " + if (enabled) "enabled" else "disabled")
    }

    /**
     * Check for updates
     * @param silent If true, suppresses toasts and "Up to date" messages (for auto-check)
     */
    fun checkUpdate(silent: Boolean = false) {
        if (silent && !isUpdateCheckEnabled()) {
            Log.d(TAG, "Update check disabled by user")
            return
        }

        // Check network
        if (!isNetworkAvailable()) {
            if (!silent) showToast(context.getString(R.string.ota_no_internet))
            return
        }

        val projectPath = "$GITLAB_OWNER/$GITLAB_REPO".replace("/", "%2F")
        val apiUrl = "$GITLAB_API_BASE/projects/$projectPath/releases/permalink/latest"

        Log.d(TAG, "Checking for updates at: $apiUrl")

        thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(apiUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (!GITLAB_TOKEN.isNullOrEmpty()) {
                    connection.setRequestProperty("PRIVATE-TOKEN", GITLAB_TOKEN)
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "API request failed with code: $responseCode")
                    if (!silent) showToast(context.getString(R.string.ota_check_failed_server, responseCode))
                    return@thread
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                parseReleaseInfo(response.toString(), silent)

            } catch (e: Exception) {
                Log.e(TAG, "API request failed: ${e.message}")
                if (!silent) showToast(context.getString(R.string.ota_check_failed_generic, e.message))
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun parseReleaseInfo(jsonResponse: String, silent: Boolean) {
        try {
            val release = JSONObject(jsonResponse)
            var version = release.getString("tag_name")
            if (version.startsWith("v", ignoreCase = true)) {
                version = version.substring(1)
            }
            latestVersion = version

            changelog = release.optString("description", context.getString(R.string.ota_no_changelog))
            changelog = cleanMarkdown(changelog ?: context.getString(R.string.ota_no_changelog))

            val assets = release.optJSONObject("assets")
            val links = assets?.optJSONArray("links")

            if (links != null) {
                for (i in 0 until links.length()) {
                    val link = links.getJSONObject(i)
                    val name = link.optString("name", "")
                    val url = link.optString("url", "")

                    if (name.matches(Regex(APK_PHONE_PATTERN))) {
                        downloadUrlPhone = url
                    }
                    if (name.matches(Regex(APK_WATCH_PATTERN))) {
                        downloadUrlWatch = url
                    }
                }
            }

            if (downloadUrlPhone == null && downloadUrlWatch == null) {
                Log.e(TAG, "No APKs found in release")
                if (!silent) showToast(context.getString(R.string.ota_no_artifacts))
                return
            }

            compareAndPromptUpdate(silent)

        } catch (e: JSONException) {
            Log.e(TAG, "JSON parsing error: ${e.message}")
            if (!silent) showToast(context.getString(R.string.ota_parse_error))
        }
    }

    private fun compareAndPromptUpdate(silent: Boolean) {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersion = pInfo.versionName

            Log.d(TAG, "Current: $currentVersion, Latest: $latestVersion")

            val comparison = compareVersions(currentVersion ?: "0.0.0", latestVersion ?: "0.0.0")

            mainHandler.post {
                if (comparison < 0) {
                    showUpdateDialog()
                } else {
                    if (!silent) showToast(context.getString(R.string.ota_up_to_date, currentVersion))
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get package info: ${e.message}")
        }
    }

    private fun showUpdateDialog() {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.ota_update_available_title, latestVersion))
            .setMessage(changelog)
            .setCancelable(true)
            .setPositiveButton(context.getString(R.string.ota_btn_update)) { _, _ ->
                initiateDownloads()
            }
            .setNeutralButton(context.getString(R.string.ota_btn_later)) { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton(context.getString(R.string.ota_btn_dont_ask)) { dialog, _ ->
                setUpdateCheckEnabled(false)
                Toast.makeText(context, context.getString(R.string.ota_checks_disabled_toast), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    private fun initiateDownloads() {
        if (downloadUrlPhone != null) {
            downloadFile(downloadUrlPhone!!, DOWNLOAD_FILE_PHONE, context.getString(R.string.ota_downloading_phone_title))
        }
        if (downloadUrlWatch != null) {
            downloadFile(downloadUrlWatch!!, DOWNLOAD_FILE_WATCH, context.getString(R.string.ota_downloading_watch_title))
        }
    }

    private fun downloadFile(url: String, fileName: String, title: String) {
        val destination = File(context.getExternalFilesDir(null), fileName)
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(url))
        request.setMimeType(MIME_TYPE)
        request.setTitle(title)
        request.setDescription("v$latestVersion")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalFilesDir(context, null, fileName)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)

        setupDownloadCompleteReceiver(destination.absolutePath, fileName)
        Toast.makeText(context, context.getString(R.string.ota_download_started, title), Toast.LENGTH_SHORT).show()
    }

    private fun setupDownloadCompleteReceiver(path: String, fileName: String) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val file = File(path)
                if (file.exists()) {
                     if (fileName == DOWNLOAD_FILE_PHONE) {
                         installPhoneApk(file)
                     } else if (fileName == DOWNLOAD_FILE_WATCH) {
                         sendToWatch(file)
                     }
                     try {
                         ctx.unregisterReceiver(this)
                     } catch (e: Exception) { /* ignore */ }
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installPhoneApk(file: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, MIME_TYPE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.message}")
            showToast(context.getString(R.string.ota_install_failed, e.message))
        }
    }

    private fun sendToWatch(file: File) {
        val intent = Intent("com.ailife.rtosify.ACTION_SEND_OTA")
        intent.putExtra("file_path", file.absolutePath)
        context.sendBroadcast(intent)
        
        showToast(context.getString(R.string.ota_watch_downloaded_transferring))
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val reg = Regex("[^0-9.]")
        val p1 = v1.replace(reg, "").split(".").map { it.toIntOrNull() ?: 0 }
        val p2 = v2.replace(reg, "").split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val num1 = p1.getOrElse(i) { 0 }
            val num2 = p2.getOrElse(i) { 0 }
            if (num1 < num2) return -1
            if (num1 > num2) return 1
        }
        return 0
    }

    private fun cleanMarkdown(text: String): String {
        return text.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1").replace(Regex("[*#_`]+"), "")
    }
    
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return false
            val actNw = cm.getNetworkCapabilities(nw) ?: return false
            return actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            return cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
}
