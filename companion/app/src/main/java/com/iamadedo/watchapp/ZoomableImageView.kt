package com.iamadedo.watchapp

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * A custom ImageView that supports smooth pinch-to-zoom, pan, and double-tap to zoom.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val imageMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var minScale = 1f
    private var maxScale = 6f
    private var midScale = 3f

    private var viewWidth = 0
    private var viewHeight = 0
    private var intrinsicWidth = 0f
    private var intrinsicHeight = 0f

    // Touch state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1
    private var isScaling = false

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val focusX = detector.focusX
                val focusY = detector.focusY

                val currentScale = getCurrentScale()
                var newScale = currentScale * scaleFactor

                // Clamp scale
                newScale = max(minScale, min(maxScale, newScale))
                val actualScaleFactor = newScale / currentScale

                imageMatrix.postScale(actualScaleFactor, actualScaleFactor, focusX, focusY)
                constrainMatrix()
                setImageMatrix(imageMatrix)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val currentScale = getCurrentScale()
                val targetScale: Float

                targetScale = when {
                    currentScale < midScale - 0.1f -> midScale
                    currentScale < maxScale - 0.1f -> maxScale
                    else -> minScale
                }

                animateZoom(currentScale, targetScale, e.x, e.y)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                return false
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        setupInitialMatrix()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        drawable?.let {
            intrinsicWidth = it.intrinsicWidth.toFloat()
            intrinsicHeight = it.intrinsicHeight.toFloat()
        }
        setupInitialMatrix()
    }

    private fun setupInitialMatrix() {
        val drawable = drawable ?: return
        if (viewWidth == 0 || viewHeight == 0) return

        intrinsicWidth = drawable.intrinsicWidth.toFloat()
        intrinsicHeight = drawable.intrinsicHeight.toFloat()

        if (intrinsicWidth == 0f || intrinsicHeight == 0f) return

        // Calculate scale to fit image in view
        val scaleX = viewWidth / intrinsicWidth
        val scaleY = viewHeight / intrinsicHeight
        val scale = min(scaleX, scaleY)

        minScale = scale
        midScale = scale * 2.5f
        maxScale = scale * 6f

        // Center the image
        val dx = (viewWidth - intrinsicWidth * scale) / 2f
        val dy = (viewHeight - intrinsicHeight * scale) / 2f

        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        setImageMatrix(imageMatrix)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // When second finger goes down, update tracking to primary finger
                activePointerId = event.getPointerId(0)
                lastTouchX = event.getX(0)
                lastTouchY = event.getY(0)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isScaling && event.pointerCount == 1) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)

                        val dx = x - lastTouchX
                        val dy = y - lastTouchY

                        // Only pan if zoomed in
                        if (getCurrentScale() > minScale + 0.01f) {
                            imageMatrix.postTranslate(dx, dy)
                            constrainMatrix()
                            setImageMatrix(imageMatrix)
                        }

                        lastTouchX = x
                        lastTouchY = y
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    // Switch to another pointer
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    if (newPointerIndex < event.pointerCount) {
                        lastTouchX = event.getX(newPointerIndex)
                        lastTouchY = event.getY(newPointerIndex)
                        activePointerId = event.getPointerId(newPointerIndex)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
            }
        }

        return true
    }

    private fun getCurrentScale(): Float {
        imageMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun constrainMatrix() {
        val rect = getImageRect() ?: return

        var dx = 0f
        var dy = 0f

        if (rect.width() <= viewWidth) {
            // Center horizontally
            dx = (viewWidth - rect.width()) / 2f - rect.left
        } else {
            // Constrain to edges
            if (rect.left > 0) dx = -rect.left
            else if (rect.right < viewWidth) dx = viewWidth - rect.right
        }

        if (rect.height() <= viewHeight) {
            // Center vertically
            dy = (viewHeight - rect.height()) / 2f - rect.top
        } else {
            // Constrain to edges
            if (rect.top > 0) dy = -rect.top
            else if (rect.bottom < viewHeight) dy = viewHeight - rect.bottom
        }

        if (dx != 0f || dy != 0f) {
            imageMatrix.postTranslate(dx, dy)
        }
    }

    private fun getImageRect(): RectF? {
        if (intrinsicWidth == 0f || intrinsicHeight == 0f) return null
        val rect = RectF(0f, 0f, intrinsicWidth, intrinsicHeight)
        imageMatrix.mapRect(rect)
        return rect
    }

    private fun animateZoom(startScale: Float, targetScale: Float, focusX: Float, focusY: Float) {
        val startMatrix = Matrix(imageMatrix)

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 250
        animator.interpolator = android.view.animation.DecelerateInterpolator()

        animator.addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float
            val currentScale = startScale + (targetScale - startScale) * fraction
            val scaleFactor = currentScale / getCurrentScale()

            imageMatrix.set(startMatrix)
            val totalScale = currentScale / startScale
            imageMatrix.postScale(totalScale, totalScale, focusX, focusY)
            constrainMatrix()
            setImageMatrix(imageMatrix)
        }

        animator.start()
    }

    fun resetZoom() {
        setupInitialMatrix()
    }
}
