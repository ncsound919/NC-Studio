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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.audio.dsp.FourBandEq
import com.example.audio.model.ChannelLimiterConfig
import com.example.audio.model.ChorusConfig
import com.example.audio.model.CompressorConfig
import com.example.audio.model.DuckingDelayConfig
import com.example.audio.model.EqBand
import com.example.audio.model.EqConfig
import com.example.audio.model.PresetLibrary
import com.example.audio.model.ReverbConfig
import com.example.audio.model.SaturationConfig
import com.example.audio.model.TrackData
import com.example.audio.model.TrackPreset
import com.example.ui.components.AudioFaderSlider
import com.example.ui.components.AudioRotaryKnob
import com.example.ui.components.AudioSwitchToggle
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
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

enum class StripModule(val title: String, val icon: ImageVector, val color: Color) {
    EQ("4-Band EQ", Icons.Default.GraphicEq, NeonCyan),
    SATURATION("Saturation", Icons.Default.Whatshot, StudioAmber),
    COMPRESSOR("Compressor", Icons.Default.Tune, ElectricLime),
    CHORUS("Chorus / Doubler", Icons.Default.Waves, StudioPurple),
    DELAY("Ducking Delay", Icons.Default.HourglassBottom, Color(0xFF00E5FF)),
    LIMITER("Limiter", Icons.Default.Security, ClipRed),
    REVERB("Reverb Send", Icons.Default.Waves, Color(0xFF00B0FF)),
    AUTOPITCH("AutoPitch", Icons.Default.AutoAwesome, StudioPurple),
    NOISERED("Noise Red.", Icons.Default.Security, StudioAmber)
}

@Composable
fun TrackInspector(
    track: TrackData,
    gainReductionDb: Float,
    onUpdateEq: (EqConfig) -> Unit,
    onUpdateSaturation: (SaturationConfig) -> Unit,
    onUpdateCompressor: (CompressorConfig) -> Unit,
    onUpdateChorus: (ChorusConfig) -> Unit,
    onUpdateDelay: (DuckingDelayConfig) -> Unit,
    onUpdateLimiter: (ChannelLimiterConfig) -> Unit,
    onUpdateReverbSend: (Float) -> Unit,
    onUpdateAutoPitch: (com.example.audio.model.AutoPitchConfig) -> Unit = {},
    onUpdateNoiseReduction: (com.example.audio.model.NoiseReductionConfig) -> Unit = {},
    masterReverb: ReverbConfig = ReverbConfig(),
    onUpdateMasterReverb: (ReverbConfig) -> Unit = {},
    onApplyPreset: (TrackPreset) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeModule by remember { mutableIntStateOf(0) }
    var showPresetDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
            .padding(10.dp)
            .testTag("track_inspector")
    ) {
        // Dedicated Channel Strip Header Banner
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp)),
            color = StudioSurface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(track.colorHex))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "CH ${track.index + 1} • ${track.name.uppercase()}",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Category: ${track.stemCategory.displayName}",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Pro Presets Quick Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(StudioPurple.copy(alpha = 0.25f))
                            .border(1.dp, StudioPurple, RoundedCornerShape(4.dp))
                            .clickable { showPresetDialog = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("open_presets_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = StudioPurple, modifier = Modifier.size(12.dp))
                            Text("PRO PRESETS", color = StudioPurple, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }

                    // Mini Active DSP Badges
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        MiniDspBadge("EQ", track.eqConfig.enabled, NeonCyan)
                        MiniDspBadge("SAT", track.saturationConfig.enabled, StudioAmber)
                        MiniDspBadge("COMP", track.compressorConfig.enabled, ElectricLime)
                        MiniDspBadge("CHO", track.chorusConfig.enabled, StudioPurple)
                        MiniDspBadge("DLY", track.delayConfig.enabled, Color(0xFF00E5FF))
                        MiniDspBadge("LIM", track.limiterConfig.enabled, ClipRed)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Module Selection Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(StudioSurface)
                .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StripModule.values().forEachIndexed { index, module ->
                val isSelected = activeModule == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) module.color.copy(alpha = 0.2f) else Color.Transparent)
                        .border(
                            1.dp,
                            if (isSelected) module.color else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { activeModule = index }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = module.icon,
                            contentDescription = module.title,
                            tint = if (isSelected) module.color else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = module.title.substringBefore(" "),
                            color = if (isSelected) module.color else TextMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Selected Module Editor Pane
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            when (StripModule.values()[activeModule]) {
                StripModule.EQ -> EqModuleEditor(track.eqConfig, onUpdateEq)
                StripModule.SATURATION -> SaturationModuleEditor(track.saturationConfig, onUpdateSaturation)
                StripModule.COMPRESSOR -> CompressorModuleEditor(track.compressorConfig, gainReductionDb, onUpdateCompressor)
                StripModule.CHORUS -> ChorusModuleEditor(track.chorusConfig, onUpdateChorus)
                StripModule.DELAY -> DuckingDelayModuleEditor(track.delayConfig, onUpdateDelay)
                StripModule.LIMITER -> ChannelLimiterModuleEditor(track.limiterConfig, onUpdateLimiter)
                StripModule.REVERB -> ReverbSendModuleEditor(
                    sendLevel = track.reverbSendLevel,
                    masterReverb = masterReverb,
                    onUpdateSend = onUpdateReverbSend,
                    onUpdateMasterReverb = onUpdateMasterReverb
                )
                StripModule.AUTOPITCH -> AutoPitchModuleEditor(track.autoPitchConfig, onUpdateAutoPitch)
                StripModule.NOISERED -> NoiseReductionModuleEditor(track.noiseReductionConfig, onUpdateNoiseReduction)
            }
        }
    }

    // Pro Mix Preset Dialog
    if (showPresetDialog) {
        TrackPresetDialog(
            trackName = track.name,
            presets = PresetLibrary.TRACK_PRESETS,
            onSelectPreset = { preset ->
                onApplyPreset(preset)
                showPresetDialog = false
            },
            onDismiss = { showPresetDialog = false }
        )
    }
}

