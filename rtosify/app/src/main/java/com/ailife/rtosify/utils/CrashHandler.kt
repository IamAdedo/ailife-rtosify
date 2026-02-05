package com.ailife.rtosify.utils

import android.content.Context
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
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "crash_log_$timestamp.txt"
            
            // Get the external files directory
            val logDir = context.getExternalFilesDir(null)
            if (logDir == null) {
                Log.e("CrashHandler", "External files dir is null")
                return
            }

            val logFile = File(logDir, fileName)
            
            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            e.printStackTrace(printWriter)
            val stackTrace = stringWriter.toString()
            printWriter.close()

            FileOutputStream(logFile).use { fos ->
                fos.write(stackTrace.toByteArray())
            }
            
            Log.d("CrashHandler", "Crash log saved to ${logFile.absolutePath}")

        } catch (ex: Exception) {
            Log.e("CrashHandler", "Error saving crash log", ex)
        }
    }
}
