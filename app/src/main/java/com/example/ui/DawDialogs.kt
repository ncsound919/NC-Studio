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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.engine.OfflineRenderer
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
                Text(
                    text = "DSP Core Unit Tests & Regression",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Offline mathematical test vectors: Sine, Impulse, Noise, Pan Law (-3dB), Limiter ceiling, and Bit-Identical determinism.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results) { res ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(StudioCard)
                                .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp))
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
                                    fontSize = 9.sp,
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
                Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ExportDialog(
    progress: Float,
    exportedFile: File?,
    onStartExport: (com.example.audio.engine.ExportFormat) -> Unit,
    onStartStemsExport: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf(com.example.audio.engine.ExportFormat.WAV_24BIT) }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                Text(
                    text = "Offline Session Export",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Renders multi-track session with full DSP chain (EQ, compression, chorus, ducking delay, reverb, limiter) to studio audio files.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (exportedFile == null && progress == 0f) {
                    Text(
                        text = "EXPORT AUDIO FORMAT",
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
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) NeonCyan.copy(alpha = 0.15f) else StudioCard)
                                .border(1.dp, if (isSel) NeonCyan else StudioCardBorder, RoundedCornerShape(6.dp))
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
                } else if (exportedFile != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
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
                                    text = "Export Succeeded!",
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
                                text = "Size: ${exportedFile.length() / 1024} KB • Stereo 48kHz",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (exportedFile == null && progress == 0f) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onStartStemsExport) {
                        Text("Export Stems", color = TextSecondary, fontWeight = FontWeight.Bold)
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
                    Text("Close", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
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
                Text(
                    text = "USB Type-C Audio Interface",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Connect any USB Class Compliant audio interface, DAC, mixer, or USB-C microphone directly to your device port.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (devices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioCard)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No USB Audio Interface Connected",
                                color = StudioAmber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Plug in a USB-C Audio Interface (Focusrite Scarlett, MOTU, UA Volt, Behringer, USB DAC) to enable low-latency multi-channel routing.",
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
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) NeonCyan.copy(alpha = 0.15f) else StudioCard)
                                    .border(
                                        1.dp,
                                        if (isSelected) NeonCyan else StudioCardBorder,
                                        RoundedCornerShape(6.dp)
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
                                        fontSize = 9.sp
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
                Text("Refresh", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Done", color = TextSecondary)
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
    onDismiss: () -> Unit
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
                Text(
                    text = "Import Audio File / Stem",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Select destination track to import your WAV, MP3, FLAC, or M4A audio stem:",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

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
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) ElectricLime.copy(alpha = 0.15f) else StudioCard)
                                .border(
                                    1.dp,
                                    if (isSelected) ElectricLime else StudioCardBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onSelectTrack(trk.index) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
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
                colors = ButtonDefaults.buttonColors(containerColor = ElectricLime)
            ) {
                Text("Choose File", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
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
                    Text(
                        text = "Master Bus Engineering Presets",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Industry master bus chains & Schroeder reverb curves",
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
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioCard)
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(6.dp))
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
                                        fontSize = 9.sp,
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
                Text("Close", color = TextSecondary)
            }
        }
    )
}


