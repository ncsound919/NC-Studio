package com.example.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Science
import com.example.R
import com.example.audio.model.MasterPreset
import com.example.audio.model.PresetLibrary
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.engine.OfflineRenderer
import com.example.ui.theme.Radius
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.util.Locale

@Composable
fun DspTestDialog(
    results: List<OfflineRenderer.DspTestResult>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dsp_test_dialog"),
        containerColor = StudioSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Science,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.ui_dsp_core_unit_tests_regression),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.ui_offline_mathematical_test_vectors_sine_impulse_n),
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results) { res ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.md))
                                .background(StudioCard)
                                .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (res.passed) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = if (res.passed) "Passed" else "Failed",
                                tint = if (res.passed) ElectricLime else ClipRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = res.testName,
                                    color = if (res.passed) TextPrimary else ClipRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = res.message,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text(stringResource(R.string.ui_done), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ExportDialog(
    progress: Float,
    exportedFile: File?,
    errorMessage: String? = null,
    sampleRate: Int = 48000,
    durationSeconds: Float = 16f,
    stemFiles: List<File> = emptyList(),
    destinationName: String = "App storage / exports",
    onChooseDestination: () -> Unit = {},
    onStartExport: (com.example.audio.engine.ExportFormat) -> Unit,
    onStartStemsExport: () -> Unit = {},
    onShareFile: (File) -> Unit = {},
    onOpenFile: (File) -> Unit = {},
    onShareAllStems: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf(com.example.audio.engine.ExportFormat.WAV_24BIT) }
    val isRendering = exportedFile == null && progress > 0f && progress < 1f && stemFiles.isEmpty()

    AlertDialog(
        onDismissRequest = { if (!isRendering) onDismiss() },
        modifier = Modifier.testTag("export_dialog"),
        containerColor = StudioSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.ui_offline_session_export),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(text = stringResource(R.string.ui_renders_multi_track_session_with_full_dsp_chain_),
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Destination  $destinationName",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onChooseDestination,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.ui_change), color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = "Session  ${formatDuration(durationSeconds)}  •  ${sampleRate / 1000} kHz",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(ClipRed.copy(alpha = 0.1f))
                            .border(1.dp, ClipRed.copy(alpha = 0.4f), RoundedCornerShape(Radius.md))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            color = ClipRed,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (exportedFile == null && progress == 0f) {
                    Text(text = stringResource(R.string.ui_export_audio_format),
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    com.example.audio.engine.ExportFormat.values().forEach { fmt ->
                        val isSel = selectedFormat == fmt
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (isSel) NeonCyan.copy(alpha = 0.15f) else StudioCard)
                                .border(1.dp, if (isSel) NeonCyan else StudioCardBorder, RoundedCornerShape(Radius.md))
                                .clickable { selectedFormat = fmt }
                                .padding(10.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isSel) NeonCyan else TextMuted)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = fmt.displayName,
                                        color = if (isSel) NeonCyan else TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = fmt.description,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                                )
                                Text(
                                    text = "${formatExportSpec(fmt, sampleRate)}  •  ~${formatExportSize(fmt, sampleRate, durationSeconds)}",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (progress > 0f && progress < 1f) {
                    Text(
                        text = "Rendering: ${(progress * 100).toInt()}%",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonCyan,
                        trackColor = StudioCard
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.ui_keep_this_dialog_open_rendering_cannot_be_cancel),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                } else if (exportedFile != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(StudioCard)
                            .padding(10.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = ElectricLime,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (stemFiles.size > 1) "${stemFiles.size} stems exported" else "Export Succeeded!",
                                    color = ElectricLime,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "File: ${exportedFile.name}",
                                color = TextPrimary,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "Size: ${exportedFile.length() / 1024} KB • Stereo ${sampleRate / 1000} kHz",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    if (stemFiles.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        stemFiles.forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = file.name,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${file.length() / 1024} KB",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                TextButton(
                                    onClick = { onOpenFile(file) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.ui_open), color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = { onShareFile(file) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.ui_share), color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = onShareAllStems,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("share_all_stems_button"),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
                        ) {
                            Text(stringResource(R.string.ui_share_all_stems), color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onOpenFile(exportedFile) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.ui_open_file), color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(
                                onClick = { onShareFile(exportedFile) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.ui_share_file), color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (exportedFile == null && progress == 0f) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onStartStemsExport) {
                        Text(stringResource(R.string.ui_export_stems), color = TextSecondary, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onStartExport(selectedFormat) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        modifier = Modifier.testTag("confirm_export_button")
                    ) {
                        Text("Render ${selectedFormat.extension.uppercase()}", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricLime)
                ) {
                    Text(stringResource(R.string.ui_close), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (!isRendering) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ui_cancel), color = TextSecondary)
                }
            }
        }
    )
}

internal fun formatDuration(seconds: Float): String {
    val total = seconds.coerceAtLeast(0f).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun formatExportSpec(
    format: com.example.audio.engine.ExportFormat,
    sampleRate: Int
): String = when (format) {
    com.example.audio.engine.ExportFormat.WAV_24BIT -> "${sampleRate / 1000} kHz • 24-bit PCM"
    com.example.audio.engine.ExportFormat.FLAC_LOSSLESS -> "${sampleRate / 1000} kHz • 16-bit FLAC"
    com.example.audio.engine.ExportFormat.AAC_320KBPS -> "${sampleRate / 1000} kHz • AAC 320 kbps"
    com.example.audio.engine.ExportFormat.MP3_320KBPS -> "${sampleRate / 1000} kHz • MP3 320 kbps"
}

internal fun formatExportSize(
    format: com.example.audio.engine.ExportFormat,
    sampleRate: Int,
    durationSeconds: Float
): String {
    val seconds = durationSeconds.coerceAtLeast(1f)
    val bytes = when (format) {
        com.example.audio.engine.ExportFormat.WAV_24BIT -> sampleRate * 3L * 2L * seconds.toLong()
        com.example.audio.engine.ExportFormat.FLAC_LOSSLESS -> (sampleRate * 2L * 2L * seconds.toLong() * 0.6f).toLong()
        else -> (320_000L / 8L * seconds.toLong())
    }
    return when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        else -> "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}

@Composable
fun UsbAudioInterfaceDialog(
    devices: List<com.example.audio.model.UsbAudioDeviceInfo>,
    activeDevice: com.example.audio.model.UsbAudioDeviceInfo?,
    onSelectDevice: (Int) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.ui_usb_type_c_audio_interface),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.ui_connect_any_usb_class_compliant_audio_interface_),
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (devices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(StudioCard)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = stringResource(R.string.ui_no_usb_audio_interface_connected),
                                color = StudioAmber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = stringResource(R.string.ui_plug_in_a_usb_c_audio_interface_focusrite_scarle),
                                color = TextMuted,
                                fontSize = 10.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(devices) { dev ->
                            val isSelected = activeDevice?.id == dev.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.md))
                                    .background(if (isSelected) NeonCyan.copy(alpha = 0.15f) else StudioCard)
                                    .border(
                                        1.dp,
                                        if (isSelected) NeonCyan else StudioCardBorder,
                                        RoundedCornerShape(Radius.md)
                                    )
                                    .clickable { onSelectDevice(dev.id) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dev.productName,
                                        color = if (isSelected) NeonCyan else TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${dev.typeName} • ${dev.sampleRates.joinToString("/") { "${it/1000}k" }} • ${dev.channelCounts.maxOrNull() ?: 2} Channels",
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Active",
                                        tint = ElectricLime,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text(stringResource(R.string.ui_refresh), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_done), color = TextSecondary)
            }
        }
    )
}

