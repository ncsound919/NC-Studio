package com.example.ui


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.audio.dsp.FourBandEq
import com.example.audio.model.ChannelLimiterConfig
import com.example.audio.model.ChorusConfig
import com.example.audio.model.CompressorConfig
import com.example.audio.model.CompressorType
import com.example.audio.model.DuckingDelayConfig
import com.example.audio.model.EqBand
import com.example.audio.model.EqConfig
import com.example.audio.model.PresetLibrary
import com.example.audio.model.ResonanceSmootherConfig
import com.example.audio.model.ReverbConfig
import com.example.audio.model.SaturationConfig
import com.example.audio.model.SaturationType
import com.example.audio.model.TrackData
import com.example.audio.model.TrackPreset
import com.example.audio.model.VocalRiderConfig
import com.example.ui.components.AudioFaderSlider
import com.example.ui.components.AudioRotaryKnob
import com.example.ui.components.AudioSwitchToggle
import com.example.ui.theme.Radius
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.PanelDeep
import com.example.ui.theme.SurfaceSunken
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioPurple
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun EqModuleEditor(config: EqConfig, onUpdate: (EqConfig) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.ui_4_band_parametric_equalizer), color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_robert_bristow_johnson_biquads_with_live_visual_), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_eq_power),
                    activeColor = NeonCyan
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val eqCalc = remember(config) { FourBandEq().apply { updateConfig(config) } }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(SurfaceSunken)
                    .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val minF = 20.0
                    val maxF = 20000.0
                    val logRange = log10(maxF / minF)

                    for (gf in doubleArrayOf(100.0, 1000.0, 10000.0)) {
                        val x = ((log10(gf / minF) / logRange) * w).toFloat()
                        drawLine(StudioCardBorder, Offset(x, 0f), Offset(x, h), 1f)
                    }

                    val yZero = h / 2f
                    drawLine(StudioCardBorder, Offset(0f, yZero), Offset(w, yZero), 1f)

                    val curvePath = Path()
                    var first = true
                    val points = 80
                    for (p in 0..points) {
                        val frac = p.toFloat() / points
                        val freq = minF * 10.0.pow(frac * logRange)
                        val respDb = eqCalc.calculateTotalResponseDb(freq.toFloat())
                        val x = frac * w
                        val y = (yZero - (respDb / 18f) * (h / 2f)).coerceIn(2f, h - 2f)

                        if (first) {
                            curvePath.moveTo(x, y)
                            first = false
                        } else {
                            curvePath.lineTo(x, y)
                        }
                    }

                    drawPath(
                        path = curvePath,
                        color = NeonCyan,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Dedicated high-pass and low-pass filters run before the musical bands.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(StudioCard)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EqFilterCluster(
                    title = "High-Pass",
                    band = config.highPass,
                    freqRange = 20f..800f,
                    accentColor = NeonCyan
                ) { onUpdate(config.copy(highPass = it)) }
                EqFilterCluster(
                    title = "Low-Pass",
                    band = config.lowPass,
                    freqRange = 2000f..20000f,
                    accentColor = StudioPurple
                ) { onUpdate(config.copy(lowPass = it)) }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4 Band Knobs Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                EqBandRotaryCluster("BAND 1", config.band1, 20f..500f, NeonCyan) {
                    onUpdate(config.copy(band1 = it))
                }
                EqBandRotaryCluster("BAND 2 MID", config.band2, 100f..3000f, ElectricLime) {
                    onUpdate(config.copy(band2 = it))
                }
                EqBandRotaryCluster("BAND 3 HI-MID", config.band3, 800f..10000f, StudioAmber) {
                    onUpdate(config.copy(band3 = it))
                }
                EqBandRotaryCluster("BAND 4 SHELF", config.band4, 3000f..18000f, StudioPurple) {
                    onUpdate(config.copy(band4 = it))
                }
            }
        }
    }
}

