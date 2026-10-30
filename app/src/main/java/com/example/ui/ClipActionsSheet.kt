package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.audio.model.AudioClip
import com.example.audio.model.FadeCurve
import com.example.audio.model.StripSilenceMode
import com.example.audio.model.TrackData
import com.example.ui.components.AudioRotaryKnob
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.OnAccent
import com.example.ui.theme.OverlayWhite
import com.example.ui.theme.Radius
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioPurple
import com.example.ui.theme.StudioSurface
import com.overlay.studio.ui.StudioButton
import com.overlay.studio.ui.StudioButtonVariant
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.overlay.audio.sample.SampleEdit
import java.util.Locale

/**
 * The single clip button system for the NC Studio timeline.
 *
 * One CLIP button opens this sheet; every clip operation is here exactly once,
 * including Pro Tools-style Strip Silence. The strip controls follow the Pro
 * Tools dialog (threshold, minimum silence, minimum clip, start/end pad) and
 * run the shared `nc.sample-edit.v1` analyser, so the same silence definition
 * is used by the sampler in the other music apps.
 */
@Composable
fun ClipActionsSheet(
    clip: AudioClip?,
    track: TrackData?,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onSetGain: (Float) -> Unit,
    onSetFadeIn: (Float, FadeCurve) -> Unit,
    onSetFadeOut: (Float, FadeCurve) -> Unit,
    onPasteChorusSection: (Int) -> Unit,
    onPasteChorusDouble: () -> Unit,
    onAdaptChorusLoop: () -> Unit,
    onSeparateAtPlayhead: () -> Unit,
    onSeparateAtSelection: () -> Unit,
    onHeal: () -> Unit,
    onCrossfade: () -> Unit,
    onDuplicate: () -> Unit,
    onNudgeBack: () -> Unit,
    onNudgeForward: () -> Unit,
    onDeleteClips: () -> Unit,
    onStripSilence: (SampleEdit.StripSettings, StripSilenceMode) -> Unit,
) {
    val defaults = remember { SampleEdit.StripSettings() }
    var thresholdDb by remember { mutableFloatStateOf(defaults.thresholdDb) }
    var minSilenceMs by remember { mutableFloatStateOf(defaults.minSilenceMs) }
    var minKeepMs by remember { mutableFloatStateOf(defaults.minKeepMs) }
    var padMs by remember { mutableFloatStateOf(defaults.padMs) }
    var stripMode by remember { mutableStateOf(StripSilenceMode.SPLIT_REGIONS) }

    var gainDb by remember(clip?.id) { mutableFloatStateOf(clip?.gainDb ?: 0f) }
    var fadeInMs by remember(clip?.id) { mutableFloatStateOf(((clip?.fadeInFrames ?: 0L) / 48f)) }
    var fadeOutMs by remember(clip?.id) { mutableFloatStateOf(((clip?.fadeOutFrames ?: 0L) / 48f)) }
    var selectedCurve by remember(clip?.id) { mutableStateOf(clip?.fadeInCurve ?: FadeCurve.EQUAL_POWER) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .padding(8.dp)
                .testTag("clip_actions_sheet"),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = BorderStroke(1.dp, StudioCardBorder),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("CLIP ACTIONS", color = NeonCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (selectedCount > 1) {
                                "$selectedCount clips selected on track ${(track?.index ?: 0) + 1}"
                            } else {
                                "${clip?.name ?: "No clip"} · track ${(track?.index ?: 0) + 1}"
                            },
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close clip actions", tint = TextSecondary)
                    }
                }

                // One grid, one entry per operation: no duplicate clip buttons on
                // the toolbar. All of these act on the clip selection.
                val actions = listOf(
                    ClipAction("Separate @ playhead", true, NeonCyan, "action_separate_playhead", onSeparateAtPlayhead),
                    ClipAction("Separate @ selection", true, NeonCyan, "action_separate_selection", onSeparateAtSelection),
                    ClipAction("Heal", clip != null, ElectricLime, "action_heal_clip", onHeal),
                    ClipAction("Crossfade", selectedCount >= 2, NeonCyan, "action_crossfade_clips", onCrossfade),
                    ClipAction("Duplicate", clip != null, ElectricLime, "action_duplicate_clip", onDuplicate),
                    ClipAction("Nudge ←", clip != null, StudioAmber, "action_nudge_back", onNudgeBack),
                    ClipAction("Nudge →", clip != null, StudioAmber, "action_nudge_forward", onNudgeForward),
                    ClipAction("Delete clips", selectedCount > 0, StudioPurple, "action_delete_clips", onDeleteClips),
                )
                actions.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { action ->
                            ActionTile(action, Modifier.weight(1f))
                        }
                    }
                }

                // Pro Tools Strip Silence: the one-button system for silence.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(StudioCard)
                        .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("STRIP SILENCE", color = StudioAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Pro Tools detector · shared with the samplers",
                                color = TextMuted,
                                fontSize = 9.sp,
                            )
                        }
                        Text(
                            "1 BUTTON",
                            color = StudioAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top,
                    ) {
                        AudioRotaryKnob(
                            value = thresholdDb,
                            onValueChange = { thresholdDb = it },
                            range = -60f..-20f,
                            label = "Threshold",
                            accentColor = StudioAmber,
                            defaultValue = defaults.thresholdDb,
                            knobSize = 52.dp,
                            valueFormatter = { "${it.toInt()} dB" },
                            testTag = "strip_threshold_knob",
                        )
                        AudioRotaryKnob(
                            value = minSilenceMs,
                            onValueChange = { minSilenceMs = it },
                            range = 50f..2000f,
                            label = "Min silence",
                            accentColor = NeonCyan,
                            defaultValue = defaults.minSilenceMs,
                            knobSize = 52.dp,
                            valueFormatter = { "${it.toInt()} ms" },
                            testTag = "strip_min_silence_knob",
                        )
                        AudioRotaryKnob(
                            value = minKeepMs,
                            onValueChange = { minKeepMs = it },
                            range = 10f..1000f,
                            label = "Min clip",
                            accentColor = ElectricLime,
                            defaultValue = defaults.minKeepMs,
                            knobSize = 52.dp,
                            valueFormatter = { "${it.toInt()} ms" },
                            testTag = "strip_min_clip_knob",
                        )
                        AudioRotaryKnob(
                            value = padMs,
                            onValueChange = { padMs = it },
                            range = 0f..250f,
                            label = "Pad",
                            accentColor = StudioPurple,
                            defaultValue = defaults.padMs,
                            knobSize = 52.dp,
                            valueFormatter = { "${it.toInt()} ms" },
                            testTag = "strip_pad_knob",
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StripSilenceMode.values().forEach { mode ->
                            val active = stripMode == mode
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(if (active) StudioAmber.copy(alpha = 0.18f) else StudioCard)
                                    .border(
                                        1.dp,
                                        if (active) StudioAmber else StudioCardBorder,
                                        RoundedCornerShape(Radius.sm),
                                    )
                                    .clickable(role = Role.Button) { stripMode = mode }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                    .testTag("strip_mode_${mode.name.lowercase()}"),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    mode.displayName.uppercase(),
                                    color = if (active) StudioAmber else TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    mode.description,
                                    color = TextMuted,
                                    fontSize = 8.sp,
                                    lineHeight = 10.sp,
                                )
                            }
                        }
                    }

                    StudioButton(
                        text = "Strip Silence  ·  ${thresholdDb.toInt()} dB / ${minSilenceMs.toInt()} ms",
                        onClick = {
                            onStripSilence(
                                SampleEdit.StripSettings(
                                    thresholdDb = thresholdDb,
                                    minSilenceMs = minSilenceMs,
                                    minKeepMs = minKeepMs,
                                    padMs = padMs,
                                ),
                                stripMode,
                            )
                            onDismiss()
                        },
                        enabled = clip != null || selectedCount > 0,
                        accent = StudioAmber,
                        variant = StudioButtonVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "strip_silence_button",
                    )
                }

                if (clip != null) {
                    Text("FADES & GAIN", color = StudioPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        AudioRotaryKnob(
                            value = gainDb,
                            onValueChange = {
                                gainDb = it
                                onSetGain(it)
                            },
                            range = -24f..12f,
                            label = "Clip gain",
                            accentColor = StudioAmber,
                            defaultValue = 0f,
                            valueFormatter = { String.format(Locale.US, "%+.1f dB", it) },
                        )
                        AudioRotaryKnob(
                            value = fadeInMs,
                            onValueChange = {
                                fadeInMs = it
                                onSetFadeIn(it, selectedCurve)
                            },
                            range = 0f..2000f,
                            label = "Fade in",
                            accentColor = ElectricLime,
                            defaultValue = 0f,
                            valueFormatter = { String.format(Locale.US, "%.0f ms", it) },
                        )
                        AudioRotaryKnob(
                            value = fadeOutMs,
                            onValueChange = {
                                fadeOutMs = it
                                onSetFadeOut(it, selectedCurve)
                            },
                            range = 0f..2000f,
                            label = "Fade out",
                            accentColor = StudioPurple,
                            defaultValue = 0f,
                            valueFormatter = { String.format(Locale.US, "%.0f ms", it) },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FadeCurve.values().forEach { curve ->
                            val isSel = selectedCurve == curve
                            Text(
                                text = curve.displayName.substringBefore(" (").uppercase(),
                                color = if (isSel) NeonCyan else TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(if (isSel) NeonCyan.copy(alpha = 0.18f) else StudioCard)
                                    .border(
                                        1.dp,
                                        if (isSel) NeonCyan else StudioCardBorder,
                                        RoundedCornerShape(Radius.sm),
                                    )
                                    .clickable(role = Role.Button) {
                                        selectedCurve = curve
                                        onSetFadeIn(fadeInMs, curve)
                                        onSetFadeOut(fadeOutMs, curve)
                                    }
                                    .padding(6.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }

                    Text("CHORUS ADAPTING", color = StudioPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SmallAction("+4 bars", StudioPurple, "chorus_4_bars", Modifier.weight(1f)) { onPasteChorusSection(4) }
                        SmallAction("Doubler", NeonCyan, "chorus_doubler", Modifier.weight(1f)) { onPasteChorusDouble() }
                        SmallAction("4-bar loop", ElectricLime, "chorus_loop", Modifier.weight(1f)) { onAdaptChorusLoop() }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Rule: one clip button opens every clip operation; a strip pass is one undo entry.",
                    color = TextMuted,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

private data class ClipAction(
    val label: String,
    val enabled: Boolean,
    val accent: androidx.compose.ui.graphics.Color,
    val tag: String,
    val onClick: () -> Unit,
)

@Composable
private fun ActionTile(action: ClipAction, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (action.enabled) StudioCard else StudioCard.copy(alpha = 0.45f))
            .border(
                1.dp,
                if (action.enabled) action.accent.copy(alpha = 0.65f) else StudioCardBorder,
                RoundedCornerShape(Radius.sm),
            )
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .padding(horizontal = 5.dp, vertical = 6.dp)
            .testTag(action.tag)
            .semantics { contentDescription = action.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            action.label,
            color = if (action.enabled) TextPrimary else TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SmallAction(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = OverlayWhite,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(accent.copy(alpha = 0.28f))
            .border(1.dp, accent, RoundedCornerShape(Radius.sm))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp)
            .testTag(tag),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
