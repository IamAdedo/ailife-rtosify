package com.marinov.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class MyNotificationListener : NotificationListenerService() {

    private lateinit var prefs: SharedPreferences

    // Receiver para ouvir comandos do BluetoothService (Ex: Watch pediu pra apagar)
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothService.ACTION_CMD_DISMISS_ON_PHONE) {
                val key = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
                if (key != null) {
                    try {
                        // Método do sistema para cancelar a notificação usando a Key
                        cancelNotification(key)
                        Log.d("Listener", "Cancelando notificação solicitada pelo Watch: $key")
                    } catch (e: Exception) {
                        Log.e("Listener", "Erro ao cancelar notificação: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        val filter = IntentFilter(BluetoothService.ACTION_CMD_DISMISS_ON_PHONE)

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

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getString("android.text") ?: ""

        if (title.isNotEmpty() || text.isNotEmpty()) {
            val json = JSONObject()
            json.put("package", sbn.packageName)
            json.put("title", title)
            json.put("text", text)
            json.put("key", sbn.key) // Importante: Envia a chave única

            val intent = Intent(BluetoothService.ACTION_SEND_NOTIF_TO_WATCH)
            intent.putExtra(BluetoothService.EXTRA_NOTIF_JSON, json.toString())
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
    }

    // --- REMOÇÃO (Telefone -> Watch) ---
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return

        val isMirroringEnabled = prefs.getBoolean("notification_mirroring_enabled", false)
        if (!isMirroringEnabled) return

        val intent = Intent(BluetoothService.ACTION_SEND_REMOVE_TO_WATCH)
        intent.putExtra(BluetoothService.EXTRA_NOTIF_KEY, sbn.key)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }
}