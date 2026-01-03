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
            // Debug: Log all extras keys to see what's available
            val extras = notification.extras
            Log.d("Listener", "Notification extras keys: ${extras.keySet().joinToString()}")

            // Extract large icon from notification
            var largeIconBase64 = extractLargeIcon(notification)
            Log.d("Listener", "Large icon extracted: ${if (largeIconBase64 != null) "YES (${largeIconBase64.length} chars)" else "NO"}")

            // Extract small icon from notification (original status bar icon)
            var smallIconBase64 = extractSmallIcon(notification)
            Log.d("Listener", "Small icon extracted: ${if (smallIconBase64 != null) "YES (${smallIconBase64.length} chars)" else "NO"}")

            // Extract app icon as fallback
            val appIconBase64 = extractAppIcon(sbn.packageName)
            Log.d("Listener", "App icon extracted: ${if (appIconBase64 != null) "YES (${appIconBase64.length} chars)" else "NO"}")

            // Use app icon as fallback for small icon if no small icon extracted
            if (smallIconBase64 == null && appIconBase64 != null) {
                smallIconBase64 = appIconBase64
                Log.d("Listener", "Using app icon as small icon fallback")
            }

            // Use app icon as fallback for large icon if no large icon exists
            if (largeIconBase64 == null && appIconBase64 != null) {
                largeIconBase64 = appIconBase64
                Log.d("Listener", "Using app icon as large icon fallback")
            }

            // Extract big picture (for BigPictureStyle notifications)
            val bigPictureBase64 = extractBigPicture(notification)
            Log.d("Listener", "Big picture extracted: ${if (bigPictureBase64 != null) "YES (${bigPictureBase64.length} chars)" else "NO"}")

            // Extract actions
            val actions = extractActions(sbn.key, notification)

            // Create NotificationData object
            val notificationData = NotificationData(
                packageName = sbn.packageName,
                title = title,
                text = text,
                key = sbn.key,
                largeIcon = largeIconBase64,
                smallIcon = smallIconBase64,  // Original small icon or app icon as fallback
                bigPicture = bigPictureBase64,
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
            // Try getting large icon from notification
            var largeIcon = notification.getLargeIcon()
            Log.d("Listener", "getLargeIcon() returned: ${largeIcon != null}")

            // If getLargeIcon is null, try getting from extras
            if (largeIcon == null) {
                val extras = notification.extras

                // Try different keys for large icon
                // Check Icon types FIRST (M+) before trying Bitmap to avoid ClassCastException

                // 1. Try android.largeIcon.big as Icon (for BigPictureStyle) - MOST COMMON
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        largeIcon = extras.getParcelable<android.graphics.drawable.Icon>("android.largeIcon.big")
                        if (largeIcon != null) {
                            Log.d("Listener", "Found large icon in extras as Icon (android.largeIcon.big)")
                        }
                    } catch (e: ClassCastException) {
                        // Not an Icon, will try as Bitmap below
                        Log.d("Listener", "android.largeIcon.big is not an Icon, trying Bitmap")
                    }
                }

                // 2. Try android.largeIcon as Icon
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && largeIcon == null) {
                    try {
                        largeIcon = extras.getParcelable<android.graphics.drawable.Icon>("android.largeIcon")
                        if (largeIcon != null) {
                            Log.d("Listener", "Found large icon in extras as Icon (android.largeIcon)")
                        }
                    } catch (e: ClassCastException) {
                        Log.d("Listener", "android.largeIcon is not an Icon, trying Bitmap")
                    }
                }

                // 3. Now try Bitmap types if Icon checks didn't work
                if (largeIcon == null) {
                    var largeIconBitmap = extras.getParcelable<Bitmap>("android.largeIcon")
                    if (largeIconBitmap != null) {
                        Log.d("Listener", "Found large icon in extras as Bitmap (android.largeIcon)")
                        return bitmapToBase64(largeIconBitmap)
                    }

                    // 4. Try android.largeIcon.big as Bitmap
                    largeIconBitmap = extras.getParcelable<Bitmap>("android.largeIcon.big")
                    if (largeIconBitmap != null) {
                        Log.d("Listener", "Found large icon in extras as Bitmap (android.largeIcon.big)")
                        return bitmapToBase64(largeIconBitmap)
                    }
                }

                // 4. Try android.pictureIcon
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && largeIcon == null) {
                    largeIcon = extras.getParcelable("android.pictureIcon")
                    if (largeIcon != null) {
                        Log.d("Listener", "Found large icon as pictureIcon")
                    }
                }

                // 5. Try as Icon object with standard key
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && largeIcon == null) {
                    largeIcon = extras.getParcelable("android.largeIcon")
                    if (largeIcon != null) {
                        Log.d("Listener", "Found large icon in extras as Icon (android.largeIcon)")
                    }
                }
            }

            if (largeIcon != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val drawable = largeIcon.loadDrawable(this)
                Log.d("Listener", "Loaded drawable from icon: ${drawable != null}")
                if (drawable != null) {
                    val bitmap = drawableToBitmap(drawable)
                    return bitmapToBase64(bitmap)
                }
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting large icon: ${e.message}", e)
        }
        return null
    }

    private fun extractBigPicture(notification: android.app.Notification): String? {
        try {
            val extras = notification.extras

            // Try to get big picture from extras
            // 1. Try as Bitmap first
            var bigPictureBitmap = extras.getParcelable<Bitmap>("android.picture")
            if (bigPictureBitmap != null) {
                Log.d("Listener", "Found big picture as Bitmap")
                return bitmapToBase64(bigPictureBitmap)
            }

            // 2. Try as Icon (if android.pictureIcon exists)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val pictureIcon = extras.getParcelable<android.graphics.drawable.Icon>("android.pictureIcon")
                    if (pictureIcon != null) {
                        val drawable = pictureIcon.loadDrawable(this)
                        if (drawable != null) {
                            Log.d("Listener", "Found big picture as Icon")
                            val bitmap = drawableToBitmap(drawable)
                            return bitmapToBase64(bitmap)
                        }
                    }
                } catch (e: ClassCastException) {
                    Log.d("Listener", "android.pictureIcon is not an Icon")
                }
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting big picture: ${e.message}", e)
        }
        return null
    }

    private fun extractSmallIcon(notification: android.app.Notification): String? {
        try {
            // Try to get the small icon from notification (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val smallIcon = notification.smallIcon
                if (smallIcon != null) {
                    val drawable = smallIcon.loadDrawable(this)
                    if (drawable != null) {
                        Log.d("Listener", "Extracted original small icon from notification")
                        val bitmap = drawableToBitmap(drawable)
                        return bitmapToBase64(bitmap)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting small icon: ${e.message}")
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
        // Resize if too large to avoid protocol size issues
        val resizedBitmap = if (bitmap.width > 512 || bitmap.height > 512) {
            val scale = 512f / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        // Use JPEG for better compression (quality 85 is good balance)
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val byteArray = outputStream.toByteArray()

        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        Log.d("Listener", "Compressed icon to ${byteArray.size} bytes")
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}