package com.iamadedo.watchapp

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.iamadedo.watchapp.utils.CrashHandler

class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply Material You dynamic color on Android 12+ (API 31+)
        // Falls back to the fixed seed palette in themes.xml on older devices
        DynamicColors.applyToActivitiesIfAvailable(this)
        CrashHandler(this).init()
    }
}
