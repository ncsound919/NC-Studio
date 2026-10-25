package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
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

enum class DawTab(val title: String, val icon: ImageVector) {
    MIXER("Mixer", Icons.Default.Tune),
    TIMELINE("Timeline", Icons.Default.GraphicEq),
    INSPECTOR("Inspector", Icons.Default.MusicNote),
    AI_STUDIO("AI Studio", Icons.Default.AutoAwesome),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun DawMainScreen(
    viewModel: DawViewModel,
    modifier: Modifier = Modifier
) {
    val session by viewModel.session.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val features by viewModel.features.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val usbDevices by viewModel.usbDevices.collectAsState()
    val activeUsbDevice by viewModel.activeUsbDevice.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    // Audio file picker launcher for stem upload / import
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importAudioFile(uri, uiState.selectedTrackIndex)
            viewModel.showImportDialog(false)
        }
    }

    // Audio recording permission launcher
    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        }
    }

    val onRecordClick = {
        if (uiState.isRecording) {
            viewModel.stopRecording()
        } else {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                viewModel.startRecording()
            } else {
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("daw_main_screen"),
        containerColor = StudioBackground,
        topBar = {
            // Top App Bar with Branding & Quick Tools
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("top_bar"),
                color = StudioSurface,
                tonalElevation = 4.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
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
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(NeonCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "PULSE DAW",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(ElectricLime.copy(alpha = 0.2f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("16-TRACK OBOE", color = ElectricLime, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(
                                text = "Revision #${session.revision} • 48 kHz 32-bit Float",
                                color = TextMuted,
                                fontSize = 9.sp
                            )
                        }
                    }

                    // Top Action Tools: USB Interface, File Import, DSP Unit Tests, WAV Render
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // USB Type-C Interface Button
                        IconButton(
                            onClick = { viewModel.showUsbDialog(true) },
                            modifier = Modifier.testTag("usb_interface_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Usb,
                                contentDescription = "USB Audio Interface",
                                tint = if (activeUsbDevice != null) ElectricLime else TextSecondary
                            )
                        }

                        // Upload / Import Audio Stem Button
                        IconButton(
                            onClick = { viewModel.showImportDialog(true) },
                            modifier = Modifier.testTag("upload_audio_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "Upload Audio Stem",
                                tint = NeonCyan
                            )
                        }

                        // DSP Regression Test Runner Button
                        IconButton(
                            onClick = { viewModel.runDspUnitTests() },
                            modifier = Modifier.testTag("run_dsp_tests_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = "Run DSP Tests",
                                tint = StudioAmber
                            )
                        }

                        // Export to WAV Button
                        IconButton(
                            onClick = { viewModel.showExportDialog(true) },
                            modifier = Modifier.testTag("export_wav_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Export WAV",
                                tint = ElectricLime
                            )
                        }

                        // Studio Settings Button
                        IconButton(
                            onClick = { activeTab = DawTab.SETTINGS.ordinal },
                            modifier = Modifier.testTag("open_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Studio Settings",
                                tint = if (activeTab == DawTab.SETTINGS.ordinal) NeonCyan else TextSecondary
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            // Main Navigation Tab Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bottom_tab_bar"),
                color = StudioSurface,
                tonalElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    DawTab.values().forEachIndexed { index, tab ->
                        val isSelected = activeTab == index
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) StudioCard else Color.Transparent)
                                .clickable { activeTab = index }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                .testTag("tab_${tab.title.lowercase()}"),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) NeonCyan else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tab.title,
                                color = if (isSelected) NeonCyan else TextMuted,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Pinned Transport Bar
            TransportBar(
                session = session,
                uiState = uiState,
                canUndo = viewModel.canUndo(),
                canRedo = viewModel.canRedo(),
                onTogglePlay = { viewModel.togglePlayPause() },
                onStop = { viewModel.stop() },
                onToggleRecord = onRecordClick,
                onToggleLoop = { viewModel.toggleLoop() },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onCalibrateLatency = { viewModel.runLatencyCalibration() }
            )

            // Dynamic Tab Views
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (DawTab.values()[activeTab]) {
                    DawTab.MIXER -> {
                        MixerView(
                            session = session,
                            uiState = uiState,
                            onSelectTrack = { idx ->
                                viewModel.selectTrack(idx)
                                activeTab = 2 // jump to inspector
                            },
                            onSetTrackGain = { idx, g -> viewModel.setTrackGain(idx, g) },
                            onSetTrackPan = { idx, p -> viewModel.setTrackPan(idx, p) },
                            onToggleMute = { idx -> viewModel.toggleTrackMute(idx) },
                            onToggleSolo = { idx -> viewModel.toggleTrackSolo(idx) },
                            onToggleArm = { idx -> viewModel.toggleTrackArm(idx) },
                            onSetReverbSend = { idx, s -> viewModel.setTrackReverbSend(idx, s) },
                            onSetMasterGain = { g -> viewModel.setMasterGain(g) },
                            onApplyMasterPreset = { preset -> viewModel.applyMasterPreset(preset) }
                        )
                    }
                    DawTab.TIMELINE -> {
                        TimelineView(
                            session = session,
                            uiState = uiState,
                            settings = settings,
                            onSeekFraction = { f -> viewModel.seekToFraction(f) },
                            onSelectTrack = { idx -> viewModel.selectTrack(idx) },
                            onSplitClipAtPlayhead = { idx -> viewModel.splitClipAtPlayhead(idx) },
                            onTrimClipEnd = { idx, id -> viewModel.trimClipEnd(idx, id, 48000L) },
                            onDuplicateClip = { idx, id -> viewModel.duplicateClip(idx, id) },
                            onOpenClipBin = { viewModel.showClipBin(true) },
                            onToggleMagneticSnap = { viewModel.toggleMagneticSnap() },
                            onCycleGridResolution = { viewModel.cycleGridResolution() },
                            onNudgeClip = { trackIdx, clipId, fwd -> viewModel.nudgeSelectedClip(trackIdx, clipId, fwd) },
                            onSetClipGain = { trackIdx, clipId, gain -> viewModel.setClipGain(trackIdx, clipId, gain) },
                            onSetClipFadeIn = { trackIdx, clipId, ms, curve -> viewModel.setClipFadeIn(trackIdx, clipId, ms, curve) },
                            onSetClipFadeOut = { trackIdx, clipId, ms, curve -> viewModel.setClipFadeOut(trackIdx, clipId, ms, curve) },
                            onPasteChorusSection = { trackIdx, clipId, bars -> viewModel.pasteClipAsChorusSection(trackIdx, clipId, bars) },
                            onPasteChorusDouble = { trackIdx, clipId, targetTrack -> viewModel.pasteClipAsDoubledChorus(trackIdx, clipId, targetTrack) },
                            onAdaptChorusLoop = { trackIdx, clipId, rpts -> viewModel.adaptClipToChorusLoop(trackIdx, clipId, rpts) }
                        )
                    }
                    DawTab.INSPECTOR -> {
                        val selTrack = session.tracks.getOrNull(uiState.selectedTrackIndex)
                            ?: session.tracks.first()
                        val gr = uiState.trackGainReductions.getOrElse(selTrack.index) { 0f }

                        TrackInspector(
                            track = selTrack,
                            gainReductionDb = gr,
                            onUpdateEq = { eq -> viewModel.updateTrackEq(selTrack.index, eq) },
                            onUpdateSaturation = { sat -> viewModel.updateTrackSaturation(selTrack.index, sat) },
                            onUpdateCompressor = { comp -> viewModel.updateTrackCompressor(selTrack.index, comp) },
                            onUpdateChorus = { cho -> viewModel.updateTrackChorus(selTrack.index, cho) },
                            onUpdateDelay = { delay -> viewModel.updateTrackDuckingDelay(selTrack.index, delay) },
                            onUpdateLimiter = { lim -> viewModel.updateTrackLimiter(selTrack.index, lim) },
                            onUpdateReverbSend = { send -> viewModel.setTrackReverbSend(selTrack.index, send) },
                            onUpdateAutoPitch = { cfg -> viewModel.updateTrackAutoPitch(selTrack.index, cfg) },
                            onUpdateNoiseReduction = { cfg -> viewModel.updateTrackNoiseReduction(selTrack.index, cfg) },
                            masterReverb = session.masterReverb,
                            onUpdateMasterReverb = { rev -> viewModel.updateMasterReverb(rev) }
                        )
                    }
                    DawTab.AI_STUDIO -> {
                        AiMixingStudio(
                            session = session,
                            uiState = uiState,
                            features = features,
                            suggestions = suggestions,
                            onRefreshAnalysis = { viewModel.refreshAudioAnalysis() },
                            onApplyAllSuggestions = { viewModel.applyAllSuggestions() },
                            onToggleAbCompare = { viewModel.toggleAbCompare() },
                            onNaturalLanguagePrompt = { p -> viewModel.handleNaturalLanguageMixPrompt(p) },
                            onScanLocalModels = { url -> viewModel.scanForLocalModels(url) },
                            onSelectLocalModel = { model -> viewModel.selectLocalModel(model) },
                            onSendQueryToLocalModel = { prompt -> viewModel.sendPromptToLocalModel(prompt) }
                        )
                    }
                    DawTab.SETTINGS -> {
                        SettingsScreen(
                            settings = settings,
                            uiState = uiState,
                            onUpdateSettings = { s -> viewModel.updateSettings(s) },
                            onCalibrateLatency = { viewModel.runLatencyCalibration() },
                            onRunDspTests = { viewModel.runDspUnitTests() },
                            onOpenUsbDialog = { viewModel.showUsbDialog(true) },
                            onClearCache = { viewModel.clearScratchCache() },
                            onResetDefaults = { viewModel.resetSettingsToDefaults() },
                            onNavigateBack = { activeTab = DawTab.MIXER.ordinal }
                        )
                    }
                }
            }
        }
    }

    // Settings Screen Dialog (if opened via dialog action)
    if (uiState.showSettingsDialog) {
        Dialog(
            onDismissRequest = { viewModel.showSettingsDialog(false) },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            SettingsScreen(
                settings = settings,
                uiState = uiState,
                onUpdateSettings = { s -> viewModel.updateSettings(s) },
                onCalibrateLatency = { viewModel.runLatencyCalibration() },
                onRunDspTests = { viewModel.runDspUnitTests() },
                onOpenUsbDialog = { viewModel.showUsbDialog(true) },
                onClearCache = { viewModel.clearScratchCache() },
                onResetDefaults = { viewModel.resetSettingsToDefaults() },
                onNavigateBack = { viewModel.showSettingsDialog(false) }
            )
        }
    }

    // USB Audio Interface Dialog
    if (uiState.showUsbDialog) {
        UsbAudioInterfaceDialog(
            devices = usbDevices,
            activeDevice = activeUsbDevice,
            onSelectDevice = { devId -> viewModel.selectUsbDevice(devId) },
            onRefresh = { /* USB manager automatically registers hotplug updates */ },
            onDismiss = { viewModel.showUsbDialog(false) }
        )
    }

    // Audio File Import / Stem Upload Dialog
    if (uiState.showImportDialog) {
        AudioImportDialog(
            tracks = session.tracks,
            selectedTrackIndex = uiState.selectedTrackIndex,
            onSelectTrack = { idx -> viewModel.selectTrack(idx) },
            onLaunchPicker = {
                audioPickerLauncher.launch("audio/*")
            },
            onDismiss = { viewModel.showImportDialog(false) }
        )
    }

    // DSP Unit Test Dialog
    if (uiState.showDspTestDialog) {
        DspTestDialog(
            results = uiState.dspTestResults,
            onDismiss = { viewModel.dismissDspTestDialog() }
        )
    }

    // Export Dialog
    if (uiState.showExportDialog) {
        ExportDialog(
            progress = uiState.exportProgress,
            exportedFile = uiState.exportedFile,
            onStartExport = { fmt -> viewModel.renderSessionToAudio(fmt) },
            onStartStemsExport = { viewModel.renderStemsExport() },
            onDismiss = { viewModel.showExportDialog(false) }
        )
    }

    // Pro Tools Clip List / Region Bin Dialog
    if (uiState.showClipBin) {
        ClipBinDialog(
            session = session,
            selectedTrackIndex = uiState.selectedTrackIndex,
            onDuplicateClip = { trackIdx, clipId -> viewModel.duplicateClip(trackIdx, clipId) },
            onDismiss = { viewModel.showClipBin(false) }
        )
    }
}
