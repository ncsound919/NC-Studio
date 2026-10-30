package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.Radius
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.text.DateFormat
import java.util.Date
import com.example.ui.theme.OnAccent

/**
 * One dialog for both lifecycle actions: Open shows the saved projects on
 * disk, Save As asks for a name. Keeping them together means the current
 * project name and the list come from the same state.
 */
@Composable
fun ProjectDialog(
    state: ProjectUiState,
    onDismiss: () -> Unit,
    onOpen: (File) -> Unit,
    onSaveAs: (String) -> Unit,
    onNewProject: () -> Unit,
) {
    var name by rememberSaveable(state.dialogMode) { mutableStateOf(state.currentName) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(StudioSurface, RoundedCornerShape(Radius.xl))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.xl))
                .padding(20.dp)
                .testTag("project_dialog"),
        ) {
            Text(
                text = stringResource(
                    if (state.dialogMode == ProjectDialogMode.OPEN) R.string.project_dialog_open_title
                    else R.string.project_dialog_save_title,
                ),
                color = TextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.project_current_label, state.currentName),
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))

            if (state.dialogMode == ProjectDialogMode.SAVE_AS) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = { Text(stringResource(R.string.project_name_label), color = TextMuted, fontSize = 11.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricLime,
                        unfocusedBorderColor = StudioCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("project_name_input"),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.project_cancel), color = TextSecondary)
                    }
                    Spacer(Modifier.height(0.dp))
                    Button(
                        onClick = { onSaveAs(name.trim()) },
                        enabled = name.isNotBlank() && !state.isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricLime,
                            contentColor = OnAccent,
                        ),
                        modifier = Modifier.testTag("project_save_button"),
                    ) {
                        Text(
                            text = stringResource(R.string.project_save_action),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else {
                Button(
                    onClick = onNewProject,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCard,
                        contentColor = ElectricLime,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("project_new_button"),
                ) {
                    Text(stringResource(R.string.project_new), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))

                if (state.savedProjects.isEmpty()) {
                    Text(
                        text = stringResource(R.string.project_dialog_empty),
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.savedProjects, key = { it.file.absolutePath }) { project ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(StudioCard, RoundedCornerShape(Radius.md))
                                    .border(1.dp, StudioCardBorder, RoundedCornerShape(Radius.md))
                                    .clickable { onOpen(project.file) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("project_row_${project.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = project.name,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.project_saved_at,
                                            formatProjectTime(project.modifiedAt),
                                        ),
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.project_open_action),
                                    color = ElectricLime,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.project_close), color = TextSecondary)
                    }
                }
            }
        }
    }
}

private fun formatProjectTime(epochMs: Long): String =
    if (epochMs <= 0L) "—"
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
