package com.example.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import com.example.ui.theme.StudioType
import com.example.ui.theme.TextPrimary

/**
 * Long-press hint for dense icon-only controls. Tooltips supplement, never
 * replace, the accessibility label on the control itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioTooltip(
    text: String,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(text, style = StudioType.caption, color = TextPrimary)
            }
        },
        state = rememberTooltipState(),
        content = content
    )
}
