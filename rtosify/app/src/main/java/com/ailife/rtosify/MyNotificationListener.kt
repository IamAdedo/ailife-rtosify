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
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import org.json.JSONArray

class MyNotificationListener : NotificationListenerService() {

    private lateinit var devicePrefManager: DevicePrefManager
    private lateinit var globalPrefs: SharedPreferences
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()
    private val gson = Gson()

    // Map to store notification actions for later execution
    private val notificationActionsMap =
            mutableMapOf<String, MutableMap<String, android.app.Notification.Action>>()

    // Map to track the last time a reply was sent for a notification (to prevent premature
    // dismissal)
    private val lastReplyTimes = mutableMapOf<String, Long>()

    // Cache for notification rules
    private var notificationRules = mutableListOf<NotificationRule>()

    // SharedPreferences listener for rule changes
    private val prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                if (key == "notification_rules") {
                    Log.d("Listener", "Rules preference changed, reloading rules...")
                    loadNotificationRules()
                }
            }

    data class NotificationRule(
            val id: String,
            val packageName: String,
            val mode: String,
            val titlePattern: String,
            val contentPattern: String
    )

    // Receiver para ouvir comandos do BluetoothService (Ex: Watch pediu pra apagar)
    private val commandReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        "com.ailife.rtosify.ACTION_RULES_UPDATED" -> {
                            Log.d("Listener", "Rules updated, reloading...")
                            loadNotificationRules()
                        }
                        BluetoothService.ACTION_CMD_DISMISS_ON_PHONE -> {
                            val key = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
                            if (key != null) {
                                try {
                                    cancelNotification(key)
                                    notificationActionsMap.remove(key)
                                    Log.d(
                                            "Listener",
                                            "Cancelando notificação solicitada pelo Watch: $key"
                                    )
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
        devicePrefManager = DevicePrefManager(this)
        globalPrefs = devicePrefManager.getGlobalPrefs()

        val filter =
                IntentFilter().apply {
                    addAction("com.ailife.rtosify.ACTION_RULES_UPDATED")
                    addAction(BluetoothService.ACTION_CMD_DISMISS_ON_PHONE)
                    addAction(BluetoothService.ACTION_CMD_EXECUTE_ACTION)
                    addAction(BluetoothService.ACTION_CMD_SEND_REPLY)
                }

        // CORREÇÃO CRÍTICA PARA ANDROID 14 (API 34):
        // É obrigatório definir se o receiver é exportado ou não.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Android 12 e inferiores - lint requires explicit flag check
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(commandReceiver, filter)
        }

        // Register SharedPreferences listener for rule changes in active prefs
        activePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Log.d("Listener", "SharedPreferences listener registered for active device")

        // Load notification rules
        loadNotificationRules()
    }

    private fun loadNotificationRules() {
        val rulesJson = activePrefs.getString("notification_rules", "[]") ?: "[]"
        notificationRules.clear()

        try {
            Log.d("Listener", "Loading rules from preferences, JSON: $rulesJson")
            val jsonArray = JSONArray(rulesJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rule =
                        NotificationRule(
                                id = obj.getString("id"),
                                packageName = obj.getString("packageName"),
                                mode = obj.getString("mode"),
                                titlePattern = obj.optString("titlePattern", ""),
                                contentPattern = obj.optString("contentPattern", "")
                        )
                notificationRules.add(rule)
                Log.d(
                        "Listener",
                        "Loaded rule: pkg=${rule.packageName}, mode=${rule.mode}, title='${rule.titlePattern}', content='${rule.contentPattern}'"
                )
            }
            Log.d("Listener", "Total rules loaded: ${notificationRules.size}")
        } catch (e: Exception) {
            Log.e("Listener", "Error loading notification rules: ${e.message}", e)
        }
    }

    private fun shouldForwardNotification(
            packageName: String,
            title: String,
            text: String
    ): Boolean {
        Log.d("NotifRules", "=== Checking notification ===")
        Log.d("NotifRules", "Package: $packageName")
        Log.d("NotifRules", "Title: '$title'")
        Log.d("NotifRules", "Text: '$text'")
        Log.d("NotifRules", "Total rules configured: ${notificationRules.size}")

        // If no rules configured, allow all notifications
        if (notificationRules.isEmpty()) {
            Log.d("NotifRules", "No rules configured, allowing notification")
            return true
        }

        // Find rules for this package
        val rulesForPackage = notificationRules.filter { it.packageName == packageName }
        Log.d("NotifRules", "Rules for this package: ${rulesForPackage.size}")

        // If no rules for this package, allow notification
        if (rulesForPackage.isEmpty()) {
            Log.d("NotifRules", "No rules for this package, allowing notification")
            return true
        }

        // Log all rules for this package
        rulesForPackage.forEachIndexed { index, rule ->
            Log.d(
                    "NotifRules",
                    "Rule #${index + 1}: mode=${rule.mode}, titlePattern='${rule.titlePattern}', contentPattern='${rule.contentPattern}'"
            )
        }

        // Process each rule
        for (rule in rulesForPackage) {
            val matchesPattern = matchesRulePattern(rule, title, text)
            Log.d("NotifRules", "Rule check: mode=${rule.mode}, matches=$matchesPattern")

            when (rule.mode) {
                "whitelist" -> {
                    // For whitelist: if pattern matches (or no pattern), ALLOW
                    if (matchesPattern) {
                        Log.d("NotifRules", "✓ ALLOWED: Whitelist rule matched")
                        return true
                    } else {
                        Log.d("NotifRules", "✗ Whitelist rule did NOT match")
                    }
                }
                "blacklist" -> {
                    // For blacklist: if pattern matches (or no pattern), BLOCK
                    if (matchesPattern) {
                        Log.d("NotifRules", "✗ BLOCKED: Blacklist rule matched")
                        return false
                    } else {
                        Log.d("NotifRules", "✓ Blacklist rule did NOT match")
                    }
                }
            }
        }

        // Default behavior after checking all rules:
        // If we have whitelist rules for this app but none matched, block it
        // If we have only blacklist rules and none matched, allow it
        val hasWhitelistRules = rulesForPackage.any { it.mode == "whitelist" }
        val result = !hasWhitelistRules
        Log.d(
                "NotifRules",
                "Default behavior: hasWhitelistRules=$hasWhitelistRules, result=$result"
        )
        if (hasWhitelistRules && !result) {
            Log.d("NotifRules", "✗ BLOCKED: No whitelist rules matched")
        }
        return result
    }

    private fun matchesRulePattern(rule: NotificationRule, title: String, text: String): Boolean {
        val hasTitlePattern = rule.titlePattern.isNotEmpty()
        val hasContentPattern = rule.contentPattern.isNotEmpty()

        Log.d(
                "NotifRules",
                "  Pattern matching: hasTitlePattern=$hasTitlePattern, hasContentPattern=$hasContentPattern"
        )

        // If no patterns specified, the rule applies to all notifications from this app
        if (!hasTitlePattern && !hasContentPattern) {
            Log.d("NotifRules", "  No patterns specified, rule applies to all notifications")
            return true
        }

        var titleMatches = true
        var contentMatches = true

        // Check title pattern if specified
        if (hasTitlePattern) {
            titleMatches = title.contains(rule.titlePattern, ignoreCase = true)
            Log.d(
                    "NotifRules",
                    "  Title check: '$title' contains '${rule.titlePattern}' (ignoreCase) = $titleMatches"
            )
        }

        // Check content pattern if specified
        if (hasContentPattern) {
            contentMatches = text.contains(rule.contentPattern, ignoreCase = true)
            Log.d(
                    "NotifRules",
                    "  Content check: '$text' contains '${rule.contentPattern}' (ignoreCase) = $contentMatches"
            )
        }

        // Both patterns must match (if specified)
        val result = titleMatches && contentMatches
        Log.d(
                "NotifRules",
                "  Final pattern match result: $result (titleMatches=$titleMatches, contentMatches=$contentMatches)"
        )
        return result
    }

    override fun onDestroy() {
        super.onDestroy()
        // É boa prática desregistrar para evitar leaks, embora o sistema mate o serviço junto
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            // Ignora se já não estiver registrado
        }

        // Unregister SharedPreferences listener
        try {
            activePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {
            // Ignora se já não estiver registrado
        }
    }

    // --- POSTAGEM (Telefone -> Watch) ---
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val isMirroringEnabled = activePrefs.getBoolean("notification_mirroring_enabled", false)
        if (!isMirroringEnabled) return

        // Check for "Skip when screen on" option
        val skipIfScreenOn = activePrefs.getBoolean("skip_screen_on_enabled", false)
        if (skipIfScreenOn) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) {
                Log.d("Listener", "Skipping notification: Screen is ON and interactive")
                return
            }
        }

        // Whitelist check
        if (activePrefs.contains("allowed_notif_packages")) {
            val allowedApps = activePrefs.getStringSet("allowed_notif_packages", emptySet())
            if (allowedApps == null || !allowedApps.contains(sbn.packageName)) return
        }

        if (sbn.packageName == packageName) return

        val notification = sbn.notification

        // Check if ongoing notifications should be forwarded
        val isOngoing = sbn.isOngoing
        if (isOngoing) {
            // Check per-app setting first
            val appOngoing = activePrefs.getBoolean("app_ongoing_${sbn.packageName}", false)
            if (!appOngoing) {
                // Determine global default (removed from settings UI but keep fallback or assume false)
                // Since we moved to per-app, default should probably be false unless explicitly enabled for app
                Log.d("Listener", "Skipping ongoing notification (app_ongoing disabled for ${sbn.packageName})")
                return
            }
        }

        // Check if silent notifications should be forwarded
        val isSilent =
                (notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE) != 0 ||
                        (notification.defaults == 0 &&
                                notification.sound == null &&
                                notification.vibrate == null)
        
        if (isSilent) {
             // Check per-app setting first
            val appSilent = activePrefs.getBoolean("app_silent_${sbn.packageName}", true)
            if (!appSilent) {
                 Log.d("Listener", "Skipping silent notification (app_silent disabled for ${sbn.packageName})")
                 return
            }
        }

        // Check application specific priority requirement
        // 0 = Any, 1 = Low+, 2 = Default+, 3 = High
        val minPriority = activePrefs.getInt("app_min_priority_${sbn.packageName}", 0)
        if (minPriority > 0) {
            // Priority Mapping:
            // IMPORTANCE_MIN (1) ~ Low
            // IMPORTANCE_LOW (2) ~ Low
            // IMPORTANCE_DEFAULT (3) ~ Default
            // IMPORTANCE_HIGH (4) ~ High
            // IMPORTANCE_MAX (5) ~ High
            
            val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                 // SBN importance is only available in specific contexts or might need extraction from channel
                 // But sbn.notification.priority is deprecated.
                 // Actually sbn.notification.channelId gives us the channel, and we can check that.
                 // However, obtaining the channel for every notification might be heavy.
                 // Let's rely on standard extraction or deprecated priority as fallback if importance not easily available on SBN directly (it usually isn't without querying AM).
                 // Wait, RankingMap is passed to onNotificationPosted/Removed usually? No, it's separated.
                 // getCurrentRanking() is available.
                 
                 var finalImportance = -1
                 try {
                     val ranking = Ranking()
                     if (currentRanking.getRanking(sbn.key, ranking)) {
                         finalImportance = ranking.importance
                     }
                 } catch (e: Exception) {
                     Log.e("Listener", "Error getting ranking importance: ${e.message}")
                 }
                 finalImportance
            } else {
                 // Fallback for older APIs (unlikely for this app structure but safe to have)
                 // Construct rough equivalent from priority
                 when (notification.priority) {
                     android.app.Notification.PRIORITY_MIN -> 1
                     android.app.Notification.PRIORITY_LOW -> 2
                     android.app.Notification.PRIORITY_DEFAULT -> 3
                     android.app.Notification.PRIORITY_HIGH -> 4
                     android.app.Notification.PRIORITY_MAX -> 5
                     else -> 3
                 }
            }
            
            // Filter logic
            // minPriority 1 (Low+) -> needs importance >= 2 (Low)
            // minPriority 2 (Default+) -> needs importance >= 3 (Default)
            // minPriority 3 (High) -> needs importance >= 4 (High)
            
            val requiredImportance = when (minPriority) {
                1 -> 2 // Low
                2 -> 3 // Default
                3 -> 4 // High
                else -> 0
            }
            
            if (importance < requiredImportance && importance != -1) { // -1 means we couldn't determine, so let it pass or block? Let pass to be safe.
                 Log.d("Listener", "Skipping notification: Priority $importance < Required $requiredImportance for ${sbn.packageName}")
                 return
            }
        }

        val extras = notification.extras

        // Use getCharSequence instead of getString to handle SpannableString
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        if (title.isEmpty() && text.isEmpty()) return

        // Apply notification rules
        val shouldForward = shouldForwardNotification(sbn.packageName, title, text)
        Log.d("Listener", "Rule decision: shouldForward=$shouldForward for ${sbn.packageName}")
        if (!shouldForward) {
            Log.d("Listener", "✗ Notification BLOCKED by rules: ${sbn.packageName} - '$title'")
            return
        }
        Log.d("Listener", "✓ Notification ALLOWED by rules: ${sbn.packageName} - '$title'")

        try {
            // Debug: Log all extras keys to see what's available
            val extras = notification.extras
            Log.d("Listener", "Notification extras keys: ${extras.keySet().joinToString()}")

            // 1. Extract large icon (original large icon if present)
            var largeIconBase64 = extractLargeIcon(notification)
            val hasOriginalLargeIcon = largeIconBase64 != null
            Log.d(
                    "Listener",
                    "Large icon extracted: ${if (hasOriginalLargeIcon) "YES (${largeIconBase64!!.length} chars)" else "NO"}"
            )

            // 2. Extract group icon (API 30+)
            val groupIconBase64 = extractGroupIcon(notification)
            Log.d(
                    "Listener",
                    "Group icon extracted: ${if (groupIconBase64 != null) "YES (${groupIconBase64.length} chars)" else "NO"}"
            )

            // 3. Extract sender icon and name (MessagingStyle)
            val senderInfo = extractSenderInfo(notification)
            val senderIconBase64 = senderInfo.first
            val senderName = senderInfo.second
            Log.d(
                    "Listener",
                    "Sender info: icon=${if (senderIconBase64 != null) "YES" else "NO"}, name=$senderName"
            )

            // 4. Extract self user icon and name (for replies)
            val selfInfo = extractSelfInfo(notification)
            val selfIconBase64 = selfInfo.first
            val selfName = selfInfo.second
            Log.d(
                    "Listener",
                    "Self info: icon=${if (selfIconBase64 != null) "YES" else "NO"}, name=$selfName"
            )

            // Extract isGroupConversation early to make decisions
            val isGroupConversation = extras.getBoolean("android.isGroupConversation", false)
            val conversationTitle = extras.getCharSequence("android.conversationTitle")?.toString()
            val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                notification.shortcutId
            } else {
                null
            }

            // Refine the title for group conversations (API 30+)
            val finalTitle = if (isGroupConversation && !conversationTitle.isNullOrEmpty()) {
                conversationTitle
            } else {
                title
            }

            // Special handling for apps (like Discord) that use largeIcon for group avatar 
            // but don't set specific group icon keys
            var finalGroupIconBase64 = groupIconBase64
            if (isGroupConversation && finalGroupIconBase64 == null && hasOriginalLargeIcon) {
                finalGroupIconBase64 = largeIconBase64
                Log.d("Listener", "Using original largeIcon as groupIcon (isGroupConversation=true)")
            }

            // Extract message history
            val messagesList = extractMessages(notification)
            
            // Append remote input history (API 24+) for better conversation context
            val finalMessages = messagesList.toMutableList()
            val remoteInputHistory = extras.getCharSequenceArray(android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY)
            if (remoteInputHistory != null) {
                for (historyText in remoteInputHistory.reversed()) {
                    finalMessages.add(
                        NotificationMessageData(
                            text = historyText.toString(),
                            timestamp = System.currentTimeMillis(),
                            senderName = "Me"
                        )
                    )
                }
            }
            Log.d("Listener", "Extracted ${finalMessages.size} messages (including ${remoteInputHistory?.size ?: 0} from history)")

            // 4. Extract app icon (base fallback)
            val appIconBase64 = extractAppIcon(sbn.packageName)
            Log.d(
                    "Listener",
                    "App icon extracted: ${if (appIconBase64 != null) "YES (${appIconBase64.length} chars)" else "NO"}"
            )

            // --- Fallback logic ---
            // If original notification had NO large icon, and we have NO group icon AND NO sender
            // icon,
            // then use app icon as fallback for largeIcon.
            if (!hasOriginalLargeIcon &&
                            finalGroupIconBase64 == null &&
                            senderIconBase64 == null &&
                            appIconBase64 != null
            ) {
                largeIconBase64 = appIconBase64
                Log.d("Listener", "Using app icon as large icon fallback (no other icons found)")
            }

            // Extract small icon from notification (original status bar icon)
            var smallIconBase64 = extractSmallIcon(notification)
            if (smallIconBase64 == null && appIconBase64 != null) {
                smallIconBase64 = appIconBase64
                Log.d("Listener", "Using app icon as small icon fallback")
            }

            // Extract big picture (for BigPictureStyle notifications)
            val bigPictureBase64 = extractBigPicture(notification)
            Log.d(
                    "Listener",
                    "Big picture extracted: ${if (bigPictureBase64 != null) "YES (${bigPictureBase64.length} chars)" else "NO"}"
            )

            // Extract actions
            val actions = extractActions(sbn.key, notification)

            // Create NotificationData object
            val notificationData =
                    NotificationData(
                            packageName = sbn.packageName,
                            title = finalTitle,
                            text = text,
                            key = sbn.key,
                            appName = extractAppName(sbn.packageName),
                            largeIcon = largeIconBase64,
                            smallIcon = smallIconBase64,
                            groupIcon = finalGroupIconBase64,
                            senderIcon = senderIconBase64,
                            senderName = senderName,
                            selfIcon = selfIconBase64,
                            selfName = selfName,
                            messages = finalMessages,
                            bigPicture = bigPictureBase64,
                            actions = actions,
                            isGroupConversation = isGroupConversation,
                            conversationTitle = conversationTitle,
                            shortcutId = shortcutId
                    )

            // Store in Cache instead of serialized JSON to avoid TransactionTooLargeException
            NotificationCache.put(sbn.key, notificationData)

            val intent = Intent(BluetoothService.ACTION_SEND_NOTIF_TO_WATCH)
            intent.putExtra(BluetoothService.EXTRA_NOTIF_CACHE_KEY, sbn.key)
            intent.setPackage(packageName)
            sendBroadcast(intent)

            Log.d("Listener", "Notification sent with ${actions.size} actions")
        } catch (e: Exception) {
            Log.e("Listener", "Error processing notification: ${e.message}", e)
        }
    }

    // --- REMOÇÃO (Telefone -> Watch) ---
    override fun onNotificationRemoved(
            sbn: StatusBarNotification?,
            rankingMap: RankingMap?,
            reason: Int
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return

        val isMirroringEnabled = activePrefs.getBoolean("notification_mirroring_enabled", false)
        if (!isMirroringEnabled) return

        // 如果这通知刚才回复过，暂时忽略移除指令，给用户留时间连发
        val lastReplyTime = lastReplyTimes[sbn.key] ?: 0L
        if (System.currentTimeMillis() - lastReplyTime < 5000) {
            Log.d("Listener", "Ignorando remoção de notificação após resposta recente: ${sbn.key}")
            return
        }

        // Clean up actions map
        notificationActionsMap.remove(sbn.key)
        lastReplyTimes.remove(sbn.key)

        val intent = Intent(BluetoothService.ACTION_SEND_REMOVE_TO_WATCH)
        intent.putExtra(BluetoothService.EXTRA_NOTIF_KEY, sbn.key)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // --- HELPER FUNCTIONS ---

    private fun extractLargeIcon(notification: android.app.Notification): String? {
        try {
            // Try getting large icon from notification (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val icon = notification.getLargeIcon()
                if (icon != null) {
                    Log.d("Listener", "Found large icon via getLargeIcon()")
                    val base64 = iconToBase64(icon)
                    if (base64 != null) return base64
                }
            }

            val extras = notification.extras
            val keysToTry = arrayOf("android.largeIcon", "android.largeIcon.big", "android.pictureIcon")
            
            for (key in keysToTry) {
                val obj = extras.get(key)
                if (obj != null) {
                    Log.d("Listener", "Extra '$key' found, type: ${obj.javaClass.simpleName}")
                    val base64 = objToBase64(obj)
                    if (base64 != null) {
                        Log.d("Listener", "Successfully extracted icon from '$key'")
                        return base64
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting large icon: ${e.message}", e)
        }
        return null
    }

    private fun extractGroupIcon(notification: android.app.Notification): String? {
        try {
            val extras = notification.extras

            // 1. Try EXTRA_CONVERSATION_ICON (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val icon = extras.getParcelable<android.graphics.drawable.Icon>("android.conversationIcon")
                if (icon != null) {
                    val drawable = icon.loadDrawable(this)
                    if (drawable != null) {
                        Log.d("Listener", "Found group icon as conversationIcon")
                        return bitmapToBase64(drawableToBitmap(drawable))
                    }
                }
            }

            // 2. Try EXTRA_LARGE_ICON_BIG (standard for messaging)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val icon = extras.getParcelable<android.graphics.drawable.Icon>("android.largeIcon.big")
                if (icon != null) {
                    val drawable = icon.loadDrawable(this)
                    if (drawable != null) {
                        Log.d("Listener", "Found group icon as largeIcon.big")
                        return bitmapToBase64(drawableToBitmap(drawable))
                    }
                }
            }

            // 3. Try messagingStyleUser icon (for group conversations)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val messagingUser = extras.getParcelable<android.app.Person>("android.messagingStyleUser")
                if (messagingUser?.icon != null) {
                    val drawable = messagingUser.icon!!.loadDrawable(this)
                    if (drawable != null) {
                        Log.d("Listener", "Found group icon from messagingStyleUser")
                        return bitmapToBase64(drawableToBitmap(drawable))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting group icon: ${e.message}")
        }
        return null
    }

    private fun objToBase64(obj: Any?): String? {
        if (obj == null) return null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && obj is android.graphics.drawable.Icon) {
                return iconToBase64(obj)
            } else if (obj is Bitmap) {
                return bitmapToBase64(obj)
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error converting ${obj.javaClass.simpleName} to base64: ${e.message}")
        }
        return null
    }

    private fun iconToBase64(icon: android.graphics.drawable.Icon): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val drawable = icon.loadDrawable(this)
                if (drawable != null) {
                    return bitmapToBase64(drawableToBitmap(drawable))
                }
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error loading drawable from icon: ${e.message}")
        }
        return null
    }

    private fun extractBigPicture(notification: android.app.Notification): String? {
        try {
            val extras = notification.extras

            // Try to get big picture from extras
            val bigPictureBitmap = extras.getParcelable<Bitmap>("android.picture")
            if (bigPictureBitmap != null) {
                Log.d("Listener", "Found big picture as Bitmap")
                return bitmapToBase64(bigPictureBitmap)
            }

            // Try as Icon (if android.pictureIcon exists)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pictureIcon =
                        extras.getParcelable<android.graphics.drawable.Icon>("android.pictureIcon")
                if (pictureIcon != null) {
                    val drawable = pictureIcon.loadDrawable(this)
                    if (drawable != null) {
                        Log.d("Listener", "Found big picture as Icon")
                        return bitmapToBase64(drawableToBitmap(drawable))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting big picture: ${e.message}")
        }
        return null
    }

    private fun extractMessages(
            notification: android.app.Notification
    ): List<NotificationMessageData> {
        val messagesList = mutableListOf<NotificationMessageData>()
        try {
            val extras = notification.extras
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val messages = extras.getParcelableArray("android.messages")
                if (messages != null) {
                    for (msg in messages) {
                        val m = msg as? android.os.Bundle ?: continue
                        val text = m.getCharSequence("text")?.toString() ?: ""
                        val timestamp = m.getLong("time")
                        val person = m.getParcelable<android.app.Person>("sender_person")

                        var iconBase64: String? = null
                        if (person?.icon != null) {
                            val drawable = person.icon!!.loadDrawable(this)
                            if (drawable != null) {
                                iconBase64 = bitmapToBase64(drawableToBitmap(drawable))
                            }
                        }

                        messagesList.add(
                                NotificationMessageData(
                                        text = text,
                                        timestamp = timestamp,
                                        senderName = person?.name?.toString(),
                                        senderIcon = iconBase64
                                )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting messages: ${e.message}")
        }
        return messagesList
    }

    private fun extractSenderInfo(notification: android.app.Notification): Pair<String?, String?> {
        try {
            val extras = notification.extras

            // Try to extract from MessagingStyle
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Try to get messages
                val messages = extras.getParcelableArray("android.messages")
                if (messages != null && messages.isNotEmpty()) {
                    // Get the last message
                    val lastMessage = messages.last() as? android.os.Bundle
                    if (lastMessage != null) {
                        val person = lastMessage.getParcelable<android.app.Person>("sender_person")
                        if (person != null) {
                            var iconBase64: String? = null
                            if (person.icon != null) {
                                iconBase64 = iconToBase64(person.icon!!)
                            }
                            return Pair(iconBase64, person.name?.toString())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting sender info: ${e.message}")
        }
        return Pair(null, null)
    }

    private fun extractSelfInfo(notification: android.app.Notification): Pair<String?, String?> {
        try {
            val extras = notification.extras

            // Extract self user from android.messagingUser (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val selfUser = extras.get("android.messagingUser")
                if (selfUser is android.app.Person) {
                    var iconBase64: String? = null
                    if (selfUser.icon != null) {
                        iconBase64 = iconToBase64(selfUser.icon!!)
                    }
                    return Pair(iconBase64, selfUser.name?.toString())
                }
            }

            // Fallback: try selfDisplayName
            val selfDisplayName = extras.getCharSequence("android.selfDisplayName")?.toString()
            if (selfDisplayName != null) {
                return Pair(null, selfDisplayName)
            }
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting self info: ${e.message}")
        }
        return Pair(null, null)
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

    private fun extractAppName(packageName: String): String {
        try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            return pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.e("Listener", "Error extracting app name: ${e.message}")
            return packageName // Fallback to package name
        }
    }

    private fun extractActions(
            notifKey: String,
            notification: android.app.Notification
    ): List<NotificationActionData> {
        val actionsList = mutableListOf<NotificationActionData>()
        val actionsMap = mutableMapOf<String, android.app.Notification.Action>()

        try {
            notification.actions?.forEachIndexed { index, action ->
                if (action != null && action.title != null) {
                    val actionKey = "action_$index"
                    val isReply = action.remoteInputs?.any { it.allowFreeFormInput } ?: false

                    actionsList.add(
                            NotificationActionData(
                                    title = action.title.toString(),
                                    actionKey = actionKey,
                                    isReplyAction = isReply
                            )
                    )

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
            val actionsMap =
                    notificationActionsMap[notifKey]
                            ?: run {
                                Log.e("Listener", "No actions found for notification: $notifKey")
                                return
                            }

            val action =
                    actionsMap[actionKey]
                            ?: run {
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

                    // Set result source to convince the target app that this is a real user reply
                    // (API 28+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
                    }

                    action.actionIntent?.send(this, 0, intent)

                    // Track reply time to ignore the immediate dismissal event
                    lastReplyTimes[notifKey] = System.currentTimeMillis()

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

        val bitmap =
                Bitmap.createBitmap(
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
        val resizedBitmap =
                if (bitmap.width > 512 || bitmap.height > 512) {
                    val scale = 512f / maxOf(bitmap.width, bitmap.height)
                    val newWidth = (bitmap.width * scale).toInt()
                    val newHeight = (bitmap.height * scale).toInt()
                    Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                } else {
                    bitmap
                }

        // Use PNG to preserve transparency (critical for notification icons)
        resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()

        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        Log.d("Listener", "Compressed icon to ${byteArray.size} bytes")
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