@Composable
fun EqFilterCluster(
    title: String,
    band: EqBand,
    freqRange: ClosedFloatingPointRange<Float>,
    accentColor: Color,
    onUpdate: (EqBand) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.md))
            .padding(4.dp)
    ) {
        AudioSwitchToggle(
            checked = band.enabled,
            onCheckedChange = { onUpdate(band.copy(enabled = it)) },
            label = title,
            activeColor = accentColor
        )
        Spacer(modifier = Modifier.height(4.dp))
        AudioRotaryKnob(
            value = band.frequencyHz,
            onValueChange = { onUpdate(band.copy(frequencyHz = it)) },
            range = freqRange,
            label = stringResource(R.string.label_freq),
            accentColor = accentColor,
            knobSize = 52.dp,
            valueFormatter = { if (it >= 1000f) String.format(java.util.Locale.US, "%.1fkHz", it / 1000f) else "${it.toInt()}Hz" }
        )
    }
}

@Composable
fun EqBandRotaryCluster(
    title: String,
    band: EqBand,
    freqRange: ClosedFloatingPointRange<Float>,
    accentColor: Color,
    onUpdate: (EqBand) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(StudioCard)
            .border(0.5.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
            .padding(6.dp)
    ) {
        Text(title, color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(4.dp))
        AudioRotaryKnob(
            value = band.frequencyHz,
            onValueChange = { onUpdate(band.copy(frequencyHz = it)) },
            range = freqRange,
            label = stringResource(R.string.label_freq),
            accentColor = accentColor,
            knobSize = 52.dp,
            valueFormatter = { if (it >= 1000f) String.format(java.util.Locale.US, "%.1fkHz", it / 1000f) else "${it.toInt()}Hz" }
        )
        Spacer(modifier = Modifier.height(4.dp))
        AudioRotaryKnob(
            value = band.gainDb,
            onValueChange = { onUpdate(band.copy(gainDb = it)) },
            range = -18f..18f,
            label = stringResource(R.string.label_gain),
            accentColor = accentColor,
            knobSize = 52.dp,
            defaultValue = 0f,
            valueFormatter = { String.format(java.util.Locale.US, "%+.1fdB", it) }
        )
    }
}

@Composable
fun SaturationModuleEditor(config: SaturationConfig, onUpdate: (SaturationConfig) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.ui_tape_tube_saturation), color = StudioAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_harmonic_warmth_soft_clip_tape_compression), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_tape_on),
                    activeColor = StudioAmber
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            SaturationTypeSelector(config) { onUpdate(it) }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AudioRotaryKnob(
                    value = config.driveDb,
                    onValueChange = { onUpdate(config.copy(driveDb = it)) },
                    range = 0f..24f,
                    label = stringResource(R.string.label_drive),
                    accentColor = StudioAmber,
                    defaultValue = 0f,
                    valueFormatter = { String.format(java.util.Locale.US, "+%.1fdB", it) }
                )
                AudioRotaryKnob(
                    value = config.warmth,
                    onValueChange = { onUpdate(config.copy(warmth = it)) },
                    range = 0f..1f,
                    label = stringResource(R.string.label_warmth),
                    accentColor = StudioAmber,
                    defaultValue = 0.5f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
                AudioRotaryKnob(
                    value = config.mix,
                    onValueChange = { onUpdate(config.copy(mix = it)) },
                    range = 0f..1f,
                    label = stringResource(R.string.label_dry_wet),
                    accentColor = StudioAmber,
                    defaultValue = 1.0f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
            }
        }
    }
}

