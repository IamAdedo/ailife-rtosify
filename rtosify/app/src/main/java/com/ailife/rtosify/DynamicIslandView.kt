package com.ailife.rtosify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.view.setPadding

class DynamicIslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val PILL_HEIGHT_COLLAPSED_DP = 40
        private const val PILL_WIDTH_COLLAPSED_DP = 150
        private const val PILL_HEIGHT_EXPANDED_DP = 80 // Reasonable for title + text
        private const val PILL_WIDTH_EXPANDED_DP = 320
    }

    enum class BackgroundMode { IMAGE, COLOR }
    private var backgroundMode = BackgroundMode.IMAGE
    private var solidColor = Color.parseColor("#1C1C1E")

    private val rootBackgroundImageView: ImageView // Full image for cropping help
    private val pillContainer: FrameLayout
    private val backgroundImageView: ImageView // Clipped to pill
    private val contentContainer: LinearLayout
    
    private var previewMode: Boolean = false
    private var previewBitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val scaleDetector by lazy { ScaleGestureDetector(context, ScaleListener()) }
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1
    
    private var pillHeightCollapsed = PILL_HEIGHT_COLLAPSED_DP
    private var pillWidthCollapsed = PILL_WIDTH_COLLAPSED_DP

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        clipChildren = false
        clipToPadding = false

        // 1. Root dimmed background (only for preview mode)
        rootBackgroundImageView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.MATRIX
            alpha = 0.3f
            visibility = GONE
        }
        addView(rootBackgroundImageView)

        // 2. Main pill container
        pillContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(dpToPx(pillWidthCollapsed), dpToPx(pillHeightCollapsed))
                .apply { 
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                    topMargin = dpToPx(20)
                }
            background = createPillBackground()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dpToPx(4).toFloat()
        }
        
        backgroundImageView = ImageView(context).apply {
             layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
             scaleType = ImageView.ScaleType.MATRIX
        }
        pillContainer.addView(backgroundImageView)

        contentContainer = LinearLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
        }
        pillContainer.addView(contentContainer)

        addView(pillContainer)
    }

    private fun createPillBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // Always use dark background for the pill itself as a base
            setColor(Color.parseColor("#1C1C1E"))
            // We use height/2 for full rounding. Note: this might need update if height changes.
            val currentH = pillContainer?.layoutParams?.height ?: dpToPx(pillHeightCollapsed)
            cornerRadius = (currentH / 2f)
        }
    }

    fun setBackgroundMode(mode: BackgroundMode) {
        backgroundMode = mode
        updateBackgroundUI()
    }

    fun setPreviewColor(color: Int, brightness: Int = 100) {
        solidColor = color
        backgroundMode = BackgroundMode.COLOR
        updateBackgroundUI()
        setPreviewOpacity((brightness * 255) / 100)
    }

    private fun updateBackgroundUI() {
        if (backgroundMode == BackgroundMode.COLOR) {
            backgroundImageView.visibility = VISIBLE
            backgroundImageView.setImageDrawable(ColorDrawable(solidColor))
            rootBackgroundImageView.visibility = GONE
        } else {
            backgroundImageView.visibility = VISIBLE
            backgroundImageView.setImageBitmap(previewBitmap)
            if (previewMode) rootBackgroundImageView.visibility = VISIBLE
        }
        pillContainer.background = createPillBackground()
    }

    fun showIdleState() {
        showConnectedState("BT")
    }

    fun showExpandedPreview() {
        val mockNotif = NotificationData(
            packageName = context.packageName,
            title = "Preview Notification",
            text = "This is how it looks expanded",
            key = "preview_key"
        )
        showNotification(mockNotif)
    }

    fun showConnectedState(transportType: String = "") {
        contentContainer.removeAllViews()
        contentContainer.orientation = LinearLayout.HORIZONTAL
        contentContainer.gravity = Gravity.CENTER
        
        val iconSize = dpToPx(24)
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setImageResource(R.drawable.ic_bluetooth)
            setColorFilter(Color.parseColor("#30D158"))
        }
        contentContainer.addView(icon)
        
        animateToSize(dpToPx(pillWidthCollapsed), dpToPx(pillHeightCollapsed))
    }

    fun showNotification(notif: NotificationData) {
        contentContainer.removeAllViews()
        contentContainer.orientation = LinearLayout.HORIZONTAL
        contentContainer.gravity = Gravity.CENTER_VERTICAL
        
        // Icon
        val iconSize = dpToPx(32)
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { 
                marginStart = dpToPx(4)
                marginEnd = dpToPx(12) 
            }
            // Use current app icon
            val appIcon = context.packageManager.getApplicationIcon(context.packageName)
            setImageDrawable(appIcon)
        }
        contentContainer.addView(icon)
        
        // Text Layout
        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            
            addView(TextView(context).apply {
                text = notif.title
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, Typeface.BOLD)
            })
            
            addView(TextView(context).apply {
                text = notif.text
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        contentContainer.addView(textLayout)

        animateToSize(dpToPx(PILL_WIDTH_EXPANDED_DP), dpToPx(PILL_HEIGHT_EXPANDED_DP))
    }

    private fun animateToSize(targetWidth: Int, targetHeight: Int) {
        val initialWidth = pillContainer.width
        val initialHeight = pillContainer.height
        
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val w = (initialWidth + (targetWidth - initialWidth) * fraction).toInt()
                val h = (initialHeight + (targetHeight - initialHeight) * fraction).toInt()
                
                pillContainer.layoutParams.width = w
                pillContainer.layoutParams.height = h
                pillContainer.background = createPillBackground()
                pillContainer.requestLayout()
                
                // Keep background image synced with pill position relative to root
                updateMatrices()
            }
            start()
        }
    }

    fun updateDimensions(widthDp: Int, heightDp: Int) {
        pillWidthCollapsed = widthDp
        pillHeightCollapsed = heightDp
        
        pillContainer.layoutParams.width = dpToPx(pillWidthCollapsed)
        pillContainer.layoutParams.height = dpToPx(pillHeightCollapsed)
        pillContainer.background = createPillBackground()
        pillContainer.requestLayout()
        updateMatrices()
    }

    fun setPreviewMode(enabled: Boolean) {
        previewMode = enabled
        rootBackgroundImageView.visibility = if (enabled) VISIBLE else GONE
        updateMatrices()
    }

    fun setPreviewImage(bitmap: Bitmap?) {
        previewBitmap = bitmap
        backgroundImageView.setImageBitmap(bitmap)
        rootBackgroundImageView.setImageBitmap(bitmap)
        if (bitmap != null) resetImageMatrix()
    }

    fun setPreviewOpacity(alpha: Int) {
        backgroundImageView.alpha = alpha / 255f
    }
    private fun resetImageMatrix() {
        val bmp = previewBitmap ?: return
        val viewW = width.toFloat().takeIf { it > 0 } ?: dpToPx(300).toFloat()
        val viewH = height.toFloat().takeIf { it > 0 } ?: dpToPx(300).toFloat()

        imageMatrix.reset()
        // Simple center crop logic for initial state
        val scale = Math.max(viewW / bmp.width, viewH / bmp.height)
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate((viewW - bmp.width * scale) / 2f, (viewH - bmp.height * scale) / 2f)
        updateMatrices()
    }

    private fun updateMatrices() {
        // The root background shows the full image in the view's coordinate system
        rootBackgroundImageView.imageMatrix = imageMatrix
        
        // The pill background image needs to be offset by the pill's top/left relative to the root
        // so that it looks like a "window" into the full image.
        val pillMatrix = Matrix(imageMatrix)
        pillMatrix.postTranslate(-pillContainer.left.toFloat(), -pillContainer.top.toFloat())
        backgroundImageView.imageMatrix = pillMatrix
    }

    fun generateCroppedBitmap(): Bitmap? {
        val bmp = previewBitmap ?: return null
        
        // We want to crop specifically what is visible inside the pill container
        val w = pillContainer.width
        val h = pillContainer.height
        if (w <= 0 || h <= 0) return null
        
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        val pillMatrix = Matrix(imageMatrix)
        pillMatrix.postTranslate(-pillContainer.left.toFloat(), -pillContainer.top.toFloat())
        
        canvas.drawBitmap(bmp, pillMatrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return result
    }

    fun getMatrixValues(): FloatArray {
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        return values
    }

    fun setMatrixValues(values: FloatArray) {
        imageMatrix.setValues(values)
        updateMatrices()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) updateMatrices()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (previewMode) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (previewMode) {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    activePointerId = event.getPointerId(0)
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx != -1 && !scaleDetector.isInProgress) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        imageMatrix.postTranslate(x - lastTouchX, y - lastTouchY)
                        updateMatrices()
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val idx = event.actionIndex
                    if (event.getPointerId(idx) == activePointerId) {
                        val nextIdx = if (idx == 0) 1 else 0
                        lastTouchX = event.getX(nextIdx)
                        lastTouchY = event.getY(nextIdx)
                        activePointerId = event.getPointerId(nextIdx)
                    }
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            imageMatrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            updateMatrices()
            return true
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
    private fun dpToPx(dp: Float): Int = (dp * context.resources.displayMetrics.density).toInt()
}
