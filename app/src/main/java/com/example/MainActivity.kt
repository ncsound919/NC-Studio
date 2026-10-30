package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.ui.DawMainScreen
import com.example.observability.StudioAnalytics
import com.example.observability.StudioDeepLink
import com.example.ui.DawViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DawViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        StudioAnalytics.track("app.open")
        handleDeepLink(intent)

        setContent {
            MyApplicationTheme {
                DawMainScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** A deep link is a real entry point, not a no-op: it is counted locally. */
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString
        StudioDeepLink.surface(raw)?.let { StudioAnalytics.track("deeplink.open.$it") }
            ?: run { if (StudioDeepLink.isOpenLink(raw)) StudioAnalytics.track("deeplink.open") }
    }
}
