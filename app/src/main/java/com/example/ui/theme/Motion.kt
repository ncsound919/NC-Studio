package com.example.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Shared motion durations. When the system animator scale is off, durations
 * collapse to zero so state changes are still truthful without movement.
 */
object Motion {
    const val SHORT = 120
    const val MEDIUM = 220
    const val LONG = 360
}

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
}

@Composable
fun <T> motionSpec(durationMs: Int) = tween<T>(
    durationMillis = if (rememberReducedMotion()) 0 else durationMs
)