@Composable
fun AudioImportDialog(
    tracks: List<com.example.audio.model.TrackData>,
    selectedTrackIndex: Int,
    onSelectTrack: (Int) -> Unit,
    onLaunchPicker: () -> Unit,
    onDismiss: () -> Unit,
    isImporting: Boolean = false,
    errorMessage: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = ElectricLime,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.ui_import_audio_file_stem),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.ui_select_destination_track_to_import_your_wav_mp3_),
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                if (isImporting) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonCyan,
                        trackColor = StudioCard
                    )
                    Text(text = stringResource(R.string.ui_decoding_audio_and_building_waveform),
                        color = NeonCyan,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (errorMessage != null && !isImporting) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = errorMessage,
                        color = ClipRed,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.height(180.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(tracks) { trk ->
                        val isSelected = trk.index == selectedTrackIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (isSelected) ElectricLime.copy(alpha = 0.15f) else StudioCard)
                                .border(
                                    1.dp,
                                    if (isSelected) ElectricLime else StudioCardBorder,
                                    RoundedCornerShape(Radius.md)
                                )
                                .clickable(enabled = !isImporting) { onSelectTrack(trk.index) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(Color(trk.colorHex))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Track ${trk.index + 1}: ${trk.name}",
                                color = if (isSelected) ElectricLime else TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onLaunchPicker,
                enabled = !isImporting,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricLime)
            ) {
                Text(if (isImporting) "Importing..." else "Choose File", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_cancel), color = TextSecondary)
            }
        }
    )
}

@Composable
fun MasterPresetDialog(
    presets: List<MasterPreset> = PresetLibrary.MASTER_PRESETS,
    onSelectPreset: (MasterPreset) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("master_preset_dialog"),
        containerColor = StudioSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = StudioAmber,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = stringResource(R.string.ui_master_bus_engineering_presets),
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = stringResource(R.string.ui_industry_master_bus_chains_schroeder_reverb_curv),
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(StudioCard)
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
                            .clickable {
                                onSelectPreset(preset)
                                onDismiss()
                            }
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
                                    color = StudioAmber,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(StudioAmber.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${preset.masterReverb.decayTimeSec}s RT60",
                                        color = StudioAmber,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = preset.description,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_close), color = TextSecondary)
            }
        }
    )
}


