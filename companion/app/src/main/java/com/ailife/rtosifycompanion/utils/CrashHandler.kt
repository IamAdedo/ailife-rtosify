package com.ailife.rtosifycompanion.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    fun init() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        handleException(e)
        defaultHandler?.uncaughtException(t, e)
    }

    private fun handleException(e: Throwable) {
        try {
            val date = Date()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
            val fileTimestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(date)
            val fileName = "crash_log_$fileTimestamp.txt"

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val versionName = packageInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            
            // Get the external files directory
            val logDir = context.getExternalFilesDir(null)
            if (logDir == null) {
                Log.e("CrashHandler", "External files dir is null")
                return
            }

            val logFile = File(logDir, fileName)
            
            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            
            printWriter.println("App Version: $versionName ($versionCode)")
            printWriter.println("Crash Time: $timestamp")
            printWriter.println("---------- Stack Trace ----------")
            e.printStackTrace(printWriter)
            val logContent = stringWriter.toString()
            printWriter.close()

            FileOutputStream(logFile).use { fos ->
                fos.write(logContent.toByteArray())
            }
            
            Log.d("CrashHandler", "Crash log saved to ${logFile.absolutePath}")

        } catch (ex: Exception) {
            Log.e("CrashHandler", "Error saving crash log", ex)
        }
    }
}
