package com.iamadedo.watchapp

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

/**
 * Minimal accessibility service for Bluetooth tethering automation.
 * Clipboard monitoring has been moved to BluetoothService.
 */
class RtosifyAccessibilityService : android.accessibilityservice.AccessibilityService() {
    companion object {
        private const val TAG = "RtosifyAccessibilityService"
        
        private var instance: RtosifyAccessibilityService? = null
        
        fun enableBluetoothTethering(deviceName: String?) {
            instance?.performBluetoothTetheringEnable(deviceName)
        }

        fun dispatchRemoteInput(action: Int, xPercentage: Float, yPercentage: Float) {
            instance?.performRemoteInput(action, xPercentage, yPercentage)
        }

        fun clickNodesWithKeywords(context: Context, keywords: List<String>, toggleOnly: Boolean = false, targetState: Boolean? = null) {
            val intent = Intent("com.iamadedo.watchapp.CLICK_KEYWORDS").apply {
                putStringArrayListExtra("keywords", ArrayList(keywords))
                putExtra("toggleOnly", toggleOnly)
                if (targetState != null) putExtra("targetState", targetState)
            }
            context.sendBroadcast(intent)
            instance?.performKeywordClick(keywords, toggleOnly, targetState)
        }

        fun performGlobalAction(context: Context, action: Int) {
            val intent = Intent("com.iamadedo.watchapp.GLOBAL_ACTION").apply {
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
        
        // Register receivers for remote input and commands
        val filter = android.content.IntentFilter()
        filter.addAction("com.iamadedo.watchapp.DISPATCH_REMOTE_INPUT")
        filter.addAction("com.iamadedo.watchapp.CLICK_KEYWORDS")
        filter.addAction("com.iamadedo.watchapp.GLOBAL_ACTION")

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

    private val commandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Command received: ${intent?.action}")
            when (intent?.action) {
                "com.iamadedo.watchapp.DISPATCH_REMOTE_INPUT" -> {
                    val action = intent.getIntExtra("action", -1)
                    val x = intent.getFloatExtra("x", 0f)
                    val y = intent.getFloatExtra("y", 0f)
                    if (action != -1) {
                        performRemoteInput(action, x, y)
                    }
                }
                "com.iamadedo.watchapp.CLICK_KEYWORDS" -> {
                    val keywords = intent.getStringArrayListExtra("keywords")
                    val toggleOnly = intent.getBooleanExtra("toggleOnly", false)
                    val targetState = if (intent.hasExtra("targetState")) intent.getBooleanExtra("targetState", false) else null
                    if (keywords != null) {
                        performKeywordClick(keywords, toggleOnly, targetState)
                    }
                }
                "com.iamadedo.watchapp.GLOBAL_ACTION" -> {
                    val action = intent.getIntExtra("action", -1)
                    if (action != -1) {
                        performGlobalAction(action)
                    }
                }
            }
        }
    }

    // Touch state tracking for swipe/drag gestures
    private var gestureStartTime: Long = 0
    private val gesturePath = android.graphics.Path()
    private var isGestureActive = false

    private fun performRemoteInput(action: Int, xP: Float, yP: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        
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
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val x = xP * width
        val y = yP * height

        Log.d(TAG, "performRemoteInput: action=$action, percentages=($xP, $yP), screen=(${width}x${height}), target=($x, $y)")

        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                gesturePath.reset()
                gesturePath.moveTo(x, y)
                gestureStartTime = System.currentTimeMillis()
                isGestureActive = true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (isGestureActive) {
                    gesturePath.lineTo(x, y)
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
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

    private fun getSwitchState(node: AccessibilityNodeInfo): Boolean {
        if (node.isCheckable) return node.isChecked
        
        val text = node.text?.toString()?.lowercase() ?: ""
        if (text == "on" || text == "开启" || text == "已开启") return true
        if (text == "off" || text == "关闭" || text == "已关闭") return false
        
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains("on") || desc.contains("开启")) return true
        if (desc.contains("off") || desc.contains("关闭")) return false

        val childSwitch = findSwitchInChildren(node)
        if (childSwitch != null && childSwitch != node) {
            return childSwitch.isChecked
        }
        return node.isChecked
    }

    private fun findSwitchInChildren(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            if (isActuallySwitch(child)) return child
            val result = findSwitchInChildren(child)
            if (result != null) return result
        }
        return null
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
                        if (toggleOnly || targetState != null) {
                            val switchNode = findSwitchInHierarchy(targetNode.parent ?: targetNode) ?: clickableNode
                            val isCurrentlyOn = getSwitchState(switchNode)
                            
                            if (targetState != null && isCurrentlyOn == targetState) {
                                Log.i(TAG, "Smart Toggle: Already in target state ($targetState), skipping")
                                return
                            }
                            
                            if (toggleOnly && isCurrentlyOn && targetState == null) {
                                Log.i(TAG, "ToggleOnly: already ON, skipping")
                                return
                            }
                        }

                        Log.i(TAG, "Clicking node for ${targetNode.text ?: "keyword"}")
                        val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, if (clicked) "✅ Click successful" else "❌ Click failed")
                    } else {
                        Log.w(TAG, "Node found but not clickable, retrying...")
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
}
