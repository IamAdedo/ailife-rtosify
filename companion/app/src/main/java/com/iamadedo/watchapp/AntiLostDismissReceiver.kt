package com.iamadedo.watchapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the "Dismiss" action from the anti-lost notification on the watch.
 * Delegates to AntiLostManager via BluetoothService.
 */
class AntiLostDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AntiLostManager.ACTION_DISMISS) {
            context.startService(
                Intent(context, BluetoothService::class.java).apply {
                    action = AntiLostManager.ACTION_DISMISS
                }
            )
        }
    }
}
