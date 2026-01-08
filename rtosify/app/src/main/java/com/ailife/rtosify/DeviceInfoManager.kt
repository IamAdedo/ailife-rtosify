package com.ailife.rtosify

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import java.io.RandomAccessFile
import java.util.regex.Pattern

class DeviceInfoManager(private val context: Context) {

    data class DeviceInfo(
        val model: String,
        val androidVersion: String,
        val ramUsage: String,
        val storageUsage: String,
        val processor: String,
        val cpuUsage: Int,
        val btRssi: Int? = null
    )

    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            model = Build.MODEL,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            ramUsage = getRamUsage(),
            storageUsage = getStorageUsage(),
            processor = getProcessorName(),
            cpuUsage = getCpuUsage()
        )
    }

    private fun getRamUsage(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        
        val totalMsg = Formatter.formatShortFileSize(context, mi.totalMem)
        val availableMsg = Formatter.formatShortFileSize(context, mi.availMem)
        val used = mi.totalMem - mi.availMem
        val usedMsg = Formatter.formatShortFileSize(context, used)
        
        return "$usedMsg / $totalMsg"
    }

    private fun getStorageUsage(): String {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        
        val total = totalBlocks * blockSize
        val available = availableBlocks * blockSize
        val used = total - available
        
        val totalMsg = Formatter.formatShortFileSize(context, total)
        val usedMsg = Formatter.formatShortFileSize(context, used)
        
        return "$usedMsg / $totalMsg"
    }

    private fun getProcessorName(): String {
        // Build.HARDWARE is often generic, let's try reading /proc/cpuinfo for Hardware line
        try {
            val raf = RandomAccessFile("/proc/cpuinfo", "r")
            var line: String?
            while (raf.readLine().also { line = it } != null) {
                if (line?.startsWith("Hardware", ignoreCase = true) == true) {
                    val parts = line!!.split(":")
                    if (parts.size > 1) return parts[1].trim()
                }
            }
        raf.close()
        } catch (e: Exception) {}
        
        return Build.HARDWARE // Fallback
    }

    private var lastTotal: Long = 0
    private var lastIdle: Long = 0

    fun getCpuUsage(): Int {
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            val toks = line.split(Pattern.compile("\\s+"))
            
            val idle = toks[4].toLong()
            val cpu = toks[1].toLong() + toks[2].toLong() + toks[3].toLong() +
                      toks[6].toLong() + toks[7].toLong() + toks[8].toLong()
            
            val total = idle + cpu
            
            val diffTotal = total - lastTotal
            val diffIdle = idle - lastIdle
            
            lastTotal = total
            lastIdle = idle
            
            reader.close()
            
            return if (diffTotal == 0L) 0 else ((diffTotal - diffIdle) * 100 / diffTotal).toInt()
        } catch (e: Exception) {
            return 0
        }
    }
}
