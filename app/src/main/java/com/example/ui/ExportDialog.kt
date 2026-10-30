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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.util.Locale
import com.example.ui.theme.OnAccent


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
                        Text(stringResource(R.string.daw_render_format, selectedFormat.extension.uppercase()), color = OnAccent, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricLime)
                ) {
                    Text(stringResource(R.string.ui_close), color = OnAccent, fontWeight = FontWeight.Bold)
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
    return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
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

