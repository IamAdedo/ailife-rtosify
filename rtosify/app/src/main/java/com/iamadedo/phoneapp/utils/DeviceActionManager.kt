package com.iamadedo.phoneapp.utils

import android.app.KeyguardManager
import android.app.NotificationChannel
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
import com.iamadedo.phoneapp.IUserService
import com.iamadedo.phoneapp.RtosifyAccessibilityService
import com.topjohnwu.superuser.Shell

class DeviceActionManager(
    private val context: Context, 
    private var userService: IUserService?,
    private val shizukuBinder: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "DeviceActionManager"
    }

    private val handler = Handler(Looper.getMainLooper())

    fun updateUserService(service: IUserService?) {
        this.userService = service
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

    fun wakeScreen(onWake: () -> Unit) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (!powerManager.isInteractive) {
            Log.d(TAG, "Waking up screen...")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "rtosify:DeviceActionWakeLock"
            )
            wakeLock.acquire(3000)
            wakeLock.release()
        }

        // On some devices we might need to dismiss keyguard if we want to click something
        // but for system dialogs it's often not needed.
        
        handler.postDelayed({ onWake() }, 500)
    }

    fun reboot() {
        Log.i(TAG, "Requesting reboot")
        if (tryUserService { it.reboot() }) return
        if (tryRoot("svc power reboot") || tryRoot("reboot")) return
        
        // Root fallback: show power menu and click reboot
        showPowerMenu()
        handler.postDelayed({
            RtosifyAccessibilityService.clickNodesWithKeywords(context, listOf("Restart", "Reboot", "重新启动", "重新開機"))
        }, 1000)
    }

    fun shutdown() {
        Log.i(TAG, "Requesting shutdown")
        if (tryUserService { it.shutdown() }) return
        if (tryRoot("svc power shutdown") || tryRoot("reboot -p")) return
        
        // Root fallback: show power menu and click shutdown
        showPowerMenu()
        handler.postDelayed({
            RtosifyAccessibilityService.clickNodesWithKeywords(context, listOf("Power off", "Shutdown", "关机", "關機"))
        }, 1000)
    }

    private fun showPowerMenu() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RtosifyAccessibilityService.performGlobalAction(context, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        }
    }

    fun lockDevice() {
        Log.i(TAG, "Executing Lock Device")
        if (tryRoot("input keyevent 223")) return
        
        // Accessibility fallback (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RtosifyAccessibilityService.performGlobalAction(context, android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    fun setWifiEnabled(enabled: Boolean) {
        Log.i(TAG, "Setting WiFi to $enabled")

        // Legacy direct toggle if possible (< Q)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                if (wifiManager.setWifiEnabled(enabled)) {
                    Log.i(TAG, "Successfully toggled WiFi via WifiManager")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle WiFi via WifiManager: ${e.message}")
            }
        }
        if (tryUserService { it.setWifiEnabled(enabled) }) return
        if (tryRoot("svc wifi ${if (enabled) "enable" else "disable"}")) return

        wakeScreen {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            handler.postDelayed({
                RtosifyAccessibilityService.clickNodesWithKeywords(context, 
                    listOf("Wi-Fi", "WLAN", "无线网", "开关"), 
                    toggleOnly = true,
                    targetState = enabled
                )
            }, 1500)
        }
    }

    fun setMobileDataEnabled(enabled: Boolean) {
        Log.i(TAG, "Setting Mobile Data to $enabled")
        if (tryUserService { it.setMobileDataEnabled(enabled) }) return
        if (tryRoot("svc data ${if (enabled) "enable" else "disable"}")) return

        wakeScreen {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            } else {
                Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            handler.postDelayed({
                RtosifyAccessibilityService.clickNodesWithKeywords(context, 
                    listOf("Mobile data", "移动数据", "流量", "数据"), 
                    toggleOnly = true,
                    targetState = enabled
                )
            }, 1500)
        }
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        Log.i(TAG, "Setting Bluetooth to $enabled")        

        // 1. Direct toggle attempt (Legacy fallback)
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
                Log.e(TAG, "Failed to start bluetooth request intent: ${e.message}")
            }
        }
    }

    fun setBluetoothTetheringEnabled(enabled: Boolean) {
        Log.i(TAG, "Setting Bluetooth Tethering to $enabled")
        // No direct UserService method for this yet, or we use executeCommand
        
        wakeScreen {
            val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent2 = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent2)
            }

            handler.postDelayed({
                RtosifyAccessibilityService.clickNodesWithKeywords(context, listOf("Bluetooth tethering", "蓝牙网络共享", "蓝牙共享"))
            }, 2000)
        }
    }

    fun installApk(apkPath: String, fileUri: Uri) {
        Log.i(TAG, "Installing APK: $apkPath")
        if (tryUserService { it.installApp(apkPath) }) return
        if (tryRoot("pm install -r \"$apkPath\"")) return

        // Fallback to standard installer
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = fileUri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)

        // Accessibility to click "Install" or "Update"
        handler.postDelayed({
            RtosifyAccessibilityService.clickNodesWithKeywords(context, listOf("Install", "Update", "安装", "更新"))
        }, 1000)
    }

    fun uninstallApk(packageName: String) {
        Log.i(TAG, "Uninstalling APK: $packageName")
        if (tryUserService { it.uninstallApp(packageName) }) return
        if (tryRoot("pm uninstall \"$packageName\"")) return

        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)

        handler.postDelayed({
            RtosifyAccessibilityService.clickNodesWithKeywords(context, listOf("OK", "Uninstall", "确定", "卸载"))
        }, 1000)
    }
    
    fun automateMirroringAllow() {
        Log.i(TAG, "Automating mirroring allow (Step 1: Open Spinner)")
        // Step 1: Open spinner /drop-down (if it exists and shows 'Single app')
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
}
