package com.ailife.rtosifycompanion

import android.util.Log

class UserService : IUserService.Stub() {
    companion object {
        private const val TAG = "UserService"
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
}
