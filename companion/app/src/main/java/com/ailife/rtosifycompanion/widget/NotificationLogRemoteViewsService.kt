package com.ailife.rtosifycompanion.widget

import android.content.Intent
import android.widget.RemoteViewsService

class NotificationLogRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return NotificationLogRemoteViewsFactory(this.applicationContext, intent)
    }
}