@Composable
fun SaturationTypeSelector(config: SaturationConfig, onUpdate: (SaturationConfig) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.inspector_saturation_model),
            color = StudioAmber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SaturationType.values().forEach { type ->
                val selected = config.type == type
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(if (selected) StudioAmber.copy(alpha = 0.22f) else StudioCard)
                        .border(1.dp, if (selected) StudioAmber else StudioCardBorder, RoundedCornerShape(Radius.sm))
                        .clickable { onUpdate(config.withType(type)) }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("saturation_type_${type.name.lowercase()}")
                ) {
                    Text(
                        text = type.displayName.uppercase(),
                        color = if (selected) StudioAmber else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = config.type.character, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
fun CompressorTypeSelector(config: CompressorConfig, onUpdate: (CompressorConfig) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.inspector_compressor_model),
            color = ElectricLime,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CompressorType.values().forEach { type ->
                val selected = config.type == type
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(if (selected) ElectricLime.copy(alpha = 0.20f) else StudioCard)
                        .border(1.dp, if (selected) ElectricLime else StudioCardBorder, RoundedCornerShape(Radius.sm))
                        .clickable { onUpdate(config.withType(type)) }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("compressor_type_${type.name.lowercase()}")
                ) {
                    Text(
                        text = type.displayName.uppercase(),
                        color = if (selected) ElectricLime else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = config.type.character, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
fun CompressorModuleEditor(
    config: CompressorConfig,
    gainReductionDb: Float,
    onUpdate: (CompressorConfig) -> Unit
) {
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.ui_dynamic_compressor), color = ElectricLime, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_feedforward_soft_knee_dynamics_with_live_gr_mete), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_comp_on),
                    activeColor = ElectricLime
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            CompressorTypeSelector(config) { onUpdate(it) }

            Spacer(modifier = Modifier.height(8.dp))

            // Real-time Gain Reduction Meter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(SurfaceSunken)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.ui_gain_reduction), color = StudioAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(StudioCard)
                ) {
                    val grFrac = (gainReductionDb / 18f).coerceIn(0f, 1f)
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(grFrac).background(StudioAmber))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    String.format(java.util.Locale.US, stringResource(R.string.ui_1fdb), gainReductionDb),
                    color = StudioAmber,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary controls stay visible; time constants and makeup gain
            // are disclosure-only so the module reads at a glance.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AudioRotaryKnob(
                    value = config.thresholdDb,
                    onValueChange = { onUpdate(config.copy(thresholdDb = it)) },
                    range = -48f..0f,
                    label = stringResource(R.string.label_thresh),
                    accentColor = ElectricLime,
                    defaultValue = -16f,
                    valueFormatter = { String.format(java.util.Locale.US, "%.0fdB", it) }
                )
                AudioRotaryKnob(
                    value = config.ratio,
                    onValueChange = { onUpdate(config.copy(ratio = it)) },
                    range = 1f..16f,
                    label = stringResource(R.string.label_ratio),
                    accentColor = ElectricLime,
                    defaultValue = 3.5f,
                    valueFormatter = { String.format(java.util.Locale.US, "%.1f:1", it) }
                )
            }

            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("compressor_advanced_toggle")
            ) {
                Text(
                    text = if (showAdvanced) "Hide advanced" else "Advanced: attack, release, makeup",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (showAdvanced) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AudioRotaryKnob(
                        value = config.attackMs,
                        onValueChange = { onUpdate(config.copy(attackMs = it)) },
                        range = 0.1f..100f,
                        label = stringResource(R.string.label_attack),
                        accentColor = ElectricLime,
                        defaultValue = 15f,
                        valueFormatter = { if (it < 1f) String.format(java.util.Locale.US, "%.2fms", it) else String.format(java.util.Locale.US, "%.0fms", it) }
                    )
                    AudioRotaryKnob(
                        value = config.releaseMs,
                        onValueChange = { onUpdate(config.copy(releaseMs = it)) },
                        range = 20f..600f,
                        label = stringResource(R.string.label_release),
                        accentColor = ElectricLime,
                        defaultValue = 120f,
                        valueFormatter = { String.format(java.util.Locale.US, "%.0fms", it) }
                    )
                    AudioRotaryKnob(
                        value = config.makeupGainDb,
                        onValueChange = { onUpdate(config.copy(makeupGainDb = it)) },
                        range = 0f..18f,
                        label = stringResource(R.string.label_makeup),
                        accentColor = ElectricLime,
                        defaultValue = 2f,
                        valueFormatter = { String.format(java.util.Locale.US, "+%.1fdB", it) }
                    )
                }
            }
        }
    }
}

