package com.ailife.rtosifycompanion

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.ClipboardManager
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.IntentFilter

/**
 * Accessibility service for background clipboard access and Bluetooth tethering automation.
 */
class ClipboardAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ClipboardAccessibilityService"
        const val ACTION_CLIPBOARD_CHANGED = "com.ailife.rtosifycompanion.ACTION_CLIPBOARD_CHANGED"
        const val ACTION_START_MONITORING = "com.ailife.rtosifycompanion.ACTION_START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.ailife.rtosifycompanion.ACTION_STOP_MONITORING"
        const val ACTION_SET_CLIPBOARD = "com.ailife.rtosifycompanion.ACTION_SET_CLIPBOARD"
        const val EXTRA_TEXT = "extra_text"

        private var instance: ClipboardAccessibilityService? = null
        
        fun enableBluetoothTethering(deviceName: String?) {
            instance?.performBluetoothTetheringEnable(deviceName)
        }
    }

    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastClipboardText: String? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_MONITORING -> startClipboardMonitoring()
                ACTION_STOP_MONITORING -> stopClipboardMonitoring()
                ACTION_SET_CLIPBOARD -> {
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                    performSetClipboard(text)
                }
            }
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        
        val filter = IntentFilter().apply {
            addAction(ACTION_START_MONITORING)
            addAction(ACTION_STOP_MONITORING)
            addAction(ACTION_SET_CLIPBOARD)
        }
        registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        
        Log.d(TAG, "Accessibility service connected")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        stopClipboardMonitoring()
        instance = null
    }

    private fun startClipboardMonitoring() {
        if (clipboardListener != null) return
        Log.d(TAG, "Starting clipboard monitoring")

        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (text != null && text != lastClipboardText) {
                    lastClipboardText = text
                    Log.d(TAG, "Clipboard text changed: $text")
                    val intent = Intent(ACTION_CLIPBOARD_CHANGED).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_TEXT, text)
                    }
                    sendBroadcast(intent)
                }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
    }

    private fun stopClipboardMonitoring() {
        Log.d(TAG, "Stopping clipboard monitoring")
        clipboardListener?.let {
            clipboardManager?.removePrimaryClipChangedListener(it)
        }
        clipboardListener = null
    }

    private fun performSetClipboard(text: String) {
        if (text == lastClipboardText) return
        lastClipboardText = text
        Log.d(TAG, "Writing text to clipboard: $text")
        val clip = ClipData.newPlainText("RTOSify", text)
        clipboardManager?.setPrimaryClip(clip)
    }
    
    private fun performBluetoothTetheringEnable(deviceName: String?) {
        try {
            Log.d(TAG, "Attempting to enable Internet Access via accessibility for device: $deviceName")

            // Check if screen is on, wake it up if needed
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isInteractive) {
                Log.d(TAG, "Screen is off, waking up...")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "rtosifycompanion:BtInternetWakeLock"
                )
                wakeLock.acquire(8000) // 8 seconds

                // Wait for screen to wake up, then wait for phone tethering
                Handler(Looper.getMainLooper()).postDelayed({
                    wakeLock.release()
                    // Wait for phone to enable tethering first
                    Handler(Looper.getMainLooper()).postDelayed({
                        startBluetoothSettingsAndToggle(deviceName)
                    }, 3000)
                }, 1000)
            } else {
                // Wait for phone to enable tethering first
                Handler(Looper.getMainLooper()).postDelayed({
                    startBluetoothSettingsAndToggle(deviceName)
                }, 3000)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate automation: ${e.message}")
        }
    }

    private fun startBluetoothSettingsAndToggle(deviceName: String?) {
        val rootNode = rootInActiveWindow
        val pkg = rootNode?.packageName?.toString() ?: ""
        
        if (!pkg.contains("com.android.settings")) {
            Log.d(TAG, "Opening Bluetooth settings")
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        // Wait for settings to open or settle
        Handler(Looper.getMainLooper()).postDelayed({
            val currentRoot = rootInActiveWindow
            if (currentRoot == null) {
                Log.e(TAG, "Root node still null after opening settings")
                return@postDelayed
            }

            if (deviceName != null) {
                // First find and click the device name to enter details
                Log.d(TAG, "Searching for device: $deviceName")
                val deviceNode = findNodeByText(currentRoot, deviceName)
                if (deviceNode != null) {
                    val clickableDevice = findClickableOrSwitch(deviceNode)
                    if (clickableDevice != null) {
                        Log.d(TAG, "Clicking device to enter details page")
                        val clicked = clickableDevice.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            // Wait for details page to open
                            Handler(Looper.getMainLooper()).postDelayed({
                                findAndClickTetheringToggle(listOf("Internet access", "Internet", "Bluetooth tethering"), true)
                            }, 1500)
                            return@postDelayed
                        } else {
                            Log.w(TAG, "Failed to click device node")
                        }
                    } else {
                        Log.w(TAG, "Device node found but not clickable")
                    }
                } else {
                    Log.w(TAG, "Device '$deviceName' not found in list, falling back to direct toggle search")
                }
            }
            
            // Fallback: search for toggle in the current view
            findAndClickTetheringToggle(listOf("Internet access", "Internet", "Bluetooth tethering"), false)
        }, 2000)
    }
    
    private fun findAndClickTetheringToggle(keywords: List<String>, isDetailsPage: Boolean) {
        try {
            val rootNode = rootInActiveWindow ?: run {
                Log.e(TAG, "No root node available")
                return
            }

            var targetNode: AccessibilityNodeInfo? = null
            for (keyword in keywords) {
                targetNode = findNodeByText(rootNode, keyword)
                if (targetNode != null) {
                    Log.d(TAG, "Found target node with keyword: $keyword")
                    break
                }
            }

            if (targetNode != null) {
                val clickableNode = findClickableOrSwitch(targetNode)
                if (clickableNode != null) {
                    // Check current state before toggling
                    val switchNode = findSwitchInHierarchy(targetNode.parent ?: targetNode)
                    val isCurrentlyOn = switchNode?.isChecked ?: false

                    Log.d(TAG, "Internet Access current state: ${if (isCurrentlyOn) "ON" else "OFF"}")

                    if (isCurrentlyOn) {
                        Log.i(TAG, "✅ Internet Access already ON, skipping toggle")
                        // Still navigate back
                        Handler(Looper.getMainLooper()).postDelayed({
                            performControlledBackSequence()
                        }, 500)
                        return
                    }

                    Log.d(TAG, "Internet Access is OFF, toggling ON...")
                    val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, if (clicked) "✅ Successfully toggled ON" else "❌ Failed to toggle")

                    if (clicked) {
                        // Controlled back navigation
                        Handler(Looper.getMainLooper()).postDelayed({
                            performControlledBackSequence()
                        }, 1000)
                    }
                } else {
                    Log.w(TAG, "Target text found but no clickable switch/row found nearby")
                    logNodeHeirarchy(rootNode, 0)
                }
            } else {
                Log.w(TAG, "Could not find any of $keywords on screen")
                logNodeHeirarchy(rootNode, 0)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in findAndClickTetheringToggle: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun findClickableOrSwitch(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. Target the clickable row/container first
        var clickableRow = node
        while (clickableRow.parent != null && !clickableRow.isClickable) {
            clickableRow = clickableRow.parent ?: break
        }
        
        // 2. Look for a switch inside this row
        val internalSwitch = findSwitchInHierarchy(clickableRow)
        
        // 3. If switch exists and is clickable, use it
        if (internalSwitch != null && internalSwitch.isClickable) {
            return internalSwitch
        }
        
        // 4. Fallback: Click the row itself (this usually toggles the internal switch)
        if (clickableRow.isClickable) {
            Log.d(TAG, "Using clickable row as toggle trigger")
            return clickableRow
        }
        
        return if (node.isClickable) node else null
    }

    private fun findSwitchInHierarchy(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isActuallySwitch(node)) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            val found = findSwitchInHierarchy(child)
            if (found != null) return found
        }
        return null
    }

    private fun isActuallySwitch(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        return className.contains("Switch", ignoreCase = true) || 
               className.contains("CheckBox", ignoreCase = true) || 
               className.contains("ToggleButton", ignoreCase = true) || 
               node.isCheckable
    }

    private fun performControlledBackSequence() {
        // Back 1
        if (isInsideSettings()) {
            Log.d(TAG, "Navigating back (1/2)")
            performGlobalAction(GLOBAL_ACTION_BACK)
            
            // Back 2 (if still in settings)
            Handler(Looper.getMainLooper()).postDelayed({
                if (isInsideSettings()) {
                    Log.d(TAG, "Navigating back (2/2)")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                } else {
                    Log.d(TAG, "Already exited settings after first back, stopping.")
                }
            }, 1000)
        } else {
            Log.d(TAG, "Already exited settings, skipping back navigation.")
        }
    }

    private fun isInsideSettings(): Boolean {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        return pkg.contains("com.android.settings")
    }
    
    private fun logNodeHeirarchy(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 10) return
        val indent = " ".repeat(depth * 2)
        val text = node.text?.toString() ?: "null"
        val desc = node.contentDescription?.toString() ?: "null"
        Log.d(TAG, "${indent}Node: text=$text, desc=$desc, class=${node.className}, clickable=${node.isClickable}")
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                logNodeHeirarchy(child, depth + 1)
            }
        }
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        
        try {
            val nodeText = node.text?.toString()
            val nodeDesc = node.contentDescription?.toString()
            
            if (nodeText?.contains(text, ignoreCase = true) == true ||
                nodeDesc?.contains(text, ignoreCase = true) == true) {
                return node
            }
        } catch (e: Exception) {
            // Ignore property access errors
        }
        
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                val found = findNodeByText(child, text)
                if (found != null) return found
            }
        }
        
        return null
    }
    
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        repeat(5) { // Search up to 5 levels up
            if (current.isClickable) return current
            current = current.parent ?: return null
        }
        return null
    }
}
