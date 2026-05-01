package com.iamadedo.phoneapp

import android.content.Context
import android.content.ContextWrapper
import android.util.Log

/**
 * FakeContext implementation following scrcpy's pattern.
 * Provides a context that identifies as "com.android.shell" for privileged operations.
 */
class FakeContext private constructor(base: Context) : ContextWrapper(base) {
    
    companion object {
        private const val TAG = "FakeContext"
        const val PACKAGE_NAME = "com.android.shell"
        
        @Volatile
        private var instance: FakeContext? = null
        
        /**
         * Get the singleton FakeContext instance.
         * Must call initialize() first or this will create it automatically.
         */
        fun get(): FakeContext {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        // Try to get system context like scrcpy does
                        val systemContext = getSystemContext()
                        if (systemContext != null) {
                            instance = FakeContext(systemContext)
                            Log.d(TAG, "FakeContext initialized with system context")
                        } else {
                            throw IllegalStateException("FakeContext not initialized and cannot get system context")
                        }
                    }
                }
            }
            return instance!!
        }
        
        /**
         * Get system context via ActivityThread reflection (scrcpy pattern).
         * Creates an ActivityThread instance and calls getSystemContext() on it.
         */
        private fun getSystemContext(): Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                
                // Create ActivityThread instance
                val activityThreadConstructor = activityThreadClass.getDeclaredConstructor()
                activityThreadConstructor.isAccessible = true
                val activityThread = activityThreadConstructor.newInstance()
                
                // Set ActivityThread.sCurrentActivityThread = activityThread
                val sCurrentActivityThreadField = activityThreadClass.getDeclaredField("sCurrentActivityThread")
                sCurrentActivityThreadField.isAccessible = true
                sCurrentActivityThreadField.set(null, activityThread)
                
                // Call getSystemContext() on the instance
                val getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext")
                getSystemContextMethod.invoke(activityThread) as? Context
            } catch (e: Exception) {
                Log.e(TAG, "Could not get system context: ${e.message}", e)
                null
            }
        }
    }
    
    override fun getPackageName(): String {
        return PACKAGE_NAME
    }
    
    override fun getOpPackageName(): String {
        return PACKAGE_NAME
    }
    
    override fun getApplicationContext(): Context {
        return this
    }
    
    override fun createPackageContext(packageName: String, flags: Int): Context {
        return this
    }
    
    override fun getSystemService(name: String): Any? {
        val service = super.getSystemService(name) ?: return null
        
        // For clipboard service, inject our FakeContext as the mContext field
        // This ensures clipboard operations identify as com.android.shell
        if (Context.CLIPBOARD_SERVICE == name) {
            try {
                val field = service.javaClass.getDeclaredField("mContext")
                field.isAccessible = true
                field.set(service, this)
            } catch (e: Exception) {
                // If reflection fails, continue with original service
                Log.w(TAG, "Failed to inject context into clipboard service: ${e.message}")
            }
        }
        
        return service
    }
}
