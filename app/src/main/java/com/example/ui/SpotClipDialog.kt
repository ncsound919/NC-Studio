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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.R
import com.example.audio.model.MasterPreset
import com.example.audio.model.PresetLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.engine.OfflineRenderer
import com.example.ui.theme.Radius
import com.example.ui.theme.ClipRed
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
import java.io.File
import java.util.Locale
import com.example.ui.theme.OnAccent


/**
 * Pro Tools style Spot dialog: type the time a clip should land on and it is
 * placed there exactly, with no grid or magnet snap. The field accepts
 * mm:ss.cc, mm:ss or plain seconds, and the read-back underneath shows the
 * resulting clip position before it is committed.
 */
@Composable
fun SpotClipDialog(
    clipName: String,
    startFrame: Long,
    clipLengthFrames: Long,
    sampleRate: Int,
    onDismiss: () -> Unit,
    onSpot: (frame: Long, placeByEnd: Boolean) -> Unit
) {
    var placeByEnd by remember { mutableStateOf(false) }
    var text by remember {
        mutableStateOf(Timecode.format(startFrame + if (placeByEnd) clipLengthFrames else 0L, sampleRate))
    }
    val parsedFrame = Timecode.parse(text, sampleRate)
    val landedStart = parsedFrame?.let { frame ->
        if (placeByEnd) (frame - clipLengthFrames).coerceAtLeast(0L) else frame
    }

    fun switchAnchor(byEnd: Boolean) {
        if (byEnd == placeByEnd) return
        placeByEnd = byEnd
        val anchor = startFrame + if (byEnd) clipLengthFrames else 0L
        text = Timecode.format(anchor, sampleRate)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.spot_clip_title),
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = clipName,
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.spot_clip_description),
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to "Start", true to "End").forEach { (byEnd, label) ->
                        val active = placeByEnd == byEnd
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (active) NeonCyan.copy(alpha = 0.22f) else StudioCard)
                                .border(
                                    1.dp,
                                    if (active) NeonCyan else StudioCardBorder,
                                    RoundedCornerShape(Radius.md)
                                )
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                    switchAnchor(byEnd)
                                }
                                .testTag(if (byEnd) "spot_anchor_end" else "spot_anchor_start"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label.uppercase(),
                                color = if (active) NeonCyan else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = {
                        Text(
                            text = if (placeByEnd) "End time (mm:ss.cc)" else "Start time (mm:ss.cc)",
                            fontSize = 11.sp
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("spot_time_field")
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (parsedFrame == null) {
                    Text(
                        text = stringResource(R.string.spot_clip_hint),
                        color = StudioAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("spot_time_error")
                    )
                } else {
                    Text(
                        text = "Clip will start at ${Timecode.format(landedStart ?: 0L, sampleRate)}",
                        color = ElectricLime,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("spot_time_preview")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { parsedFrame?.let { onSpot(it, placeByEnd) } },
                enabled = parsedFrame != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = StudioBackground
                ),
                modifier = Modifier.testTag("spot_confirm_button")
            ) {
                Text(text = stringResource(R.string.label_spot), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("spot_cancel_button")
            ) {
                Text(text = stringResource(R.string.spot_cancel), color = TextSecondary, fontSize = 12.sp)
            }
        }
    )
}
