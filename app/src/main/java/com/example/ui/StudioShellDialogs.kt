package com.example.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.Radius
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioPurple
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

const val STUDIO_VERSION = "1.3.0"
const val RELEASE_NOTES_VERSION = "1.3.0"
const val ONBOARDING_PREF = "onboarding_complete"
const val RELEASE_NOTES_PREF = "whats_new_seen_version"

private data class OnboardingStep(
    val icon: ImageVector,
    val accent: Color,
    val title: String,
    val body: String
)

private val onboardingSteps = listOf(
    OnboardingStep(Icons.Default.Mic, NeonCyan, "Record", "Arm a track, hit record, and punch in only where you need a take."),
    OnboardingStep(Icons.Default.CloudUpload, ElectricLime, "Import", "Bring in WAV, MP3, FLAC, or M4A stems and place them on any track."),
    OnboardingStep(Icons.Default.AutoAwesome, StudioPurple, "Mix", "Balance, pan, EQ, and compress. AI suggestions stay deterministic and bounded."),
    OnboardingStep(Icons.Default.Download, StudioAmber, "Export", "Render a master or individual stems, then share or save to your chosen folder.")
)

@Composable
fun StudioOnboardingDialog(onDismiss: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val current = onboardingSteps[step]

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(StudioSurface, RoundedCornerShape(Radius.xl))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.xl))
                .padding(24.dp)
                .testTag("onboarding_dialog"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StudioEmptyStateIllustration(
                accent = current.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = current.icon,
                contentDescription = null,
                tint = current.accent,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(current.title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = current.body,
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                onboardingSteps.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == step) 18.dp else 6.dp, 6.dp)
                            .background(
                                if (index == step) current.accent else StudioCardBorder,
                                CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    if (step == onboardingSteps.lastIndex) onDismiss() else step++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_primary_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = current.accent,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = if (step == onboardingSteps.lastIndex) "Start making music" else "Next",
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("onboarding_skip_button")
            ) {
                Text(stringResource(R.string.ui_skip_tour), color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

private data class ReleaseNote(val title: String, val body: String)

private val releaseNotes = listOf(
    ReleaseNote("Drag clips directly", "Long-press a clip to move it on the timeline. The new overview strip shows the whole session and your visible window."),
    ReleaseNote("Choose where exports go", "Pick any folder with the system picker. Pulse remembers it and writes masters and stems there."),
    ReleaseNote("Know when the engine is struggling", "Settings now summarizes callback headroom, underruns, and UI frame pacing in one health card."),
    ReleaseNote("Fewer hidden gestures", "Dense timeline icons explain themselves on long-press, with light haptics on snap, drag, and transport actions."),
    ReleaseNote("A real first run", "New installs get a four-step tour; release notes appear once per version.")
)

@Composable
fun StudioWhatsNewDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(StudioSurface, RoundedCornerShape(Radius.xl))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.xl))
                .padding(24.dp)
                .testTag("whats_new_dialog")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.ui_what_s_new), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("NC Studio $STUDIO_VERSION", color = TextMuted, fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .height(300.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                releaseNotes.forEach { note ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .background(ElectricLime, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(note.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(note.body, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("whats_new_dismiss_button"),
                border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
            ) {
                Text(stringResource(R.string.ui_got_it), color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Studio-palette empty state: a quiet waveform over a fader track. */
@Composable
fun StudioEmptyStateIllustration(
    modifier: Modifier = Modifier,
    accent: Color = NeonCyan
) {
    Canvas(modifier = modifier.testTag("empty_state_illustration")) {
        val barCount = 22
        val gap = size.width * 0.012f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val heights = floatArrayOf(
            0.18f, 0.32f, 0.55f, 0.78f, 0.46f, 0.24f, 0.6f, 0.88f, 0.52f, 0.3f, 0.7f,
            0.95f, 0.62f, 0.36f, 0.5f, 0.8f, 0.44f, 0.26f, 0.58f, 0.4f, 0.22f, 0.14f
        )
        heights.forEachIndexed { index, fraction ->
            val barHeight = size.height * 0.62f * fraction
            drawRoundRect(
                color = accent.copy(alpha = 0.24f + fraction * 0.55f),
                topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
            )
        }
        drawLine(
            color = accent.copy(alpha = 0.5f),
            start = Offset(0f, size.height * 0.9f),
            end = Offset(size.width, size.height * 0.9f),
            strokeWidth = 1.5f
        )
        drawCircle(color = StudioCard, radius = 7f, center = Offset(size.width * 0.68f, size.height * 0.9f))
        drawCircle(color = accent, radius = 7f, center = Offset(size.width * 0.68f, size.height * 0.9f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
    }
}
