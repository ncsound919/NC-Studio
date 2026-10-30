package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.audio.reference.ReferenceProfile
import com.example.audio.reference.ReferenceMatchReport
import com.example.audio.reference.SpectrumBalanceAnalyzer
import com.example.ui.theme.ClipRed
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.MeterRed
import com.example.ui.theme.MeterYellow
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.PanelDeep
import com.example.ui.theme.Radius
import com.example.ui.theme.SurfaceSunken
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.OnAccent

/**
 * The reference A/B card: what the reference measures, what the mix measures,
 * the band-by-band difference, and the bounded moves that would close it.
 * Loading, level matching and applying all go through the ViewModel, so this
 * reads state and reports intent, nothing more.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReferenceAbCard(
    uiState: EngineUiState,
    onToggleAudition: () -> Unit,
    onRefreshMatch: () -> Unit,
    onApplyMoves: () -> Unit,
    onClearReference: () -> Unit
) {
    val reference = uiState.referenceProfile
    val mix = uiState.mixProfile
    val report: ReferenceMatchReport? = uiState.referenceMatch

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ui_reference_ab_title),
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))

        Card(
            modifier = Modifier.fillMaxWidth().testTag("reference_ab_card"),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!uiState.hasReferenceTrack || reference == null) {
                    Text(
                        text = stringResource(R.string.ui_reference_ab_empty),
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    return@Column
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.referenceName.ifBlank { "Reference" },
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.isReferenceWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = StudioAmber
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = stringResource(R.string.ui_reference_ab_clear),
                        color = TextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { onClearReference() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("reference_ab_clear")
                    )
                }

                Text(
                    text = referenceProfileLine(uiState.referenceName, reference.integratedLufs, reference.truePeakDb, reference.widthDb, reference.loudnessRangeLu),
                    color = TextSecondary,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (mix == null) {
                    Text(
                        text = stringResource(R.string.ui_reference_ab_mix_unmeasured),
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                } else {
                    Text(
                        text = referenceProfileLine("", mix.integratedLufs, mix.truePeakDb, mix.widthDb, mix.loudnessRangeLu),
                        color = TextPrimary,
                        fontSize = 10.sp
                    )
                }

                if (report != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.ui_reference_ab_level_delta, abSigned(report.levelDeltaLu)),
                            color = StudioAmber,
                            fontSize = 10.sp
                        )
                        Text(
                            text = stringResource(R.string.ui_reference_ab_width_delta, abSigned(report.widthDeltaDb)),
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.ui_reference_ab_bands_title),
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("reference_ab_bands"),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpectrumBalanceAnalyzer.BAND_LABELS.forEachIndexed { index, label ->
                            val delta = (report.bandDeltasDb.getOrNull(index) ?: 0f).coerceIn(-6f, 6f)
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(18.dp),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    if (delta > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height((delta / 6f * 18f).dp)
                                                .background(StudioAmber)
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(StudioCardBorder)
                                )
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(18.dp),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    if (delta < 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height((-delta / 6f * 18f).dp)
                                                .background(NeonCyan)
                                        )
                                    }
                                }
                                Text(text = label, color = TextMuted, fontSize = 10.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.ui_reference_ab_moves_title),
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (report.moves.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.ui_reference_ab_no_moves),
                            color = ElectricLime,
                            fontSize = 10.sp
                        )
                    } else {
                        report.moves.forEach { move ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = move.title, color = StudioAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(text = move.detail, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onToggleAudition,
                        enabled = uiState.hasReferenceTrack,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isReferenceAuditioning) NeonCyan else StudioAmber
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("reference_ab_audition")
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.isReferenceAuditioning) R.string.ui_reference_ab_listen_reference
                                else R.string.ui_reference_ab_listen_mix
                            ),
                            fontSize = 10.sp
                        )
                    }
                    Button(
                        onClick = onRefreshMatch,
                        enabled = uiState.hasReferenceTrack && !uiState.isReferenceWorking,
                        colors = ButtonDefaults.buttonColors(containerColor = StudioCard),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("reference_ab_recheck")
                    ) {
                        Text(text = stringResource(R.string.ui_reference_ab_recheck), fontSize = 10.sp)
                    }
                    Button(
                        onClick = onApplyMoves,
                        enabled = report?.moves?.isNotEmpty() == true && !uiState.isReferenceWorking,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricLime),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("reference_ab_apply")
                    ) {
                        Text(text = stringResource(R.string.ui_reference_ab_apply), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** "-8.6 LUFS | -1.2 dBTP | width 4.2 dB | range 5.1 LU", with an optional label prefix. */
private fun referenceProfileLine(
    label: String,
    integratedLufs: Float,
    truePeakDb: Float,
    widthDb: Float,
    loudnessRangeLu: Float
): String {
    val body = String.format(
        java.util.Locale.US,
        "%.1f LUFS | %.1f dBTP | width %.1f dB | range %.1f LU",
        integratedLufs,
        truePeakDb,
        widthDb,
        loudnessRangeLu
    )
    return if (label.isBlank()) body else "$label: $body"
}

private fun abSigned(value: Float): String {
    val rounded = kotlin.math.round(value * 10f) / 10f
    return if (rounded > 0f) "+$rounded" else "$rounded"
}