@Composable
fun ChorusModuleEditor(config: ChorusConfig, onUpdate: (ChorusConfig) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.ui_stereo_chorus_doubler), color = StudioPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_lfo_delay_modulation_for_vocal_synth_doubling), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_chorus_on),
                    activeColor = StudioPurple
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AudioRotaryKnob(
                    value = config.rateHz,
                    onValueChange = { onUpdate(config.copy(rateHz = it)) },
                    range = 0.1f..8.0f,
                    label = stringResource(R.string.label_rate),
                    accentColor = StudioPurple,
                    defaultValue = 1.2f,
                    valueFormatter = { String.format(java.util.Locale.US, "%.1fHz", it) }
                )
                AudioRotaryKnob(
                    value = config.depthMs,
                    onValueChange = { onUpdate(config.copy(depthMs = it)) },
                    range = 0.5f..12.0f,
                    label = stringResource(R.string.label_depth),
                    accentColor = StudioPurple,
                    defaultValue = 3.5f,
                    valueFormatter = { String.format(java.util.Locale.US, "%.1fms", it) }
                )
                AudioRotaryKnob(
                    value = config.feedback,
                    onValueChange = { onUpdate(config.copy(feedback = it)) },
                    range = 0f..0.7f,
                    label = stringResource(R.string.label_reso),
                    accentColor = StudioPurple,
                    defaultValue = 0.2f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
                AudioRotaryKnob(
                    value = config.stereoSpread,
                    onValueChange = { onUpdate(config.copy(stereoSpread = it)) },
                    range = 0f..1.0f,
                    label = stringResource(R.string.label_spread),
                    accentColor = StudioPurple,
                    defaultValue = 0.85f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
                AudioRotaryKnob(
                    value = config.mix,
                    onValueChange = { onUpdate(config.copy(mix = it)) },
                    range = 0f..1.0f,
                    label = stringResource(R.string.label_mix),
                    accentColor = StudioPurple,
                    defaultValue = 0.40f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
            }
        }
    }
}

