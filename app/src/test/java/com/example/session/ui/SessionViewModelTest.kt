package com.example.session.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.session.net.ConnectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionViewModelTest {

    private fun viewModel(): SessionViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("pulse_ui_prefs", 0).edit().clear().commit()
        return SessionViewModel(app)
    }

    @Test
    fun roleSelectionAndInputNormalization() {
        val viewModel = viewModel()

        viewModel.chooseRole(ConnectionSettings.ROLE_ENGINEER)
        assertEquals(SessionStage.ENGINEER_SETUP, viewModel.ui.value.stage)
        assertEquals(ConnectionSettings.ROLE_ENGINEER, viewModel.ui.value.role)

        viewModel.chooseRole(ConnectionSettings.ROLE_ARTIST)
        assertEquals(SessionStage.ARTIST_SETUP, viewModel.ui.value.stage)
        assertEquals(ConnectionSettings.ROLE_ARTIST, viewModel.ui.value.role)

        viewModel.updateInviteInput("ab12cd34ef")
        assertEquals("AB12CD34", viewModel.ui.value.inviteInput)

        viewModel.updateDisplayName("Alex")
        assertEquals("Alex", viewModel.ui.value.displayName)

        viewModel.updateServerHost("10.0.0.2:8787")
        assertEquals("10.0.0.2:8787", viewModel.ui.value.serverHost)
    }

    @Test
    fun joiningWithoutAnInviteIsRejectedBeforeAnyNetworkWork() {
        val viewModel = viewModel()
        viewModel.chooseRole(ConnectionSettings.ROLE_ARTIST)

        viewModel.joinAsArtist()

        assertEquals("Enter the invite code from the engineer", viewModel.ui.value.error)
        assertFalse(viewModel.ui.value.busy)
        assertEquals(SessionStage.ARTIST_SETUP, viewModel.ui.value.stage)
    }
}
