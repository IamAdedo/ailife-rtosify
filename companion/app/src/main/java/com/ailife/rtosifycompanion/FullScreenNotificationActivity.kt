package com.ailife.rtosifycompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import com.google.android.material.button.MaterialButton
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ailife.rtosifycompanion.ProtocolHelper
import com.ailife.rtosifycompanion.BluetoothService
import com.ailife.rtosifycompanion.NotificationData
import com.ailife.rtosifycompanion.FileDetectedData
import com.google.gson.Gson
import androidx.core.content.ContextCompat
import android.view.WindowManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable

class FullScreenNotificationActivity : AppCompatActivity() {

    private lateinit var tvAppName: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvText: TextView
    private lateinit var imgAppIcon: ImageView
    private lateinit var imgSmallIcon: ImageView
    private lateinit var imgBigPicture: ImageView
    private lateinit var btnClose: View
    private lateinit var containerActions: LinearLayout
    private lateinit var btnReply: MaterialButton
    private lateinit var btnDismiss: MaterialButton

    private val notifications = mutableListOf<NotificationData>()
    private var currentIndex = 0
    private var isFileDetected: Boolean = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val closeOnScreenOff = prefs.getBoolean("full_screen_close_on_screen_off", false)
                if (closeOnScreenOff) {
                    finish()
                }
            }
        }
    }

    private val notificationDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothService.ACTION_DISMISS_FROM_FULL_SCREEN) {
                val key = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
                if (key != null) {
                    val index = notifications.indexOfFirst { it.key == key }
                    if (index != -1) {
                        Log.d(TAG, "Dismissing notification from full screen due to remote removal: $key")
                        notifications.removeAt(index)
                        if (notifications.isEmpty()) {
                            finish()
                        } else {
                            if (currentIndex >= notifications.size) {
                                currentIndex = notifications.size - 1
                            }
                            displayCurrentNotification()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.activity.enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_notification)

        EdgeToEdgeUtils.applyEdgeToEdge(this, findViewById(R.id.rootLayout))

        initViews()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothService.ACTION_DISMISS_FROM_FULL_SCREEN)
        }
        registerReceiver(screenOffReceiver, filter)
        registerReceiver(notificationDismissReceiver, filter)

        processIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private val autoCloseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoCloseRunnable = Runnable { finish() }
    private var actionTextSize: Float = 20f

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenOffReceiver)
        unregisterReceiver(notificationDismissReceiver)
        autoCloseHandler.removeCallbacks(autoCloseRunnable)
    }

    private fun initViews() {
        tvAppName = findViewById(R.id.tvAppName)
        tvTime = findViewById(R.id.tvTime)
        tvTitle = findViewById(R.id.tvTitle)
        tvText = findViewById(R.id.tvText)
        imgAppIcon = findViewById(R.id.imgAppIcon)
        imgSmallIcon = findViewById(R.id.imgSmallIcon)
        imgBigPicture = findViewById(R.id.imgBigPicture)
        btnClose = findViewById(R.id.btnClose)
        containerActions = findViewById(R.id.containerActions)
        btnReply = findViewById(R.id.btnReply)
        btnDismiss = findViewById(R.id.btnDismiss)

        btnClose.setOnClickListener {
            popNotification(dismissOnPhone = false)
        }

        btnDismiss.setOnClickListener {
            popNotification(dismissOnPhone = true)
        }
        
        applySettings()
    }

    private fun applySettings() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        
        // Text Sizes
        val appNameSize = prefs.getInt("full_screen_app_name_size", 20).toFloat()
        val titleSize = prefs.getInt("full_screen_title_size", 22).toFloat()
        val contentSize = prefs.getInt("full_screen_content_size", 18).toFloat()
        
        actionTextSize = appNameSize // Actions use same size as App Name
        
        tvAppName.textSize = appNameSize
        tvTitle.textSize = titleSize
        tvText.textSize = contentSize
        
        btnReply.textSize = actionTextSize
        btnDismiss.textSize = actionTextSize
        
        // Auto Close
        val autoCloseEnabled = prefs.getBoolean("full_screen_auto_close_enabled", false)
        if (autoCloseEnabled) {
            val timeout = prefs.getInt("full_screen_auto_close_timeout", 10)
            autoCloseHandler.removeCallbacks(autoCloseRunnable)
            autoCloseHandler.postDelayed(autoCloseRunnable, timeout * 1000L)
        }
        
        // Keep Screen On
        val keepScreenOn = prefs.getBoolean("full_screen_keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun processIntent(intent: Intent?) {
        if (intent == null) {
            if (notifications.isEmpty()) finish()
            return
        }
        
        // Reset auto-close timer on new intent/update
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val autoCloseEnabled = prefs.getBoolean("full_screen_auto_close_enabled", false)
        if (autoCloseEnabled) {
            val timeout = prefs.getInt("full_screen_auto_close_timeout", 10)
            autoCloseHandler.removeCallbacks(autoCloseRunnable)
            autoCloseHandler.postDelayed(autoCloseRunnable, timeout * 1000L)
        }

        val gson = Gson()
        
        isFileDetected = intent.getBooleanExtra("is_file_detected", false)
        val notificationKey = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
        val json = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_JSON)
        val fileDataJson = intent.getStringExtra("file_data_json")

        if (isFileDetected) {
            if (fileDataJson != null) {
                try {
                    val fileData = gson.fromJson(fileDataJson, FileDetectedData::class.java)
                    val notifData = fileToNotification(fileData)
                    addOrUpdateNotification(notifData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing file data: ${e.message}")
                    if (notifications.isEmpty()) finish()
                }
            }
        } else {
             if (json != null) {
                 try {
                     val notifData = gson.fromJson(json, NotificationData::class.java)
                     addOrUpdateNotification(notifData)
                 } catch (e: Exception) {
                     Log.e(TAG, "Error parsing notification data: ${e.message}")
                     if (notificationKey != null) {
                         val cached = NotificationCache.get(notificationKey)
                         if (cached != null) {
                             addOrUpdateNotification(cached)
                         } else if (notifications.isEmpty()) {
                             finish()
                         }
                     } else if (notifications.isEmpty()) {
                         finish()
                     }
                 }
             } else if (notificationKey != null) {
                 val cached = NotificationCache.get(notificationKey)
                 if (cached != null) {
                     addOrUpdateNotification(cached)
                 } else if (notifications.isEmpty()) {
                     finish()
                 }
             }
        }
    }

    private fun fileToNotification(data: FileDetectedData): NotificationData {
        val sizeText = android.text.format.Formatter.formatFileSize(this, data.size)
        val durationText = data.duration?.let { " • ${formatDuration(it)}" } ?: ""
        val typeText = "Type: ${data.type}"
        val textValue = "$typeText • $sizeText$durationText" + (data.textContent?.let { "\n\n$it" } ?: "")

        return NotificationData(
            packageName = "com.ailife.rtosify.file",
            title = data.name,
            text = textValue,
            key = "file_${data.path}",
            appName = data.notificationTitle ?: getString(R.string.notification_file_detected_title),
            largeIcon = data.largeIcon,
            bigPicture = data.thumbnail,
            fileType = data.type,
            textContent = data.textContent,
            localFilePath = data.path
        )
    }

    private fun addOrUpdateNotification(data: NotificationData) {
        val existingIndex = notifications.indexOfFirst { it.key == data.key }
        if (existingIndex != -1) {
            notifications[existingIndex] = data
            // If we are currently viewing this notification, refresh it
            if (currentIndex == existingIndex) {
                displayCurrentNotification()
            }
        } else {
            notifications.add(data)
            currentIndex = notifications.size - 1
            displayCurrentNotification()
        }
    }

    private fun displayCurrentNotification() {
        if (currentIndex in notifications.indices) {
            val data = notifications[currentIndex]
            if (data.packageName == "com.ailife.rtosify.file") {
                displayFileNotification(data)
            } else {
                displayNotification(data)
            }
            updateNavigationUI()
        } else if (notifications.isEmpty()) {
            finish()
        }
    }

    private fun updateNavigationUI() {
        // UI navigation removed as per user request
    }

    private fun popNotification(dismissOnPhone: Boolean) {
        if (currentIndex in notifications.indices) {
            val data = notifications[currentIndex]
            if (dismissOnPhone && data.packageName != "com.ailife.rtosify.file") {
                // Send dismiss to phone (only for regular notifications)
                val intent = Intent(BluetoothService.ACTION_WATCH_DISMISSED_LOCAL)
                intent.putExtra(BluetoothService.EXTRA_NOTIF_KEY, data.key)
                sendBroadcast(intent)
            }
            
            notifications.removeAt(currentIndex)
            if (notifications.isEmpty()) {
                finish()
            } else {
                if (currentIndex >= notifications.size) {
                    currentIndex = notifications.size - 1
                }
                displayCurrentNotification()
            }
        } else {
            finish()
        }
    }

    private fun dismissCurrentNotification() {
        popNotification(dismissOnPhone = true)
    }

    private fun displayNotification(data: NotificationData) {
        tvAppName.text = data.appName ?: "Unknown"
        tvTitle.text = data.title
        tvText.text = data.text
        
        // Time? Use current time or data.timestamp if available (Protocol doesn't send timestamp for notif, sadly, using current)
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        tvTime.text = timeFormat.format(java.util.Date())

        // Icons
        // Priority: Group Icon > Sender Icon > Large Icon > Small Icon
        val iconBitmap = when {
            data.isGroupConversation && data.groupIcon != null -> decodeBase64(data.groupIcon)
            data.isGroupConversation && data.largeIcon != null -> decodeBase64(data.largeIcon)
            data.senderIcon != null -> decodeBase64(data.senderIcon)
            data.groupIcon != null -> decodeBase64(data.groupIcon)
            data.largeIcon != null -> decodeBase64(data.largeIcon)
            data.smallIcon != null -> decodeBase64(data.smallIcon)
            else -> null
        }

        if (iconBitmap != null) {
            imgAppIcon.setImageBitmap(getCircularBitmap(iconBitmap))
        } else {
            imgAppIcon.setImageResource(R.mipmap.ic_launcher)
        }

        // Small Icon (Bottom Right)
        // Show if smallIcon exists AND (we are using a fancy icon OR it is a file type)
        // Basically if we have a main icon that is NOT the small icon, we show small icon as badge.
        // Or if we specifically have a small icon different from the one we showed.
        
        // DynamicIsland Logic simplifies to:
        // if (notif.fileType != null || (notif.smallIcon != null && (notif.senderIcon != null || notif.groupIcon != null || notif.largeIcon != null)))
        
        if (data.fileType != null || (data.smallIcon != null && (data.senderIcon != null || data.groupIcon != null || data.largeIcon != null))) {
             val smallIconBitmap = if (data.smallIcon != null) decodeBase64(data.smallIcon) else null
             
             if (smallIconBitmap != null) {
                 imgSmallIcon.setImageBitmap(getCircularBitmap(smallIconBitmap))
                 imgSmallIcon.visibility = View.VISIBLE
                 
                 // Apply dark background for visibility in light mode
                 imgSmallIcon.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#333333"))
                    setStroke(2.dpToPx(), Color.parseColor("#1C1C1E"))
                }
                // Padding to ensure icon doesn't touch the border
                val pad = 2.dpToPx()
                imgSmallIcon.setPadding(pad, pad, pad, pad)
                
             } else {
                 imgSmallIcon.visibility = View.GONE
             }
        } else {
             imgSmallIcon.visibility = View.GONE
        }

        // Big Picture
        if (data.bigPicture != null) {
            val bigPic = decodeBase64(data.bigPicture)
            if (bigPic != null) {
                imgBigPicture.setImageBitmap(bigPic)
                imgBigPicture.visibility = View.VISIBLE
            } else {
                imgBigPicture.visibility = View.GONE
            }
        } else {
            imgBigPicture.visibility = View.GONE
        }

        // Actions
        setupActions(data)
    }

    private fun displayFileNotification(data: NotificationData) {
        tvAppName.text = data.appName ?: getString(R.string.notification_file_detected_title)
        tvTitle.text = data.title
        tvText.text = data.text
        
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        tvTime.text = timeFormat.format(java.util.Date())

        // Icon for file detected
        if (data.largeIcon != null) {
            val largeIconBitmap = decodeBase64(data.largeIcon)
            if (largeIconBitmap != null) {
                imgAppIcon.setImageBitmap(largeIconBitmap)
            } else {
                imgAppIcon.setImageResource(R.drawable.ic_smartwatch_notification)
            }
        } else {
            imgAppIcon.setImageResource(R.drawable.ic_smartwatch_notification)
        }
        
        // Show small icon overlay based on file type
        val fileTypeIcon = when (data.fileType) {
            "image" -> android.R.drawable.ic_menu_gallery
            "video" -> android.R.drawable.ic_media_play
            "audio" -> android.R.drawable.ic_lock_silent_mode_off
            "text" -> android.R.drawable.ic_menu_edit
            else -> null
        }
        if (fileTypeIcon != null && data.largeIcon != null) {
            imgSmallIcon.setImageResource(fileTypeIcon)
            imgSmallIcon.visibility = View.VISIBLE
            imgSmallIcon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#333333"))
                setStroke(2.dpToPx(), Color.parseColor("#1C1C1E"))
            }
            val pad = 2.dpToPx()
            imgSmallIcon.setPadding(pad, pad, pad, pad)
        } else {
            imgSmallIcon.visibility = View.GONE
        }

        // Thumbnail
        if (data.bigPicture != null) {
            val thumb = decodeBase64(data.bigPicture)
            if (thumb != null) {
                imgBigPicture.setImageBitmap(thumb)
                imgBigPicture.visibility = View.VISIBLE
                
                if (data.fileType == "image" || data.fileType == "video") {
                    imgBigPicture.isClickable = true
                    imgBigPicture.setOnClickListener {
                        val mediaIntent = Intent(this@FullScreenNotificationActivity, FullScreenMediaActivity::class.java)
                        // Mock FileDetectedData for FullScreenMediaActivity if possible, 
                        // or better, just use the path.
                        // FullScreenMediaActivity expects EXTRA_DATA_JSON.
                        // We might need to store the original JSON if we want full compat.
                        // For now, let's see if we can just pass the path.
                        mediaIntent.putExtra(FullScreenMediaActivity.EXTRA_LOCAL_FILE_PATH, data.localFilePath)
                        mediaIntent.putExtra(FullScreenMediaActivity.EXTRA_NOTIFICATION_KEY, data.key)
                        startActivity(mediaIntent)
                        // In popNotification(true) logic? No, viewing shouldn't pop from stack usually,
                        // but user said "no matter what action is pressed".
                        // However, clicking the image to VIEW is a navigation.
                        // I'll leave it as is for now.
                    }
                }
            } else {
                imgBigPicture.visibility = View.GONE
            }
        } else {
            imgBigPicture.visibility = View.GONE
        }

        // Setup generic actions for file
        containerActions.removeAllViews()
        btnReply.visibility = View.GONE
        btnDismiss.visibility = View.VISIBLE
        btnDismiss.text = getString(R.string.action_dismiss)

        // Add Play/View actions
        val isAudio = data.fileType == "audio"
        val isImage = data.fileType == "image"
        val isVideo = data.fileType == "video"
        
        if (isAudio) {
             val btnPlay = Button(this).apply {
                text = getString(R.string.notification_file_play_audio)
                textSize = actionTextSize
                background = ContextCompat.getDrawable(this@FullScreenNotificationActivity, R.drawable.bg_action_button)
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4.dpToPx()
                }
                setOnClickListener {
                    val mediaIntent = Intent(this@FullScreenNotificationActivity, FullScreenMediaActivity::class.java)
                    mediaIntent.putExtra(FullScreenMediaActivity.EXTRA_LOCAL_FILE_PATH, data.localFilePath)
                    mediaIntent.putExtra(FullScreenMediaActivity.EXTRA_NOTIFICATION_KEY, data.key)
                    startActivity(mediaIntent)
                    popNotification(dismissOnPhone = false)
                }
            }
            containerActions.addView(btnPlay)
        }

        if (isImage || isVideo) {
             val btnView = Button(this).apply {
                text = getString(R.string.notification_file_view)
                textSize = actionTextSize
                background = ContextCompat.getDrawable(this@FullScreenNotificationActivity, R.drawable.bg_action_button)
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4.dpToPx()
                }
                setOnClickListener {
                     val mediaIntent = Intent(this@FullScreenNotificationActivity, FullScreenMediaActivity::class.java)
                     mediaIntent.putExtra(FullScreenMediaActivity.EXTRA_LOCAL_FILE_PATH, data.localFilePath)
                     mediaIntent.putExtra(FullScreenMediaActivity.EXTRA_NOTIFICATION_KEY, data.key)
                     startActivity(mediaIntent)
                     popNotification(dismissOnPhone = false)
                }
            }
            containerActions.addView(btnView)
        }
    }
    
    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun setupActions(data: NotificationData) {
        containerActions.removeAllViews()
        
        // Standard Dismiss logic moved to dismissCurrentNotification()

        // Reply
        // Check if there is a reply action in actions list or strictly use data.actions?
        // Protocol defines standard reply via separate message usually? 
        // Logic in NotificationActionReceiver handles replies. 
        // We'll verify if ANY action is a reply.
        
        var hasReply = false
        data.actions.forEach { action ->
            if (action.isReplyAction) {
                hasReply = true
                return@forEach
            }
        }

        if (hasReply) {
            btnReply.visibility = View.VISIBLE
            btnReply.setOnClickListener {
                // Cancel auto-close timer when replying
                autoCloseHandler.removeCallbacks(autoCloseRunnable)
                
                // Launch Reply Dialog
                val replyIntent = Intent(this, ReplyDialogActivity::class.java)
                replyIntent.putExtra(BluetoothService.EXTRA_NOTIF_KEY, data.key)
                // We need the action key for the reply. Usually the first reply action.
                val replyAction = data.actions.firstOrNull { it.isReplyAction }
                if (replyAction != null) {
                    replyIntent.putExtra(BluetoothService.EXTRA_ACTION_KEY, replyAction.actionKey)
                    startActivity(replyIntent)
                    popNotification(dismissOnPhone = false)
                }
            }
        } else {
            btnReply.visibility = View.GONE
        }

        // Custom Actions
        data.actions.filter { !it.isReplyAction }.forEach { action ->
            val btn = Button(this)
            btn.text = action.title
            btn.textSize = actionTextSize
            
            // Apply rounded background
            btn.background = ContextCompat.getDrawable(this, R.drawable.bg_action_button)
            
            btn.setTextColor(0xFFFFFFFF.toInt())
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 4.dpToPx()
            }
            
            btn.setOnClickListener {
                val intent = Intent(BluetoothService.ACTION_CMD_EXECUTE_ACTION)
                intent.putExtra(BluetoothService.EXTRA_NOTIF_KEY, data.key)
                intent.putExtra(BluetoothService.EXTRA_ACTION_KEY, action.actionKey)
                intent.setPackage(packageName)
                sendBroadcast(intent)
                popNotification(dismissOnPhone = false)
            }
            containerActions.addView(btn)
        }
    }

    private fun decodeBase64(input: String?): Bitmap? {
        if (input == null) return null
        return try {
            val decodedByte = Base64.decode(input, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedByte, 0, decodedByte.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val color = -0xbdbdbe
        val paint = Paint()
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)
        val roundPx = Math.min(bitmap.width, bitmap.height) / 2f
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = color
        canvas.drawRoundRect(rectF, roundPx, roundPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "FullScreenNotif"
    }
}


