package com.ailife.rtosifycompanion

import android.app.Application
import com.ailife.rtosifycompanion.utils.CrashHandler

class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler(this).init()
    }
}
