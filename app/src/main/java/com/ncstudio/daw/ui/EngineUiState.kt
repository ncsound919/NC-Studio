package com.ncstudio.daw.ui

import com.ncstudio.daw.audio.ai.LocalModelDetectorState
import com.ncstudio.daw.audio.ai.VoiceEngineerState
import com.ncstudio.daw.audio.engine.OfflineRenderer
import com.ncstudio.daw.audio.model.AudioSourceFormat
import com.ncstudio.daw.audio.model.AudioTelemetry
import com.ncstudio.daw.audio.model.EditSelection
import com.ncstudio.daw.audio.reference.ReferenceMatchReport
import com.ncstudio.daw.audio.reference.ReferenceProfile
import java.io.File

data class EngineUiState(
    val isPlaying: Boolean = false,
    val isRecording: Boolean = false,
    val punchInFrame: Long? = null,
    val punchOutFrame: Long? = null,
    val isInputMonitoringEnabled: Boolean = false,
    val playheadFrame: Long = 0L,
    val totalFrames: Long = 48000L * 16L,
    val timeFormatted: String = "00:00.00",
    val trackPeaks: FloatArray = FloatArray(16),
    val trackGainReductions: FloatArray = FloatArray(16),
    val masterPeakL: Float = 0f,
    val masterPeakR: Float = 0f,
    val momentaryRmsDb: Float = -60f,
    val latestRmsDb: Float = -60f,
    val telemetry: AudioTelemetry = AudioTelemetry(),
    val selectedTrackIndex: Int = 0,
    val selectedClipIds: Set<String> = emptySet(),
    val editSelection: EditSelection? = null,
    val isAbComparing: Boolean = false,
    val hasReferenceTrack: Boolean = false,
    val abMode: String = "A",
    val isReferenceAuditioning: Boolean = false,
    val isReferenceWorking: Boolean = false,
    val referenceName: String = "",
    val referenceProfile: ReferenceProfile? = null,
    val mixProfile: ReferenceProfile? = null,
    val referenceMatch: ReferenceMatchReport? = null,
    val isAnalyzingAi: Boolean = false,
    val localModelDetectorState: LocalModelDetectorState = LocalModelDetectorState(),
    val showDspTestDialog: Boolean = false,
    val showExportDialog: Boolean = false,
    val showUsbDialog: Boolean = false,
    val showImportDialog: Boolean = false,
    val showClipBin: Boolean = false,
    val dspTestResults: List<OfflineRenderer.DspTestResult> = emptyList(),
    val exportProgress: Float = 0f,
    val exportedFile: File? = null,
    val exportedStemFiles: List<File> = emptyList(),
    val exportError: String? = null,
    val cacheSizeBytes: Long = 0L,
    val exportDestinationName: String = "App storage / exports",
    val uiFrameMeanMs: Float = 0f,
    val uiFrameP95Ms: Float = 0f,
    val showOnboarding: Boolean = false,
    val showWhatsNew: Boolean = false,
    val presentationMode: Boolean = false,
    val isImporting: Boolean = false,
    val importError: String? = null,
    val importSourceFormat: AudioSourceFormat = AudioSourceFormat.STEMS,
    val voiceEngineer: VoiceEngineerState = VoiceEngineerState(),
    val statusMessage: String? = null,
    val statusKind: StatusKind = StatusKind.INFO,
    val statusAction: StatusAction? = null
)
