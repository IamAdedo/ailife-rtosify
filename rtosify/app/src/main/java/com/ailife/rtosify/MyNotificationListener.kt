package com.ailife.rtosify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MyNotificationListener : NotificationListenerService() {

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    // Map to store notification actions for later execution
    private val notificationActionsMap = mutableMapOf<String, MutableMap<String, android.app.Notification.Action>>()

    // Receiver para ouvir comandos do BluetoothService (Ex: Watch pediu pra apagar)
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothService.ACTION_CMD_DISMISS_ON_PHONE -> {
                    val key = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
                    if (key != null) {
                        try {
                            cancelNotification(key)
                            notificationActionsMap.remove(key)
                            Log.d("Listener", "Cancelando notificação solicitada pelo Watch: $key")
                        } catch (e: Exception) {
                            Log.e("Listener", "Erro ao cancelar notificação: ${e.message}")
                        }
                    }
                }
                BluetoothService.ACTION_CMD_EXECUTE_ACTION -> {
                    val notifKey = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
                    val actionKey = intent.getStringExtra(BluetoothService.EXTRA_ACTION_KEY)
                    if (notifKey != null && actionKey != null) {
                        executeNotificationAction(notifKey, actionKey, null)
                    }
                }
                BluetoothService.ACTION_CMD_SEND_REPLY -> {
                    val notifKey = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
                    val actionKey = intent.getStringExtra(BluetoothService.EXTRA_ACTION_KEY)
                    val replyText = intent.getStringExtra(BluetoothService.EXTRA_REPLY_TEXT)
                    if (notifKey != null && actionKey != null && replyText != null) {
                        executeNotificationAction(notifKey, actionKey, replyText)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        val filter = IntentFilter().apply {
            addAction(BluetoothService.ACTION_CMD_DISMISS_ON_PHONE)
            addAction(BluetoothService.ACTION_CMD_EXECUTE_ACTION)
            addAction(BluetoothService.ACTION_CMD_SEND_REPLY)
        }

        // CORREÇÃO CRÍTICA PARA ANDROID 14 (API 34):
        // É obrigatório definir se o receiver é exportado ou não.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Android 12 e inferiores não suportam/exigem essa flag
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // É boa prática desregistrar para evitar leaks, embora o sistema mate o serviço junto
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            // Ignora se já não estiver registrado
        }
    }

    // --- POSTAGEM (Telefone -> Watch) ---
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val isMirroringEnabled = prefs.getBoolean("notification_mirroring_enabled", false)
        if (!isMirroringEnabled) return

        // Whitelist check
        if (prefs.contains("allowed_notif_packages")) {
            val allowedApps = prefs.getStringSet("allowed_notif_packages", emptySet())
            if (allowedApps == null || !allowedApps.contains(sbn.packageName)) return
        }

        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getString("android.text") ?: ""

        if (title.isEmpty() && text.isEmpty()) return

        try {
            // Extract large icon from notification
            val largeIconBase64 = extractLargeIcon(notification)

            // Extract app icon
            val appIconBase64 = extractAppIcon(sbn.packageName)

            // Extract actions
            val actions = extractActions(sbn.key, notification)

            // Create NotificationData object
            val notificationData = NotificationData(
                packageName = sbn.packageName,
                title = title,
                text = text,
                key = sbn.key,
                largeIcon = largeIconBase64,
                smallIcon = appIconBase64,
                actions = actions
            )

            // Convert to JSON using Gson
            val jsonString = gson.toJson(notificationData)

            val intent = Intent(BluetoothService.ACTION_SEND_NOTIF_TO_WATCH)
            intent.putExtra(BluetoothService.EXTRA_NOTIF_JSON, jsonString)
            intent.setPackage(packageName)
            sendBroadcast(intent)

            Log.d("Listener", "Notification sent with ${actions.size} actions")
        } catch (e: Exception) {
            Log.e("Listener", "Error processing notification: ${e.message}", e)
        }
    }

    // --- REMOÇÃO (Telefone -> Watch) ---
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return

        val isMirroringEnabled = prefs.getBoolean("notification_mirroring_enabled", false)
        if (!isMirroringEnabled) return

        // Clean up actions map
        notificationActionsMap.remove(sbn.key)

        val intent = Intent(BluetoothService.ACTION_SEND_REMOVE_TO_WATCH)
        intent.putExtra(BluetoothService.EXTRA_NOTIF_KEY, sbn.key)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // --- HELPER FUNCTIONS ---

    private fun extractLargeIcon(notification: android.app.Notification): String? {
        try {
            val largeIcon = notification.getLargeIcon() ?: return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val drawable = largeIcon.loadDrawable(this) ?: return null
                val bitmap = drawableToBitmap(drawable)
                return bitmapToBase64(bitmap)
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting large icon: ${e.message}")
        }
        return null
    }

    private fun extractAppIcon(packageName: String): String? {
        try {
            val pm = packageManager
            val appIcon = pm.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(appIcon)
            return bitmapToBase64(bitmap)
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting app icon: ${e.message}")
        }
        return null
    }

    private fun extractActions(notifKey: String, notification: android.app.Notification): List<NotificationActionData> {
        val actionsList = mutableListOf<NotificationActionData>()
        val actionsMap = mutableMapOf<String, android.app.Notification.Action>()

        try {
            notification.actions?.forEachIndexed { index, action ->
                if (action != null && action.title != null) {
                    val actionKey = "action_$index"
                    val isReply = action.remoteInputs?.any { it.allowFreeFormInput } ?: false

                    actionsList.add(NotificationActionData(
                        title = action.title.toString(),
                        actionKey = actionKey,
                        isReplyAction = isReply
                    ))

                    actionsMap[actionKey] = action
                }
            }

            if (actionsMap.isNotEmpty()) {
                notificationActionsMap[notifKey] = actionsMap
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting actions: ${e.message}")
        }

        return actionsList
    }

    private fun executeNotificationAction(notifKey: String, actionKey: String, replyText: String?) {
        try {
            val actionsMap = notificationActionsMap[notifKey] ?: run {
                Log.e("Listener", "No actions found for notification: $notifKey")
                return
            }

            val action = actionsMap[actionKey] ?: run {
                Log.e("Listener", "Action not found: $actionKey")
                return
            }

            if (replyText != null) {
                // Handle reply action
                val remoteInput = action.remoteInputs?.firstOrNull { it.allowFreeFormInput }
                if (remoteInput != null) {
                    val intent = Intent()
                    val bundle = android.os.Bundle()
                    bundle.putCharSequence(remoteInput.resultKey, replyText)
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)

                    action.actionIntent?.send(this, 0, intent)
                    Log.d("Listener", "Reply sent: $replyText")
                }
            } else {
                // Execute regular action
                action.actionIntent?.send()
                Log.d("Listener", "Action executed: ${action.title}")
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error executing action: ${e.message}", e)
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Compress to reduce size (quality 50 to balance quality vs size)
        bitmap.compress(Bitmap.CompressFormat.PNG, 50, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}