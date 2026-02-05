package com.ailife.rtosify

import android.app.Application
import com.ailife.rtosify.utils.CrashHandler

class RtosifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler(this).init()
    }
}
