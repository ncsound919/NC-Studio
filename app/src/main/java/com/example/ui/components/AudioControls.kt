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
import com.overlay.studio.ui.StudioFader
import com.overlay.studio.ui.StudioFaderOrientation
import com.overlay.studio.ui.StudioKnob
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
import com.example.ui.theme.OverlayWhite
import com.example.ui.theme.Transparent

/**
 * NC Studio's rotary control. The implementation lives in
 * `shared/studio-ui` (StudioKnob), compiled into this app and NC Phone Beat, so
 * both studio apps turn a knob with identical geometry and gestures:
 * 270-degree LED ring, milled cap, 240 dp full-range drag, double-tap reset and
 * an OLED-style formatted readout.
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
    valueFormatter: (Float) -> String = { String.format(java.util.Locale.US, "%.1f", it) },
    testTag: String = "rotary_knob_${label.lowercase().replace(" ", "_")}"
) {
    StudioKnob(
        value = value,
        onValueChange = onValueChange,
        range = range,
        label = label,
        modifier = modifier,
        defaultValue = defaultValue,
        accentColor = accentColor,
        knobSize = knobSize,
        valueFormatter = valueFormatter,
        testTag = testTag,
    )
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
    testTag: String = "fader_" + label.lowercase().replace(" ", "_")
) {
    StudioFader(
        value = value,
        onValueChange = onValueChange,
        range = range,
        label = label,
        modifier = modifier.padding(vertical = 4.dp, horizontal = 2.dp),
        defaultValue = defaultValue,
        accentColor = accentColor,
        orientation = StudioFaderOrientation.HORIZONTAL,
        valueFormatter = { db ->
            if (db <= -59f) "-∞ dB" else String.format(java.util.Locale.US, "%+.1f dB", db)
        },
        testTag = testTag,
    )
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
                    if (checked) OverlayWhite else Transparent,
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
