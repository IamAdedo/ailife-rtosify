package com.ailife.rtosify

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service for Bluetooth tethering automation (Phone).
 * Clipboard monitoring has been moved to BluetoothService.
 */
class RtosifyAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "RtosifyAccessibilityService"

        private var instance: RtosifyAccessibilityService? = null

        fun clickNodesWithKeywords(context: Context, keywords: List<String>, toggleOnly: Boolean = false, targetState: Boolean? = null) {
            val intent = Intent("com.ailife.rtosify.CLICK_KEYWORDS").apply {
                putStringArrayListExtra("keywords", ArrayList(keywords))
                putExtra("toggleOnly", toggleOnly)
                if (targetState != null) putExtra("targetState", targetState)
            }
            context.sendBroadcast(intent)
            instance?.performKeywordClick(keywords, toggleOnly, targetState)
        }

        fun performGlobalAction(context: Context, action: Int) {
            val intent = Intent("com.ailife.rtosify.GLOBAL_ACTION").apply {
                putExtra("action", action)
            }
            context.sendBroadcast(intent)
            instance?.performGlobalAction(action)
        }
    }


    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        
        // Register receiver for commands from main process
        val filter = IntentFilter().apply {
            addAction("com.ailife.rtosify.DISPATCH_REMOTE_INPUT")
            addAction("com.ailife.rtosify.CLICK_KEYWORDS")
            addAction("com.ailife.rtosify.GLOBAL_ACTION")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.registerReceiver(this, commandReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Background automation removed. Actions are now triggered synchronously via CLICK_KEYWORDS broadcast.
    }

    override fun onInterrupt() {
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Command received: ${intent?.action}")
            when (intent?.action) {
                "com.ailife.rtosify.DISPATCH_REMOTE_INPUT" -> {
                    val action = intent.getIntExtra("action", -1)
                    val x = intent.getFloatExtra("x", 0f)
                    val y = intent.getFloatExtra("y", 0f)
                    if (action != -1) {
                        performRemoteInput(action, x, y)
                    }
                }
                "com.ailife.rtosify.CLICK_KEYWORDS" -> {
                    val keywords = intent.getStringArrayListExtra("keywords")
                    val toggleOnly = intent.getBooleanExtra("toggleOnly", false)
                    val targetState = if (intent.hasExtra("targetState")) intent.getBooleanExtra("targetState", false) else null
                    if (keywords != null) {
                        performKeywordClick(keywords, toggleOnly, targetState)
                    }
                }
                "com.ailife.rtosify.GLOBAL_ACTION" -> {
                    val action = intent.getIntExtra("action", -1)
                    if (action != -1) {
                        performGlobalAction(action)
                    }
                }
            }
        }
    }

    private fun performKeywordClick(keywords: List<String>, toggleOnly: Boolean, targetState: Boolean?) {
        val startTime = System.currentTimeMillis()
        val timeout = 5000L // 5 seconds
        
        fun attemptClick() {
            try {
                val rootNode = rootInActiveWindow ?: run {
                    if (System.currentTimeMillis() - startTime < timeout) {
                        Handler(Looper.getMainLooper()).postDelayed({ attemptClick() }, 500)
                    } else {
                        Log.e(TAG, "No root node in active window after timeout")
                    }
                    return
                }

                var targetNode: AccessibilityNodeInfo? = null
                for (keyword in keywords) {
                    targetNode = findNodeByText(rootNode, keyword)
                    if (targetNode != null) {
                        Log.i(TAG, "Found target node with keyword: $keyword")
                        break
                    }
                }

                if (targetNode != null) {
                    val clickableNode = findClickableOrSwitch(targetNode)
                    if (clickableNode != null) {
                        // Smart Toggle: Check state if requested
                        if (targetState != null) {
                            val currentState = getSwitchState(clickableNode)
                            if (currentState == targetState) {
                                Log.i(TAG, "Smart Toggle: Node already in target state ($targetState), skipping click")
                                return
                            }
                        }

                        Log.i(TAG, "Clicking node for ${targetNode.text ?: "keyword"}")
                        val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, if (clicked) "✅ Successfully clicked keyword-matched node" else "❌ Click failed")
                    } else {
                        Log.w(TAG, "Target found but no clickable node detected, retrying...")
                        if (System.currentTimeMillis() - startTime < timeout) {
                            Handler(Looper.getMainLooper()).postDelayed({ attemptClick() }, 500)
                        }
                    }
                } else {
                    if (System.currentTimeMillis() - startTime < timeout) {
                        Log.v(TAG, "Keywords $keywords not found yet, retrying...")
                        Handler(Looper.getMainLooper()).postDelayed({ attemptClick() }, 500)
                    } else {
                        Log.w(TAG, "Could not find any of $keywords on screen after ${timeout}ms")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in performKeywordClick: ${e.message}")
            }
        }

        attemptClick()
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

    private fun getSwitchState(node: AccessibilityNodeInfo): Boolean {
        // Many settings switches have isChecked property
        if (node.isCheckable) return node.isChecked
        
        // Sometimes the text indicates state (e.g. "On" or "Off")
        val text = node.text?.toString()?.lowercase() ?: ""
        if (text == "on" || text == "开启" || text == "已开启") return true
        if (text == "off" || text == "关闭" || text == "已关闭") return false
        
        // Or content description
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains("on") || desc.contains("开启")) return true
        if (desc.contains("off") || desc.contains("关闭")) return false

        // Check children for switches if this is a row
        val childSwitch = findSwitchInChildren(node)
        if (childSwitch != null && childSwitch != node) {
            return childSwitch.isChecked
        }

        return node.isChecked // Fallback
    }

    // Touch state tracking for swipe/drag gestures
    private var gestureStartTime: Long = 0
    private val gesturePath = android.graphics.Path()
    private var isGestureActive = false

    private fun performRemoteInput(action: Int, xP: Float, yP: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        // Handle global navigation actions first
        when (action) {
            RemoteInputData.ACTION_NAV_BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
            RemoteInputData.ACTION_NAV_HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
            RemoteInputData.ACTION_NAV_RECENTS -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                return
            }
        }

        val displayMetrics = resources.displayMetrics
        val x = xP * displayMetrics.widthPixels
        val y = yP * displayMetrics.heightPixels

        Log.d(TAG, "performRemoteInput: action=$action, x=$x, y=$y")

        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // Start new gesture
                gesturePath.reset()
                gesturePath.moveTo(x, y)
                gestureStartTime = System.currentTimeMillis()
                isGestureActive = true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                // Add to gesture path
                if (isGestureActive) {
                    gesturePath.lineTo(x, y)
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                // Complete and dispatch gesture
                if (isGestureActive) {
                    gesturePath.lineTo(x, y)
                    val duration = (System.currentTimeMillis() - gestureStartTime).coerceAtLeast(1).coerceAtMost(1000)
                    
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(gesturePath, 0, duration))
                        .build()
                    
                    dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                            Log.d(TAG, "Gesture completed: duration=${duration}ms")
                        }
                        override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                            Log.w(TAG, "Gesture cancelled")
                        }
                    }, null)
                    
                    isGestureActive = false
                }
            }
        }
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
