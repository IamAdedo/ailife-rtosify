package com.iamadedo.watchapp

import android.app.Application
import com.iamadedo.watchapp.utils.CrashHandler

class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler(this).init()
    }
}
