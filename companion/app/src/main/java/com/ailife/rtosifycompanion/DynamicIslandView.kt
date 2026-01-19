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
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
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
    
    // Media playback tracking
    private var mediaProgressHandler: Handler? = null
    private var mediaStartTime: Long = 0
    private var mediaStartPosition: Long = 0
    private var mediaDuration: Long = 0
    private var isMediaPlaying: Boolean = false
    private var lastAlbumArtBase64: String? = null
    private var cachedAlbumBitmap: Bitmap? = null

    private enum class State {
        IDLE,
        DISCONNECTED,
        DISCONNECTED_EXPANDED,
        CHARGING,
        PHONE_CALL,
        ALARM,
        MEDIA_PLAYING,
        MEDIA_EXPANDED,
        NOTIFICATION_EXPANDED,
        NOTIFICATION_COLLAPSED,
        LIST_EXPANDED
    }

    // Text settings
    private var textSizeMultiplier: Float = 1.0f
    private var limitMessageLength: Boolean = true

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
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    elevation = dpToPx(8).toFloat()
                    clipChildren = false
                    clipToPadding = false
                    setOnClickListener {
                        if (currentState == State.NOTIFICATION_COLLAPSED ||
                                        currentState == State.NOTIFICATION_EXPANDED ||
                                        currentState == State.LIST_EXPANDED ||
                                        currentState == State.MEDIA_PLAYING ||
                                        currentState == State.MEDIA_EXPANDED) {
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
                                text = context.getString(R.string.di_close)
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
                    
                    // Wrapper to resolve "only one direct child" scroller issue
                    val wrapper = FrameLayout(context).apply {
                        tag = "scroll_wrapper"
                    }
                    wrapper.addView(expandedList)
                    addView(wrapper)
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

    fun showIdleState(transportType: String = "") {
        if (currentState == State.IDLE) return
        currentState = State.IDLE
        
        stopMediaProgressAnimation() // Clean up media animation

        expandedContainer.visibility = GONE
        pillContainer.alpha = 1f
        closeContainer.visibility = GONE
        contentContainer.visibility = VISIBLE
        iconContainer.visibility = GONE

        animateToCollapsed {
            resetContentPadding()
            contentContainer.removeAllViews()

            // Show connected state with correct transport icons
            showConnectedState(transportType)
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

    fun showExpandedDisconnected() {
        currentState = State.DISCONNECTED_EXPANDED

        expandedContainer.visibility = GONE
        iconContainer.visibility = GONE
        closeContainer.visibility = GONE
        contentContainer.visibility = VISIBLE
        contentContainer.removeAllViews()

        // Create disconnected content layout
        val disconnectedLayout = LinearLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))

            // Disconnect icon on the left
            val iconContainer = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(ICON_SIZE_DP), dpToPx(ICON_SIZE_DP)).apply {
                    marginEnd = dpToPx(12)
                }
            }

            val iconBackground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#424242"))
            }

            val iconView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(dpToPx(ICON_SIZE_DP * 0.7f), dpToPx(ICON_SIZE_DP * 0.7f)).apply {
                    gravity = Gravity.CENTER
                }
                setImageResource(R.drawable.ic_smartphone)
                setColorFilter(Color.GRAY)
            }

            val crossLine = object : View(context) {
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val paint = Paint().apply {
                        isAntiAlias = true
                        color = Color.RED
                        strokeWidth = dpToPx(2).toFloat()
                    }
                    canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), paint)
                    canvas.drawLine(width.toFloat(), 0f, 0f, height.toFloat(), paint)
                }
            }.apply {
                layoutParams = FrameLayout.LayoutParams(dpToPx(ICON_SIZE_DP), dpToPx(ICON_SIZE_DP))
            }

            iconContainer.background = iconBackground
            iconContainer.addView(iconView)
            iconContainer.addView(crossLine)
            addView(iconContainer)

            // Text content
            val textContainer = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val titleView = TextView(context).apply {
                text = context.getString(R.string.notification_phone_disconnected_title)
                textSize = getScaledTextSize(14f)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }

            val subtitleView = TextView(context).apply {
                text = context.getString(R.string.notification_phone_disconnected_text)
                textSize = getScaledTextSize(12f)
                setTextColor(Color.parseColor("#AAAAAA"))
            }

            textContainer.addView(titleView)
            textContainer.addView(subtitleView)
            addView(textContainer)
        }

        contentContainer.addView(disconnectedLayout)

        // Animate to expanded size
        animateToExpanded {}
    }

    fun showConnectedState(transportType: String = "") {
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
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            
            // Show all active connection types
            val hasBluetooth = transportType.contains("BT") || transportType.contains("Bluetooth")
            val hasLan = transportType.contains("LAN") || transportType.contains("WiFi")
            val hasInternet = transportType.contains("Internet")

            val activeCount = listOf(hasBluetooth, hasLan, hasInternet).count { it }
            val iconMargin = if (activeCount > 1) dpToPx(4) else 0

            if (hasBluetooth) {
                container.addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        if (hasLan || hasInternet) marginEnd = iconMargin
                    }
                    setImageResource(R.drawable.ic_bluetooth)
                    setColorFilter(Color.parseColor("#30D158"))
                })
            }

            if (hasLan) {
                container.addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        if (hasInternet) marginEnd = iconMargin
                    }
                    setImageResource(R.drawable.ic_wifi)
                    setColorFilter(Color.parseColor("#30D158"))
                })
            }

            if (hasInternet) {
                container.addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    setImageResource(R.drawable.ic_globe)
                    setColorFilter(Color.parseColor("#30D158"))
                })
            }

            // Fallback if no connection type detected but still "connected"
            if (!hasBluetooth && !hasLan && !hasInternet) {
                container.addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    setImageResource(R.drawable.ic_bluetooth)
                    setColorFilter(Color.parseColor("#30D158"))
                })
            }
            contentContainer.addView(container)
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
        if (oldProgress != null) {
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

                        // Priority: Group Icon (if group chat) > Sender Icon > Large Icon > Small Icon
                        val iconBitmap =
                                when {
                                    notif.isGroupConversation && notif.groupIcon != null ->
                                            decodeBase64ToBitmap(notif.groupIcon)
                                    notif.isGroupConversation && notif.largeIcon != null ->
                                            decodeBase64ToBitmap(notif.largeIcon)
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
            if (notif.smallIcon != null && (notif.senderIcon != null || notif.groupIcon != null || notif.largeIcon != null)) {
                val smallIcon =
                        ImageView(context).apply {
                            layoutParams =
                                    FrameLayout.LayoutParams(dpToPx(18), dpToPx(18)).apply {
                                        gravity = Gravity.BOTTOM or Gravity.END
                                    }
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
                            decodeBase64ToBitmap(notif.smallIcon)?.let {
                                setImageBitmap(getCircularBitmap(it))
                            }
                            background =
                                    GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(Color.parseColor("#2C2C2E"))
                                        setStroke(dpToPx(2), Color.parseColor("#1C1C1E"))
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
                        textSize = getScaledTextSize(14f)
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
                        textSize = getScaledTextSize(12f)
                        setTextColor(Color.parseColor("#AAAAAA"))
                        maxLines = getAdaptiveMaxLines(2)
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
                                                notif.isGroupConversation && notif.groupIcon != null ->
                                                        decodeBase64ToBitmap(notif.groupIcon)
                                                notif.isGroupConversation && notif.largeIcon != null ->
                                                        decodeBase64ToBitmap(notif.largeIcon)
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
        val wrapper = expandedContainer.findViewWithTag<FrameLayout>("scroll_wrapper")
        wrapper?.findViewWithTag<View>("media_expanded_layout")?.visibility = GONE
        expandedList.visibility = VISIBLE

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

            expandedContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .start()
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
                    text = context.getString(R.string.di_clear_all)
                    textSize = getScaledTextSize(14f)
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

            // Icon with FrameLayout for overlay support
            val iconFrame =
                    FrameLayout(context).apply {
                        layoutParams =
                                LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
                                    marginEnd = dpToPx(8)
                                }
                    }

            val icon =
                    ImageView(context).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(dpToPx(32), dpToPx(32))
                        scaleType = ImageView.ScaleType.CENTER_CROP

                        val iconBitmap =
                                when {
                                    notif.isGroupConversation && notif.groupIcon != null ->
                                            decodeBase64ToBitmap(notif.groupIcon)
                                    notif.isGroupConversation && notif.largeIcon != null ->
                                            decodeBase64ToBitmap(notif.largeIcon)
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
            iconFrame.addView(icon)

            // Small overlay icon at bottom right (app icon)
            if (notif.smallIcon != null && (notif.senderIcon != null || notif.groupIcon != null || notif.largeIcon != null)) {
                val smallIcon =
                        ImageView(context).apply {
                            layoutParams =
                                    FrameLayout.LayoutParams(dpToPx(14), dpToPx(14)).apply {
                                        gravity = Gravity.BOTTOM or Gravity.END
                                    }
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
                            decodeBase64ToBitmap(notif.smallIcon)?.let {
                                setImageBitmap(getCircularBitmap(it))
                            }
                            background =
                                    GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(Color.parseColor("#2C2C2E"))
                                        setStroke(dpToPx(1), Color.parseColor("#1C1C1E"))
                                    }
                        }
                iconFrame.addView(smallIcon)
            }

            topRow.addView(iconFrame)

            val title =
                    TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                        text = notif.title
                        textSize = getScaledTextSize(14f)
                        setTextColor(Color.WHITE)
                        maxLines = 1
                    }
            topRow.addView(title)

            // No dismiss button - using swipe gesture instead

            addView(topRow)

            val content =
                    TextView(context).apply {
                        layoutParams =
                                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                                        .apply { topMargin = dpToPx(4) }
                        text = notif.text
                        textSize = getScaledTextSize(13f)
                        setTextColor(Color.parseColor("#AAAAAA"))
                        maxLines = getAdaptiveMaxLines(3)
                    }
            addView(content)

            // Big Picture
            notif.bigPicture?.let { base64Image ->
                decodeBase64ToBitmap(base64Image)?.let { bitmap ->
                    val bigPictureView =
                            ImageView(context).apply {
                                // Calculate height to maintain aspect ratio with max height of 200dp
                                val maxHeight = dpToPx(200)
                                val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                                val calculatedHeight = if (aspectRatio > 0) {
                                    minOf(maxHeight, (dpToPx(pillWidthExpanded - 32) / aspectRatio).toInt())
                                } else {
                                    maxHeight
                                }
                                
                                layoutParams =
                                        LinearLayout.LayoutParams(
                                                        LayoutParams.MATCH_PARENT,
                                                        calculatedHeight
                                                )
                                                .apply { topMargin = dpToPx(8) }
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                adjustViewBounds = true
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

                    // Determine if this message is from self
                    val msgSenderName = message.senderName
                    val isMe = msgSenderName == null ||
                               msgSenderName == notif.selfName ||
                               msgSenderName.equals("Me", ignoreCase = true) ||
                               msgSenderName.equals("You", ignoreCase = true)

                    // Use selfIcon for own messages, senderIcon for others
                    val iconBase64 = if (isMe) notif.selfIcon ?: message.senderIcon else message.senderIcon
                    iconBase64?.let { base64Icon ->
                        decodeBase64ToBitmap(base64Icon)?.let { iconBitmap ->
                            val senderIconView =
                                    ImageView(context).apply {
                                        layoutParams =
                                                LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
                                                        .apply { marginEnd = dpToPx(8) }
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        setImageBitmap(getCircularBitmap(iconBitmap))
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
                                textSize = getScaledTextSize(11f)
                                setTextColor(Color.parseColor("#CCCCCC"))
                                maxLines = getAdaptiveMaxLines(2)
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
                                textSize = getScaledTextSize(12f)
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
                    hint = context.getString(R.string.di_reply_hint)
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
                        .setTitle(context.getString(R.string.di_reply_to, notif.title))
                        .setView(container)
                        .setPositiveButton(context.getString(R.string.di_send)) { _, _ ->
                            val replyText = input.text.toString()
                            if (replyText.isNotEmpty()) {
                                onNotificationReply?.invoke(notif, replyText)
                            }
                        }
                        .setNegativeButton(context.getString(R.string.di_cancel), null)
                        .create()

        dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun animateToCollapsed(onEnd: () -> Unit) {
        // Only show content container if NOT in expanded media state (which uses close UI)
        if (currentState != State.MEDIA_EXPANDED) {
            contentContainer.visibility = VISIBLE
        }
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
                    private var hasEnded = false
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!hasEnded) {
                            hasEnded = true
                            onEnd()
                        }
                    }
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        if (!hasEnded) {
                            hasEnded = true
                            onEnd()
                        }
                    }
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
        if (base64 == lastAlbumArtBase64 && cachedAlbumBitmap != null) {
            return cachedAlbumBitmap
        }
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                lastAlbumArtBase64 = base64
                cachedAlbumBitmap = bitmap
            }
            bitmap
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

    /**
     * Update text settings from SharedPreferences
     */
    fun updateTextSettings(multiplier: Float, limitLength: Boolean) {
        textSizeMultiplier = multiplier
        limitMessageLength = limitLength
        Log.d(TAG, "Text settings updated: multiplier=$multiplier, limitLength=$limitLength")
    }

    /**
     * Get scaled text size based on multiplier
     */
    private fun getScaledTextSize(baseSize: Float): Float {
        return baseSize * textSizeMultiplier
    }

    /**
     * Get adaptive max lines based on message length setting
     */
    private fun getAdaptiveMaxLines(defaultMaxLines: Int): Int {
        return if (limitMessageLength) defaultMaxLines else Int.MAX_VALUE
    }
    
    /**
     * Show incoming phone call in Dynamic Island
     */
    fun showPhoneCall(number: String, contactName: String?, isRinging: Boolean) {
        currentState = State.PHONE_CALL
        expandedContainer.visibility = GONE
        contentContainer.visibility = VISIBLE
        closeContainer.visibility = GONE
        iconContainer.visibility = GONE
        contentContainer.removeAllViews()
        resetContentPadding()
        
        val callContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
        }
        
        if (isRinging) {
            // [Left] Answer button
            callContainer.addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
                text = "✓"
                setTextColor(Color.WHITE)
                textSize = getScaledTextSize(18f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#34C759"))
                    shape = GradientDrawable.OVAL
                }
                setOnClickListener { onCallAction?.invoke("answer") }
            })
        } else {
            // Active call icon
            callContainer.addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
                text = "📞"
                textSize = getScaledTextSize(14f)
                gravity = Gravity.CENTER
            })
        }
        
        // [Center] Name and Number
        val infoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
        }
        
        // Caller name or number
        infoContainer.addView(TextView(context).apply {
            text = contactName ?: number
            setTextColor(Color.WHITE)
            textSize = getScaledTextSize(13f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        
        // Number (if we have contact name)
        if (contactName != null) {
            infoContainer.addView(TextView(context).apply {
                text = number
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = getScaledTextSize(10f)
                gravity = Gravity.CENTER
                maxLines = 1
            })
        }
        
        callContainer.addView(infoContainer)
        
        if (isRinging) {
            // [Right] Reject button
            callContainer.addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = getScaledTextSize(18f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF3B30"))
                    shape = GradientDrawable.OVAL
                }
                setOnClickListener { onCallAction?.invoke("reject") }
            })
        } else {
            // Hang up button
            callContainer.addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
                text = "End"
                setTextColor(Color.WHITE)
                textSize = getScaledTextSize(10f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF3B30"))
                    shape = GradientDrawable.OVAL
                }
                setOnClickListener { onCallAction?.invoke("reject") }
            })
        }
        
        contentContainer.addView(callContainer)
        if (isRinging) {
            animateToExpanded {}
        }
    }
    
    // Callback for call actions
    var onCallAction: ((String) -> Unit)? = null
    
    /**
     * Show alarm in Dynamic Island
     */
    fun showAlarm(alarmTime: String, alarmLabel: String?) {
        currentState = State.ALARM
        expandedContainer.visibility = GONE
        contentContainer.visibility = VISIBLE
        closeContainer.visibility = GONE
        iconContainer.visibility = GONE
        contentContainer.removeAllViews()
        resetContentPadding()
        
        val alarmContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
        }
        
        // [Left] Snooze button
        alarmContainer.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
            text = "⏰"
            setTextColor(Color.WHITE)
            textSize = getScaledTextSize(18f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF9500")) // Orange for snooze
                shape = GradientDrawable.OVAL
            }
            setOnClickListener { onAlarmAction?.invoke("snooze") }
        })
        
        // [Center] Label and Time
        val infoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
        }
        
        infoContainer.addView(TextView(context).apply {
            text = alarmTime
            setTextColor(Color.WHITE)
            textSize = getScaledTextSize(14f)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        
        if (!alarmLabel.isNullOrBlank()) {
            infoContainer.addView(TextView(context).apply {
                text = alarmLabel
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = getScaledTextSize(10f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        
        alarmContainer.addView(infoContainer)
        
        // [Right] Dismiss button
        alarmContainer.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = getScaledTextSize(18f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF3B30"))
                shape = GradientDrawable.OVAL
            }
            setOnClickListener { onAlarmAction?.invoke("dismiss") }
        })
        
        contentContainer.addView(alarmContainer)
        animateToExpanded {}
    }
    
    fun showMediaState(title: String?, artist: String?, isPlaying: Boolean, albumArtBase64: String?) {
        if (title == null || title.isEmpty() || title == "Unknown") {
            showIdleState("")
            return
        }
        
        // Show collapsed media state
        currentState = State.MEDIA_PLAYING
        
        // Stop any progress animation from expanded view
        stopMediaProgressAnimation()
        
        expandedContainer.visibility = GONE
        
        // Also hide the media expanded layout if it was showing
        val wrapper = expandedContainer.findViewWithTag<FrameLayout>("scroll_wrapper")
        wrapper?.findViewWithTag<FrameLayout>("media_expanded_layout")?.visibility = GONE
        
        contentContainer.visibility = VISIBLE
        closeContainer.visibility = GONE
        iconContainer.visibility = GONE
        contentContainer.removeAllViews()
        contentContainer.setPadding(0) // Fill background
        
        // Ensure pill is at collapsed size
        animateToCollapsed {
            contentContainer.removeAllViews() // Clear again just in case overlap during animation
            
            val mediaContainer = FrameLayout(context).apply {
                layoutParams = LayoutParams(dpToPx(pillWidthCollapsed), dpToPx(pillHeightCollapsed))
            }
            
            // Album art background (blurred/dimmed)
            if (albumArtBase64 != null) {
                val albumBitmap = decodeBase64ToBitmap(albumArtBase64)
                if (albumBitmap != null) {
                    val backgroundView = ImageView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(albumBitmap)
                        alpha = 0.4f // Dim for text visibility
                    }
                    mediaContainer.addView(backgroundView)
                }
            } else {
                // Dim dark background if no art
                mediaContainer.setBackgroundColor(Color.parseColor("#33000000"))
            }
            
            // Content overlay
            val contentLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                gravity = Gravity.CENTER_VERTICAL
            }
            
            // Play/Pause button on the left
            val playPauseBtn = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), LayoutParams.MATCH_PARENT)
                text = if (isPlaying) "⏸" else "▶"
                setTextColor(Color.WHITE)
                textSize = getScaledTextSize(16f)
                gravity = Gravity.CENTER
                setOnClickListener { onMediaAction?.invoke(if (isPlaying) "pause" else "play") }
            }
            contentLayout.addView(playPauseBtn)
            
            // Scrolling title (Marquee)
            val titleView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dpToPx(10)
                }
                text = title
                setTextColor(Color.WHITE)
                textSize = getScaledTextSize(11f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                isSingleLine = true
                isSelected = true // Start marquee
            }
            contentLayout.addView(titleView)
            
            mediaContainer.addView(contentLayout)
            contentContainer.addView(mediaContainer)
        }
    }
    
    fun expandWithMedia(title: String?, artist: String?, isPlaying: Boolean, albumArtBase64: String?, 
                        position: Long = 0, duration: Long = 0, volume: Int = 0) {
        val isAlreadyMediaExpanded = currentState == State.MEDIA_EXPANDED
        
        // Store playback state for progress animation
        this.isMediaPlaying = isPlaying
        this.mediaDuration = duration
        if (isPlaying && !isAlreadyMediaExpanded) {
            mediaStartTime = System.currentTimeMillis()
            mediaStartPosition = position
        }
        
        val wrapper = expandedContainer.findViewWithTag<FrameLayout>("scroll_wrapper") ?: return
        expandedList.visibility = GONE
        
        var mediaLayout = wrapper.findViewWithTag<FrameLayout>("media_expanded_layout")
        
        // If already expanded, just update the views
        if (isAlreadyMediaExpanded && mediaLayout != null) {
            updateExpandedMediaUI(mediaLayout, title, artist, isPlaying, albumArtBase64, position, duration, volume)
            return
        }
        
        currentState = State.MEDIA_EXPANDED

        // Initialize or Clear media layout
        if (mediaLayout == null) {
            mediaLayout = FrameLayout(context).apply {
                tag = "media_expanded_layout"
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            wrapper.addView(mediaLayout)
        } else {
            mediaLayout.removeAllViews()
        }
        
        mediaLayout.visibility = VISIBLE
        
        // CRITICAL: Forcefully hide pill content - do this BEFORE animation
        contentContainer.visibility = GONE
        iconContainer.visibility = GONE
        
        // Collapse pill and show close UI like notifications
        animateToCollapsed {
            closeContainer.visibility = VISIBLE
            
            val currentMediaLayout = wrapper.findViewWithTag<FrameLayout>("media_expanded_layout") ?: return@animateToCollapsed
            currentMediaLayout.removeAllViews()
            currentMediaLayout.background = null

            val mainLayout = LinearLayout(context).apply {
                tag = "media_main_layout"
                orientation = LinearLayout.VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_HORIZONTAL
                // Dark overlay background (only if we have art, otherwise parent has solid bg)
                if (albumArtBase64 != null) {
                    setBackgroundColor(Color.parseColor("#CC000000")) // 80% dark overlay
                }
            }

            // Album art as background layer (behind content)
            if (albumArtBase64 != null) {
                val albumBitmap = decodeBase64ToBitmap(albumArtBase64)
                if (albumBitmap != null) {
                    val bgView = object : ImageView(context) {
                        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                            // Report 0 height only if the parent is not forcing a height (measurement phase)
                            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
                            if (heightMode == MeasureSpec.EXACTLY) {
                                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                            } else {
                                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
                            }
                        }
                    }.apply {
                        tag = "media_background"
                        layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(albumBitmap)
                    }
                    currentMediaLayout.addView(bgView)
                }
            } else {
                // Solid dark background if no art
                currentMediaLayout.setBackgroundColor(Color.parseColor("#1C1C1E"))
            }

            val headerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(8))
            }
            
            val titleView = TextView(context).apply {
                tag = "media_title"
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                text = title ?: "Unknown Title"
                setTextColor(Color.WHITE)
                textSize = getScaledTextSize(18f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            headerLayout.addView(titleView)
            
            val artistView = TextView(context).apply {
                tag = "media_artist"
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                text = artist ?: "Unknown Artist"
                setTextColor(Color.LTGRAY)
                textSize = getScaledTextSize(14f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            headerLayout.addView(artistView)
            
            mainLayout.addView(headerLayout)

            // Progress Bar
            val progressLayout = LinearLayout(context).apply {
                tag = "media_progress_layout"
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                setPadding(dpToPx(12), 0, dpToPx(12), dpToPx(12))
                visibility = if (duration > 0) VISIBLE else GONE
            }

            val progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "media_progress_bar"
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(4))
                max = if (duration > 0) duration.toInt() else 100
                progress = if (duration > 0) position.toInt() else 0
                progressDrawable = context.getDrawable(android.R.drawable.progress_horizontal)
                progressTintList = ColorStateList.valueOf(Color.WHITE)
            }
            progressLayout.addView(progressBar)

            val timeLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }

            val posText = TextView(context).apply {
                tag = "media_position"
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                text = formatTime(position)
                setTextColor(Color.LTGRAY)
                textSize = getScaledTextSize(10f)
            }
            timeLayout.addView(posText)

            val durText = TextView(context).apply {
                tag = "media_duration"
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                text = formatTime(duration)
                setTextColor(Color.LTGRAY)
                textSize = getScaledTextSize(10f)
            }
            timeLayout.addView(durText)

            progressLayout.addView(timeLayout)
            mainLayout.addView(progressLayout)
            
            // Playback Controls
            val playbackLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                setPadding(dpToPx(12), 0, dpToPx(12), dpToPx(8))
            }
            
            val prevBtn = createControlButton("⏮", 24f) { onMediaAction?.invoke("prev") }
            playbackLayout.addView(prevBtn)
            
            val playPauseBtn = createControlButton(if (isPlaying) "⏸" else "▶", 32f) { 
                onMediaAction?.invoke(if (isPlaying) "pause" else "play") 
            }.apply {
                tag = "media_play_pause"
            }
            playbackLayout.addView(playPauseBtn)
            
            val nextBtn = createControlButton("⏭", 24f) { onMediaAction?.invoke("next") }
            playbackLayout.addView(nextBtn)
            
            mainLayout.addView(playbackLayout)
            
            // Volume Controls - Larger touch targets for watch
            val volumeLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(8))
            }
            
            val volLabel = TextView(context).apply {
                text = "Volume"
                textSize = getScaledTextSize(12f)
                setTextColor(Color.LTGRAY)
                layoutParams = LinearLayout.LayoutParams(dpToPx(60), LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_VERTICAL
            }
            volumeLayout.addView(volLabel)
            
            val volDownBtn = createControlButton("−", 28f) { 
                onMediaAction?.invoke("vol_down") 
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(50), 1f)
                maxWidth = dpToPx(70)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4D2C2C2E")) // Semi-transparent
                    cornerRadius = dpToPx(8).toFloat()
                }
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dpToPx(8)
            }
            volumeLayout.addView(volDownBtn)
            
            val volText = TextView(context).apply {
                tag = "media_volume_text"
                text = "$volume%"
                textSize = getScaledTextSize(14f)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dpToPx(50), LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
            }
            volumeLayout.addView(volText)
            
            val volUpBtn = createControlButton("+", 28f) { 
                onMediaAction?.invoke("vol_up") 
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(50), 1f)
                maxWidth = dpToPx(70)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4D2C2C2E")) // Semi-transparent
                    cornerRadius = dpToPx(8).toFloat()
                }
                (layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(8)
            }
            volumeLayout.addView(volUpBtn)
            
            mainLayout.addView(volumeLayout)
            currentMediaLayout.addView(mainLayout)
            
            // Smooth fade-in animation
            expandedContainer.visibility = VISIBLE
            expandedContainer.alpha = 0f
            expandedContainer.translationY = -dpToPx(10).toFloat()
            expandedContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                   
            // Start progress animation if playing
            if (isPlaying) {
                startMediaProgressAnimation()
            }
        }
    }
    
    private fun startMediaProgressAnimation() {
        mediaProgressHandler?.removeCallbacksAndMessages(null)
        if (mediaProgressHandler == null) {
            mediaProgressHandler = Handler(Looper.getMainLooper())
        }
        
        val updateProgress = object : Runnable {
            override fun run() {
                if (isMediaPlaying && mediaDuration > 0) {
                    val elapsed = System.currentTimeMillis() - mediaStartTime
                    val currentPos = mediaStartPosition + elapsed
                    
                    val wrapper = expandedContainer.findViewWithTag<FrameLayout>("scroll_wrapper")
                    val mediaLayout = wrapper?.findViewWithTag<FrameLayout>("media_expanded_layout")
                    
                    mediaLayout?.findViewWithTag<android.widget.ProgressBar>("media_progress_bar")?.apply {
                        if (currentPos <= mediaDuration) {
                            progress = currentPos.toInt()
                        }
                    }
                    mediaLayout?.findViewWithTag<TextView>("media_position")?.text = formatTime(currentPos)
                    
                    if (currentPos < mediaDuration) {
                        mediaProgressHandler?.postDelayed(this, 500) // Update every 500ms
                    }
                }
            }
        }
        mediaProgressHandler?.post(updateProgress)
    }
    
    private fun stopMediaProgressAnimation() {
        mediaProgressHandler?.removeCallbacksAndMessages(null)
    }

    private fun updateExpandedMediaUI(wrapper: View, title: String?, artist: String?, isPlaying: Boolean, 
                                     albumArtBase64: String?, position: Long, duration: Long, volume: Int) {
        // Update playback state for progress animation
        val wasPlaying = this.isMediaPlaying
        this.isMediaPlaying = isPlaying
        this.mediaDuration = duration
        
        if (isPlaying && !wasPlaying) {
            // Started playing
            mediaStartTime = System.currentTimeMillis()
            mediaStartPosition = position
            startMediaProgressAnimation()
        } else if (!isPlaying && wasPlaying) {
            // Paused
            stopMediaProgressAnimation()
        } else if (isPlaying) {
            // Playing - update position reference if provided position changed significantly
            val elapsed = System.currentTimeMillis() - mediaStartTime
            val estimatedPos = mediaStartPosition + elapsed
            if (Math.abs(estimatedPos - position) > 2000) { // More than 2s difference
                mediaStartTime = System.currentTimeMillis()
                mediaStartPosition = position
            }
        }
        
        // Update cover art if changed
        val currentBg = wrapper.findViewWithTag<ImageView>("media_background")
        if (albumArtBase64 != null) {
            val albumBitmap = decodeBase64ToBitmap(albumArtBase64)
            if (albumBitmap != null && currentBg != null) {
                currentBg.setImageBitmap(albumBitmap)
            }
        }
        
        wrapper.findViewWithTag<TextView>("media_title")?.text = title ?: "Unknown Title"
        wrapper.findViewWithTag<TextView>("media_artist")?.text = artist ?: "Unknown Artist"
        wrapper.findViewWithTag<TextView>("media_play_pause")?.apply {
            text = if (isPlaying) "⏸" else "▶"
            setOnClickListener { onMediaAction?.invoke(if (isPlaying) "pause" else "play") }
        }
        
        wrapper.findViewWithTag<View>("media_progress_layout")?.visibility = if (duration > 0) VISIBLE else GONE
        wrapper.findViewWithTag<android.widget.ProgressBar>("media_progress_bar")?.apply {
            if (duration > 0) {
                max = duration.toInt()
                if (!isPlaying) {
                    progress = position.toInt()
                }
            }
        }
        if (!isPlaying) {
            wrapper.findViewWithTag<TextView>("media_position")?.text = formatTime(position)
        }
        wrapper.findViewWithTag<TextView>("media_duration")?.text = formatTime(duration)
        
        wrapper.findViewWithTag<TextView>("media_volume_text")?.text = "$volume%"
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60)) % 24
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
    
    private fun createControlButton(icon: String, size: Float, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(60), dpToPx(60))
            text = icon
            setTextColor(Color.WHITE)
            textSize = getScaledTextSize(size)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            
            // Basic ripple effect
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
    }
    
    // Callbacks
    var onAlarmAction: ((String) -> Unit)? = null
    var onMediaAction: ((String) -> Unit)? = null
}
