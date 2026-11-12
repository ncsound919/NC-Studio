package com.overlay.studio.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * NC Studio shared studio controls (`shared/studio-ui`).
 *
 * This one source directory is compiled into NC Studio and NC Phone Beat, so a
 * knob, fader, meter, button or panel is literally the same implementation in
 * both apps. NC Sound Lab mirrors the geometry, colours and interaction rules
 * in `new dsp/src/components/studio` from the same spec
 * (`shared/studio-ui/spec/studio-ui.v1.json`).
 *
 * The interaction model takes the best of both existing implementations:
 *  - NC Studio's 270-degree LED ring, hardware cap, notch and OLED readout;
 *  - Phone Beat's 240 dp full-range drag that feels identical at every size;
 *  - Pro Tools' mature fader rules: double-tap resets, fine readout, peak-hold
 *    meters with a fixed -20 dB reference and green/amber/red zones.
 */
object StudioPalette {
    val Background = Color(0xFF0A0D14)
    val Surface = Color(0xFF131822)
    val Card = Color(0xFF1C2230)
    val Border = Color(0xFF2B3448)
    val Inset = Color(0xFF070A0F)
    val Raised = Color(0xFF232B3B)
    val TextPrimary = Color(0xFFF0F4F8)
    val TextSecondary = Color(0xFFB6B6C6)
    val TextMuted = Color(0xFF74839A)
    val Accent = Color(0xFF00E5FF)
    val Lime = Color(0xFF00E676)
    val Amber = Color(0xFFFFB300)
    val Purple = Color(0xFFD05CE3)
    val Danger = Color(0xFFFF5C7A)
    val OnAccent = Color(0xFF061018)

    // Overlay365 brand chrome (marketing-studio DESIGN-KIT + brand/tokens).
    // App headers and primary CTAs use these; knobs, faders and meters stay
    // on the studio ramp above so mix reads never shift hue.
    val BrandMidnight = Color(0xFF05060A)
    val BrandGraphite = Color(0xFF0E1117)
    val BrandSlate = Color(0xFF141924)
    val BrandCyanElec = Color(0xFF22D3EE)
    val BrandGold = Color(0xFFF5C451)
    val BrandGoldSoft = Color(0xFFF7D983)
    val BrandSoftWhite = Color(0xFFF6F7F9)
}

/** Full-range drag distance, matching Phone Beat's knob feel. */
private val KnobFullRangeDrag = 240.dp