@Composable
fun DuckingDelayModuleEditor(config: DuckingDelayConfig, onUpdate: (DuckingDelayConfig) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.ui_stereo_delay_with_ducking), color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_sidechain_ducks_repetitions_during_phrase_vocals), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_delay_on),
                    activeColor = NeonCyan
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AudioRotaryKnob(
                    value = config.timeMs,
                    onValueChange = { onUpdate(config.copy(timeMs = it)) },
                    range = 50f..1000f,
                    label = stringResource(R.string.label_time),
                    accentColor = NeonCyan,
                    defaultValue = 350f,
                    valueFormatter = { String.format(java.util.Locale.US, "%.0fms", it) }
                )
                AudioRotaryKnob(
                    value = config.feedback,
                    onValueChange = { onUpdate(config.copy(feedback = it)) },
                    range = 0f..0.85f,
                    label = stringResource(R.string.label_feedback),
                    accentColor = NeonCyan,
                    defaultValue = 0.35f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
                AudioRotaryKnob(
                    value = config.mix,
                    onValueChange = { onUpdate(config.copy(mix = it)) },
                    range = 0f..0.8f,
                    label = stringResource(R.string.label_mix),
                    accentColor = NeonCyan,
                    defaultValue = 0.25f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Ducking Sub-Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .background(PanelDeep)
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.ui_sidechain_ducking), color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        AudioSwitchToggle(
                            checked = config.duckingEnabled,
                            onCheckedChange = { onUpdate(config.copy(duckingEnabled = it)) },
                            label = stringResource(R.string.label_duck),
                            activeColor = NeonCyan
                        )
                    }

                    if (config.duckingEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            AudioRotaryKnob(
                                value = config.duckingThresholdDb,
                                onValueChange = { onUpdate(config.copy(duckingThresholdDb = it)) },
                                range = -42f..-6f,
                                label = stringResource(R.string.label_thresh),
                                accentColor = NeonCyan,
                                defaultValue = -24f,
                                valueFormatter = { String.format(java.util.Locale.US, "%.0fdB", it) }
                            )
                            AudioRotaryKnob(
                                value = config.duckingDepthDb,
                                onValueChange = { onUpdate(config.copy(duckingDepthDb = it)) },
                                range = -24f..-3f,
                                label = stringResource(R.string.label_depth),
                                accentColor = NeonCyan,
                                defaultValue = -12f,
                                valueFormatter = { String.format(java.util.Locale.US, "%.0fdB", it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelLimiterModuleEditor(config: ChannelLimiterConfig, onUpdate: (ChannelLimiterConfig) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.ui_channel_brickwall_limiter), color = ClipRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_zero_overs_peak_protection_per_channel), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_limiter_on),
                    activeColor = ClipRed
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AudioRotaryKnob(
                    value = config.ceilingDb,
                    onValueChange = { onUpdate(config.copy(ceilingDb = it)) },
                    range = -12f..0f,
                    label = stringResource(R.string.label_ceiling),
                    accentColor = ClipRed,
                    defaultValue = -0.5f,
                    valueFormatter = { String.format(java.util.Locale.US, "%.1fdB", it) }
                )
                AudioRotaryKnob(
                    value = config.releaseMs,
                    onValueChange = { onUpdate(config.copy(releaseMs = it)) },
                    range = 5f..150f,
                    label = stringResource(R.string.label_release),
                    accentColor = ClipRed,
                    defaultValue = 30f,
                    valueFormatter = { String.format(java.util.Locale.US, "%.0fms", it) }
                )
            }
        }
    }
}

@Composable
fun ReverbSendModuleEditor(
    sendLevel: Float,
    masterReverb: ReverbConfig,
    onUpdateSend: (Float) -> Unit,
    onUpdateMasterReverb: (ReverbConfig) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Channel Send Proportion Fader
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.ui_channel_reverb_send_level_wet_dry), color = ElectricBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.ui_controls_the_dry_wet_send_proportion_feeding_the), color = TextMuted, fontSize = 10.sp)

                Spacer(modifier = Modifier.height(12.dp))

                AudioFaderSlider(
                    value = sendLevel * 100f,
                    onValueChange = { onUpdateSend((it / 100f).coerceIn(0f, 1f)) },
                    range = 0f..100f,
                    label = stringResource(R.string.label_channel_send),
                    accentColor = ElectricBlue,
                    defaultValue = 0f
                )
            }
        }

        // Master Reverb Parameters DSP Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.ui_core_schroeder_reverb_dsp_engine), color = ElectricBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.ui_decay_time_rt60_pre_delay_room_size_abbey_road_f), color = TextMuted, fontSize = 10.sp)
                    }
                    AudioSwitchToggle(
                        checked = masterReverb.enabled,
                        onCheckedChange = { onUpdateMasterReverb(masterReverb.copy(enabled = it)) },
                        label = stringResource(R.string.label_reverb_bus_on),
                        activeColor = ElectricBlue
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Core Parameters Row (Decay Time, Pre-Delay, Room Size, Return Level)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AudioRotaryKnob(
                        value = masterReverb.decayTimeSec,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(decayTimeSec = it)) },
                        range = 0.5f..10.0f,
                        label = stringResource(R.string.label_decay_time),
                        accentColor = ElectricBlue,
                        defaultValue = 2.0f,
                        valueFormatter = { String.format(java.util.Locale.US, "%.1fs", it) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.preDelayMs,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(preDelayMs = it)) },
                        range = 0f..100f,
                        label = stringResource(R.string.label_pre_delay),
                        accentColor = ElectricBlue,
                        defaultValue = 25f,
                        valueFormatter = { String.format(java.util.Locale.US, "%.0fms", it) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.roomSize,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(roomSize = it)) },
                        range = 0.10f..0.98f,
                        label = stringResource(R.string.label_room_size),
                        accentColor = ElectricBlue,
                        defaultValue = 0.60f,
                        valueFormatter = { String.format(java.util.Locale.US, "%.2f", it) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.returnLevel * 100f,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(returnLevel = it / 100f)) },
                        range = 0f..150f,
                        label = stringResource(R.string.label_return_level),
                        accentColor = ElectricBlue,
                        defaultValue = 70f,
                        valueFormatter = { String.format(java.util.Locale.US, "%.0f%%", it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Abbey Road Low-Cut & High-Cut Filters + Stereo Width
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AudioRotaryKnob(
                        value = masterReverb.lowCutHz,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(lowCutHz = it)) },
                        range = 20f..1000f,
                        label = stringResource(R.string.label_low_cut),
                        accentColor = StudioAmber,
                        defaultValue = 150f,
                        valueFormatter = { String.format(java.util.Locale.US, "%.0fHz", it) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.highCutHz,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(highCutHz = it)) },
                        range = 1000f..20000f,
                        label = stringResource(R.string.label_high_cut),
                        accentColor = StudioAmber,
                        defaultValue = 9000f,
                        valueFormatter = { String.format(java.util.Locale.US, "%.1fkHz", it / 1000f) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.stereoWidth,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(stereoWidth = it)) },
                        range = 0.0f..1.0f,
                        label = stringResource(R.string.label_stereo_width),
                        accentColor = NeonCyan,
                        defaultValue = 0.85f,
                        valueFormatter = { String.format(java.util.Locale.US, "%.0f%%", it * 100f) }
                    )
                }
            }
        }
    }
}

