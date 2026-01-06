package com.ailife.rtosify

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service to enable background clipboard access.
 * This service does not intercept or process any accessibility events.
 * It exists solely to grant clipboard access permission.
 */
class ClipboardAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for clipboard access - left empty intentionally
    }

    override fun onInterrupt() {
        // Not needed for clipboard access - left empty intentionally
    }
}
