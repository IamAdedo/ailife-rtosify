package com.iamadedo.watchapp

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
            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val useInAppReply = prefs.getBoolean("in_app_reply_dialog", false)
            Log.d("NotificationAction", "useInAppReply: $useInAppReply")

            if (useInAppReply) {
                // Launch custom reply dialog
                val dialogIntent = Intent(context, ReplyDialogActivity::class.java).apply {
                    putExtra(BluetoothService.EXTRA_NOTIF_KEY, notifKey)
                    putExtra(BluetoothService.EXTRA_ACTION_KEY, actionKey)
                    putExtra("app_name", intent.getStringExtra("app_name"))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialogIntent)
                return
            }

            // Extract reply text from RemoteInput
            val remoteInput = RemoteInput.getResultsFromIntent(intent)
            val replyText = remoteInput?.getCharSequence(BluetoothService.EXTRA_REPLY_TEXT)?.toString()

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