@Composable
fun AutoPitchModuleEditor(config: com.example.audio.model.AutoPitchConfig, onUpdate: (com.example.audio.model.AutoPitchConfig) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.ui_auto_pitch_correction), color = StudioPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_monophonic_pitch_correction_to_nearest_semitone), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_pitch_on),
                    activeColor = StudioPurple
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            AudioRotaryKnob(
                value = config.correctionStrength,
                onValueChange = { onUpdate(config.copy(correctionStrength = it)) },
                range = 0f..1f,
                label = stringResource(R.string.label_strength),
                accentColor = StudioPurple,
                defaultValue = 1.0f,
                valueFormatter = { "${(it * 100).toInt()}%" }
            )
        }
    }
}

@Composable
fun NoiseReductionModuleEditor(config: com.example.audio.model.NoiseReductionConfig, onUpdate: (com.example.audio.model.NoiseReductionConfig) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.ui_noise_reduction), color = StudioAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_spectral_noise_gate_and_reduction), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_nr_on),
                    activeColor = StudioAmber
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AudioRotaryKnob(
                    value = config.thresholdDb,
                    onValueChange = { onUpdate(config.copy(thresholdDb = it)) },
                    range = -60f..-10f,
                    label = stringResource(R.string.label_thresh),
                    accentColor = StudioAmber,
                    defaultValue = -40f,
                    valueFormatter = { String.format(java.util.Locale.US, "%.0fdB", it) }
                )
                AudioRotaryKnob(
                    value = config.reductionDb,
                    onValueChange = { onUpdate(config.copy(reductionDb = it)) },
                    range = -24f..0f,
                    label = stringResource(R.string.label_reduction),
                    accentColor = StudioAmber,
                    defaultValue = -12f,
                    valueFormatter = { String.format(java.util.Locale.US, "%.0fdB", it) }
                )
            }
        }
    }
}

