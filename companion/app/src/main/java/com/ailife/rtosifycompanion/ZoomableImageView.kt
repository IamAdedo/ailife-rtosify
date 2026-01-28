package com.ailife.rtosifycompanion

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * A custom ImageView that supports pinch-to-zoom, pan, and double-tap to zoom.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f

    private var mode = NONE

    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f

    private val matrixValues = FloatArray(9)

    private var viewWidth = 0
    private var viewHeight = 0
    private var imageWidth = 0f
    private var imageHeight = 0f

    private var isInitialized = false

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = matrix

        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        fitImageToView()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        isInitialized = false
        fitImageToView()
    }

    private fun fitImageToView() {
        val drawable = drawable ?: return
        if (viewWidth == 0 || viewHeight == 0) return

        imageWidth = drawable.intrinsicWidth.toFloat()
        imageHeight = drawable.intrinsicHeight.toFloat()

        if (imageWidth == 0f || imageHeight == 0f) return

        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        val scale = minOf(scaleX, scaleY)

        minScale = scale
        currentScale = scale

        matrix.reset()
        matrix.postScale(scale, scale)

        val redundantXSpace = viewWidth - (scale * imageWidth)
        val redundantYSpace = viewHeight - (scale * imageHeight)
        matrix.postTranslate(redundantXSpace / 2, redundantYSpace / 2)

        imageMatrix = matrix
        isInitialized = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(mid, event)
                    mode = ZOOM
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    matrix.set(savedMatrix)
                    val dx = event.x - start.x
                    val dy = event.y - start.y
                    matrix.postTranslate(dx, dy)
                    fixTranslation()
                } else if (mode == ZOOM && event.pointerCount >= 2) {
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        matrix.set(savedMatrix)
                        val scale = newDist / oldDist
                        matrix.postScale(scale, scale, mid.x, mid.y)
                        fixScaleAndTranslation()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
        }

        imageMatrix = matrix
        return true
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    private fun fixScaleAndTranslation() {
        matrix.getValues(matrixValues)
        currentScale = matrixValues[Matrix.MSCALE_X]

        if (currentScale < minScale) {
            matrix.setScale(minScale, minScale)
            currentScale = minScale
            // Re-center after reset
            val redundantXSpace = viewWidth - (minScale * imageWidth)
            val redundantYSpace = viewHeight - (minScale * imageHeight)
            matrix.postTranslate(redundantXSpace / 2, redundantYSpace / 2)
        } else if (currentScale > maxScale) {
            val scale = maxScale / currentScale
            matrix.postScale(scale, scale, mid.x, mid.y)
            currentScale = maxScale
        }

        fixTranslation()
    }

    private fun fixTranslation() {
        matrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val scale = matrixValues[Matrix.MSCALE_X]

        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        var fixTransX = 0f
        var fixTransY = 0f

        if (scaledWidth < viewWidth) {
            fixTransX = (viewWidth - scaledWidth) / 2 - transX
        } else {
            if (transX > 0) fixTransX = -transX
            else if (transX + scaledWidth < viewWidth) fixTransX = viewWidth - transX - scaledWidth
        }

        if (scaledHeight < viewHeight) {
            fixTransY = (viewHeight - scaledHeight) / 2 - transY
        } else {
            if (transY > 0) fixTransY = -transY
            else if (transY + scaledHeight < viewHeight) fixTransY = viewHeight - transY - scaledHeight
        }

        matrix.postTranslate(fixTransX, fixTransY)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor

            if (newScale in minScale..maxScale) {
                matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                currentScale = newScale
                fixTranslation()
                imageMatrix = matrix
            }
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > minScale * 1.5f) {
                // Zoom out to fit
                fitImageToView()
            } else {
                // Zoom in to 2.5x
                val targetScale = minScale * 2.5f
                val scaleFactor = targetScale / currentScale
                matrix.postScale(scaleFactor, scaleFactor, e.x, e.y)
                currentScale = targetScale
                fixScaleAndTranslation()
                imageMatrix = matrix
            }
            return true
        }
    }

    fun resetZoom() {
        fitImageToView()
    }
}
