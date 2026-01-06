package com.ailife.rtosifycompanion

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service for background clipboard access and Bluetooth tethering automation.
 */
class ClipboardAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ClipboardAccessibilityService"
        private var instance: ClipboardAccessibilityService? = null
        
        fun enableBluetoothTethering(deviceName: String?) {
            instance?.performBluetoothTetheringEnable(deviceName)
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
    
    private fun performBluetoothTetheringEnable(deviceName: String?) {
        try {
            Log.d(TAG, "Attempting to enable Internet Access via accessibility for device: $deviceName")
            
            // Wait for phone to enable tethering first (as requested by user)
            Handler(Looper.getMainLooper()).postDelayed({
                startBluetoothSettingsAndToggle(deviceName)
            }, 3000)
            
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
                    val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, if (clicked) "✅ Successfully clicked toggle" else "❌ Failed to click toggle")
                    
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
