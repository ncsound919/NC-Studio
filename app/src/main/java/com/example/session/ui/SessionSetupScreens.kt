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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun RoleChoice(onArtist: () -> Unit, onEngineer: () -> Unit) {
    SectionCard(stringResource(R.string.session_who_are_you)) {
        Button(onClick = onArtist, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.session_role_artist))
        }
        FilledTonalButton(onClick = onEngineer, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.session_role_engineer))
        }
    }
}

@Composable
internal fun EngineerSetup(viewModel: SessionViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SectionCard(stringResource(R.string.session_create_title)) {
        ServerField(ui.serverHost, viewModel::updateServerHost)
        OutlinedTextField(
            value = ui.displayName,
            onValueChange = viewModel::updateDisplayName,
            label = { Text(stringResource(R.string.session_name_optional)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::createSession,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.session_create_action, if (ui.busy) "…" else "Create"))
        }
    }
}

@Composable
internal fun ArtistSetup(viewModel: SessionViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SectionCard(stringResource(R.string.session_join_title)) {
        ServerField(ui.serverHost, viewModel::updateServerHost)
        OutlinedTextField(
            value = ui.inviteInput,
            onValueChange = viewModel::updateInviteInput,
            label = { Text(stringResource(R.string.session_invite_code)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ui.displayName,
            onValueChange = viewModel::updateDisplayName,
            label = { Text(stringResource(R.string.session_name_optional)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::joinAsArtist,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.session_join_action, if (ui.busy) "…" else "Join"))
        }
    }
}

@Composable
internal fun ArtistPreflight(
    ui: SessionUiState,
    viewModel: SessionViewModel,
    backingTrackLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    micPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    val report = ui.preflight
    SectionCard(stringResource(R.string.session_preflight_title)) {
        CheckRow(stringResource(R.string.session_check_mic), report?.micPermission == true) {
            Button(onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text(stringResource(R.string.session_grant))
            }
        }
        CheckRow(stringResource(R.string.session_check_headphones), report?.headphones == true) {
            Text(stringResource(R.string.session_connect_headphones), style = MaterialTheme.typography.bodySmall)
        }
        CheckRow(
            stringResource(R.string.session_check_storage, PreflightPolicy.formatFree(report?.freeBytes ?: -1)),
            (report?.freeBytes ?: 0) >= PreflightPolicy.MIN_FREE_BYTES,
        ) {
            Text(
                stringResource(R.string.session_storage_needs, PreflightPolicy.MIN_FREE_BYTES / (1024 * 1024)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        CheckRow(stringResource(R.string.session_check_calibration), report?.calibrationOffsetFrames != null) {
            Button(onClick = viewModel::runCalibration, enabled = !ui.busy) { Text(stringResource(R.string.session_calibrate)) }
        }
        CheckRow(
            stringResource(
                R.string.session_check_network,
                (ui.rttMs.takeIf { it >= 0 } ?: -1).let { if (it < 0) "?" else it.toString() },
                ((report?.network?.uploadKbps ?: -1).takeIf { it >= 0 } ?: -1).let { if (it < 0) "?" else it.toString() },
            ),
            report?.network?.rttMs?.let { it in 0..PreflightPolicy.GOOD_RTT_MS } == true,
        ) {
            Button(onClick = viewModel::runSpeedProbe) { Text(stringResource(R.string.session_test)) }
        }
        if (ui.calibrationOffsetFrames != null) {
            Text(
                stringResource(R.string.session_offset_frames, ui.calibrationOffsetFrames),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        report?.problems?.forEach { problem ->
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = { backingTrackLauncher.launch("audio/*") }, modifier = Modifier.fillMaxWidth()) {
            Text(ui.backingTrackName?.let { stringResource(R.string.session_backing_chosen, it) }
                ?: stringResource(R.string.session_backing_choose))
        }
        Button(
            onClick = viewModel::artistGoLive,
            enabled = report?.passed == true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.session_ready_to_record))
        }
        TextButton(onClick = viewModel::refreshPreflight, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.session_recheck))
        }
    }
}

