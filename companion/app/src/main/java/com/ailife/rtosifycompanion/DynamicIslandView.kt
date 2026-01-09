package com.ailife.rtosifycompanion

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding

class DynamicIslandView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "DynamicIslandView"
        private const val PILL_HEIGHT_COLLAPSED_DP = 40
        private const val PILL_HEIGHT_EXPANDED_DP = 80
        private const val PILL_WIDTH_COLLAPSED_DP = 150
        private const val PILL_WIDTH_EXPANDED_DP = 380
        private const val ICON_SIZE_DP = 36
        private const val STACKED_ICON_SIZE_DP = 28
        private const val CORNER_RADIUS_DP = 20f
    }

    var onNotificationClick: ((NotificationData) -> Unit)? = null
    var onNotificationDismiss: ((NotificationData) -> Unit)? = null
    var onNotificationReply: ((NotificationData, String) -> Unit)? = null

    private val pillContainer: FrameLayout
    private val contentContainer: LinearLayout
    private val iconContainer: LinearLayout
    private val expandedContainer: ScrollView
    private val expandedList: LinearLayout

    private var currentState: State = State.IDLE

    private enum class State {
        IDLE,
        DISCONNECTED,
        CHARGING,
        NOTIFICATION_EXPANDED,
        NOTIFICATION_COLLAPSED,
        LIST_EXPANDED
    }

    init {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        clipChildren = false
        clipToPadding = false

        // Main pill container
        pillContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                dpToPx(PILL_WIDTH_COLLAPSED_DP),
                dpToPx(PILL_HEIGHT_COLLAPSED_DP)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(8)
            }
            background = createPillBackground()
            elevation = dpToPx(8).toFloat()
            clipChildren = false
            clipToPadding = false
        }

        // Content container (for text/icons)
        contentContainer = LinearLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(12))
        }

        // Icon container (for stacked notification icons)
        iconContainer = LinearLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
        }

        // Expanded list container
        expandedList = LinearLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        expandedContainer = ScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
            visibility = GONE
            addView(expandedList)
        }

        pillContainer.addView(contentContainer)
        pillContainer.addView(iconContainer)

        addView(pillContainer)
        addView(expandedContainer)
    }

    private fun createPillBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(CORNER_RADIUS_DP).toFloat()
            setColor(Color.parseColor("#1C1C1E")) // Dark gray/black
        }
    }

    fun showIdleState() {
        if (currentState == State.IDLE) return
        currentState = State.IDLE

        expandedContainer.visibility = GONE
        pillContainer.alpha = 1f

        animateToCollapsed {
            contentContainer.removeAllViews()
            iconContainer.visibility = GONE
        }
    }

    fun showDisconnectedState() {
        if (currentState == State.DISCONNECTED) return
        currentState = State.DISCONNECTED

        expandedContainer.visibility = GONE
        pillContainer.alpha = 1f
        contentContainer.visibility = VISIBLE

        contentContainer.removeAllViews()
        iconContainer.visibility = GONE

        // Show bluetooth disconnected icon
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(ICON_SIZE_DP),
                dpToPx(ICON_SIZE_DP)
            )
            setImageResource(android.R.drawable.stat_sys_data_bluetooth)
            setColorFilter(Color.parseColor("#FF453A")) // Red color
        }
        contentContainer.addView(icon)

        animateToCollapsed {}
    }

    fun showChargingState(batteryPercent: Int) {
        if (currentState == State.CHARGING) {
            // Just update the percentage
            updateChargingDisplay(batteryPercent)
            return
        }
        currentState = State.CHARGING

        expandedContainer.visibility = GONE
        pillContainer.alpha = 1f
        contentContainer.visibility = VISIBLE

        contentContainer.removeAllViews()
        iconContainer.visibility = GONE

        // Circular progress (simplified - using a colored view)
        val progressRing = createCircularProgress(batteryPercent)

        // Battery percentage text
        val percentText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
            text = "$batteryPercent%"
            textSize = 16f
            setTextColor(Color.WHITE)
            tag = "percent_text"
        }

        contentContainer.addView(progressRing)
        contentContainer.addView(percentText)

        animateToCollapsed {}
    }

    private fun updateChargingDisplay(batteryPercent: Int) {
        val percentText = contentContainer.findViewWithTag<TextView>("percent_text")
        percentText?.text = "$batteryPercent%"
        // Update progress ring if needed
    }

    private fun createCircularProgress(percent: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(32),
                dpToPx(32)
            ).apply {
                marginEnd = dpToPx(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dpToPx(3), Color.parseColor("#30D158")) // Green
                // This is simplified - in production you'd use a custom drawable for progress
            }
        }
    }

    fun expandWithNotification(notif: NotificationData) {
        currentState = State.NOTIFICATION_EXPANDED

        expandedContainer.visibility = GONE
        iconContainer.visibility = GONE
        contentContainer.visibility = VISIBLE
        contentContainer.removeAllViews()

        // Create notification content layout BEFORE animation
        val notifLayout = createNotificationExpandedLayout(notif)
        contentContainer.addView(notifLayout)

        // Now animate to show it
        animateToExpanded {}
    }

    private fun createNotificationExpandedLayout(notif: NotificationData): View {
        return LinearLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))

            // Sender/App icon on the left
            val iconFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(ICON_SIZE_DP),
                    dpToPx(ICON_SIZE_DP)
                ).apply {
                    marginEnd = dpToPx(12)
                }
            }

            // Main icon (sender or app icon)
            val mainIcon = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dpToPx(ICON_SIZE_DP),
                    dpToPx(ICON_SIZE_DP)
                )
                scaleType = ImageView.ScaleType.CENTER_CROP

                // Priority: senderIcon > groupIcon > largeIcon > smallIcon
                val iconBitmap = when {
                    notif.senderIcon != null -> decodeBase64ToBitmap(notif.senderIcon)
                    notif.groupIcon != null -> decodeBase64ToBitmap(notif.groupIcon)
                    notif.largeIcon != null -> decodeBase64ToBitmap(notif.largeIcon)
                    notif.smallIcon != null -> decodeBase64ToBitmap(notif.smallIcon)
                    else -> null
                }

                if (iconBitmap != null) {
                    setImageBitmap(getCircularBitmap(iconBitmap))
                } else {
                    setImageResource(android.R.drawable.ic_dialog_info)
                }
            }
            iconFrame.addView(mainIcon)

            // Small overlay icon at bottom right (app icon)
            if (notif.smallIcon != null && notif.senderIcon != null) {
                val smallIcon = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        dpToPx(16),
                        dpToPx(16)
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    decodeBase64ToBitmap(notif.smallIcon)?.let {
                        setImageBitmap(getCircularBitmap(it))
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                    }
                }
                iconFrame.addView(smallIcon)
            }

            addView(iconFrame)

            // Text content (title and text)
            val textContainer = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LayoutParams.WRAP_CONTENT,
                    1f
                )
                orientation = LinearLayout.VERTICAL
            }

            // Title
            val title = TextView(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
                text = notif.title
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // Slide animation from center
                alpha = 0f
                translationX = 50f
                animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(300)
                    .start()
            }

            // Content text
            val contentText = TextView(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
                text = notif.text
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                // Slide animation from center
                alpha = 0f
                translationX = 50f
                animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(300)
                    .setStartDelay(100)
                    .start()
            }

            textContainer.addView(title)
            textContainer.addView(contentText)
            addView(textContainer)
        }
    }

    fun collapseToIcons(notifications: List<NotificationData>) {
        currentState = State.NOTIFICATION_COLLAPSED

        expandedContainer.visibility = GONE
        pillContainer.alpha = 1f

        contentContainer.removeAllViews()
        contentContainer.visibility = GONE
        iconContainer.visibility = VISIBLE
        iconContainer.removeAllViews()

        // Show up to 3 stacked icons, then "+N"
        val maxIcons = 3
        val iconsToShow = notifications.take(maxIcons)

        iconsToShow.forEachIndexed { index, notif ->
                val icon = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        dpToPx(STACKED_ICON_SIZE_DP),
                        dpToPx(STACKED_ICON_SIZE_DP)
                    ).apply {
                        // Overlap icons by 50%
                        if (index > 0) {
                            marginStart = -dpToPx(STACKED_ICON_SIZE_DP / 2)
                        }
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    elevation = (maxIcons - index).toFloat() * dpToPx(2)

                    val iconBitmap = when {
                        notif.senderIcon != null -> decodeBase64ToBitmap(notif.senderIcon)
                        notif.smallIcon != null -> decodeBase64ToBitmap(notif.smallIcon)
                        else -> null
                    }

                    if (iconBitmap != null) {
                        setImageBitmap(getCircularBitmap(iconBitmap))
                    } else {
                        setImageResource(android.R.drawable.ic_dialog_info)
                    }

                    // Add white border
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke(dpToPx(2), Color.WHITE)
                    }
                }
                iconContainer.addView(icon)
            }

            // Show "+N" if more than maxIcons
            if (notifications.size > maxIcons) {
                val moreText = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        dpToPx(STACKED_ICON_SIZE_DP),
                        dpToPx(STACKED_ICON_SIZE_DP)
                    ).apply {
                        marginStart = -dpToPx(STACKED_ICON_SIZE_DP / 2)
                    }
                    text = "+${notifications.size - maxIcons}"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#FF9500")) // Orange
                        setStroke(dpToPx(2), Color.WHITE)
                    }
                }
                iconContainer.addView(moreText)
            }

        animateToCollapsed {}
    }

    fun expandToList(notifications: List<NotificationData>) {
        currentState = State.LIST_EXPANDED

        // Hide pill content and show list instead
        contentContainer.visibility = GONE
        iconContainer.visibility = GONE

        // Collapse pill back to small size
        animateToCollapsed {
            // Once pill is small, hide it and show the list
            pillContainer.alpha = 0f
            expandedContainer.visibility = VISIBLE
            expandedList.removeAllViews()

            notifications.forEach { notif ->
                val itemView = createNotificationListItem(notif)
                expandedList.addView(itemView)
            }
        }
    }

    fun updateNotificationQueue(notifications: List<NotificationData>) {
        if (currentState == State.NOTIFICATION_COLLAPSED) {
            collapseToIcons(notifications)
        } else if (currentState == State.LIST_EXPANDED) {
            expandToList(notifications)
        }
    }

    private fun createNotificationListItem(notif: NotificationData): View {
        return LinearLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#2C2C2E"))
            }

            // Top row: icon + title + dismiss button
            val topRow = LinearLayout(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Icon
            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(32),
                    dpToPx(32)
                ).apply {
                    marginEnd = dpToPx(8)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP

                val iconBitmap = when {
                    notif.senderIcon != null -> decodeBase64ToBitmap(notif.senderIcon)
                    notif.smallIcon != null -> decodeBase64ToBitmap(notif.smallIcon)
                    else -> null
                }

                if (iconBitmap != null) {
                    setImageBitmap(getCircularBitmap(iconBitmap))
                } else {
                    setImageResource(android.R.drawable.ic_dialog_info)
                }
            }
            topRow.addView(icon)

            // Title
            val title = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LayoutParams.WRAP_CONTENT,
                    1f
                )
                text = notif.title
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
            }
            topRow.addView(title)

            // Dismiss button
            val dismissBtn = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(24),
                    dpToPx(24)
                )
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.GRAY)
                setOnClickListener {
                    onNotificationDismiss?.invoke(notif)
                }
            }
            topRow.addView(dismissBtn)

            addView(topRow)

            // Content text
            val content = TextView(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(4)
                }
                text = notif.text
                textSize = 13f
                setTextColor(Color.parseColor("#AAAAAA"))
                maxLines = 3
            }
            addView(content)

            // Action buttons
            if (notif.actions.isNotEmpty()) {
                val actionRow = LinearLayout(context).apply {
                    layoutParams = LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(8)
                    }
                    orientation = LinearLayout.HORIZONTAL
                }

                notif.actions.forEach { action ->
                    if (action.isReplyAction) {
                        // Reply action - show input field
                        val replyBtn = Button(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LayoutParams.WRAP_CONTENT,
                                1f
                            ).apply {
                                marginEnd = dpToPx(8)
                            }
                            text = action.title
                            textSize = 12f
                            setOnClickListener {
                                showReplyDialog(notif)
                            }
                        }
                        actionRow.addView(replyBtn)
                    } else {
                        // Regular action
                        val actionBtn = Button(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LayoutParams.WRAP_CONTENT,
                                1f
                            ).apply {
                                marginEnd = dpToPx(8)
                            }
                            text = action.title
                            textSize = 12f
                            setOnClickListener {
                                // Execute action
                                val intent = android.content.Intent(BluetoothService.ACTION_CMD_EXECUTE_ACTION).apply {
                                    putExtra(BluetoothService.EXTRA_NOTIF_KEY, notif.key)
                                    putExtra(BluetoothService.EXTRA_ACTION_KEY, action.actionKey)
                                    setPackage(context.packageName)
                                }
                                context.sendBroadcast(intent)
                                onNotificationDismiss?.invoke(notif)
                            }
                        }
                        actionRow.addView(actionBtn)
                    }
                }

                addView(actionRow)
            }

            // Click to dismiss
            setOnClickListener {
                onNotificationClick?.invoke(notif)
            }
        }
    }

    private fun showReplyDialog(notif: NotificationData) {
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("Reply")
            .setView(EditText(context).apply {
                hint = "Type your reply..."
                inputType = InputType.TYPE_CLASS_TEXT
                id = View.generateViewId()
            })
            .setPositiveButton("Send") { dialog, _ ->
                val editText = (dialog as android.app.AlertDialog).findViewById<EditText>(View.generateViewId())
                val replyText = editText?.text?.toString() ?: ""
                if (replyText.isNotEmpty()) {
                    onNotificationReply?.invoke(notif, replyText)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun animateToCollapsed(onEnd: () -> Unit) {
        contentContainer.visibility = VISIBLE
        val targetWidth = dpToPx(PILL_WIDTH_COLLAPSED_DP)
        val targetHeight = dpToPx(PILL_HEIGHT_COLLAPSED_DP)
        animateSize(pillContainer, pillContainer.width, targetWidth, pillContainer.height, targetHeight, onEnd)
    }

    private fun animateToExpanded(onEnd: () -> Unit) {
        val targetWidth = dpToPx(PILL_WIDTH_EXPANDED_DP)
        val targetHeight = dpToPx(PILL_HEIGHT_EXPANDED_DP)
        animateSize(pillContainer, pillContainer.width, targetWidth, pillContainer.height, targetHeight, onEnd)
    }

    private fun animateSize(view: View, fromWidth: Int, toWidth: Int, fromHeight: Int, toHeight: Int, onEnd: () -> Unit = {}) {
        val widthAnimator = ValueAnimator.ofInt(fromWidth, toWidth)
        val heightAnimator = ValueAnimator.ofInt(fromHeight, toHeight)

        widthAnimator.addUpdateListener { animation ->
            val lp = view.layoutParams
            lp.width = animation.animatedValue as Int
            view.layoutParams = lp
        }

        heightAnimator.addUpdateListener { animation ->
            val lp = view.layoutParams
            lp.height = animation.animatedValue as Int
            view.layoutParams = lp
        }

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(widthAnimator, heightAnimator)
        animatorSet.duration = 300
        animatorSet.interpolator = DecelerateInterpolator()
        animatorSet.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onEnd()
            }
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
        animatorSet.start()
    }

    private fun animateWidth(view: View, from: Int, to: Int, onEnd: () -> Unit = {}) {
        val animator = ValueAnimator.ofInt(from, to)
        animator.addUpdateListener { animation ->
            val lp = view.layoutParams
            lp.width = animation.animatedValue as Int
            view.layoutParams = lp
        }
        animator.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onEnd()
            }
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
        animator.duration = 300
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    private fun animateHeight(view: View, from: Int, to: Int, onEnd: () -> Unit = {}) {
        val animator = ValueAnimator.ofInt(from, to)
        animator.addUpdateListener { animation ->
            val lp = view.layoutParams
            lp.height = animation.animatedValue as Int
            view.layoutParams = lp
        }
        animator.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onEnd()
            }
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
        animator.duration = 300
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    private fun decodeBase64ToBitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode bitmap: ${e.message}")
            null
        }
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }

        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val srcRect = Rect(
            (bitmap.width - size) / 2,
            (bitmap.height - size) / 2,
            (bitmap.width + size) / 2,
            (bitmap.height + size) / 2
        )
        val dstRect = Rect(0, 0, size, size)

        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

        return output
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
