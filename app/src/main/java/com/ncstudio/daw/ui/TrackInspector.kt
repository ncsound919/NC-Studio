package com.ncstudio.daw.ui

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
import com.ncstudio.daw.R
import com.ncstudio.daw.audio.dsp.FourBandEq
import com.ncstudio.daw.audio.model.ChannelLimiterConfig
import com.ncstudio.daw.audio.model.ChorusConfig
import com.ncstudio.daw.audio.model.CompressorConfig
import com.ncstudio.daw.audio.model.CompressorType
import com.ncstudio.daw.audio.model.DuckingDelayConfig
import com.ncstudio.daw.audio.model.EqBand
import com.ncstudio.daw.audio.model.EqConfig
import com.ncstudio.daw.audio.model.PresetLibrary
import com.ncstudio.daw.audio.model.ResonanceSmootherConfig
import com.ncstudio.daw.audio.model.ReverbConfig
import com.ncstudio.daw.audio.model.SaturationConfig
import com.ncstudio.daw.audio.model.SaturationType
import com.ncstudio.daw.audio.model.TrackData
import com.ncstudio.daw.audio.model.TrackPreset
import com.ncstudio.daw.audio.model.VocalRiderConfig
import com.ncstudio.daw.ui.components.AudioFaderSlider
import com.ncstudio.daw.ui.components.AudioRotaryKnob
import com.ncstudio.daw.ui.components.AudioSwitchToggle
import com.ncstudio.daw.ui.theme.Radius
import com.ncstudio.daw.ui.theme.ClipRed
import com.ncstudio.daw.ui.theme.ElectricBlue
import com.ncstudio.daw.ui.theme.ElectricLime
import com.ncstudio.daw.ui.theme.NeonCyan
import com.ncstudio.daw.ui.theme.PanelDeep
import com.ncstudio.daw.ui.theme.SurfaceSunken
import com.ncstudio.daw.ui.theme.StudioAmber
import com.ncstudio.daw.ui.theme.StudioBackground
import com.ncstudio.daw.ui.theme.StudioCard
import com.ncstudio.daw.ui.theme.StudioCardBorder
import com.ncstudio.daw.ui.theme.StudioPurple
import com.ncstudio.daw.ui.theme.StudioSurface
import com.ncstudio.daw.ui.theme.TextMuted
import com.ncstudio.daw.ui.theme.TextPrimary
import com.ncstudio.daw.ui.theme.TextSecondary
import kotlin.math.log10
import kotlin.math.pow

