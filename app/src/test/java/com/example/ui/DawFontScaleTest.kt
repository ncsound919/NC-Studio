package com.example.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class DawFontScaleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainSurfacesFitAtLargeFontScales() {
        ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        val viewModel = DawViewModel(ApplicationProvider.getApplicationContext())
        viewModel.dismissOnboarding()
        viewModel.dismissWhatsNew()
        var fontScale by mutableFloatStateOf(1.3f)

        composeTestRule.setContent {
            MyApplicationTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale)
                ) {
                    DawMainScreen(viewModel)
                }
            }
        }

        composeTestRule.onNodeWithTag("bottom_tab_bar").assertIsDisplayed()
        val rootWidth = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot.right

        composeTestRule.runOnIdle { fontScale = 2f }
        composeTestRule.waitForIdle()

        listOf("tab_mixer", "tab_timeline", "tab_inspector", "tab_ai_studio", "tab_settings")
            .forEach { tag ->
                val node = composeTestRule.onNodeWithTag(tag)
                node.assertIsDisplayed()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue("$tag starts off-screen", bounds.left >= 0f)
                assertTrue("$tag ends off-screen", bounds.right <= rootWidth + 1f)
            }

        composeTestRule.onNodeWithTag("tab_timeline").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("timeline_view").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
    }
}
