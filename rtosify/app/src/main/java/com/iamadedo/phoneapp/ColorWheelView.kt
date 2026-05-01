package com.iamadedo.phoneapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class ColorWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private var selectedX = 0f
    private var selectedY = 0f

    var onColorSelected: ((Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        radius = min(centerX, centerY) * 0.9f
        
        val sweepGradient = SweepGradient(centerX, centerY, 
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
        wheelPaint.shader = sweepGradient
        
        updateMarkerFromColor()
    }

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val hue = hsv[0]
        val saturation = hsv[1]
        
        val angle = Math.toRadians(hue.toDouble())
        val r = saturation * radius
        
        selectedX = centerX + r * cos(angle).toFloat()
        selectedY = centerY + r * sin(angle).toFloat()
        
        invalidate()
    }

    private fun updateMarkerFromColor() {
        // This is called after size changed to ensure radius is known
        // If we have a pending color, we should apply it here
    }

    override fun onDraw(canvas: Canvas) {
        // Draw color wheel
        canvas.drawCircle(centerX, centerY, radius, wheelPaint)
        
        // Draw saturation overlay
        val radialGradient = RadialGradient(centerX, centerY, radius, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = radialGradient }
        canvas.drawCircle(centerX, centerY, radius, overlayPaint)

        // Draw marker
        canvas.drawCircle(selectedX, selectedY, 15f, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val dx = x - centerX
        val dy = y - centerY
        val distance = sqrt(dx * dx + dy * dy)

        if (event.action == MotionEvent.ACTION_DOWN && distance > radius) {
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (distance <= radius) {
                    selectedX = x
                    selectedY = y
                } else {
                    val angle = atan2(dy, dx)
                    selectedX = centerX + radius * cos(angle)
                    selectedY = centerY + radius * sin(angle)
                }
                
                val color = getColorAt(selectedX, selectedY)
                onColorSelected?.invoke(color)
                invalidate()
            }
        }
        return true
    }

    private fun getColorAt(x: Float, y: Float): Int {
        val dx = x - centerX
        val dy = y - centerY
        val angle = (atan2(dy, dx) / (2 * PI) * 360).toFloat()
        val hue = if (angle < 0) angle + 360 else angle
        val distance = sqrt(dx * dx + dy * dy)
        val saturation = (distance / radius).coerceIn(0f, 1f)
        return Color.HSVToColor(floatArrayOf(hue, saturation, 1.0f))
    }
}