enum class StripModule(val title: String, val icon: ImageVector, val color: Color) {
    EQ("4-Band EQ", Icons.Default.GraphicEq, NeonCyan),
    SATURATION("Saturation", Icons.Default.Whatshot, StudioAmber),
    COMPRESSOR("Compressor", Icons.Default.Tune, ElectricLime),
    CHORUS("Chorus / Doubler", Icons.Default.Waves, StudioPurple),
    DELAY("Ducking Delay", Icons.Default.HourglassBottom, NeonCyan),
    LIMITER("Limiter", Icons.Default.Security, ClipRed),
    REVERB("Reverb Send", Icons.Default.Waves, ElectricBlue),
    VOCAL_RIDER("Vocal Rider", Icons.Default.GraphicEq, ElectricLime),
    RESONANCE("Resonance", Icons.Default.Tune, StudioAmber),
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
    onUpdateAutoPitch: (com.ncstudio.daw.audio.model.AutoPitchConfig) -> Unit = {},
    onUpdateNoiseReduction: (com.ncstudio.daw.audio.model.NoiseReductionConfig) -> Unit = {},
    onUpdateVocalRider: (VocalRiderConfig) -> Unit = {},
    onUpdateResonanceSmoother: (ResonanceSmootherConfig) -> Unit = {},
    masterReverb: ReverbConfig = ReverbConfig(),
    onUpdateMasterReverb: (ReverbConfig) -> Unit = {},
    onApplyPreset: (TrackPreset) -> Unit = {},
    onOpenPluginRack: () -> Unit = {},
    onSelectTake: (String) -> Unit = {},
    onRateTake: (String) -> Unit = {},
    onDeleteTake: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeModule by rememberSaveable(track.index) { mutableIntStateOf(0) }
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
                .clip(RoundedCornerShape(Radius.md))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md)),
            color = StudioSurface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(track.colorHex))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "CH ${track.index + 1} • ${track.name.uppercase()}",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Category: ${track.stemCategory.displayName}",
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Pro Presets Quick Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(StudioPurple.copy(alpha = 0.25f))
                            .border(1.dp, StudioPurple, RoundedCornerShape(Radius.sm))
                            .clickable { showPresetDialog = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .semantics {
                                contentDescription = "Pro presets, opens preset dialog for ${track.name}"
                            }
                            .testTag("open_presets_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = StudioPurple, modifier = Modifier.size(12.dp))
                            Text(stringResource(R.string.ui_pro_presets), color = StudioPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                        }
                    }

                    // Studio plugin rack: ordered insert chain for this track
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(NeonCyan.copy(alpha = 0.18f))
                            .border(1.dp, NeonCyan, RoundedCornerShape(Radius.sm))
                            .clickable { onOpenPluginRack() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .semantics {
                                contentDescription = "Plugin rack, opens the ordered insert chain for ${track.name}"
                            }
                            .testTag("open_plugin_rack_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(12.dp))
                            Text(
                                text = if (track.pluginChain.isEmpty()) "RACK" else "RACK ${track.pluginChain.size}",
                                color = NeonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scrollable Module Selection - show one at a time
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(StudioSurface)
                .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left scroll arrow
            IconButton(
                onClick = { activeModule = if (activeModule > 0) activeModule - 1 else StripModule.values().size - 1 },
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Previous effect module" }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous module",
                    tint = NeonCyan
                )
            }

            // Current module - full width
            val currentModule = StripModule.values()[activeModule]
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(currentModule.color.copy(alpha = 0.2f))
                    .border(1.dp, currentModule.color, RoundedCornerShape(Radius.sm))
                    .clickable { /* Current module shown */ }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = currentModule.icon,
                        contentDescription = currentModule.title,
                        tint = currentModule.color,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = currentModule.title.substringBefore(" "),
                        color = currentModule.color,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Right scroll arrow
            IconButton(
                onClick = { activeModule = (activeModule + 1) % StripModule.values().size },
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Next effect module" }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next module",
                    tint = NeonCyan
                )
            }
        }

        TakeLanesRow(
            track = track,
            onSelectTake = onSelectTake,
            onRateTake = onRateTake,
            onDeleteTake = onDeleteTake
        )

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
                StripModule.VOCAL_RIDER -> VocalRiderModuleEditor(track.vocalRiderConfig, onUpdateVocalRider)
                StripModule.RESONANCE -> ResonanceSmootherModuleEditor(track.resonanceSmootherConfig, onUpdateResonanceSmoother)
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

/**
 * Alternate takes for the selected track. Tapping a take swaps the audible
 * clips; the star cycles the rating and the cross removes the take.
 */
@Composable
private fun TakeLanesRow(
    track: TrackData,
    onSelectTake: (String) -> Unit,
    onRateTake: (String) -> Unit,
    onDeleteTake: (String) -> Unit
) {
    // Tracks with a single implicit take get no row: the inspector's height is
    // scarce, and the section only becomes useful once an overdub exists.
    if (track.takeLanes.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().testTag("track_takes")) {
        Text(
            text = stringResource(R.string.inspector_takes),
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            track.takeLanes.forEach { lane ->
                val active = lane.id == track.activeTakeId
                val selectCd = stringResource(R.string.take_select_cd, lane.name)
                val rateCd = stringResource(R.string.take_rating_cd, lane.name)
                val deleteCd = stringResource(R.string.take_delete_cd, lane.name)
                Surface(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable {
                            onSelectTake(lane.id)
                        }
                        .semantics { contentDescription = selectCd },
                    color = if (active) NeonCyan.copy(alpha = 0.18f) else StudioSurface,
                    shape = RoundedCornerShape(Radius.sm),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (active) NeonCyan else StudioCardBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = lane.name,
                            color = if (active) NeonCyan else TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                        )
                        TextButton(
                            onClick = { onRateTake(lane.id) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                            modifier = Modifier.semantics { contentDescription = rateCd }
                        ) {
                            Text(
                                text = if (lane.rating > 0) {
                                    stringResource(R.string.take_stars, lane.rating)
                                } else {
                                    "☆"
                                },
                                color = StudioAmber,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(
                            onClick = { onDeleteTake(lane.id) },
                            modifier = Modifier
                                .size(32.dp)
                                .semantics { contentDescription = deleteCd }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
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
                        Text(stringResource(R.string.ui_professional_mix_presets), color = StudioPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(stringResource(R.string.inspector_technique_chains, trackName), color = TextMuted, fontSize = 10.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close mix presets", tint = TextSecondary)
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
                                .clip(RoundedCornerShape(Radius.md))
                                .background(StudioCard)
                                .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
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
                                            fontSize = 10.sp,
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

