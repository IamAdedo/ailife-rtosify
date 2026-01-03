package com.ailife.rtosifycompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val notifKey = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY) ?: return
        val actionKey = intent.getStringExtra(BluetoothService.EXTRA_ACTION_KEY) ?: return
        val isReply = intent.getBooleanExtra("is_reply", false)

        Log.d("NotificationAction", "Action received - notifKey: $notifKey, actionKey: $actionKey, isReply: $isReply")

        if (isReply) {
            // Extract reply text from RemoteInput
            val remoteInput = RemoteInput.getResultsFromIntent(intent)
            val replyText = remoteInput?.getCharSequence("reply_text")?.toString()

            if (replyText != null && replyText.isNotEmpty()) {
                // Send reply to the phone
                val serviceIntent = Intent(context, BluetoothService::class.java).apply {
                    action = "SEND_REPLY_TO_PHONE"
                    putExtra(BluetoothService.EXTRA_NOTIF_KEY, notifKey)
                    putExtra(BluetoothService.EXTRA_ACTION_KEY, actionKey)
                    putExtra(BluetoothService.EXTRA_REPLY_TEXT, replyText)
                }
                context.startService(serviceIntent)

                Log.d("NotificationAction", "Reply text: $replyText")
            }
        } else {
            // Regular action - send execute command to phone
            val serviceIntent = Intent(context, BluetoothService::class.java).apply {
                action = "EXECUTE_ACTION_ON_PHONE"
                putExtra(BluetoothService.EXTRA_NOTIF_KEY, notifKey)
                putExtra(BluetoothService.EXTRA_ACTION_KEY, actionKey)
            }
            context.startService(serviceIntent)

            Log.d("NotificationAction", "Regular action executed")
        }
    }
}
