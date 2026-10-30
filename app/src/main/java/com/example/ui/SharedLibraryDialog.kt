package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.audio.library.SharedSoundLibrary
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.overlay.audio.library.LibraryCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Searches the shared `Music/NC Sound` tree without importing it first. The
 * index is cached, so opening this after the first scan is instant; the file
 * itself is only read when the user picks it.
 */
@Composable
fun SharedLibraryDialog(
    onPick: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val helper = remember { SharedSoundLibrary(context) }
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf(helper.loadCache()) }
    var scanning by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<LibraryCategory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun scan(uri: Uri? = helper.treeUri) {
        if (uri == null || scanning) return
        scanning = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { helper.scan() }.getOrNull() }
            index = result ?: index
            if (result == null) error = "Could not read that folder. Pick it again and allow access."
            scanning = false
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            helper.rememberTree(uri)
            scan(uri)
        }
    }

    LaunchedEffect(Unit) {
        if (index == null && helper.treeUri != null) scan()
    }

    val results = remember(index, query, category) {
        index?.search(query, category, limit = 200) ?: emptyList()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(StudioSurface)
                .border(1.dp, StudioCardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SHARED SOUND LIBRARY",
                        color = ElectricLime,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        helper.rootName + "  ·  " + (index?.entries?.size ?: 0) + " files",
                        color = TextMuted,
                        fontSize = 10.sp,
                    )
                }
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = NeonCyan, strokeWidth = 2.dp)
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search", fontSize = 10.sp) },
                modifier = Modifier.fillMaxWidth().testTag("shared_library_search"),
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CategoryChip("ALL", category == null) { category = null }
                LibraryCategory.entries.forEach { cat ->
                    CategoryChip(cat.label, category == cat) { category = cat }
                }
            }

            if (error != null) {
                Text(error!!, color = androidx.compose.ui.graphics.Color(0xFFFF6B6B), fontSize = 10.sp)
            }

            LazyColumn(Modifier.fillMaxWidth().height(260.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(results, key = { it.path }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioCard)
                            .clickable {
                                val uri = helper.uriFor(entry)
                                if (uri != null) {
                                    onPick(uri)
                                    onDismiss()
                                } else {
                                    error = "That file is no longer accessible. Rescan the folder."
                                }
                            }
                            .padding(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(2.dp)).background(NeonCyan))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                entry.category.label + "  ·  " + entry.path,
                                color = TextMuted,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        val duration = entry.durationSec
                        if (duration != null) {
                            Text(
                                "%.1fs".format(duration),
                                color = TextSecondary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("CHOOSE FOLDER") { folderLauncher.launch(helper.treeUri) }
                ActionButton("RESCAN", enabled = helper.treeUri != null && !scanning) { scan() }
                Spacer(Modifier.weight(1f))
                ActionButton("CLOSE") { onDismiss() }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) ElectricLime else TextSecondary,
        fontSize = 9.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) ElectricLime.copy(alpha = 0.15f) else StudioCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) NeonCyan else TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(StudioCard)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}
