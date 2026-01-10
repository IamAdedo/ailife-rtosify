package com.ailife.rtosifycompanion

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.view.setPadding

class DynamicIslandView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "DynamicIslandView"
        private const val PILL_HEIGHT_COLLAPSED_DP = 40
        private const val PILL_HEIGHT_EXPANDED_DP = 80
        private const val PILL_WIDTH_COLLAPSED_DP = 150
        private const val PILL_WIDTH_EXPANDED_DP = 380
        private var ICON_SIZE_DP = 36
        private var STACKED_ICON_SIZE_DP = 28
        private const val CORNER_RADIUS_DP = 20f
    }

    var onNotificationClick: ((NotificationData) -> Unit)? = null
    var onNotificationDismiss: ((NotificationData) -> Unit)? = null
    var onNotificationReply: ((NotificationData, String) -> Unit)? = null
    var onPillClick: (() -> Unit)? = null
    var onClearAllClicked: (() -> Unit)? = null

    private val pillContainer: FrameLayout
    private val contentContainer: LinearLayout
    private val iconContainer: LinearLayout
    private val expandedContainer: ScrollView
    private val expandedList: LinearLayout
    private val closeContainer: LinearLayout
    private var pillHeightCollapsed = PILL_HEIGHT_COLLAPSED_DP
    private var pillWidthCollapsed = PILL_WIDTH_COLLAPSED_DP
    private var pillHeightExpanded = PILL_HEIGHT_EXPANDED_DP
    private var pillWidthExpanded = PILL_WIDTH_EXPANDED_DP

    private var startY: Float = 0f

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
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        clipChildren = false
        clipToPadding = false

        // Main pill container
        pillContainer =
                FrameLayout(context).apply {
                    layoutParams =
                            LayoutParams(dpToPx(pillWidthCollapsed), dpToPx(pillHeightCollapsed))
                                    .apply { gravity = Gravity.CENTER_HORIZONTAL }
                    background = createPillBackground()
                    elevation = dpToPx(8).toFloat()
                    clipChildren = false
                    clipToPadding = false
                    setOnClickListener {
                        if (currentState == State.NOTIFICATION_COLLAPSED ||
                                        currentState == State.NOTIFICATION_EXPANDED ||
                                        currentState == State.LIST_EXPANDED
                        ) {
                            onPillClick?.invoke()
                        }
                    }
                }

        // Content container (for text/icons)
        contentContainer =
                LinearLayout(context).apply {
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    val hPadding = dpToPx(pillHeightCollapsed * 0.3f)
                    val vPadding = dpToPx(pillHeightCollapsed * 0.2f)
                    setPadding(hPadding, vPadding, hPadding, vPadding)
                }

        // Icon container (for stacked notification icons)
        iconContainer =
                LinearLayout(context).apply {
                    layoutParams =
                            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    visibility = GONE
                }

        // Close UI (for expanded list)
        closeContainer =
                LinearLayout(context).apply {
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    visibility = GONE
                    val vPadding = dpToPx(pillHeightCollapsed * 0.1f)
                    val hPadding = dpToPx(pillHeightCollapsed * 0.3f)
                    setPadding(hPadding, vPadding, hPadding, vPadding)

                    val closeText =
                            TextView(context).apply {
                                text = "Close"
                                textSize = pillHeightCollapsed * 0.35f
                                setTextColor(Color.WHITE)
                                typeface = Typeface.DEFAULT_BOLD
                            }

                    val arrow =
                            TextView(context).apply {
                                text = " ▴" // Small up arrow
                                textSize = pillHeightCollapsed * 0.35f
                                setTextColor(Color.WHITE)
                            }

                    addView(closeText)
                    addView(arrow)
                }

        // Expanded list container
        expandedList =
                LinearLayout(context).apply {
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    orientation = LinearLayout.VERTICAL
                }

        expandedContainer =
                ScrollView(context).apply {
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                                    .apply {
                                        topMargin = dpToPx(60)
                                    } // Pill height (40dp) + extra spacing (20dp)
                    visibility = GONE
                    addView(expandedList)
                }

        pillContainer.addView(contentContainer)
        pillContainer.addView(iconContainer)
        pillContainer.addView(closeContainer)

        addView(pillContainer)
        addView(expandedContainer)
    }

    private fun createPillBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#1C1C1E")) // Dark gray/black
            cornerRadius = dpToPx(pillHeightCollapsed / 2).toFloat()
        }
    }

    fun showIdleState() {
        if (currentState == State.IDLE) return
        currentState = State.IDLE

        expandedContainer.visibility = GONE
        pillContainer.alpha = 1f
        closeContainer.visibility = GONE
        contentContainer.visibility = VISIBLE
        iconContainer.visibility = GONE

        animateToCollapsed {
            resetContentPadding()
            contentContainer.removeAllViews()

            // Just show connected state by default in idle
            showConnectedState()
        }
    }

    fun showDisconnectedState() {
        if (currentState == State.DISCONNECTED) return
        currentState = State.DISCONNECTED

        expandedContainer.visibility = GONE
        pillContainer.alpha = 1f
        closeContainer.visibility = GONE
        contentContainer.visibility = VISIBLE
        iconContainer.visibility = GONE

        animateToCollapsed {
            resetContentPadding()
            contentContainer.removeAllViews()
            val container =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    }

            // Watch icon
            val iconSize = dpToPx(pillHeightCollapsed * 0.6f)
            val watchIcon =
                    ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        setImageResource(R.drawable.ic_smartwatch)
                        setColorFilter(Color.GRAY)
                    }

            // Crossed line visual
            val crossLine =
                    object : View(context) {
                                override fun onDraw(canvas: Canvas) {
                                    super.onDraw(canvas)
                                    val paint =
                                            Paint().apply {
                                                isAntiAlias = true
                                                strokeWidth = dpToPx(1.5f).toFloat()
                                            }
                                    val w = width.toFloat()
                                    val h = height.toFloat()

                                    // Draw gray horizontal line
                                    paint.color = Color.GRAY
                                    canvas.drawLine(0f, h / 2f, w, h / 2f, paint)

                                    // Draw red cross in the middle
                                    paint.color = Color.RED
                                    paint.strokeWidth = dpToPx(2).toFloat()
                                    val crossSize = dpToPx(4).toFloat()
                                    canvas.drawLine(
                                            w / 2f - crossSize,
                                            h / 2f - crossSize,
                                            w / 2f + crossSize,
                                            h / 2f + crossSize,
                                            paint
                                    )
                                    canvas.drawLine(
                                            w / 2f + crossSize,
                                            h / 2f - crossSize,
                                            w / 2f - crossSize,
                                            h / 2f + crossSize,
                                            paint
                                    )
                                }
                            }
                            .apply {
                                layoutParams =
                                        LinearLayout.LayoutParams(dpToPx(20), dpToPx(10)).apply {
                                            marginStart = dpToPx(2)
                                            marginEnd = dpToPx(2)
                                        }
                            }

            // Phone icon
            val phoneIcon =
                    ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        setImageResource(R.drawable.ic_smartphone)
                        setColorFilter(Color.GRAY)
                    }

            container.addView(watchIcon)
            container.addView(crossLine)
            container.addView(phoneIcon)
            contentContainer.addView(container)
        }
    }

    fun showConnectedState() {
        currentState = State.IDLE // Or a new state if needed, but IDLE implies connected active
        expandedContainer.visibility = GONE
        pillContainer.alpha = 1f
        closeContainer.visibility = GONE
        contentContainer.visibility = VISIBLE
        iconContainer.visibility = GONE

        animateToCollapsed {
            resetContentPadding()
            contentContainer.removeAllViews()
            val iconSize = dpToPx(pillHeightCollapsed * 0.6f)
            contentContainer.addView(
                    ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        setImageResource(
                                android.R.drawable.stat_sys_data_bluetooth
                        ) // Use generic BT icon
                        setColorFilter(Color.parseColor("#30D158")) // Apple Green
                    }
            )
        }
    }

    fun showChargingState(batteryPercent: Int, animate: Boolean = false) {
        if (currentState == State.CHARGING && !animate) {
            // Just update the percentage
            updateChargingDisplay(batteryPercent)
            return
        }
        currentState = State.CHARGING

        expandedContainer.visibility = GONE
        pillContainer.alpha = 1f
        contentContainer.visibility = VISIBLE
        closeContainer.visibility = GONE

        contentContainer.removeAllViews()
        iconContainer.visibility = GONE
        contentContainer.setPadding(0) // Remove padding so progress ring can reach edges

        // Pill-shaped progress background
        val progressContainer =
                FrameLayout(context).apply {
                    layoutParams =
                            LinearLayout.LayoutParams(
                                    LayoutParams.MATCH_PARENT,
                                    LayoutParams.MATCH_PARENT
                            )
                }

        // Pill-shaped progress indicator
        val progressRing = createPillProgress(if (animate) 0 else batteryPercent)
        progressRing.tag = "progress_ring"

        // Battery percentage text (centered)
        val percentText =
                TextView(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            LayoutParams.WRAP_CONTENT,
                                            LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.CENTER }
                    text = if (animate) "0%" else "$batteryPercent%"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    tag = "percent_text"
                }

        progressContainer.addView(progressRing)
        progressContainer.addView(percentText)
        contentContainer.addView(progressContainer)

        if (animate) {
            val animator = ValueAnimator.ofFloat(0f, batteryPercent.toFloat())
            animator.duration = 1500
            animator.interpolator = DecelerateInterpolator()
            animator.addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                // Update text
                percentText.text = "${value.toInt()}%"
                // Update ring (we need a way to set its percent)
                val ring = progressRing as? Any
                try {
                    val setter = ring?.javaClass?.getMethod("setPercent", Float::class.java)
                    setter?.invoke(ring, value)
                } catch (e: Exception) {
                    // Fallback or handle differently if needed
                }
            }
            animator.start()
        }

        animateToCollapsed {}
    }

    private fun updateChargingDisplay(batteryPercent: Int) {
        // Find the progress container (first child of contentContainer)
        val progressContainer = contentContainer.getChildAt(0) as? FrameLayout
        val percentText = progressContainer?.findViewWithTag<TextView>("percent_text")
        percentText?.text = "$batteryPercent%"

        // Update progress ring
        val oldProgress = progressContainer?.findViewWithTag<View>("progress_ring")
        if (oldProgress != null && progressContainer != null) {
            progressContainer.removeView(oldProgress)
            val newProgress = createPillProgress(batteryPercent)
            newProgress.tag = "progress_ring"
            progressContainer.addView(newProgress, 0) // Add at index 0 so text stays on top
        }
    }

    private fun createPillProgress(initialPercent: Int): View {
        return object : View(context) {
                    private var currentPercent: Float = initialPercent.toFloat()

                    fun setPercent(percent: Float) {
                        currentPercent = percent
                        invalidate()
                    }

                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)

                        val width = width.toFloat()
                        val height = height.toFloat()
                        if (width <= 0 || height <= 0) return

                        val strokeWidth = dpToPx(3).toFloat()
                        val radius = height / 2f

                        // Draw filled background showing battery level
                        val fillPaint =
                                Paint().apply {
                                    isAntiAlias = true
                                    style = Paint.Style.FILL
                                    color = Color.parseColor("#30D158") // Green fill
                                    alpha = 80 // Semi-transparent
                                }

                        // Calculate fill width based on percentage
                        val fillWidth = width * (currentPercent / 100f)
                        val fillPath =
                                Path().apply {
                                    val rect = RectF(0f, 0f, fillWidth, height)
                                    addRoundRect(rect, radius, radius, Path.Direction.CW)
                                }
                        canvas.drawPath(fillPath, fillPaint)

                        // Draw outline stroke
                        val strokePaint =
                                Paint().apply {
                                    isAntiAlias = true
                                    style = Paint.Style.STROKE
                                    this.strokeWidth = strokeWidth
                                    color = Color.parseColor("#30D158") // Green
                                    strokeCap = Paint.Cap.ROUND
                                }

                        // Create pill-shaped path with stroke inset
                        val outlinePath =
                                Path().apply {
                                    val halfStroke = strokeWidth / 2f
                                    val rect =
                                            RectF(
                                                    halfStroke,
                                                    halfStroke,
                                                    width - halfStroke,
                                                    height - halfStroke
                                            )
                                    addRoundRect(
                                            rect,
                                            radius - halfStroke,
                                            radius - halfStroke,
                                            Path.Direction.CW
                                    )
                                }

                        // Draw the pill outline
                        canvas.drawPath(outlinePath, strokePaint)
                    }
                }
                .apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    LayoutParams.MATCH_PARENT,
                                    LayoutParams.MATCH_PARENT
                            )
                }
    }

    fun expandWithNotification(notif: NotificationData) {
        currentState = State.NOTIFICATION_EXPANDED

        expandedContainer.visibility = GONE
        iconContainer.visibility = GONE
        closeContainer.visibility = GONE
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
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))

            // Sender/App icon on the left
            val iconFrame =
                    FrameLayout(context).apply {
                        layoutParams =
                                LinearLayout.LayoutParams(
                                                dpToPx(ICON_SIZE_DP),
                                                dpToPx(ICON_SIZE_DP)
                                        )
                                        .apply { marginEnd = dpToPx(12) }
                    }

            // Main icon (sender or app icon)
            val mainIcon =
                    ImageView(context).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(dpToPx(ICON_SIZE_DP), dpToPx(ICON_SIZE_DP))
                        scaleType = ImageView.ScaleType.CENTER_CROP

                        // Priority: senderIcon > groupIcon > largeIcon > smallIcon
                        val iconBitmap =
                                when {
                                    notif.senderIcon != null ->
                                            decodeBase64ToBitmap(notif.senderIcon)
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
                val smallIcon =
                        ImageView(context).apply {
                            layoutParams =
                                    FrameLayout.LayoutParams(dpToPx(16), dpToPx(16)).apply {
                                        gravity = Gravity.BOTTOM or Gravity.END
                                    }
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            decodeBase64ToBitmap(notif.smallIcon)?.let {
                                setImageBitmap(getCircularBitmap(it))
                            }
                            background =
                                    GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(Color.WHITE)
                                    }
                        }
                iconFrame.addView(smallIcon)
            }

            addView(iconFrame)

            // Text content (title and text)
            val textContainer =
                    LinearLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                        orientation = LinearLayout.VERTICAL
                    }

            // Title
            val titleView =
                    TextView(context).apply {
                        layoutParams =
                                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                        text = notif.title
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        // Slide animation from center
                        alpha = 0f
                        translationX = 50f
                        animate().alpha(1f).translationX(0f).setDuration(300).start()
                    }

            // Content text
            val contentTextView =
                    TextView(context).apply {
                        layoutParams =
                                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                        text = notif.text
                        textSize = 12f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        maxLines = 2 // Increased to 2 for better display
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

            textContainer.addView(titleView)
            textContainer.addView(contentTextView)
            addView(textContainer)
        }
    }

    fun collapseToIcons(notifications: List<NotificationData>) {
        currentState = State.NOTIFICATION_COLLAPSED

        // Animate list out before hiding
        expandedContainer
                .animate()
                .alpha(0f)
                .translationY(-dpToPx(20).toFloat())
                .setDuration(300)
                .withEndAction {
                    expandedContainer.visibility = GONE
                    pillContainer.alpha = 1f
                    closeContainer.visibility = GONE

                    contentContainer.removeAllViews()
                    contentContainer.visibility = GONE
                    iconContainer.visibility = VISIBLE
                    iconContainer.removeAllViews()

                    // Show up to 3 stacked icons, then "+N"
                    val maxIcons = 3
                    val iconsToShow = notifications.take(maxIcons)

                    iconsToShow.forEachIndexed { index, notif ->
                        val icon =
                                ImageView(context).apply {
                                    layoutParams =
                                            LinearLayout.LayoutParams(
                                                            dpToPx(STACKED_ICON_SIZE_DP),
                                                            dpToPx(STACKED_ICON_SIZE_DP)
                                                    )
                                                    .apply {
                                                        if (index == 0) {
                                                            marginStart =
                                                                    (dpToPx(pillHeightCollapsed) -
                                                                            dpToPx(
                                                                                    STACKED_ICON_SIZE_DP
                                                                            )) / 2
                                                        } else {
                                                            marginStart =
                                                                    -dpToPx(
                                                                            STACKED_ICON_SIZE_DP / 2
                                                                    )
                                                        }
                                                    }
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    elevation = (maxIcons - index).toFloat() * dpToPx(2)

                                    val iconBitmap =
                                            when {
                                                notif.senderIcon != null ->
                                                        decodeBase64ToBitmap(notif.senderIcon)
                                                notif.groupIcon != null ->
                                                        decodeBase64ToBitmap(notif.groupIcon)
                                                notif.largeIcon != null ->
                                                        decodeBase64ToBitmap(notif.largeIcon)
                                                notif.smallIcon != null ->
                                                        decodeBase64ToBitmap(notif.smallIcon)
                                                else -> null
                                            }

                                    if (iconBitmap != null) {
                                        setImageBitmap(getCircularBitmap(iconBitmap))
                                    } else {
                                        setImageResource(android.R.drawable.ic_dialog_info)
                                    }

                                    background =
                                            GradientDrawable().apply {
                                                shape = GradientDrawable.OVAL
                                                val strokeWidth =
                                                        Math.max(
                                                                1,
                                                                dpToPx(STACKED_ICON_SIZE_DP / 14f)
                                                        )
                                                setStroke(strokeWidth, Color.WHITE)
                                            }
                                }
                        iconContainer.addView(icon)
                    }

                    if (notifications.size > maxIcons) {
                        val moreText =
                                TextView(context).apply {
                                    layoutParams =
                                            LinearLayout.LayoutParams(
                                                            dpToPx(STACKED_ICON_SIZE_DP),
                                                            dpToPx(STACKED_ICON_SIZE_DP)
                                                    )
                                                    .apply {
                                                        marginStart =
                                                                -dpToPx(STACKED_ICON_SIZE_DP / 2)
                                                    }
                                    text = "+${notifications.size - maxIcons}"
                                    textSize = (STACKED_ICON_SIZE_DP * 0.4f)
                                    setTextColor(Color.WHITE)
                                    gravity = Gravity.CENTER
                                    background =
                                            GradientDrawable().apply {
                                                shape = GradientDrawable.OVAL
                                                setColor(Color.parseColor("#FF9500"))
                                                val strokeWidth =
                                                        Math.max(
                                                                1,
                                                                dpToPx(STACKED_ICON_SIZE_DP / 14f)
                                                        )
                                                setStroke(strokeWidth, Color.WHITE)
                                            }
                                }
                        iconContainer.addView(moreText)
                    }

                    animateToCollapsed {}
                }
                .start()
    }

    fun expandToList(notifications: List<NotificationData>) {
        // If already in list view, just update the list items
        if (currentState == State.LIST_EXPANDED) {
            expandedList.removeAllViews()
            notifications.forEach { notif ->
                val itemView = createNotificationListItem(notif)
                expandedList.addView(itemView)
            }

            if (notifications.size > 1) {
                addClearAllButton()
            }
            return
        }

        currentState = State.LIST_EXPANDED

        // Hide pill content and show list instead
        contentContainer.visibility = GONE
        iconContainer.visibility = GONE

        // Collapse pill back to small size
        animateToCollapsed {
            // Once pill is small, show the list with animation
            expandedContainer.alpha = 0f
            expandedContainer.translationY = -dpToPx(20).toFloat()
            expandedContainer.visibility = VISIBLE
            expandedList.removeAllViews()

            notifications.forEach { notif ->
                val itemView = createNotificationListItem(notif)
                expandedList.addView(itemView)
            }

            if (notifications.size > 1) {
                addClearAllButton()
            }

            // Show Close UI in pill
            contentContainer.visibility = GONE
            iconContainer.visibility = GONE
            closeContainer.visibility = VISIBLE

            expandedContainer.animate().alpha(1f).translationY(0f).setDuration(300).start()
        }
    }

    private fun addClearAllButton() {
        val clearAllBtn =
                TextView(context).apply {
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LayoutParams.MATCH_PARENT,
                                            LayoutParams.WRAP_CONTENT
                                    )
                                    .apply {
                                        topMargin = dpToPx(8)
                                        bottomMargin = dpToPx(16)
                                    }
                    text = "Clear All"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(12))
                    background =
                            GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dpToPx(12).toFloat()
                                setColor(Color.parseColor("#3A3A3C"))
                            }
                    setOnClickListener { onClearAllClicked?.invoke() }
                }
        expandedList.addView(clearAllBtn)
    }

    fun updateNotificationQueue(notifications: List<NotificationData>) {
        if (currentState == State.NOTIFICATION_COLLAPSED) {
            collapseToIcons(notifications)
        } else if (currentState == State.LIST_EXPANDED) {
            expandToList(notifications)
        }
    }

    private fun createNotificationListItem(notif: NotificationData): View {
        var startX = 0f
        var startXTime = 0L
        return LinearLayout(context).apply {
            layoutParams =
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dpToPx(8)
                    }
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16))
            background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(12).toFloat()
                        setColor(Color.parseColor("#2C2C2E"))
                    }

            // Top row: icon + title + dismiss button
            val topRow =
                    LinearLayout(context).apply {
                        layoutParams =
                                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

            // Icon
            val icon =
                    ImageView(context).apply {
                        layoutParams =
                                LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
                                    marginEnd = dpToPx(8)
                                }
                        scaleType = ImageView.ScaleType.CENTER_CROP

                        val iconBitmap =
                                when {
                                    notif.senderIcon != null ->
                                            decodeBase64ToBitmap(notif.senderIcon)
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
            topRow.addView(icon)

            // Title
            val title =
                    TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                        text = notif.title
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        maxLines = 1
                    }
            topRow.addView(title)

            // No dismiss button - using swipe gesture instead

            addView(topRow)

            // Content text
            val content =
                    TextView(context).apply {
                        layoutParams =
                                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                                        .apply { topMargin = dpToPx(4) }
                        text = notif.text
                        textSize = 13f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        maxLines = 3
                    }
            addView(content)

            // Big Picture
            notif.bigPicture?.let { base64Image ->
                decodeBase64ToBitmap(base64Image)?.let { bitmap ->
                    val bigPictureView =
                            ImageView(context).apply {
                                layoutParams =
                                        LinearLayout.LayoutParams(
                                                        LayoutParams.MATCH_PARENT,
                                                        dpToPx(120)
                                                )
                                                .apply { topMargin = dpToPx(8) }
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                setImageBitmap(bitmap)
                                clipToOutline = true
                                outlineProvider =
                                        object : ViewOutlineProvider() {
                                            override fun getOutline(view: View, outline: Outline) {
                                                outline.setRoundRect(
                                                        0,
                                                        0,
                                                        view.width,
                                                        view.height,
                                                        dpToPx(8).toFloat()
                                                )
                                            }
                                        }
                            }
                    addView(bigPictureView)
                }
            }

            // Chat messages
            if (notif.messages.isNotEmpty()) {
                val messagesContainer =
                        LinearLayout(context).apply {
                            layoutParams =
                                    LinearLayout.LayoutParams(
                                                    LayoutParams.MATCH_PARENT,
                                                    LayoutParams.WRAP_CONTENT
                                            )
                                            .apply { topMargin = dpToPx(8) }
                            orientation = LinearLayout.VERTICAL
                        }

                notif.messages.takeLast(2).forEach { message ->
                    val messageLayout =
                            LinearLayout(context).apply {
                                layoutParams =
                                        LinearLayout.LayoutParams(
                                                        LayoutParams.MATCH_PARENT,
                                                        LayoutParams.WRAP_CONTENT
                                                )
                                                .apply { bottomMargin = dpToPx(4) }
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            }

                    message.senderIcon?.let { base64SenderIcon ->
                        decodeBase64ToBitmap(base64SenderIcon)?.let { senderBitmap ->
                            val senderIconView =
                                    ImageView(context).apply {
                                        layoutParams =
                                                LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
                                                        .apply { marginEnd = dpToPx(8) }
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        setImageBitmap(getCircularBitmap(senderBitmap))
                                    }
                            messageLayout.addView(senderIconView)
                        }
                    }

                    val messageTextView =
                            TextView(context).apply {
                                layoutParams =
                                        LinearLayout.LayoutParams(
                                                LayoutParams.WRAP_CONTENT,
                                                LayoutParams.WRAP_CONTENT
                                        )
                                text =
                                        if (message.senderName != null)
                                                "${message.senderName}: ${message.text}"
                                        else message.text
                                textSize = 11f
                                setTextColor(Color.parseColor("#CCCCCC"))
                                maxLines = 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                background =
                                        GradientDrawable().apply {
                                            cornerRadius = dpToPx(8).toFloat()
                                            setColor(Color.parseColor("#3A3A3C"))
                                        }
                                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                            }
                    messageLayout.addView(messageTextView)
                    messagesContainer.addView(messageLayout)
                }
                addView(messagesContainer)
            }

            // Action buttons
            if (notif.actions.isNotEmpty()) {
                val actionRow =
                        LinearLayout(context).apply {
                            layoutParams =
                                    LayoutParams(
                                                    LayoutParams.MATCH_PARENT,
                                                    LayoutParams.WRAP_CONTENT
                                            )
                                            .apply { topMargin = dpToPx(12) }
                            orientation = LinearLayout.HORIZONTAL
                        }

                notif.actions.forEachIndexed { index, action ->
                    val actionBtn =
                            TextView(context).apply {
                                layoutParams =
                                        LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                                                .apply {
                                                    if (index < notif.actions.size - 1) {
                                                        marginEnd = dpToPx(8)
                                                    }
                                                }
                                text = action.title
                                textSize = 12f
                                setTextColor(Color.WHITE)
                                gravity = Gravity.CENTER
                                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                                background =
                                        GradientDrawable().apply {
                                            cornerRadius = dpToPx(16).toFloat()
                                            setColor(Color.parseColor("#3A3A3C"))
                                        }

                                setOnClickListener {
                                    if (action.isReplyAction) {
                                        showReplyDialog(notif)
                                    } else {
                                        // Execute action
                                        val intent =
                                                android.content.Intent(
                                                                BluetoothService
                                                                        .ACTION_CMD_EXECUTE_ACTION
                                                        )
                                                        .apply {
                                                            putExtra(
                                                                    BluetoothService
                                                                            .EXTRA_NOTIF_KEY,
                                                                    notif.key
                                                            )
                                                            putExtra(
                                                                    BluetoothService
                                                                            .EXTRA_ACTION_KEY,
                                                                    action.actionKey
                                                            )
                                                            setPackage(context.packageName)
                                                        }
                                        context.sendBroadcast(intent)
                                        onNotificationDismiss?.invoke(notif)
                                    }
                                }
                            }
                    actionRow.addView(actionBtn)
                }

                addView(actionRow)
            }

            // Swipe right to dismiss gesture
            var startY = 0f
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startY = event.y
                        startXTime = System.currentTimeMillis()
                        true // Must return true to receive subsequent events
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.x - startX
                        val deltaY = event.y - startY

                        // Determine if horizontal or vertical swipe
                        if (kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) &&
                                        kotlin.math.abs(deltaX) > 20
                        ) {
                            // Horizontal swipe confirmed - prevent parent from intercepting
                            parent.requestDisallowInterceptTouchEvent(true)

                            // Only allow swiping right (positive deltaX)
                            if (deltaX > 0) {
                                translationX = deltaX
                                val safeWidth = if (v.width > 0) v.width else dpToPx(300)
                                alpha = 1f - (deltaX / safeWidth.toFloat()) * 0.5f
                            }
                            true
                        } else {
                            // Not a clear horizontal swipe - let parent (ScrollView) handle it
                            false
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val deltaX = event.x - startX
                        val duration = System.currentTimeMillis() - startXTime
                        val safeWidth = if (v.width > 0) v.width else dpToPx(300)

                        // Allow parent to intercept again
                        parent.requestDisallowInterceptTouchEvent(false)

                        // If it's a click (minimal move), trigger v.performClick()
                        if (kotlin.math.abs(deltaX) < 15 && kotlin.math.abs(event.y - startY) < 15
                        ) {
                            translationX = 0f
                            alpha = 1f
                            v.performClick()
                            true
                        } else {
                            // Was it a dismiss swipe?
                            val shouldDismiss =
                                    deltaX > safeWidth * 0.4f || (deltaX > 100 && duration < 300)

                            if (shouldDismiss) {
                                animate()
                                        .translationX(safeWidth.toFloat())
                                        .alpha(0f)
                                        .setDuration(200)
                                        .withEndAction { onNotificationDismiss?.invoke(notif) }
                                        .start()
                            } else {
                                // Snap back
                                animate().translationX(0f).alpha(1f).setDuration(200).start()
                            }
                            true
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        parent.requestDisallowInterceptTouchEvent(false)
                        animate().translationX(0f).alpha(1f).setDuration(200).start()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun showReplyDialog(notif: NotificationData) {
        val input =
                EditText(context).apply {
                    hint = "Type your reply..."
                    inputType = InputType.TYPE_CLASS_TEXT
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.LTGRAY)
                    setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
                    background =
                            GradientDrawable().apply {
                                cornerRadius = dpToPx(8).toFloat()
                                setColor(Color.parseColor("#3A3A3C"))
                            }
                }

        val container =
                FrameLayout(context).apply {
                    setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
                    addView(input)
                }

        val dialog =
                android.app.AlertDialog.Builder(
                                context,
                                android.R.style.Theme_DeviceDefault_Dialog_Alert
                        )
                        .setTitle("Reply to ${notif.title}")
                        .setView(container)
                        .setPositiveButton("Send") { _, _ ->
                            val replyText = input.text.toString()
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
        val targetWidth = dpToPx(pillWidthCollapsed)
        val targetHeight = dpToPx(pillHeightCollapsed)
        animateSize(
                pillContainer,
                pillContainer.width,
                targetWidth,
                pillContainer.height,
                targetHeight,
                onEnd
        )
    }

    private fun animateToExpanded(onEnd: () -> Unit) {
        val targetWidth = dpToPx(pillWidthExpanded)
        val targetHeight = dpToPx(pillHeightExpanded)
        animateSize(
                pillContainer,
                pillContainer.width,
                targetWidth,
                pillContainer.height,
                targetHeight,
                onEnd
        )
    }

    private fun animateSize(
            view: View,
            fromWidth: Int,
            toWidth: Int,
            fromHeight: Int,
            toHeight: Int,
            onEnd: () -> Unit = {}
    ) {
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
        animatorSet.addListener(
                object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd()
                    }
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationCancel(animation: android.animation.Animator) {}
                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                }
        )
        animatorSet.start()
    }

    private fun animateWidth(view: View, from: Int, to: Int, onEnd: () -> Unit = {}) {
        val animator = ValueAnimator.ofInt(from, to)
        animator.addUpdateListener { animation ->
            val lp = view.layoutParams
            lp.width = animation.animatedValue as Int
            view.layoutParams = lp
        }
        animator.addListener(
                object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd()
                    }
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationCancel(animation: android.animation.Animator) {}
                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                }
        )
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
        animator.addListener(
                object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd()
                    }
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationCancel(animation: android.animation.Animator) {}
                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                }
        )
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

        val paint =
                Paint().apply {
                    isAntiAlias = true
                    color = Color.WHITE
                }

        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val srcRect =
                Rect(
                        (bitmap.width - size) / 2,
                        (bitmap.height - size) / 2,
                        (bitmap.width + size) / 2,
                        (bitmap.height + size) / 2
                )
        val dstRect = Rect(0, 0, size, size)

        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

        return output
    }

    fun updateDimensions(width: Int, height: Int) {
        pillWidthCollapsed = width
        pillHeightCollapsed = height

        // Update icon sizes relative to new height
        ICON_SIZE_DP = (height * 0.9f).toInt()
        STACKED_ICON_SIZE_DP = (height * 0.7f).toInt()

        // Re-scale expanded height if needed, keeping it larger than collapsed
        pillHeightExpanded = Math.max(PILL_HEIGHT_EXPANDED_DP, height + 40)

        // Update current pill if not expanded
        if (currentState != State.NOTIFICATION_EXPANDED && currentState != State.LIST_EXPANDED) {
            val params = pillContainer.layoutParams as LayoutParams
            params.width = dpToPx(pillWidthCollapsed)
            params.height = dpToPx(pillHeightCollapsed)
            pillContainer.layoutParams = params
            pillContainer.background = createPillBackground()

            // Update contentContainer padding
            val hPadding = dpToPx(pillHeightCollapsed * 0.3f)
            val vPadding = dpToPx(pillHeightCollapsed * 0.2f)
            contentContainer.setPadding(hPadding, vPadding, hPadding, vPadding)

            // Update closeContainer scaling
            val cvPadding = dpToPx(pillHeightCollapsed * 0.1f)
            val chPadding = dpToPx(pillHeightCollapsed * 0.3f)
            closeContainer.setPadding(chPadding, cvPadding, chPadding, cvPadding)
            for (i in 0 until closeContainer.childCount) {
                val child = closeContainer.getChildAt(i)
                if (child is TextView) {
                    child.textSize = pillHeightCollapsed * 0.35f
                }
            }
        }
    }

    private fun resetContentPadding() {
        val hPadding = dpToPx(pillHeightCollapsed * 0.3f)
        val vPadding = dpToPx(pillHeightCollapsed * 0.2f)
        contentContainer.setPadding(hPadding, vPadding, hPadding, vPadding)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
