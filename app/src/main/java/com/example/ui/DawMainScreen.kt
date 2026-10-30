package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.audio.model.DawSettings
import com.example.audio.model.EditMode
import com.example.audio.model.ProjectSession
import com.example.audio.model.TimelineTool
import com.example.ui.theme.Radius
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.io.File
import com.example.ui.theme.OnAccent
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun audioContentUri(context: Context, file: File): Uri? = runCatching {
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

private fun shareAudioFile(context: Context, file: File) {
    if (!file.exists()) return
    val uri = audioContentUri(context, file) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }.onFailure {
        Toast.makeText(context, "No app can share this file", Toast.LENGTH_SHORT).show()
    }
}

private fun shareAudioFiles(context: Context, files: List<File>) {
    val existing = files.filter { it.exists() }
    if (existing.isEmpty()) return
    if (existing.size == 1) {
        shareAudioFile(context, existing.first())
        return
    }
    val uris = ArrayList(existing.mapNotNull { audioContentUri(context, it) })
    if (uris.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "audio/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share ${existing.size} stems"))
    }.onFailure {
        Toast.makeText(context, "No app can share these files", Toast.LENGTH_SHORT).show()
    }
}

private fun openAudioFile(context: Context, file: File) {
    if (!file.exists()) return
    val uri = audioContentUri(context, file) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "audio/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open ${file.name}"))
    }.onFailure {
        Toast.makeText(context, "No app can open this audio file", Toast.LENGTH_SHORT).show()
    }
}

enum class DawTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    MIXER(R.string.tab_mixer, Icons.Default.Tune),
    TIMELINE(R.string.tab_timeline, Icons.Default.GraphicEq),
    INSPECTOR(R.string.tab_inspector, Icons.Default.MusicNote),
    AI_STUDIO(R.string.tab_ai_studio, Icons.Default.AutoAwesome),
    SETTINGS(R.string.tab_settings, Icons.Default.Settings)
}

