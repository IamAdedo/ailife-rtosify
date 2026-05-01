package com.iamadedo.watchapp

import android.content.ClipData
import android.content.Context

/**
 * ClipboardManager wrapper following scrcpy's pattern.
 * Provides simple clipboard access using the system ClipboardManager.
 */
class ClipboardManagerWrapper private constructor(
    private val manager: android.content.ClipboardManager
) {
    
    companion object {
        fun create(): ClipboardManagerWrapper? {
            val manager = FakeContext.get().getSystemService(Context.CLIPBOARD_SERVICE) 
                as? android.content.ClipboardManager
            
            if (manager == null) {
                // Some devices have no clipboard manager
                android.util.Log.w("ClipboardManager", "Clipboard service not available")
                return null
            }
            
            return ClipboardManagerWrapper(manager)
        }
    }
    
    /**
     * Get the current clipboard text.
     * Returns null if clipboard is empty or contains non-text data.
     */
    fun getText(): CharSequence? {
        val clipData = manager.primaryClip
        if (clipData == null || clipData.itemCount == 0) {
            return null
        }
        return clipData.getItemAt(0).text
    }
    
    /**
     * Set the clipboard text.
     * Returns true if successful.
     */
    fun setText(text: CharSequence): Boolean {
        val clipData = ClipData.newPlainText(null, text)
        manager.setPrimaryClip(clipData)
        return true
    }
    
    /**
     * Add a listener for clipboard changes.
     */
    fun addPrimaryClipChangedListener(listener: android.content.ClipboardManager.OnPrimaryClipChangedListener) {
        manager.addPrimaryClipChangedListener(listener)
    }
    
    /**
     * Remove a clipboard change listener.
     */
    fun removePrimaryClipChangedListener(listener: android.content.ClipboardManager.OnPrimaryClipChangedListener) {
        manager.removePrimaryClipChangedListener(listener)
    }
}
