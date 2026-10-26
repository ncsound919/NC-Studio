package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.audio.model.AudioClip
import com.example.audio.model.DawSettings
import com.example.audio.model.ProjectSession
import com.example.audio.model.TrackData
import com.example.ui.theme.MyApplicationTheme

private val previewSession = ProjectSession(
    tracks = List(4) { index ->
        TrackData(
            index = index,
            name = listOf("Kick", "Snare", "Hi-Hats", "Vocal")[index],
            colorHex = longArrayOf(0xFFFF5722, 0xFFFF9800, 0xFFFFEB3B, 0xFF00E5FF)[index],
            clips = listOf(
                AudioClip(
                    name = "Take ${index + 1}",
                    sourcePath = "preview_take.wav",
                    lengthFrames = 48_000L * 3,
                    timelineStartFrame = 48_000L * index,
                    trackIndex = index
                )
            )
        )
    }
)

private val previewUiState = EngineUiState(
    isPlaying = true,
    playheadFrame = 56_000L,
    totalFrames = previewSession.totalLengthFrames,
    timeFormatted = "00:01.17"
)

@Preview(name = "Transport 360", widthDp = 360, heightDp = 220, showBackground = true)
@Preview(name = "Transport 360 @ 2x", widthDp = 360, heightDp = 260, fontScale = 2f, showBackground = true)
@Composable
private fun TransportPreview() {
    MyApplicationTheme {
        TransportBar(
            session = previewSession,
            uiState = previewUiState,
            canUndo = true,
            canRedo = false,
            onTogglePlay = {},
            onStop = {},
            onToggleRecord = {},
            onToggleLoop = {},
            onUndo = {},
            onRedo = {},
            onCalibrateLatency = {},
            hasReferenceTrack = true
        )
    }
}

@Preview(name = "Timeline 360", widthDp = 360, heightDp = 640, showBackground = true)
@Preview(name = "Timeline 360 @ 1.3x", widthDp = 360, heightDp = 700, fontScale = 1.3f, showBackground = true)
@Preview(name = "Timeline tablet", widthDp = 800, heightDp = 1280, showBackground = true)
@Composable
private fun TimelinePreview() {
    MyApplicationTheme {
        TimelineView(
            session = previewSession,
            uiState = previewUiState,
            settings = DawSettings(),
            onSeekFraction = {},
            onSelectTrack = {},
            onSplitClipAtPlayhead = {},
            onTrimClipEnd = { _, _ -> },
            onDuplicateClip = { _, _ -> },
            onOpenClipBin = {}
        )
    }
}

@Preview(name = "Mixer 360", widthDp = 360, heightDp = 640, showBackground = true)
@Preview(name = "Mixer 800", widthDp = 800, heightDp = 500, showBackground = true)
@Composable
private fun MixerPreview() {
    MyApplicationTheme {
        MixerView(
            session = previewSession,
            uiState = previewUiState,
            onSelectTrack = {},
            onSetTrackGain = { _, _ -> },
            onSetTrackPan = { _, _ -> },
            onToggleMute = {},
            onToggleSolo = {},
            onToggleArm = {},
            onSetReverbSend = { _, _ -> },
            onSetMasterGain = {}
        )
    }
}

@Preview(name = "Settings 360 @ 1.3x", widthDp = 360, heightDp = 800, fontScale = 1.3f, showBackground = true)
@Preview(name = "Settings 360 @ 2x", widthDp = 360, heightDp = 900, fontScale = 2f, showBackground = true)
@Preview(name = "Settings tablet", widthDp = 800, heightDp = 1280, showBackground = true)
@Composable
private fun SettingsPreview() {
    MyApplicationTheme {
        SettingsScreen(
            settings = DawSettings(),
            uiState = previewUiState,
            onUpdateSettings = {},
            onCalibrateLatency = {},
            onRunDspTests = {},
            onOpenUsbDialog = {},
            onClearCache = {},
            onResetDefaults = {},
            onNavigateBack = {}
        )
    }
}
