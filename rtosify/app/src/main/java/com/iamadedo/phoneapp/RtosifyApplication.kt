package com.iamadedo.phoneapp

import android.app.Application
import com.iamadedo.phoneapp.utils.CrashHandler

class RtosifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler(this).init()
    }
}
