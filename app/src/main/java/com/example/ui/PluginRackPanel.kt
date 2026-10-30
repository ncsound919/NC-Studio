package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import androidx.compose.ui.window.Dialog
import com.example.audio.plugins.ChainRole
import com.example.audio.plugins.MixBuses
import com.example.audio.plugins.MixReferenceTemplate
import com.example.audio.plugins.MixReferenceTemplateLibrary
import com.overlay.audio.plugins.PluginCatalog
import com.overlay.audio.plugins.PluginChain
import com.overlay.audio.plugins.PluginKind
import com.overlay.audio.plugins.PluginSlot
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.Radius
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioPurple
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * Studio plugin rack: the ordered insert chain for one track.
 *
 * Signal flows top to bottom. Each row can be bypassed, reordered, removed or
 * expanded to its hardware parameters, and dynamics rows show live gain
 * reduction so the chain can be set by eye the way a rack of outboard gear is.
 */
@Composable
fun PluginRackDialog(
    trackName: String,
    chain: List<PluginSlot>,
    outputBusId: String,
    gainReductionProvider: () -> List<Float>,
    onChainChange: (List<PluginSlot>) -> Unit,
    onOutputBusChange: (String) -> Unit,
    onApplyTemplateAll: (String) -> Unit,
    onApplyTemplateRole: (ChainRole, String) -> Unit,
    onDismiss: () -> Unit
) {
    var gainReductions by remember { mutableStateOf(List(chain.size) { 0f }) }
    var expandedSlotId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    val chainModel = PluginChain(chain)

    LaunchedEffect(chain.size, trackName) {
        while (true) {
            gainReductions = gainReductionProvider()
            delay(100)
        }
    }

    if (showTemplates) {
        MixTemplatePickerDialog(
            trackName = trackName,
            onApplyAll = { id -> onApplyTemplateAll(id); showTemplates = false },
            onApplyRole = { role, id -> onApplyTemplateRole(role, id); showTemplates = false },
            onDismiss = { showTemplates = false }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = BorderStroke(1.dp, StudioCardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.plugin_rack_title),
                            color = NeonCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "$trackName • ${chain.size} inserts in order (top = first)",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close plugin rack", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showTemplates = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.plugin_reference_templates), fontSize = 11.sp, color = ElectricLime, maxLines = 1)
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        Button(onClick = { showAddMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.plugin_add_module), fontSize = 11.sp, maxLines = 1)
                        }
                        DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                            PluginKind.entries.sortedBy { it.category.ordinal }.forEach { kind ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(kind.displayName, fontSize = 12.sp, color = TextPrimary)
                                            Text(kind.blurb, fontSize = 10.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    },
                                    onClick = {
                                        showAddMenu = false
                                        onChainChange(chain + PluginCatalog.defaultSlot(kind))
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (chain.isEmpty()) {
                    Text(
                        text = stringResource(R.string.plugin_legacy_hint),
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        chain.forEachIndexed { index, slot ->
                            val gr = gainReductions.getOrElse(index) { 0f }
                            RackSlotRow(
                                index = index,
                                slot = slot,
                                gainReductionDb = gr,
                                expanded = expandedSlotId == slot.id,
                                onToggleExpand = {
                                    expandedSlotId = if (expandedSlotId == slot.id) null else slot.id
                                },
                                onToggleEnabled = { onChainChange(chainModel.toggle(slot.id).slots) },
                                onMoveUp = { onChainChange(chainModel.move(slot.id, -1).slots) },
                                onMoveDown = { onChainChange(chainModel.move(slot.id, 1).slots) },
                                onRemove = { onChainChange(chainModel.remove(slot.id).slots) },
                                onParamChange = { key, value ->
                                    onChainChange(chainModel.update(slot.id) { it.withParam(key, value) }.slots)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(stringResource(R.string.plugin_output_bus), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val busOptions = listOf("master") + MixBuses.ALL.map { it.id }
                    busOptions.forEach { busId ->
                        FilterChip(
                            selected = busId == outputBusId,
                            onClick = { onOutputBusChange(busId) },
                            label = {
                                Text(
                                    text = if (busId == "master") "Master" else MixBuses.nameFor(busId),
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RackSlotRow(
    index: Int,
    slot: PluginSlot,
    gainReductionDb: Float,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onParamChange: (String, Float) -> Unit
) {
    val accent = when (slot.kind.category) {
        com.overlay.audio.plugins.PluginCategory.DYNAMICS -> ElectricLime
        com.overlay.audio.plugins.PluginCategory.COLOR -> StudioAmber
        com.overlay.audio.plugins.PluginCategory.SPACE -> ElectricBlue
        com.overlay.audio.plugins.PluginCategory.FILTER -> NeonCyan
        com.overlay.audio.plugins.PluginCategory.MODULATION -> StudioPurple
        com.overlay.audio.plugins.PluginCategory.LIMITER -> ClipRed
        com.overlay.audio.plugins.PluginCategory.GAIN -> TextSecondary
        com.overlay.audio.plugins.PluginCategory.PHONE -> StudioAmber
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(StudioCard)
            .border(1.dp, if (slot.enabled) accent.copy(alpha = 0.5f) else StudioCardBorder, RoundedCornerShape(Radius.sm))
            .clickable { onToggleExpand() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1}",
                color = TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(16.dp)
            )
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = if (slot.enabled) accent else TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = slot.kind.displayName,
                    color = if (slot.enabled) TextPrimary else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = slot.shortLabel(),
                    color = TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (gainReductionDb > 0.05f) {
                Text(
                    text = "${String.format(java.util.Locale.US, "-%.1f", gainReductionDb)} dB",
                    color = ElectricLime,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${slot.kind.displayName} earlier", tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${slot.kind.displayName} later", tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
            TextButton(onClick = onToggleEnabled, modifier = Modifier.height(28.dp)) {
                Text(if (slot.enabled) "ON" else "BYP", fontSize = 10.sp, color = if (slot.enabled) ElectricLime else TextMuted)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${slot.kind.displayName}", tint = ClipRed.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            PluginParamEditors(slot = slot, onParamChange = onParamChange)
        }
    }
}

@Composable
private fun PluginParamEditors(slot: PluginSlot, onParamChange: (String, Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PluginCatalog.params(slot.kind).forEach { param ->
            val value = slot.value(param.key)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(param.label, color = TextSecondary, fontSize = 10.sp)
                    Text(param.labelFor(value), color = NeonCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                if (param.isChoice) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        param.choices.forEach { (choiceValue, label) ->
                            FilterChip(
                                selected = kotlin.math.abs(choiceValue - value) < 0.001f,
                                onClick = { onParamChange(param.key, choiceValue) },
                                label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                } else {
                    Slider(
                        value = value,
                        onValueChange = { onParamChange(param.key, it) },
                        valueRange = param.min..param.max,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Reference template picker. Every entry comes from the mixing reference
 * material in this repo: a named record, its genre targets, and the ordered
 * chain per source role.
 */
@Composable
fun MixTemplatePickerDialog(
    trackName: String,
    onApplyAll: (String) -> Unit,
    onApplyRole: (ChainRole, String) -> Unit,
    onDismiss: () -> Unit
) {
    var expandedTemplateId by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = BorderStroke(1.dp, StudioCardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.plugin_reference_mix_templates), color = ElectricLime, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(stringResource(R.string.plugin_reference_subtitle), color = TextMuted, fontSize = 10.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close templates", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MixReferenceTemplateLibrary.ALL.forEach { template ->
                        TemplateCard(
                            template = template,
                            trackName = trackName,
                            expanded = expandedTemplateId == template.id,
                            onToggle = {
                                expandedTemplateId = if (expandedTemplateId == template.id) null else template.id
                            },
                            onApplyAll = { onApplyAll(template.id) },
                            onApplyRole = { role -> onApplyRole(role, template.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: MixReferenceTemplate,
    trackName: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onApplyAll: () -> Unit,
    onApplyRole: (ChainRole) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(StudioCard)
            .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(template.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${template.genre} • ${template.tempoRange.first}-${template.tempoRange.last} BPM • ${template.lufsTarget.first} to ${template.lufsTarget.second} LUFS",
                    color = TextMuted,
                    fontSize = 10.sp
                )
                Text(stringResource(R.string.plugin_reference_track, template.referenceTrack), color = ElectricBlue, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(template.referenceCredit, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onToggle) {
                Text(if (expanded) "HIDE" else "CHAINS", fontSize = 10.sp, color = NeonCyan)
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(template.description, color = TextSecondary, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(6.dp))
            template.roleChains.forEach { (role, chain) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = role.displayName,
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(120.dp)
                    )
                    Text(
                        text = chain.joinToString(" > ") { it.kind.displayName },
                        color = TextMuted,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onApplyRole(role) }) {
                        Text(stringResource(R.string.plugin_apply_to_track, trackName), fontSize = 10.sp, color = ElectricLime, maxLines = 1)
                    }
                }
            }
            if (template.drumBusChain.isNotEmpty()) {
                Text(
                    "Drum bus: " + template.drumBusChain.joinToString(" > ") { it.kind.displayName },
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
            if (template.mixBusChain.isNotEmpty()) {
                Text(
                    "Mix bus: " + template.mixBusChain.joinToString(" > ") { it.kind.displayName },
                    color = StudioPurple,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApplyAll, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.plugin_apply_to_session), fontSize = 11.sp)
            }
        }
    }
}

/** Small helper used by the inspector header button. */
fun pluginChainSummary(chain: List<PluginSlot>): String =
    if (chain.isEmpty()) "Legacy strip" else chain.joinToString(" > ") { it.kind.displayName }
