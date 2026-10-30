package com.example.session.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.session.net.ConnectionSettings
import com.example.session.net.PreflightPolicy

@Composable
internal fun EngineerLive(
    ui: SessionUiState,
    viewModel: SessionViewModel,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
) {
    SectionCard(stringResource(R.string.session_invite_title)) {
        Text(
            text = ui.inviteCode ?: stringResource(R.string.session_busy_placeholder),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(stringResource(R.string.session_invite_hint), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            ui.inviteCode?.let { clipboard.setText(AnnotatedString(it)) }
        }) { Text(stringResource(R.string.session_copy_code)) }
    }

    LinkCard(ui)

    SectionCard(stringResource(R.string.session_transport_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.session_arm_artist), modifier = Modifier.weight(1f))
            Switch(checked = ui.armed, onCheckedChange = { viewModel.engineerArm(it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::engineerStartTake,
                enabled = ui.armed && ui.sessionState == "ARMED",
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.session_start_take)) }
            FilledTonalButton(onClick = viewModel::engineerStopTake, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.session_stop)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::engineerMarkTake, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.session_mark)) }
            OutlinedButton(onClick = viewModel::engineerPause, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.session_pause)) }
        }
        Text(
            stringResource(
                R.string.session_last_take,
                (ui.lastTakeFrames / 48_000f).coerceAtLeast(0f).let { "%.1fs".format(it) },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    SectionCard(stringResource(R.string.session_monitor_title)) {
        // Drag locally and send once on release: a command per slider frame
        // flooded the WebSocket and the artist's command queue.
        var cueLevel by remember { mutableFloatStateOf(ui.cueLevelDb) }
        LaunchedEffect(ui.cueLevelDb) { cueLevel = ui.cueLevelDb }
        Text(
            stringResource(R.string.session_cue_level, "%.0f".format(cueLevel)),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = cueLevel,
            onValueChange = { cueLevel = it },
            onValueChangeFinished = { viewModel.engineerSetCueLevel(cueLevel) },
            valueRange = -60f..6f,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.session_talkback), modifier = Modifier.weight(1f))
            Switch(checked = ui.engineerTalkbackOn, onCheckedChange = viewModel::engineerTalkback)
        }
    }

    TakeList(ui, viewModel, engineer = true)

    OutlinedButton(onClick = viewModel::stopSession, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.session_end))
    }
}

@Composable
internal fun ArtistLive(ui: SessionUiState, viewModel: SessionViewModel) {
    SectionCard(stringResource(R.string.session_artist_title)) {
        StatusRow(ok = ui.monitorLive, text = if (ui.monitorLive) stringResource(R.string.session_monitor_live_on) else stringResource(R.string.session_monitor_off))
        StatusRow(ok = ui.connected, text = if (ui.connected) stringResource(R.string.session_engineer_connected) else stringResource(R.string.session_waiting_engineer))
        Text(
            stringResource(R.string.session_state, ui.sessionState),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            stringResource(
                R.string.session_backing_chosen,
                ui.backingTrackName ?: stringResource(R.string.session_backing_none),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.session_cue_level, "%.0f".format(ui.cueLevelDb)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (ui.artistStopAvailable) {
            Button(onClick = viewModel::artistStop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.session_stop_take))
            }
        } else {
            Text(
                stringResource(R.string.session_stop_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    TakeList(ui, viewModel, engineer = false)

    OutlinedButton(onClick = viewModel::stopSession, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.session_leave))
    }
}

@Composable
internal fun TakeList(ui: SessionUiState, viewModel: SessionViewModel, engineer: Boolean) {
    if (ui.takes.isEmpty()) return
    SectionCard(stringResource(if (engineer) R.string.session_takes_artist_phone else R.string.session_takes_yours)) {
        ui.takes.forEach { take ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = "${take.takeId.take(8)}…  ${take.state}  ${"%.1fs".format(take.seconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (take.chunksTotal > 0 && take.state != "verified") {
                    LinearProgressIndicator(
                        progress = { take.chunksDone.toFloat() / take.chunksTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                take.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (engineer && take.state == "available") {
                    FilledTonalButton(onClick = { viewModel.downloadTake(take.takeId) }) {
                        Text(stringResource(R.string.session_download_verify))
                    }
                }
                if (engineer && take.state == "verified") {
                    StatusRow(ok = true, text = if (take.localPath == null) stringResource(R.string.session_verified_here)
                    else stringResource(R.string.session_verified_here_path, take.localPath))
                }
            }
        }
    }
}

@Composable
internal fun LinkCard(ui: SessionUiState) {
    SectionCard(stringResource(R.string.session_link_title)) {
        StatusRow(ok = ui.connected, text = if (ui.connected) stringResource(R.string.session_connected) else stringResource(R.string.session_connecting))
        Text(
            stringResource(
                R.string.session_link_stats,
                (ui.rttMs.takeIf { it >= 0 } ?: -1).let { if (it < 0) "?" else it.toString() },
                ui.jitterMs,
                ui.sessionState,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        ui.peerName?.let {
            Text(stringResource(R.string.session_artist_name, it), style = MaterialTheme.typography.bodySmall)
        }
    }
}

