package com.ailife.rtosify

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service for background clipboard access and Bluetooth tethering automation (Phone).
 */
class ClipboardAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "ClipboardAccessibilityService"
        private var instance: ClipboardAccessibilityService? = null
        
        fun enableBluetoothTethering() {
            instance?.performBluetoothTetheringEnable()
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
    
    private fun performBluetoothTetheringEnable() {
        try {
            Log.d(TAG, "Attempting to enable Bluetooth Tethering (Phone hotspot)")
            
            val rootNode = rootInActiveWindow
            val pkg = rootNode?.packageName?.toString() ?: ""
            
            // On phone, we want "Bluetooth tethering" in Hotspot settings
            if (pkg.contains("com.android.settings")) {
                Log.d(TAG, "Settings open, searching for BT tethering toggle...")
                findAndClickTetheringToggle(listOf("Bluetooth tethering", "Tethering", "Hotspot"))
                return
            }

            // Open Tethering settings
            Log.d(TAG, "Opening tethering settings")
            val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent("android.settings.TETHER_SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            }
            
            Handler(Looper.getMainLooper()).postDelayed({
                findAndClickTetheringToggle(listOf("Bluetooth tethering", "Portable hotspot", "Tethering", "Hotspot"))
            }, 2500)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed: ${e.message}")
        }
    }
    
    private fun findAndClickTetheringToggle(keywords: List<String>) {
        try {
            val rootNode = rootInActiveWindow ?: return
            
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
                    Log.i(TAG, if (clicked) "✅ Clicked BT tether toggle" else "❌ Click failed")
                    
                    if (clicked) {
                        // Controlled back navigation: only if inside settings
                        Handler(Looper.getMainLooper()).postDelayed({
                            performControlledBack()
                        }, 1000)
                    }
                } else {
                    Log.w(TAG, "Target found but no clickable switch detected")
                    logNodeHierarchy(rootNode, 0)
                }
            } else {
                Log.w(TAG, "Keywords $keywords not found")
                logNodeHierarchy(rootNode, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    private fun performControlledBack() {
        val currentRoot = rootInActiveWindow
        val pkg = currentRoot?.packageName?.toString() ?: ""
        if (pkg.contains("com.android.settings")) {
            Log.d(TAG, "Still in settings ($pkg), pressing back")
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            Log.d(TAG, "Already exited settings ($pkg), stopping back navigation")
        }
    }

    private fun logNodeHierarchy(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 10) return
        val indent = " ".repeat(depth * 2)
        val text = node.text?.toString() ?: "null"
        val desc = node.contentDescription?.toString() ?: "null"
        Log.d(TAG, "${indent}Node: text=$text, desc=$desc, class=${node.className}, clickable=${node.isClickable}")
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                logNodeHierarchy(child, depth + 1)
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

    private fun findSwitchInChildren(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isActuallySwitch(child)) return child
            val result = findSwitchInChildren(child)
            if (result != null) return result
        }
        return null
    }
}
