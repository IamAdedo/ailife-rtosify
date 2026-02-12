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

    private var notificationKey: String? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_notification)

        initViews()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

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
            finish()
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
            finish()
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
        
        if (isFileDetected) {
            val json = intent.getStringExtra("file_data_json")
            if (json != null) {
                try {
                    val fileData = gson.fromJson(json, FileDetectedData::class.java)
                    displayFileDetected(fileData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing file data: ${e.message}")
                    finish()
                }
            }
        } else {
             notificationKey = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
             val json = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_JSON)
             
             if (json != null) {
                 try {
                     val notifData = gson.fromJson(json, NotificationData::class.java)
                     displayNotification(notifData)
                 } catch (e: Exception) {
                     Log.e(TAG, "Error parsing notification data: ${e.message}")
                     // Fallback to cache if key exists?
                     if (notificationKey != null) {
                         val cached = NotificationCache.get(notificationKey!!)
                         if (cached != null) {
                             displayNotification(cached)
                         } else {
                             finish()
                         }
                     } else {
                         finish()
                     }
                 }
             } else if (notificationKey != null) {
                 val cached = NotificationCache.get(notificationKey!!)
                 if (cached != null) {
                     displayNotification(cached)
                 } else {
                     finish()
                 }
             }
        }
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

    private fun displayFileDetected(data: FileDetectedData) {
        tvAppName.text = data.notificationTitle ?: getString(R.string.notification_file_detected_title)
        tvTitle.text = data.name
        
        // Build text with type, size, and duration if available
        val sizeText = android.text.format.Formatter.formatFileSize(this, data.size)
        val durationText = data.duration?.let { " • ${formatDuration(it)}" } ?: ""
        val typeText = "Type: ${data.type}"
        tvText.text = "$typeText • $sizeText$durationText"
        
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        tvTime.text = timeFormat.format(java.util.Date(data.timestamp))

        // Icon for file detected - use large icon if available, otherwise use file type icon
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
        val fileTypeIcon = when (data.type) {
            "image" -> android.R.drawable.ic_menu_gallery
            "video" -> android.R.drawable.ic_media_play
            "audio" -> android.R.drawable.ic_lock_silent_mode_off
            "text" -> android.R.drawable.ic_menu_edit
            else -> null
        }
        if (fileTypeIcon != null && data.largeIcon != null) {
            imgSmallIcon.setImageResource(fileTypeIcon)
            imgSmallIcon.visibility = View.VISIBLE
            
            // Apply dark background for visibility in light mode
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

        // Thumbnail or text content
        val isText = data.type == "text"
        val isImage = data.type == "image"
        val isVideo = data.type == "video"
        
        if (isText && !data.textContent.isNullOrEmpty()) {
            // Show text preview
            tvText.text = "$typeText • $sizeText\n\n${data.textContent}"
            imgBigPicture.visibility = View.GONE
        } else if (data.thumbnail != null) {
            val thumb = decodeBase64(data.thumbnail)
            if (thumb != null) {
                imgBigPicture.setImageBitmap(thumb)
                imgBigPicture.visibility = View.VISIBLE
                
                // Make thumbnail clickable for images and videos
                if (isImage || isVideo) {
                    imgBigPicture.isClickable = true
                    imgBigPicture.setOnClickListener {
                        val intent = Intent(this@FullScreenNotificationActivity, FullScreenMediaActivity::class.java)
                        intent.putExtra(FullScreenMediaActivity.EXTRA_DATA_JSON, Gson().toJson(data))
                        startActivity(intent)
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
        btnDismiss.visibility = View.GONE // User requested no dismiss button

        // Add Play/View actions
        val isAudio = data.type == "audio"
        
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
                    // Open FullScreenMediaActivity for audio playback
                    val intent = Intent(this@FullScreenNotificationActivity, FullScreenMediaActivity::class.java)
                    intent.putExtra(FullScreenMediaActivity.EXTRA_DATA_JSON, Gson().toJson(data))
                    startActivity(intent)
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
                     // Open FullScreenMediaActivity
                     val intent = Intent(this@FullScreenNotificationActivity, FullScreenMediaActivity::class.java)
                     intent.putExtra(FullScreenMediaActivity.EXTRA_DATA_JSON, Gson().toJson(data))
                     startActivity(intent)
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
        
        // Standard Dismiss
        btnDismiss.setOnClickListener {
            // Send dismiss to phone
            val intent = Intent(BluetoothService.ACTION_WATCH_DISMISSED_LOCAL)
            intent.putExtra(BluetoothService.EXTRA_NOTIF_KEY, data.key)
            sendBroadcast(intent)
            finish()
        }

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
                // Launch Reply Dialog
                val replyIntent = Intent(this, ReplyDialogActivity::class.java)
                replyIntent.putExtra(BluetoothService.EXTRA_NOTIF_KEY, data.key)
                // We need the action key for the reply. Usually the first reply action.
                val replyAction = data.actions.firstOrNull { it.isReplyAction }
                if (replyAction != null) {
                    replyIntent.putExtra(BluetoothService.EXTRA_ACTION_KEY, replyAction.actionKey)
                    startActivity(replyIntent)
                    finish()
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
                finish()
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