@Composable
fun StudioKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    modifier: Modifier = Modifier,
    defaultValue: Float = range.start + (range.endInclusive - range.start) * 0.5f,
    accentColor: Color = StudioPalette.Accent,
    knobSize: Dp = 58.dp,
    valueFormatter: (Float) -> String = { "%.2f".format(it) },
    testTag: String = "studio_knob_${label.lowercase().replace(' ', '_')}",
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestRange by rememberUpdatedState(range)
    var dragValue by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!dragging) dragValue = value
    }

    val animated by animateFloatAsState(
        targetValue = if (dragging) dragValue else value,
        animationSpec = tween(durationMillis = if (dragging) 0 else 120),
        label = "knob_$label",
    )
    val normalized = ((animated - range.start) / span).coerceIn(0f, 1f)
    val startAngle = 135f
    val sweep = 270f
    val currentSweep = normalized * sweep
    val valueAngle = ((startAngle + currentSweep) * PI / 180.0).toFloat()

    Column(
        modifier = modifier.testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.uppercase(),
            color = if (dragging) accentColor else StudioPalette.TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))

        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(Unit) {
                    val fullRangePx = KnobFullRangeDrag.toPx()
                    var start = 0f
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            start = latestValue
                            acc = 0f
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            acc -= dy
                            val lo = latestRange.start
                            val hi = latestRange.endInclusive
                            val full = (hi - lo).takeIf { it > 0f } ?: 1f
                            val next = (start + (acc / fullRangePx) * full).coerceIn(lo, hi)
                            dragValue = next
                            latestChange(next)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            dragValue = defaultValue
                            latestChange(defaultValue)
                        },
                    )
                }
                .semantics {
                    contentDescription = label
                    stateDescription = valueFormatter(animated)
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outer = size.minDimension / 2f - 4f
                val inner = outer * 0.72f

                // Arc track + illuminated value arc.
                drawArc(
                    color = StudioPalette.Raised,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - outer, center.y - outer),
                    size = Size(outer * 2f, outer * 2f),
                    style = Stroke(width = 4.5f, cap = StrokeCap.Round),
                )
                if (currentSweep > 1f) {
                    drawArc(
                        color = accentColor.copy(alpha = 0.32f),
                        startAngle = startAngle,
                        sweepAngle = currentSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - outer, center.y - outer),
                        size = Size(outer * 2f, outer * 2f),
                        style = Stroke(width = 8f, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = accentColor,
                        startAngle = startAngle,
                        sweepAngle = currentSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - outer, center.y - outer),
                        size = Size(outer * 2f, outer * 2f),
                        style = Stroke(width = 4.5f, cap = StrokeCap.Round),
                    )
                }

                // Fixed tick marks at min / centre / max.
                for (tick in listOf(135f, 270f, 405f)) {
                    val rad = (tick * PI / 180.0).toFloat()
                    drawLine(
                        color = StudioPalette.TextMuted,
                        start = Offset(center.x + (outer + 2f) * cos(rad), center.y + (outer + 2f) * sin(rad)),
                        end = Offset(center.x + (outer + 6f) * cos(rad), center.y + (outer + 6f) * sin(rad)),
                        strokeWidth = 1.5f,
                    )
                }

                // Milled cap.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(StudioPalette.Border, StudioPalette.Card, StudioPalette.Surface),
                        center = center,
                        radius = inner,
                    ),
                    radius = inner,
                    center = center,
                )
                drawCircle(
                    color = StudioPalette.Raised,
                    radius = inner,
                    center = center,
                    style = Stroke(width = 1.5f),
                )

                // Accent notch pointer.
                val notchStart = Offset(
                    center.x + (inner * 0.34f) * cos(valueAngle),
                    center.y + (inner * 0.34f) * sin(valueAngle),
                )
                val notchEnd = Offset(
                    center.x + (inner * 0.88f) * cos(valueAngle),
                    center.y + (inner * 0.88f) * sin(valueAngle),
                )
                drawLine(
                    color = accentColor.copy(alpha = 0.5f),
                    start = notchStart,
                    end = notchEnd,
                    strokeWidth = 5f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = notchStart,
                    end = notchEnd,
                    strokeWidth = 2.2f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(StudioPalette.Inset)
                .border(0.5.dp, StudioPalette.Raised, RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                text = valueFormatter(animated),
                color = accentColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Console fader. Drag the cap (or anywhere on the track); double-tap resets.
 * The cap position is absolute, so a tap or drag lands on the value under the
 * finger, the same rule Pro Tools uses for its volume faders.
 */
@Composable
fun StudioFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    modifier: Modifier = Modifier,
    defaultValue: Float = range.start + (range.endInclusive - range.start) * 0.5f,
    accentColor: Color = StudioPalette.Accent,
    height: Dp = 150.dp,
    orientation: StudioFaderOrientation = StudioFaderOrientation.VERTICAL,
    valueFormatter: (Float) -> String = { "%.2f".format(it) },
    testTag: String = "studio_fader_${label.lowercase().replace(' ', '_')}",
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val latestRange by rememberUpdatedState(range)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(value) }

    LaunchedEffect(value) {
        if (!dragging) dragValue = value
    }
    val normalized = ((dragValue - range.start) / span).coerceIn(0f, 1f)

    if (orientation == StudioFaderOrientation.HORIZONTAL) {
        val totalRange = span
        Column(
            modifier = modifier.testTag(testTag),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = StudioPalette.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    valueFormatter(dragValue),
                    color = if (dragValue > 0f) StudioPalette.Amber else accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(StudioPalette.Inset)
                    .border(1.dp, StudioPalette.Border, RoundedCornerShape(6.dp))
                    .pointerInput(totalRange) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                val next = range.start + frac * totalRange
                                dragValue = next
                                latestChange(next)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onDrag = { change, _ ->
                                val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                                val next = range.start + frac * totalRange
                                dragValue = next
                                latestChange(next)
                            },
                        )
                    }
                    .pointerInput(defaultValue) {
                        detectTapGestures(
                            onTap = { offset ->
                                val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                val next = range.start + frac * totalRange
                                dragValue = next
                                latestChange(next)
                            },
                            onDoubleTap = {
                                dragValue = defaultValue
                                latestChange(defaultValue)
                            },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val trackY = size.height / 2f
                    val faderX = size.width * normalized
                    drawRoundRect(
                        color = StudioPalette.Inset,
                        topLeft = Offset(8f, trackY - 3f),
                        size = Size(size.width - 16f, 6f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                    )
                    drawRoundRect(
                        color = accentColor.copy(alpha = 0.7f),
                        topLeft = Offset(8f, trackY - 2f),
                        size = Size((faderX - 8f).coerceAtLeast(0f), 4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                    )
                    val zeroFrac = ((0f - range.start) / totalRange).coerceIn(0f, 1f)
                    val zeroX = size.width * zeroFrac
                    drawLine(
                        color = StudioPalette.TextPrimary,
                        start = Offset(zeroX, 2f),
                        end = Offset(zeroX, size.height - 2f),
                        strokeWidth = 1.5f,
                    )
                    val capWidth = 24f
                    val capHeight = size.height - 6f
                    val capLeft = faderX - capWidth / 2f
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(StudioPalette.Border, StudioPalette.Raised, StudioPalette.Surface),
                            startY = 3f,
                            endY = size.height - 3f,
                        ),
                        topLeft = Offset(capLeft, 3f),
                        size = Size(capWidth, capHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                    )
                    drawRoundRect(
                        color = StudioPalette.TextMuted,
                        topLeft = Offset(capLeft, 3f),
                        size = Size(capWidth, capHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                        style = Stroke(width = 1f),
                    )
                    drawLine(
                        color = accentColor,
                        start = Offset(faderX, 5f),
                        end = Offset(faderX, size.height - 5f),
                        strokeWidth = 2.5f,
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.width(58.dp).testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(height)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            // Absolute positioning from the touch point is handled
                            // by the drag delta below; start from the live value.
                            dragValue = latestValue
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val lo = latestRange.start
                            val hi = latestRange.endInclusive
                            val next = (dragValue - (dy / h) * (hi - lo)).coerceIn(lo, hi)
                            dragValue = next
                            latestChange(next)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            dragValue = defaultValue
                            latestChange(defaultValue)
                        },
                    )
                }
                .semantics {
                    contentDescription = label
                    stateDescription = valueFormatter(dragValue)
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackW = size.width * 0.18f
                val trackX = (size.width - trackW) / 2f
                val pad = 9f
                val usable = size.height - pad * 2f
                val y = pad + usable * (1f - normalized)

                drawRoundRect(
                    color = StudioPalette.Inset,
                    topLeft = Offset(trackX, pad),
                    size = Size(trackW, usable),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackW / 2f),
                )
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.7f),
                    topLeft = Offset(trackX, y),
                    size = Size(trackW, (pad + usable) - y),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackW / 2f),
                )
                for (i in 0..6) {
                    val ty = pad + usable * i / 6f
                    drawLine(
                        color = StudioPalette.Border,
                        start = Offset(trackX - 7f, ty),
                        end = Offset(trackX - 2f, ty),
                        strokeWidth = 1f,
                    )
                }
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color(0xFF4A4A5A), Color(0xFF20202A))),
                    topLeft = Offset(0f, (y - 9f).coerceIn(0f, size.height - 18f)),
                    size = Size(size.width, 18f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
                )
                drawLine(
                    color = accentColor,
                    start = Offset(2f, y.coerceIn(0f, size.height)),
                    end = Offset(size.width - 2f, y.coerceIn(0f, size.height)),
                    strokeWidth = 2f,
                )
            }
        }
        Text(label.uppercase(), color = StudioPalette.TextMuted, fontSize = 8.sp, maxLines = 1)
        Text(valueFormatter(dragValue), color = accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Peak-hold level meter. Linear level in, dB mapping out: the fixed -20 dB
 * reference and green/amber/red zones are the Pro Tools conventions, so a mix
 * reads the same here as in the studio.
 */
@Composable
fun StudioLevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
    accent: Color = StudioPalette.Lime,
    vertical: Boolean = false,
    height: Dp = 10.dp,
    showPeakHold: Boolean = true,
    testTag: String = "studio_level_meter",
) {
    val target = level.coerceIn(0f, 1.2f)
    val latestTarget by rememberUpdatedState(target)
    var peak by remember { mutableFloatStateOf(target) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60)
            val live = latestTarget
            peak = when {
                live > peak -> live
                live == 0f -> 0f
                else -> (peak - 0.006f).coerceAtLeast(live)
            }
        }
    }

    val db = if (target <= 1e-6f) -60f else (20f * log10(target)).coerceIn(-60f, 0f)
    val meterColor = when {
        db >= -3f -> StudioPalette.Danger
        db >= -12f -> StudioPalette.Amber
        else -> accent
    }
    val peakNorm = if (peak <= 1e-6f) 0f else ((20f * log10(peak)).coerceIn(-60f, 0f) + 60f) / 60f

    Canvas(
        modifier
            .then(if (vertical) Modifier.width(height).fillMaxHeight() else Modifier.height(height).fillMaxWidth())
            .clip(RoundedCornerShape(3.dp))
            .background(StudioPalette.Inset)
            .border(0.5.dp, StudioPalette.Border, RoundedCornerShape(3.dp))
            .testTag(testTag)
            .semantics {
                contentDescription = "Level meter"
                stateDescription = "%.1f dB".format(db)
            },
    ) {
        val fraction = ((db + 60f) / 60f).coerceIn(0f, 1f)
        if (vertical) {
            val filled = size.height * fraction
            drawRect(meterColor.copy(alpha = 0.9f), Offset(0f, size.height - filled), Size(size.width, filled))
            // -20 dB reference
            val refY = size.height * (1f - ((-20f + 60f) / 60f))
            drawLine(StudioPalette.TextMuted.copy(alpha = 0.45f), Offset(0f, refY), Offset(size.width, refY), 1f)
            if (showPeakHold) {
                val peakY = size.height * (1f - peakNorm)
                drawLine(StudioPalette.TextPrimary, Offset(0f, peakY), Offset(size.width, peakY), 1.5f)
            }
        } else {
            val filled = size.width * fraction
            drawRect(meterColor.copy(alpha = 0.9f), Offset(0f, 0f), Size(filled, size.height))
            val refX = size.width * ((-20f + 60f) / 60f)
            drawLine(StudioPalette.TextMuted.copy(alpha = 0.45f), Offset(refX, 0f), Offset(refX, size.height), 1f)
            if (showPeakHold) {
                val peakX = size.width * peakNorm
                drawLine(StudioPalette.TextPrimary, Offset(peakX, 0f), Offset(peakX, size.height), 1.5f)
            }
        }
    }
}

