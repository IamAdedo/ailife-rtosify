package com.ailife.rtosifycompanion

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import java.io.File
import java.io.RandomAccessFile
import java.util.regex.Pattern

class DeviceInfoManager(private val context: Context) {

    private var lastTotal: Long = 0
    private var lastIdle: Long = 0

    fun getDeviceInfo(userService: IUserService? = null): DeviceInfoData {
        return DeviceInfoData(
                model = Build.MODEL,
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                ramUsage = getRamUsage(),
                storageUsage = getStorageUsage(),
                processor = getProcessorName(),
                cpuUsage = getCpuUsage(userService)
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

        return Build.HARDWARE
    }

    fun getCpuUsage(userService: IUserService? = null): Int {
        // Method 1: Shizuku / Root (Accurate)
        if (userService != null) {
            try {
                val stat = userService.runShellCommandWithOutput("cat /proc/stat")
                if (stat.isNotEmpty()) {
                    val usage = parseCpuUsageFromStat(stat)
                    if (usage >= 0) return usage
                }
            } catch (e: Exception) {
                android.util.Log.e(
                        "DeviceInfoManager",
                        "Error getting CPU via Shizuku: ${e.message}"
                )
            }
        }

        // Method 2: Local /proc/stat (Works on older Android or Root)
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            reader.close()
            if (line != null) {
                val usage = parseCpuUsageFromLine(line)
                if (usage >= 0) return usage
            }
        } catch (e: Exception) {
            // restricted on newer Android
        }

        // Method 3: Core Frequency Heuristic (Permission-less fallback)
        return getCpuUsageFromFreq()
    }

    private fun parseCpuUsageFromStat(stat: String): Int {
        val line = stat.split("\n").firstOrNull { it.startsWith("cpu ") } ?: return -1
        return parseCpuUsageFromLine(line)
    }

    private fun parseCpuUsageFromLine(line: String): Int {
        try {
            val toks = line.trim().split(Pattern.compile("\\s+"))
            if (toks.size < 5) return -1

            val idle = toks[4].toLong()
            val cpu =
                    toks[1].toLong() +
                            toks[2].toLong() +
                            toks[3].toLong() +
                            toks[6].toLong() +
                            toks[7].toLong() +
                            toks[8].toLong()

            val total = idle + cpu
            val diffTotal = total - lastTotal
            val diffIdle = idle - lastIdle

            lastTotal = total
            lastIdle = idle

            return if (diffTotal <= 0L) 0 else ((diffTotal - diffIdle) * 100 / diffTotal).toInt()
        } catch (e: Exception) {
            return -1
        }
    }

    private fun getCpuUsageFromFreq(): Int {
        try {
            var totalPct = 0
            var coreCount = 0

            for (i in 0..31) { // Check up to 32 cores
                val curPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"
                val maxPath = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq"

                try {
                    val curFile = File(curPath)
                    val maxFile = File(maxPath)

                    if (curFile.exists() && maxFile.exists()) {
                        val curFreq = curFile.readText().trim().toLong()
                        val maxFreq = maxFile.readText().trim().toLong()

                        if (maxFreq > 0) {
                            totalPct += (curFreq * 100 / maxFreq).toInt()
                            coreCount++
                        }
                    }
                } catch (e: Exception) {
                    // No more cores or restricted
                    if (i > 0 && !File("/sys/devices/system/cpu/cpu$i").exists()) break
                }
            }

            if (coreCount > 0) {
                return (totalPct / coreCount).coerceIn(0, 100)
            }
        } catch (e: Exception) {}
        return 0
    }
}
