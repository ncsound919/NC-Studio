package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.Radius
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RulerTick
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.PanelInset
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioPurple
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.SurfaceRaised
import com.example.ui.theme.SurfaceSunken
import com.example.ui.theme.TrackHeader
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Authentic studio hardware-inspired rotary knob.
 * Features:
 * - 270-degree rotation arc with calibrated LED ring
 * - Milled brushed-aluminum cap with position notch
 * - Fine vertical drag gesture (drag up to increase, down to decrease)
 * - Double tap to reset to default value
 * - High-contrast OLED style formatted readout
 */
@Composable
fun AudioRotaryKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    modifier: Modifier = Modifier,
    defaultValue: Float = range.start + (range.endInclusive - range.start) * 0.5f,
    accentColor: Color = NeonCyan,
    knobSize: Dp = 68.dp,
    valueFormatter: (Float) -> String = { "%.1f".format(it) },
    testTag: String = "rotary_knob_${label.lowercase().replace(" ", "_")}"
) {
    val totalRange = (range.endInclusive - range.start).coerceAtLeast(0.001f)

    // Knob gestures own their value while dragging. Reading the composed
    // `value` inside a long-lived pointerInput would keep using the value from
    // the start of the gesture and make the knob feel sticky.
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    var dragValue by remember { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (!isDragging) dragValue = value
    }
    val normalized = ((dragValue - range.start) / totalRange).coerceIn(0f, 1f)

    // Sweep from 135 deg to 405 deg (270 degrees total)
    val startAngle = 135f
    val sweepMax = 270f
    val currentSweep = normalized * sweepMax
    val currentAngleRad = ((startAngle + currentSweep) * PI / 180.0).toFloat()

    Column(
        modifier = modifier
            .testTag(testTag)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Label
        Text(
            text = label.uppercase(),
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(3.dp))

        // Interactive Knob Canvas
        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(totalRange) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragValue = latestValue
                        },
                        onVerticalDrag = { _, dragAmount ->
                            // Drag up is negative y -> increases value.
                            val sensitivity = totalRange / 180f
                            val next = (dragValue - dragAmount * sensitivity)
                                .coerceIn(range.start, range.endInclusive)
                            dragValue = next
                            latestOnValueChange(next)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                }
                .pointerInput(defaultValue) {
                    detectTapGestures(
                        onDoubleTap = {
                            dragValue = defaultValue
                            latestOnValueChange(defaultValue)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2f - 4f
                val innerRadius = outerRadius * 0.72f

                // 1. Background Arc Track
                drawArc(
                    color = SurfaceRaised,
                    startAngle = startAngle,
                    sweepAngle = sweepMax,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = Size(outerRadius * 2f, outerRadius * 2f),
                    style = Stroke(width = 4.5f, cap = StrokeCap.Round)
                )

                // 2. Glowing LED Value Arc
                if (currentSweep > 1f) {
                    drawArc(
                        color = accentColor.copy(alpha = 0.35f),
                        startAngle = startAngle,
                        sweepAngle = currentSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                        size = Size(outerRadius * 2f, outerRadius * 2f),
                        style = Stroke(width = 8f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = accentColor,
                        startAngle = startAngle,
                        sweepAngle = currentSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                        size = Size(outerRadius * 2f, outerRadius * 2f),
                        style = Stroke(width = 4.5f, cap = StrokeCap.Round)
                    )
                }

                // 3. Tick Marks at extremities and center
                val tickAngles = listOf(135f, 270f, 405f)
                for (a in tickAngles) {
                    val rad = (a * PI / 180.0).toFloat()
                    val tStart = Offset(center.x + (outerRadius + 2f) * cos(rad), center.y + (outerRadius + 2f) * sin(rad))
                    val tEnd = Offset(center.x + (outerRadius + 6f) * cos(rad), center.y + (outerRadius + 6f) * sin(rad))
                    drawLine(
                        color = TextMuted,
                        start = tStart,
                        end = tEnd,
                        strokeWidth = 1.5f
                    )
                }

                // 4. Aluminum Brushed Knob Body (Radial Gradient)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            StudioCardBorder,
                            StudioCard,
                            TrackHeader
                        ),
                        center = center,
                        radius = innerRadius
                    ),
                    radius = innerRadius,
                    center = center
                )

                // Knob Bezel Border
                drawCircle(
                    color = RulerTick,
                    radius = innerRadius,
                    center = center,
                    style = Stroke(width = 1.5f)
                )

                // 5. Etched Notch Pointer
                val notchStart = Offset(
                    center.x + (innerRadius * 0.35f) * cos(currentAngleRad),
                    center.y + (innerRadius * 0.35f) * sin(currentAngleRad)
                )
                val notchEnd = Offset(
                    center.x + (innerRadius * 0.88f) * cos(currentAngleRad),
                    center.y + (innerRadius * 0.88f) * sin(currentAngleRad)
                )

                // Glow under pointer
                drawLine(
                    color = accentColor.copy(alpha = 0.5f),
                    start = notchStart,
                    end = notchEnd,
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
                // Crisp white/accent core
                drawLine(
                    color = Color.White,
                    start = notchStart,
                    end = notchEnd,
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Formatted Readout Badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(SurfaceSunken)
                .border(0.5.dp, SurfaceRaised, RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = valueFormatter(dragValue),
                color = accentColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Authentic console hardware fader / slider with calibrated dB scale.
 */
@Composable
fun AudioFaderSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    modifier: Modifier = Modifier,
    accentColor: Color = NeonCyan,
    defaultValue: Float = 0.0f,
    testTag: String = "fader_${label.lowercase().replace(" ", "_")}"
) {
    val totalRange = (range.endInclusive - range.start).coerceAtLeast(0.001f)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    var dragValue by remember { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (!isDragging) dragValue = value
    }
    val fraction = ((dragValue - range.start) / totalRange).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .testTag(testTag)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (dragValue <= -59f) "-∞ dB" else "%+.1f dB".format(dragValue),
                color = if (dragValue > 0f) StudioAmber else accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(TrackHeader)
                .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.sm))
                .pointerInput(totalRange) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            val next = range.start + frac * totalRange
                            dragValue = next
                            latestOnValueChange(next)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, _ ->
                            val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                            val next = range.start + frac * totalRange
                            dragValue = next
                            latestOnValueChange(next)
                        }
                    )
                }
                .pointerInput(defaultValue) {
                    detectTapGestures(
                        onTap = { offset ->
                            val tapFrac = (offset.x / size.width).coerceIn(0f, 1f)
                            val next = range.start + tapFrac * totalRange
                            dragValue = next
                            latestOnValueChange(next)
                        },
                        onDoubleTap = {
                            dragValue = defaultValue
                            latestOnValueChange(defaultValue)
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackY = size.height / 2f
                val faderX = size.width * fraction

                // 1. Recessed Fader Slot
                drawRoundRect(
                    color = PanelInset,
                    topLeft = Offset(8f, trackY - 3f),
                    size = Size(size.width - 16f, 6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                )

                // 2. Active fill line
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.7f),
                    topLeft = Offset(8f, trackY - 2f),
                    size = Size((faderX - 8f).coerceAtLeast(0f), 4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                )

                // 3. Unity Detent Tick (0 dB mark)
                val zeroFrac = ((0.0f - range.start) / totalRange).coerceIn(0f, 1f)
                val zeroX = size.width * zeroFrac
                drawLine(
                    color = TextPrimary,
                    start = Offset(zeroX, 2f),
                    end = Offset(zeroX, size.height - 2f),
                    strokeWidth = 1.5f
                )

                // 4. Metallic Fader Cap Thumb
                val capWidth = 24f
                val capHeight = size.height - 6f
                val capLeft = faderX - capWidth / 2f

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            RulerTick,
                            SurfaceRaised,
                            TrackHeader
                        ),
                        startY = 3f,
                        endY = size.height - 3f
                    ),
                    topLeft = Offset(capLeft, 3f),
                    size = Size(capWidth, capHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )

                // Fader Cap Rim
                drawRoundRect(
                    color = TextMuted,
                    topLeft = Offset(capLeft, 3f),
                    size = Size(capWidth, capHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                    style = Stroke(width = 1f)
                )

                // Fader Center Indicator Strip
                drawLine(
                    color = accentColor,
                    start = Offset(faderX, 5f),
                    end = Offset(faderX, size.height - 5f),
                    strokeWidth = 2.5f
                )
            }
        }
    }
}

/**
 * Illuminated studio push button / switch.
 */
@Composable
fun AudioSwitchToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    activeColor: Color = ElectricLime,
    testTag: String = "switch_${label.lowercase().replace(" ", "_")}"
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(if (checked) activeColor.copy(alpha = 0.15f) else StudioCard)
            .border(
                1.dp,
                if (checked) activeColor else StudioCardBorder,
                RoundedCornerShape(Radius.md)
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Glowing LED Indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (checked) activeColor else StudioCardBorder)
                .border(
                    0.5.dp,
                    if (checked) Color.White else Color.Transparent,
                    CircleShape
                )
        )
        Text(
            text = label.uppercase(),
            color = if (checked) activeColor else TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