enum class StudioFaderOrientation { VERTICAL, HORIZONTAL }

enum class StudioButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * One button for every app: 48 dp minimum target, monospace uppercase label,
 * accent border, pressed state. `selected` turns it into a toggle.
 */
@Composable
fun StudioButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = StudioPalette.Accent,
    variant: StudioButtonVariant = StudioButtonVariant.SECONDARY,
    selected: Boolean = false,
    enabled: Boolean = true,
    testTag: String? = null,
    contentDescription: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val background = when {
        !enabled -> StudioPalette.Card.copy(alpha = 0.5f)
        selected -> accent.copy(alpha = 0.22f)
        variant == StudioButtonVariant.PRIMARY -> accent.copy(alpha = 0.9f)
        variant == StudioButtonVariant.DANGER -> StudioPalette.Danger.copy(alpha = 0.18f)
        variant == StudioButtonVariant.GHOST -> Color.Transparent
        else -> StudioPalette.Card
    }
    val foreground = when {
        !enabled -> StudioPalette.TextMuted
        variant == StudioButtonVariant.PRIMARY && !selected -> StudioPalette.OnAccent
        selected -> accent
        variant == StudioButtonVariant.DANGER -> StudioPalette.Danger
        else -> StudioPalette.TextPrimary
    }
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                1.dp,
                when {
                    !enabled -> StudioPalette.Border
                    selected || variant == StudioButtonVariant.PRIMARY -> accent
                    variant == StudioButtonVariant.DANGER -> StudioPalette.Danger
                    else -> StudioPalette.Border
                },
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                stateDescription = if (selected) "Selected" else "Not selected"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text.uppercase(),
            color = foreground,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Check, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
        }
    }
}

