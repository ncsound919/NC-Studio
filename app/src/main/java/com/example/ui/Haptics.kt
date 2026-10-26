package com.example.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/** Light tick for a value landing on a detent. */
fun HapticFeedback.studioTick(enabled: Boolean) {
    if (enabled) performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

/** Confirmation for a committed action such as play, record, or drag start. */
fun HapticFeedback.studioConfirm(enabled: Boolean) {
    if (enabled) performHapticFeedback(HapticFeedbackType.LongPress)
}
