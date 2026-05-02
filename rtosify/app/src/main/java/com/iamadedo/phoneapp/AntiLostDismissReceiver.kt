package com.iamadedo.phoneapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the "Dismiss" action from the anti-lost notification on the phone.
 * Delegates to AntiLostPhoneHandler via BluetoothService.
 */
class AntiLostDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AntiLostPhoneHandler.ACTION_DISMISS) {
            context.startService(
                Intent(context, BluetoothService::class.java).apply {
                    action = AntiLostPhoneHandler.ACTION_DISMISS
                }
            )
        }
    }
}