/** Shared section panel: accent rule, uppercase title, optional trailing control. */
@Composable
fun StudioSection(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = StudioPalette.Accent,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StudioPalette.Card)
            .border(1.dp, StudioPalette.Border, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(4.dp, 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
                Text(
                    title.uppercase(),
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            trailing?.invoke()
        }
        content()
    }
}

/** Compact selection chip used for modes, grids and articulations. */
@Composable
fun StudioChoiceChip(
    label: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.9f) else StudioPalette.Card)
            .border(1.dp, if (selected) accent else StudioPalette.Border, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics {
                contentDescription = label
                stateDescription = if (selected) "Selected" else "Not selected"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            color = if (selected) StudioPalette.OnAccent else StudioPalette.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Shared waveform peak painter, used by samplers that cannot use Canvas in TS. */
@Composable
fun StudioWaveformPeaks(
    peaks: FloatArray,
    accent: Color,
    modifier: Modifier = Modifier,
    trimStart: Float = 0f,
    trimEnd: Float = 1f,
    slices: List<Float> = emptyList(),
    testTag: String = "studio_waveform",
) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(StudioPalette.Inset)
            .testTag(testTag),
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        for (g in 0..8) {
            val x = w * g / 8f
            drawLine(StudioPalette.Border.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, h), 1f)
        }
        drawLine(StudioPalette.Border, Offset(0f, mid), Offset(w, mid), 1f)
        val cols = peaks.size / 2
        if (cols > 0 && w > 0f) {
            val step = w / cols
            for (c in 0 until cols) {
                val lo = peaks[c * 2]
                val hi = peaks[c * 2 + 1]
                val x = c * step + step / 2f
                val y1 = mid - hi * mid * 0.94f
                val y2 = mid - lo * mid * 0.94f
                drawLine(
                    accent.copy(alpha = 0.9f),
                    Offset(x, y1),
                    Offset(x, y2.coerceAtLeast(y1 + 1f)),
                    max(1f, step * 0.9f),
                )
            }
        }
        val a = trimStart.coerceIn(0f, 1f)
        val b = trimEnd.coerceIn(a, 1f)
        if (a > 0.001f) drawRect(Color.Black.copy(alpha = 0.5f), Offset(0f, 0f), Size(w * a, h))
        if (b < 0.999f) drawRect(Color.Black.copy(alpha = 0.5f), Offset(w * b, 0f), Size(w * (1f - b), h))
        for (frac in slices.sorted()) {
            val x = frac.coerceIn(0f, 1f) * w
            drawLine(accent, Offset(x, 0f), Offset(x, h), 1.5f)
            drawCircle(accent, 6f, Offset(x.coerceIn(6f, w - 6f), 8f))
        }
    }
}