@Composable
fun TrackPresetDialog(
    trackName: String,
    presets: List<TrackPreset>,
    onSelectPreset: (TrackPreset) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("PROFESSIONAL MIX PRESETS", color = StudioPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("Technique-based channel strip chains for $trackName", color = TextMuted, fontSize = 10.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    presets.forEach { preset ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(StudioCard)
                                .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp))
                                .clickable { onSelectPreset(preset) }
                                .padding(10.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = preset.name,
                                        color = NeonCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(StudioPurple.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = preset.category.uppercase(),
                                            color = StudioPurple,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = preset.description,
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

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
                    Text("4-BAND PARAMETRIC EQUALIZER", color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Robert Bristow-Johnson Biquads with Live Visual Curve", color = TextMuted, fontSize = 9.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = "EQ Power",
                    activeColor = NeonCyan
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val eqCalc = remember(config) { FourBandEq().apply { updateConfig(config) } }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF080B10))
                    .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val minF = 20.0
                    val maxF = 20000.0
                    val logRange = log10(maxF / minF)

                    for (gf in doubleArrayOf(100.0, 1000.0, 10000.0)) {
                        val x = ((log10(gf / minF) / logRange) * w).toFloat()
                        drawLine(Color(0xFF1E283A), Offset(x, 0f), Offset(x, h), 1f)
                    }

                    val yZero = h / 2f
                    drawLine(Color(0xFF334155), Offset(0f, yZero), Offset(w, yZero), 1f)

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

            Spacer(modifier = Modifier.height(12.dp))

            // 4 Band Knobs Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                EqBandRotaryCluster("BAND 1 HPF", config.band1, 20f..500f, NeonCyan) {
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
            .clip(RoundedCornerShape(6.dp))
            .background(StudioCard)
            .border(0.5.dp, StudioCardBorder, RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Text(title, color = accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(4.dp))
        AudioRotaryKnob(
            value = band.frequencyHz,
            onValueChange = { onUpdate(band.copy(frequencyHz = it)) },
            range = freqRange,
            label = "Freq",
            accentColor = accentColor,
            knobSize = 52.dp,
            valueFormatter = { if (it >= 1000f) "%.1fkHz".format(it / 1000f) else "${it.toInt()}Hz" }
        )
        Spacer(modifier = Modifier.height(4.dp))
        AudioRotaryKnob(
            value = band.gainDb,
            onValueChange = { onUpdate(band.copy(gainDb = it)) },
            range = -18f..18f,
            label = "Gain",
            accentColor = accentColor,
            knobSize = 52.dp,
            defaultValue = 0f,
            valueFormatter = { "%+.1fdB".format(it) }
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
                    Text("TAPE / TUBE SATURATION", color = StudioAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Harmonic warmth & soft-clip tape compression", color = TextMuted, fontSize = 9.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = "Tape On",
                    activeColor = StudioAmber
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AudioRotaryKnob(
                    value = config.driveDb,
                    onValueChange = { onUpdate(config.copy(driveDb = it)) },
                    range = 0f..24f,
                    label = "Drive",
                    accentColor = StudioAmber,
                    defaultValue = 0f,
                    valueFormatter = { "+%.1fdB".format(it) }
                )
                AudioRotaryKnob(
                    value = config.warmth,
                    onValueChange = { onUpdate(config.copy(warmth = it)) },
                    range = 0f..1f,
                    label = "Warmth",
                    accentColor = StudioAmber,
                    defaultValue = 0.5f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
                AudioRotaryKnob(
                    value = config.mix,
                    onValueChange = { onUpdate(config.copy(mix = it)) },
                    range = 0f..1f,
                    label = "Dry/Wet",
                    accentColor = StudioAmber,
                    defaultValue = 1.0f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
            }
        }
    }
}

@Composable
fun CompressorModuleEditor(
    config: CompressorConfig,
    gainReductionDb: Float,
    onUpdate: (CompressorConfig) -> Unit
) {
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
                    Text("DYNAMIC COMPRESSOR", color = ElectricLime, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Feedforward soft-knee dynamics with live GR meter", color = TextMuted, fontSize = 9.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = "Comp On",
                    activeColor = ElectricLime
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Real-time Gain Reduction Meter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF090D14))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("GAIN REDUCTION", color = StudioAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF1E2638))
                ) {
                    val grFrac = (gainReductionDb / 18f).coerceIn(0f, 1f)
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(grFrac).background(StudioAmber))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("-%.1fdB".format(gainReductionDb), color = StudioAmber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5 Rotary Knobs Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AudioRotaryKnob(
                    value = config.thresholdDb,
                    onValueChange = { onUpdate(config.copy(thresholdDb = it)) },
                    range = -48f..0f,
                    label = "Thresh",
                    accentColor = ElectricLime,
                    defaultValue = -16f,
                    valueFormatter = { "%.0fdB".format(it) }
                )
                AudioRotaryKnob(
                    value = config.ratio,
                    onValueChange = { onUpdate(config.copy(ratio = it)) },
                    range = 1f..16f,
                    label = "Ratio",
                    accentColor = ElectricLime,
                    defaultValue = 3.5f,
                    valueFormatter = { "%.1f:1".format(it) }
                )
                AudioRotaryKnob(
                    value = config.attackMs,
                    onValueChange = { onUpdate(config.copy(attackMs = it)) },
                    range = 1f..100f,
                    label = "Attack",
                    accentColor = ElectricLime,
                    defaultValue = 15f,
                    valueFormatter = { "%.0fms".format(it) }
                )
                AudioRotaryKnob(
                    value = config.releaseMs,
                    onValueChange = { onUpdate(config.copy(releaseMs = it)) },
                    range = 20f..600f,
                    label = "Release",
                    accentColor = ElectricLime,
                    defaultValue = 120f,
                    valueFormatter = { "%.0fms".format(it) }
                )
                AudioRotaryKnob(
                    value = config.makeupGainDb,
                    onValueChange = { onUpdate(config.copy(makeupGainDb = it)) },
                    range = 0f..18f,
                    label = "Makeup",
                    accentColor = ElectricLime,
                    defaultValue = 2f,
                    valueFormatter = { "+%.1fdB".format(it) }
                )
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
                    Text("STEREO CHORUS / DOUBLER", color = StudioPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("LFO delay modulation for vocal & synth doubling", color = TextMuted, fontSize = 9.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = "Chorus On",
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
                    label = "Rate",
                    accentColor = StudioPurple,
                    defaultValue = 1.2f,
                    valueFormatter = { "%.1fHz".format(it) }
                )
                AudioRotaryKnob(
                    value = config.depthMs,
                    onValueChange = { onUpdate(config.copy(depthMs = it)) },
                    range = 0.5f..12.0f,
                    label = "Depth",
                    accentColor = StudioPurple,
                    defaultValue = 3.5f,
                    valueFormatter = { "%.1fms".format(it) }
                )
                AudioRotaryKnob(
                    value = config.feedback,
                    onValueChange = { onUpdate(config.copy(feedback = it)) },
                    range = 0f..0.7f,
                    label = "Reso",
                    accentColor = StudioPurple,
                    defaultValue = 0.2f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
                AudioRotaryKnob(
                    value = config.stereoSpread,
                    onValueChange = { onUpdate(config.copy(stereoSpread = it)) },
                    range = 0f..1.0f,
                    label = "Spread",
                    accentColor = StudioPurple,
                    defaultValue = 0.85f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
                AudioRotaryKnob(
                    value = config.mix,
                    onValueChange = { onUpdate(config.copy(mix = it)) },
                    range = 0f..1.0f,
                    label = "Mix",
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
                    Text("STEREO DELAY WITH DUCKING", color = Color(0xFF00E5FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Sidechain ducks repetitions during phrase vocals/leads", color = TextMuted, fontSize = 9.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = "Delay On",
                    activeColor = Color(0xFF00E5FF)
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
                    label = "Time",
                    accentColor = Color(0xFF00E5FF),
                    defaultValue = 350f,
                    valueFormatter = { "%.0fms".format(it) }
                )
                AudioRotaryKnob(
                    value = config.feedback,
                    onValueChange = { onUpdate(config.copy(feedback = it)) },
                    range = 0f..0.85f,
                    label = "Feedback",
                    accentColor = Color(0xFF00E5FF),
                    defaultValue = 0.35f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
                AudioRotaryKnob(
                    value = config.mix,
                    onValueChange = { onUpdate(config.copy(mix = it)) },
                    range = 0f..0.8f,
                    label = "Mix",
                    accentColor = Color(0xFF00E5FF),
                    defaultValue = 0.25f,
                    valueFormatter = { "${(it * 100).toInt()}%" }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Ducking Sub-Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0F1420))
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SIDECHAIN DUCKING", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        AudioSwitchToggle(
                            checked = config.duckingEnabled,
                            onCheckedChange = { onUpdate(config.copy(duckingEnabled = it)) },
                            label = "Duck",
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
                                label = "Thresh",
                                accentColor = NeonCyan,
                                defaultValue = -24f,
                                valueFormatter = { "%.0fdB".format(it) }
                            )
                            AudioRotaryKnob(
                                value = config.duckingDepthDb,
                                onValueChange = { onUpdate(config.copy(duckingDepthDb = it)) },
                                range = -24f..-3f,
                                label = "Depth",
                                accentColor = NeonCyan,
                                defaultValue = -12f,
                                valueFormatter = { "%.0fdB".format(it) }
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
                    Text("CHANNEL BRICKWALL LIMITER", color = ClipRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Zero-overs peak protection per channel", color = TextMuted, fontSize = 9.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = "Limiter On",
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
                    label = "Ceiling",
                    accentColor = ClipRed,
                    defaultValue = -0.5f,
                    valueFormatter = { "%.1fdB".format(it) }
                )
                AudioRotaryKnob(
                    value = config.releaseMs,
                    onValueChange = { onUpdate(config.copy(releaseMs = it)) },
                    range = 5f..150f,
                    label = "Release",
                    accentColor = ClipRed,
                    defaultValue = 30f,
                    valueFormatter = { "%.0fms".format(it) }
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
                Text("CHANNEL REVERB SEND LEVEL (WET / DRY)", color = Color(0xFF00B0FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Controls the dry/wet send proportion feeding the Schroeder Reverb bus", color = TextMuted, fontSize = 9.sp)

                Spacer(modifier = Modifier.height(12.dp))

                AudioFaderSlider(
                    value = sendLevel * 100f,
                    onValueChange = { onUpdateSend((it / 100f).coerceIn(0f, 1f)) },
                    range = 0f..100f,
                    label = "Channel Send %",
                    accentColor = Color(0xFF00B0FF),
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
                        Text("CORE SCHROEDER REVERB DSP ENGINE", color = Color(0xFF00B0FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Decay Time (RT60), Pre-Delay, Room Size & Abbey Road Filters", color = TextMuted, fontSize = 9.sp)
                    }
                    AudioSwitchToggle(
                        checked = masterReverb.enabled,
                        onCheckedChange = { onUpdateMasterReverb(masterReverb.copy(enabled = it)) },
                        label = "Reverb Bus On",
                        activeColor = Color(0xFF00B0FF)
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
                        label = "Decay Time",
                        accentColor = Color(0xFF00B0FF),
                        defaultValue = 2.0f,
                        valueFormatter = { "%.1fs".format(it) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.preDelayMs,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(preDelayMs = it)) },
                        range = 0f..100f,
                        label = "Pre-Delay",
                        accentColor = Color(0xFF00B0FF),
                        defaultValue = 25f,
                        valueFormatter = { "%.0fms".format(it) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.roomSize,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(roomSize = it)) },
                        range = 0.10f..0.98f,
                        label = "Room Size",
                        accentColor = Color(0xFF00B0FF),
                        defaultValue = 0.60f,
                        valueFormatter = { "%.2f".format(it) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.returnLevel * 100f,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(returnLevel = it / 100f)) },
                        range = 0f..150f,
                        label = "Return Level",
                        accentColor = Color(0xFF00B0FF),
                        defaultValue = 70f,
                        valueFormatter = { "%.0f%%".format(it) }
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
                        label = "Low-Cut",
                        accentColor = StudioAmber,
                        defaultValue = 150f,
                        valueFormatter = { "%.0fHz".format(it) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.highCutHz,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(highCutHz = it)) },
                        range = 1000f..20000f,
                        label = "High-Cut",
                        accentColor = StudioAmber,
                        defaultValue = 9000f,
                        valueFormatter = { "%.1fkHz".format(it / 1000f) }
                    )
                    AudioRotaryKnob(
                        value = masterReverb.stereoWidth,
                        onValueChange = { onUpdateMasterReverb(masterReverb.copy(stereoWidth = it)) },
                        range = 0.0f..1.0f,
                        label = "Stereo Width",
                        accentColor = NeonCyan,
                        defaultValue = 0.85f,
                        valueFormatter = { "%.0f%%".format(it * 100f) }
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
                    Text("AUTO PITCH CORRECTION", color = StudioPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Monophonic pitch correction to nearest semitone", color = TextMuted, fontSize = 9.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = "Pitch On",
                    activeColor = StudioPurple
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            AudioRotaryKnob(
                value = config.correctionStrength,
                onValueChange = { onUpdate(config.copy(correctionStrength = it)) },
                range = 0f..1f,
                label = "Strength",
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
                    Text("NOISE REDUCTION", color = StudioAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Spectral noise gate and reduction", color = TextMuted, fontSize = 9.sp)
                }
                AudioSwitchToggle(
                    checked = config.enabled,
                    onCheckedChange = { onUpdate(config.copy(enabled = it)) },
                    label = "NR On",
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
                    label = "Thresh",
                    accentColor = StudioAmber,
                    defaultValue = -40f,
                    valueFormatter = { "%.0fdB".format(it) }
                )
                AudioRotaryKnob(
                    value = config.reductionDb,
                    onValueChange = { onUpdate(config.copy(reductionDb = it)) },
                    range = -24f..0f,
                    label = "Reduction",
                    accentColor = StudioAmber,
                    defaultValue = -12f,
                    valueFormatter = { "%.0fdB".format(it) }
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
            .background(if (active) activeColor.copy(alpha = 0.2f) else Color(0xFF0F1420))
            .border(1.dp, if (active) activeColor else StudioCardBorder, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(text, color = if (active) activeColor else TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}
