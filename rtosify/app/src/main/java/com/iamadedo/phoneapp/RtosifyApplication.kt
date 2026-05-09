package com.iamadedo.phoneapp

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.iamadedo.phoneapp.utils.CrashHandler

class RtosifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply Material You dynamic color on Android 12+ (API 31+)
        // Falls back to the fixed seed palette defined in themes.xml on older devices
        DynamicColors.applyToActivitiesIfAvailable(this)
        CrashHandler(this).init()
    }
}
