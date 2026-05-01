package com.iamadedo.watchapp.utils

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.iamadedo.watchapp.IUserService
import com.iamadedo.watchapp.RtosifyAccessibilityService
import com.topjohnwu.superuser.Shell

/**
 * Manages device-level actions and automations for the Watch (companion).
 * Priority: Shizuku (UserService) -> AccessibilityService (UI Automation) -> Manual.
 */
class DeviceActionManager(
    private val context: Context,
    private var userService: IUserService? = null,
    private val shizukuBinder: (() -> Unit)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    companion object {
        private const val TAG = "DeviceActionManager"
    }

    fun updateUserService(newService: IUserService?) {
        this.userService = newService
    }

    private fun tryUserService(action: (IUserService) -> Unit): Boolean {
        if (userService == null) {
            Log.d(TAG, "UserService null, triggering binder...")
            shizukuBinder?.invoke()
        }
        return try {
            userService?.let {
                action(it)
                true
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "UserService action failed: ${e.message}")
            false
        }
    }

    private fun tryRoot(command: String): Boolean {
        return if (Shell.getShell().isRoot) {
            val result = Shell.cmd(command).exec()
            result.isSuccess
        } else false
    }

    fun shutdown() {
        Log.i(TAG, "Requesting shutdown")
        if (tryUserService { it.shutdown() }) return
        if (tryRoot("svc power shutdown") || tryRoot("reboot -p")) return
        
        // Root fallback: show power menu and click shutdown
        showPowerMenu()
        handler.postDelayed({
            RtosifyAccessibilityService.clickNodesWithKeywords(context, listOf("Power off", "Shutdown", "关机", "關機"))
            // Secondary confirmation (for some devices)
            handler.postDelayed({
                RtosifyAccessibilityService.clickNodesWithKeywords(context, listOf("Power off", "Shutdown", "OK", "Confirm", "确定", "確定", "关机", "關機"))
            }, 1000)
        }, 1200)
    }

    /**
     * Reboots the device.
     */
    fun reboot() {
        Log.i(TAG, "Requesting reboot")
        if (tryUserService { it.reboot() }) return
        if (tryRoot("svc power reboot") || tryRoot("reboot")) return
        
        // Root fallback: show power menu and click reboot
        showPowerMenu()
        handler.postDelayed({
            RtosifyAccessibilityService.clickNodesWithKeywords(context, listOf("Restart", "Reboot", "重新启动", "重启", "重新開機"))
            // Secondary confirmation (for some devices)
            handler.postDelayed({
                RtosifyAccessibilityService.clickNodesWithKeywords(context, listOf("Restart", "Reboot", "OK", "Confirm", "确定", "確定", "重新启动", "重新開機", "重启"))
            }, 1000)
        }, 1200)
    }

    private fun showPowerMenu() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RtosifyAccessibilityService.performGlobalAction(context, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        }
    }

    fun lockDevice() {
        Log.i(TAG, "Attempting lock device...")
        if (tryRoot("input keyevent 223")) return
        
        // Accessibility fallback (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RtosifyAccessibilityService.performGlobalAction(context, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    /**
     * Enables or disables WiFi.
     */
    fun setWifiEnabled(enabled: Boolean) {
        Log.i(TAG, "Setting WiFi enabled: $enabled")

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled == enabled) {
            Log.i(TAG, "WiFi already in desired state: $enabled")
            return
        }

        // 1. Legacy direct toggle if possible (< Q)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                @Suppress("DEPRECATION")
                if (wifiManager.setWifiEnabled(enabled)) {
                    Log.i(TAG, "Successfully toggled WiFi via WifiManager")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle WiFi via WifiManager: ${e.message}")
            }
        }
 
        // 2. Shizuku
        if (tryUserService { it.setWifiEnabled(enabled) }) return

        // 3. Root
        if (tryRoot("svc wifi ${if (enabled) "enable" else "disable"}")) return

        // 4. Fallback: Open settings and toggle
        wakeScreen {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            handler.postDelayed({
                RtosifyAccessibilityService.clickNodesWithKeywords(context, 
                    listOf("Wi-Fi", "WLAN", "无线网络", "开关"),
                    targetState = enabled
                )
            }, 2000)
        }
    }

    fun setMobileDataEnabled(enabled: Boolean) {
        Log.i(TAG, "Setting Mobile Data enabled: $enabled")
        if (tryUserService { it.setMobileDataEnabled(enabled) }) return
        if (tryRoot("svc data ${if (enabled) "enable" else "disable"}")) return

        // Fallback: Open cellular settings
        wakeScreen {
            val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            handler.postDelayed({
                RtosifyAccessibilityService.clickNodesWithKeywords(context, 
                    listOf("Mobile data", "Cellular data", "移动数据", "流量", "数据"),
                    targetState = enabled
                )
            }, 2000)
        }
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        Log.i(TAG, "Setting Bluetooth enabled: $enabled")
        
        // 1. Direct toggle attempt (Legacy fallback, likely fails on Android 13+)
        try {
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            if (btAdapter != null) {
                val success = if (enabled) {
                    @Suppress("DEPRECATION")
                    btAdapter.enable()
                } else {
                    @Suppress("DEPRECATION")
                    btAdapter.disable()
                }
                if (success) {
                    Log.i(TAG, "Successfully toggled Bluetooth via adapter")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth via adapter: ${e.message}")
        }

        // 2. Shizuku
        if (tryUserService { it.setBluetoothEnabled(enabled) }) return

        // 3. Root
        if (tryRoot("cmd bluetooth ${if (enabled) "enable" else "disable"}") || 
            tryRoot("service call bluetooth_manager ${if (enabled) "6" else "8"}")) return

        // 4. Request Dialog via Intent (Always use dialog, no panel)
        val action = if (enabled) {
            BluetoothAdapter.ACTION_REQUEST_ENABLE
        } else {
            "android.bluetooth.adapter.action.REQUEST_DISABLE"
        }
        
        wakeScreen {
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                Log.i(TAG, "Starting Bluetooth request dialog ($action)...")
                context.startActivity(intent)
                handler.postDelayed({
                    RtosifyAccessibilityService.clickNodesWithKeywords(context, 
                        listOf("Allow", "允许", "Start", "开始", "Turn on", "开启", "Enable", "启用")
                    )
                }, 1500)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start bluetooth request: ${e.message}")
            }
        }
    }

    /**
     * Enables Bluetooth Internet (client side).
     * This automates clicking "Internet access" in Bluetooth device details.
     */
    fun setBluetoothInternetEnabled(deviceName: String?) {
        Log.i(TAG, "Automating Bluetooth Internet for device: $deviceName")
        wakeScreen {
            RtosifyAccessibilityService.enableBluetoothTethering(deviceName)
        }
    }

    /**
     * Installs an APK silently if possible, otherwise via dialog.
     */
    fun installApk(path: String, fileUri: Uri) {
        Log.i(TAG, "Installing APK: $path")
        
        if (tryUserService { it.installApp(path) }) return
        if (tryRoot("pm install -r \"$path\"")) return

        // Manual install fallback
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(fileUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        
        // Automate "Install" click
        handler.postDelayed({
            RtosifyAccessibilityService.clickNodesWithKeywords(context, 
                listOf("Install", "Update", "Done", "安装", "更新", "完成")
            )
        }, 2000)
    }

    /**
     * Uninstalls a package.
     */
    fun uninstallApp(packageName: String) {
        Log.i(TAG, "Uninstalling app: $packageName")
        if (tryUserService { it.uninstallApp(packageName) }) return
        if (tryRoot("pm uninstall \"$packageName\"")) return

        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        
        // Automate "OK" click
        handler.postDelayed({
            RtosifyAccessibilityService.clickNodesWithKeywords(context, 
                listOf("OK", "Uninstall", "确定", "卸载")
            )
        }, 2000)
    }

    fun automateMirroringAllow() {
        Log.i(TAG, "Automating mirroring allow (Step 1: Open Spinner)")
        // Step 1: Open spinner
        RtosifyAccessibilityService.clickNodesWithKeywords(context, 
            listOf("Single app", "单个项目", "Cast mode")
        )
        
        handler.postDelayed({
            Log.i(TAG, "Automating mirroring allow (Step 2: Select Entire Screen)")
            // Step 2: Select "Entire screen"
            RtosifyAccessibilityService.clickNodesWithKeywords(context, 
                listOf("Entire screen", "整个屏幕")
            )
            
            handler.postDelayed({
                Log.i(TAG, "Automating mirroring allow (Step 3: Click Start Now)")
                // Step 3: Click "Start now"
                RtosifyAccessibilityService.clickNodesWithKeywords(context, 
                    listOf("Start now", "立即开始", "Allow", "允许", "Start", "开始")
                )
            }, 1000)
        }, 1000)
    }

    /**
     * Utility to wake the screen before actions.
     */
    @SuppressLint("InvalidWakeLockTag")
    fun wakeScreen(onWake: () -> Unit) {
        if (!powerManager.isInteractive) {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "rtosify:ActionWakeLock"
            )
            wakeLock.acquire(3000)
            handler.postDelayed({
                if (wakeLock.isHeld) wakeLock.release()
            }, 3000)
            Log.d(TAG, "Screen woken up")
        }
        handler.postDelayed({ onWake() }, 500)
    }
}