@Composable
fun DawMainScreen(
    viewModel: DawViewModel,
    modifier: Modifier = Modifier
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val projectState by viewModel.projectState.collectAsStateWithLifecycle()
    val features by viewModel.features.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val usbDevices by viewModel.usbDevices.collectAsStateWithLifecycle()
    val activeUsbDevice by viewModel.activeUsbDevice.collectAsStateWithLifecycle()

    var activeTab by rememberSaveable(
        stateSaver = Saver<DawTab, String>(
            save = { it.name },
            restore = { DawTab.valueOf(it) }
        )
    ) { mutableStateOf(DawTab.MIXER) }
    var showOverflow by remember { mutableStateOf(false) }
    var showSharedLibrary by remember { mutableStateOf(false) }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isWide = screenWidthDp >= 700
    val isThreePane = screenWidthDp >= 840
    val tabStateHolder = rememberSaveableStateHolder()
    val context = LocalContext.current
    val shortcutsFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { shortcutsFocus.requestFocus() }
    }

    // Audio file picker launcher for stem upload / import
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importAudioFile(uri, uiState.selectedTrackIndex)
            viewModel.showImportDialog(false)
        }
    }

    // NC Phone Beat (.ncbeat) package picker
    val ncbeatPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importNcbeat(uri)
            viewModel.showImportDialog(false)
        }
    }

    val exportFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.setExportDestination(uri)
    }

    // A/B reference picker: load a commercial mix for level matching
    val referencePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.loadReferenceTrack(uri)
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

    // Keep transient status messages from becoming permanent UI furniture.
    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage ?: return@LaunchedEffect
        delay(if (uiState.statusKind == StatusKind.ERROR) 8_000L else 4_500L)
        viewModel.clearStatusMessage()
    }

    BackHandler(enabled = activeTab != DawTab.MIXER) {
        activeTab = DawTab.MIXER
    }

    LaunchedEffect(activeTab) {
        if (activeTab == DawTab.SETTINGS) viewModel.refreshCacheSize()
    }

    // Real presentation timings from the window feed the session-health card.
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, viewModel) {
        val tracker = FramePacingTracker()
        val handler = Handler(Looper.getMainLooper())
        var framesSinceReport = 0
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            tracker.record(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION))
            framesSinceReport++
            if (framesSinceReport >= 120) {
                framesSinceReport = 0
                tracker.snapshot()?.let {
                    viewModel.reportUiFrameTiming(it.meanMs, it.p95Ms)
                }
            }
        }
        activity?.window?.addOnFrameMetricsAvailableListener(listener, handler)
        onDispose {
            activity?.window?.removeOnFrameMetricsAvailableListener(listener)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(shortcutsFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (uiState.showImportDialog || uiState.showExportDialog ||
                    uiState.showUsbDialog || uiState.showClipBin || uiState.showDspTestDialog
                ) return@onKeyEvent false
                when (event.key) {
                    Key.Spacebar -> {
                        viewModel.togglePlayPause()
                        true
                    }
                    Key.R -> {
                        onRecordClick()
                        true
                    }
                    Key.L -> {
                        viewModel.toggleLoop()
                        true
                    }
                    Key.S -> {
                        viewModel.stop()
                        true
                    }
                    // Pro Tools edit commands.
                    Key.B -> {
                        viewModel.separateAtPlayhead()
                        true
                    }
                    Key.E -> if (event.isCtrlPressed || event.isMetaPressed) {
                        viewModel.separateAtSelection()
                        true
                    } else false
                    Key.H -> if (event.isCtrlPressed || event.isMetaPressed) {
                        viewModel.healSelectedClip()
                        true
                    } else false
                    Key.F -> if (event.isCtrlPressed || event.isMetaPressed) {
                        viewModel.crossfadeSelectedClips()
                        true
                    } else false
                    Key.Delete, Key.Backspace -> {
                        viewModel.deleteSelectedClips()
                        true
                    }
                    Key.DirectionLeft -> {
                        viewModel.nudgeSelection(forward = false, byBar = event.isShiftPressed)
                        true
                    }
                    Key.DirectionRight -> {
                        viewModel.nudgeSelection(forward = true, byBar = event.isShiftPressed)
                        true
                    }
                    Key.Escape -> {
                        viewModel.clearEditSelection()
                        true
                    }
                    else -> false
                }
            }
            .testTag("daw_main_screen"),
        containerColor = StudioBackground,
        topBar = {
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
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(NeonCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = OnAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.pulse_daw),
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!uiState.presentationMode) {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.top_bar_meta,
                                        session.tracks.size,
                                        session.tracks.size,
                                        session.sampleRate / 1000
                                    ),
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { viewModel.showUsbDialog(true) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("usb_interface_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = stringResource(R.string.usb_audio_interface),
                            tint = if (activeUsbDevice != null) ElectricLime else TextSecondary
                        )
                    }

                    IconButton(
                        onClick = { viewModel.showImportDialog(true) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("upload_audio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = stringResource(R.string.import_audio),
                            tint = NeonCyan
                        )
                    }

                    IconButton(
                        onClick = { viewModel.showExportDialog(true) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("export_wav_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = stringResource(R.string.export_audio),
                            tint = ElectricLime
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showOverflow = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("more_actions_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_actions),
                                tint = TextSecondary
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_new), color = TextPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = TextSecondary)
                                },
                                onClick = {
                                    showOverflow = false
                                    viewModel.newProject()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_open), color = TextPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextSecondary)
                                },
                                onClick = {
                                    showOverflow = false
                                    viewModel.showProjectDialog(ProjectDialogMode.OPEN)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_save), color = TextPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Default.Save, contentDescription = null, tint = TextSecondary)
                                },
                                onClick = {
                                    showOverflow = false
                                    viewModel.saveCurrentProject()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.project_save_as), color = TextPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Default.Save, contentDescription = null, tint = TextSecondary)
                                },
                                onClick = {
                                    showOverflow = false
                                    viewModel.showProjectDialog(ProjectDialogMode.SAVE_AS)
                                }
                            )
                            if (!uiState.presentationMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.run_diagnostics), color = TextPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Science,
                                            contentDescription = null,
                                            tint = TextSecondary
                                        )
                                    },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.runDspUnitTests()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (uiState.presentationMode) "Exit presentation mode" else "Presentation mode",
                                        color = TextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (uiState.presentationMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = TextSecondary
                                    )
                                },
                                onClick = {
                                    showOverflow = false
                                    viewModel.togglePresentationMode()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_what_s_new), color = TextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.NewReleases,
                                        contentDescription = null,
                                        tint = TextSecondary
                                    )
                                },
                                onClick = {
                                    showOverflow = false
                                    viewModel.showWhatsNew(true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.studio_settings), color = TextPrimary) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = TextSecondary
                                    )
                                },
                                onClick = {
                                    showOverflow = false
                                    activeTab = DawTab.SETTINGS
                                }
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bottom_tab_bar"),
                containerColor = StudioSurface,
                tonalElevation = 6.dp
            ) {
                DawTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(tab.titleRes),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            indicatorColor = StudioCard,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        ),
                        modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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
                onCalibrateLatency = { viewModel.runLatencyCalibration() },
                onSetPunchIn = { viewModel.setPunchIn() },
                onSetPunchOut = { viewModel.setPunchOut() },
                onClearPunch = { viewModel.clearPunchPoints() },
                onToggleAb = { viewModel.toggleAbComparison() },
                onLoadReference = { referencePickerLauncher.launch("audio/*") },
                onMatchToReference = { viewModel.matchToReference() },
                hasReferenceTrack = uiState.hasReferenceTrack,
                onDismissStatus = { viewModel.clearStatusMessage() },
                onStatusAction = {
                    viewModel.undo()
                    viewModel.clearStatusMessage()
                },
                showDiagnostics = !uiState.presentationMode,
                hapticsEnabled = settings.enableHapticFeedback
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val currentTab = activeTab
                tabStateHolder.SaveableStateProvider(currentTab.name) {
                    when (currentTab) {
                        DawTab.MIXER -> {
                            MixerPane(
                                viewModel = viewModel,
                                session = session,
                                uiState = uiState,
                                settings = settings,
                                onSelectTrack = { idx ->
                                    viewModel.selectTrack(idx)
                                    activeTab = DawTab.INSPECTOR
                                }
                            )
                        }
                        DawTab.TIMELINE -> {
                            if (isThreePane) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.weight(1.5f)) {
                                        TimelinePane(viewModel, session, uiState, settings)
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        InspectorPane(viewModel, session, uiState)
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        MixerPane(
                                            viewModel = viewModel,
                                            session = session,
                                            uiState = uiState,
                                            settings = settings,
                                            onSelectTrack = { idx -> viewModel.selectTrack(idx) }
                                        )
                                    }
                                }
                            } else if (isWide) {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.weight(1.6f)) {
                                        TimelinePane(viewModel, session, uiState, settings)
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        InspectorPane(viewModel, session, uiState)
                                    }
                                }
                            } else {
                                TimelinePane(viewModel, session, uiState, settings)
                            }
                        }
                        DawTab.INSPECTOR -> InspectorPane(viewModel, session, uiState)
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
                                onSendQueryToLocalModel = { prompt -> viewModel.sendPromptToLocalModel(prompt) },
                                onStartVoiceCommand = { viewModel.startVoiceCommand() },
                                onStopVoiceCommand = { viewModel.stopVoiceCommand() },
                                onEngineerTextCommand = { request -> viewModel.runEngineerRequest(request) },
                                onToggleReferenceAudition = { viewModel.toggleAbComparison() },
                                onRefreshReferenceMatch = { viewModel.refreshReferenceMatch() },
                                onApplyReferenceMoves = { viewModel.applyReferenceMoves() },
                                onClearReference = { viewModel.clearReference() }
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
                                onNavigateBack = { activeTab = DawTab.MIXER }
                            )
                        }
                    }
                }
            }
        }
    }

    if (uiState.showOnboarding) {
        StudioOnboardingDialog(onDismiss = { viewModel.dismissOnboarding() })
    }

    if (uiState.showWhatsNew) {
        StudioWhatsNewDialog(onDismiss = { viewModel.dismissWhatsNew() })
    }

    // USB Audio Interface Dialog
    if (uiState.showUsbDialog) {
        UsbAudioInterfaceDialog(
            devices = usbDevices,
            activeDevice = activeUsbDevice,
            onSelectDevice = { devId -> viewModel.selectUsbDevice(devId) },
            onRefresh = { viewModel.refreshUsbDevices() },
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
            onLaunchNcbeat = { ncbeatPickerLauncher.launch(arrayOf("*/*")) },
            onLaunchSharedLibrary = { showSharedLibrary = true },
            onDismiss = { viewModel.showImportDialog(false) },
            isImporting = uiState.isImporting,
            errorMessage = uiState.importError
        )
    }

    if (showSharedLibrary) {
        SharedLibraryDialog(
            onPick = { uri ->
                viewModel.importAudioFile(uri, uiState.selectedTrackIndex)
                viewModel.showImportDialog(false)
                showSharedLibrary = false
            },
            onDismiss = { showSharedLibrary = false },
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
            errorMessage = uiState.exportError,
            sampleRate = session.sampleRate,
            durationSeconds = session.totalLengthFrames.toFloat() / session.sampleRate,
            stemFiles = uiState.exportedStemFiles,
            destinationName = uiState.exportDestinationName,
            onChooseDestination = { exportFolderLauncher.launch(null) },
            onStartExport = { fmt -> viewModel.renderSessionToAudio(fmt) },
            onStartStemsExport = { viewModel.renderStemsExport() },
            onShareFile = { file -> shareAudioFile(context, file) },
            onOpenFile = { file -> openAudioFile(context, file) },
            onShareAllStems = { shareAudioFiles(context, uiState.exportedStemFiles) },
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

    if (projectState.showDialog) {
        ProjectDialog(
            state = projectState,
            onDismiss = { viewModel.dismissProjectDialog() },
            onOpen = { file -> viewModel.openProject(file) },
            onSaveAs = { name -> viewModel.saveProjectAs(name) },
            onNewProject = { viewModel.newProject() },
        )
    }
}