@Composable
fun VocalRiderModuleEditor(config: VocalRiderConfig, onUpdate: (VocalRiderConfig) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.inspector_vocal_rider), color = ElectricLime, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_vocal_rider_description), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_ride_on),
                    activeColor = ElectricLime
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AudioRotaryKnob(
                    value = config.targetLufs,
                    onValueChange = { onUpdate(config.copy(targetLufs = it)) },
                    range = -30f..-10f,
                    label = stringResource(R.string.label_target),
                    accentColor = ElectricLime,
                    defaultValue = -18f,
                    knobSize = 58.dp,
                    valueFormatter = { String.format(java.util.Locale.US, "%.0f LUFS", it) }
                )
                AudioRotaryKnob(
                    value = config.maxGainDb,
                    onValueChange = { onUpdate(config.copy(maxGainDb = it)) },
                    range = 0f..12f,
                    label = stringResource(R.string.label_max_up),
                    accentColor = ElectricLime,
                    defaultValue = 6f,
                    knobSize = 58.dp,
                    valueFormatter = { String.format(java.util.Locale.US, "+%.1fdB", it) }
                )
                AudioRotaryKnob(
                    value = config.minGainDb,
                    onValueChange = { onUpdate(config.copy(minGainDb = it)) },
                    range = -18f..0f,
                    label = stringResource(R.string.label_max_down),
                    accentColor = ElectricLime,
                    defaultValue = -6f,
                    knobSize = 58.dp,
                    valueFormatter = { String.format(java.util.Locale.US, "%.1fdB", it) }
                )
                AudioRotaryKnob(
                    value = config.sensitivity,
                    onValueChange = { onUpdate(config.copy(sensitivity = it)) },
                    range = 0f..1f,
                    label = stringResource(R.string.label_speed),
                    accentColor = ElectricLime,
                    defaultValue = 0.5f,
                    knobSize = 58.dp,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
            }
        }
    }
}

@Composable
fun ResonanceSmootherModuleEditor(config: ResonanceSmootherConfig, onUpdate: (ResonanceSmootherConfig) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.inspector_resonance_suppression), color = StudioAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ui_resonance_suppression_description), color = TextMuted, fontSize = 10.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = stringResource(R.string.label_dyn_notch),
                    activeColor = StudioAmber
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AudioRotaryKnob(
                    value = config.frequencyHz,
                    onValueChange = { onUpdate(config.copy(frequencyHz = it)) },
                    range = 1000f..10000f,
                    label = stringResource(R.string.label_frequency),
                    accentColor = StudioAmber,
                    defaultValue = 3200f,
                    knobSize = 58.dp,
                    valueFormatter = { String.format(java.util.Locale.US, "%.1fkHz", it / 1000f) }
                )
                AudioRotaryKnob(
                    value = config.thresholdDb,
                    onValueChange = { onUpdate(config.copy(thresholdDb = it)) },
                    range = -40f..-6f,
                    label = stringResource(R.string.label_threshold),
                    accentColor = StudioAmber,
                    defaultValue = -18f,
                    knobSize = 58.dp,
                    valueFormatter = { String.format(java.util.Locale.US, "%.0fdB", it) }
                )
                AudioRotaryKnob(
                    value = config.depthDb,
                    onValueChange = { onUpdate(config.copy(depthDb = it)) },
                    range = -18f..0f,
                    label = stringResource(R.string.label_depth),
                    accentColor = StudioAmber,
                    defaultValue = -6f,
                    knobSize = 58.dp,
                    valueFormatter = { String.format(java.util.Locale.US, "%.1fdB", it) }
                )
                AudioRotaryKnob(
                    value = config.qFactor,
                    onValueChange = { onUpdate(config.copy(qFactor = it)) },
                    range = 1f..12f,
                    label = stringResource(R.string.label_q),
                    accentColor = StudioAmber,
                    defaultValue = 4f,
                    knobSize = 58.dp,
                    valueFormatter = { String.format(java.util.Locale.US, "%.1f", it) }
                )
            }
        }
    }
}

@Composable
fun MiniDspBadge(text: String, active: Boolean, activeColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (active) activeColor.copy(alpha = 0.2f) else PanelDeep)
            .border(1.dp, if (active) activeColor else StudioCardBorder, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(text, color = if (active) activeColor else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
