package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.audio.model.DawSettings
import com.example.audio.model.ProjectSession

@Composable
internal fun MixerPane(
    viewModel: DawViewModel,
    session: ProjectSession,
    uiState: EngineUiState,
    settings: DawSettings,
    onSelectTrack: (Int) -> Unit
) {
    MixerView(
        session = session,
        uiState = uiState,
        onSelectTrack = onSelectTrack,
        onSetTrackGain = { idx, g -> viewModel.setTrackGain(idx, g) },
        onSetTrackPan = { idx, p -> viewModel.setTrackPan(idx, p) },
        onToggleMute = { idx -> viewModel.toggleTrackMute(idx) },
        onToggleSolo = { idx -> viewModel.toggleTrackSolo(idx) },
        onToggleArm = { idx -> viewModel.toggleTrackArm(idx) },
        onSetReverbSend = { idx, s -> viewModel.setTrackReverbSend(idx, s) },
        onSetMasterGain = { g -> viewModel.setMasterGain(g) },
        onApplyMasterPreset = { preset -> viewModel.applyMasterPreset(preset) },
        hapticsEnabled = settings.enableHapticFeedback
    )
}

@Composable
internal fun TimelinePane(
    viewModel: DawViewModel,
    session: ProjectSession,
    uiState: EngineUiState,
    settings: DawSettings
) {
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
        onSetTimelineTool = { tool -> viewModel.setTimelineTool(tool) },
        onSetEditMode = { mode -> viewModel.setEditMode(mode) },
        onTrimClipToFrame = { trackIdx, clipId, isStartEdge, frame, tolerance ->
            viewModel.beginClipGesture(trackIdx, if (isStartEdge) "Trim clip start" else "Trim clip end")
            if (isStartEdge) {
                viewModel.trimClipStartToFrame(trackIdx, clipId, frame, tolerance)
            } else {
                viewModel.trimClipEndToFrame(trackIdx, clipId, frame, tolerance)
            }
        },
        onEditSelectionChange = { trackIdx, start, end ->
            if (start < 0L || end < 0L) viewModel.clearEditSelection()
            else viewModel.setEditSelection(trackIdx, start, end)
        },
        onClearEditSelection = { viewModel.clearEditSelection() },
        onBeginClipGesture = { trackIdx, description ->
            viewModel.beginClipGesture(trackIdx, description)
        },
        onEndClipGesture = { viewModel.endClipGesture() },
        onHealClip = { trackIdx, clipId -> viewModel.healSeparation(trackIdx, clipId) },
        onCrossfadeClips = { viewModel.crossfadeSelectedClips() },
        onSeparateAtSelection = { viewModel.separateAtSelection() },
        onClearSelectedClips = { viewModel.deleteSelectedClips() },
        onSpotClipAtFrame = { trackIdx, clipId, frame, placeByEnd ->
            viewModel.spotClipAtFrame(trackIdx, clipId, frame, placeByEnd)
        },
        onSetLoopRange = { start, end -> viewModel.setLoopRange(start, end) },
        onSetPunchRange = { start, end -> viewModel.setPunchRange(start, end) },
        onToggleClipSelection = { trackIdx, clipId -> viewModel.toggleClipSelection(trackIdx, clipId) },
        onClearClipSelection = { viewModel.clearClipSelection() },
        onNudgeClip = { trackIdx, clipId, fwd -> viewModel.nudgeSelectedClip(trackIdx, clipId, fwd) },
        onSetClipGain = { trackIdx, clipId, gain -> viewModel.setClipGain(trackIdx, clipId, gain) },
        onSetClipFadeIn = { trackIdx, clipId, ms, curve -> viewModel.setClipFadeIn(trackIdx, clipId, ms, curve) },
        onSetClipFadeOut = { trackIdx, clipId, ms, curve -> viewModel.setClipFadeOut(trackIdx, clipId, ms, curve) },
        onPasteChorusSection = { trackIdx, clipId, bars -> viewModel.pasteClipAsChorusSection(trackIdx, clipId, bars) },
        onPasteChorusDouble = { trackIdx, clipId, targetTrack -> viewModel.pasteClipAsDoubledChorus(trackIdx, clipId, targetTrack) },
        onAdaptChorusLoop = { trackIdx, clipId, rpts -> viewModel.adaptClipToChorusLoop(trackIdx, clipId, rpts) },
        onStripSilence = { settings, mode -> viewModel.stripSilenceSelectedClips(settings, mode) }
    )
}

@Composable
internal fun InspectorPane(
    viewModel: DawViewModel,
    session: ProjectSession,
    uiState: EngineUiState
) {
    val selTrack = session.tracks.getOrNull(uiState.selectedTrackIndex)
        ?: session.tracks.firstOrNull()
        ?: return
    val gr = uiState.trackGainReductions.getOrElse(selTrack.index) { 0f }
    var showPluginRack by remember { mutableStateOf(false) }

    if (showPluginRack) {
        PluginRackDialog(
            trackName = selTrack.name,
            chain = selTrack.pluginChain,
            outputBusId = selTrack.outputBusId,
            gainReductionProvider = { viewModel.trackSlotGainReductions(selTrack.index) },
            onChainChange = { chain -> viewModel.updateTrackPluginChain(selTrack.index, chain) },
            onOutputBusChange = { busId -> viewModel.setTrackOutputBus(selTrack.index, busId) },
            onApplyTemplateAll = { templateId -> viewModel.applyMixTemplate(templateId) },
            onApplyTemplateRole = { role, templateId ->
                viewModel.applyTemplateRoleToTrack(selTrack.index, role, templateId)
            },
            onDismiss = { showPluginRack = false }
        )
    }

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
        onUpdateVocalRider = { cfg -> viewModel.updateTrackVocalRider(selTrack.index, cfg) },
        onUpdateResonanceSmoother = { cfg -> viewModel.updateTrackResonanceSmoother(selTrack.index, cfg) },
        onApplyPreset = { preset -> viewModel.applyTrackPreset(selTrack.index, preset) },
        onOpenPluginRack = { showPluginRack = true },
        masterReverb = session.masterReverb,
        onUpdateMasterReverb = { rev -> viewModel.updateMasterReverb(rev) }
    )
}
