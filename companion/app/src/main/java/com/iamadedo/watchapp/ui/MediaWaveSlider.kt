package com.iamadedo.watchapp.ui

import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import kotlin.math.sin
import kotlin.math.PI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaWaveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val waveHeight by animateDpAsState(
        targetValue = if (isPlaying) 6.dp else 0.dp,
        label = "waveHeight",
        animationSpec = spring(stiffness = Spring.StiffnessLow)
    )

    val infiniteTransition = rememberInfiniteTransition(label = "wavePhase")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        modifier = modifier,
        track = { sliderState ->
            val range = valueRange.endInclusive - valueRange.start
            val fraction = if (range > 0) (value - valueRange.start) / range else 0f
            
            Canvas(modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)) {
                val width = size.width
                val height = size.height
                val centerY = height / 2
                val thumbX = width * fraction
                
                // Draw inactive track (straight line)
                drawLine(
                    color = inactiveTrackColor,
                    start = Offset(thumbX, centerY),
                    end = Offset(width, centerY),
                    strokeWidth = 4.dp.toPx()
                )
                
                // Draw active track (wavy line)
                if (waveHeight > 0.dp) {
                    val path = Path()
                    path.moveTo(0f, centerY)
                    val stepPx = 2f
                    val stepsCount = (thumbX / stepPx).toInt()
                    val waveLength = 40f
                    val amplitude = waveHeight.toPx()
                    
                    for (i in 1..stepsCount) {
                        val x = i.toFloat() * stepPx
                        // Include phase to create the moving effect
                        val angle = (x / waveLength) * 2 * PI.toFloat() - phase
                        val y = centerY + sin(angle) * amplitude
                        path.lineTo(x, y)
                    }
                    path.lineTo(thumbX, centerY) // Ensure it ends at the thumb position
                    
                    drawPath(
                        path = path,
                        color = primaryColor,
                        style = Stroke(width = 4.dp.toPx())
                    )
                } else {
                    // Straight active track if not playing
                    drawLine(
                        color = primaryColor,
                        start = Offset(0f, centerY),
                        end = Offset(thumbX, centerY),
                        strokeWidth = 4.dp.toPx()
                    )
                }
            }
        },
        colors = SliderDefaults.colors(
            thumbColor = primaryColor,
            activeTrackColor = Color.Transparent, // We draw our own track
            inactiveTrackColor = Color.Transparent
        )
    )
}
