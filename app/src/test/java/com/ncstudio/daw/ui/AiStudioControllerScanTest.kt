package com.ncstudio.daw.ui

import com.ncstudio.daw.audio.ai.EndpointProbeResult
import com.ncstudio.daw.audio.ai.LocalModelDetector
import com.ncstudio.daw.audio.ai.LocalModelEndpoint
import com.ncstudio.daw.audio.model.DawSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan has two phases and only the user may pay for the second one: a
 * 254-host x 3-port sweep on every launch is exactly what this pins down.
 */
class AiStudioControllerScanTest {

    @Test
    fun automaticScanProbesThisPhoneOnlyAndNeverSweepsTheWifiSubnet() {
        val probes = mutableListOf<LocalModelEndpoint>()
        val sweeps = mutableListOf<String>()
        val state = MutableStateFlow(EngineUiState())
        val controller = controller(state, probes, sweeps)

        controller.scan(dispatcher = Dispatchers.Unconfined)

        assertEquals("the startup scan must not touch the Wi-Fi subnet", emptyList<String>(), sweeps)
        assertFalse(state.value.localModelDetectorState.isScanning)
        assertTrue(state.value.localModelDetectorState.scannedSubnet.isEmpty())
        assertEquals(LocalModelDetector.DEFAULT_ENDPOINTS.size, probes.size)
        assertEquals(
            LocalModelDetector.DEFAULT_ENDPOINTS.size,
            state.value.localModelDetectorState.totalEndpointsToScan,
        )
        assertTrue(
            "every probed endpoint is on this phone or the emulator host",
            probes.all { it.baseUrl.startsWith("http://127.0.0.1:") || it.baseUrl.startsWith("http://10.0.2.2:") },
        )
    }

    @Test
    fun explicitScanSweepsTheWifiSubnetWhenNoServerAnswersOnThePhone() {
        val probes = mutableListOf<LocalModelEndpoint>()
        val sweeps = mutableListOf<String>()
        val state = MutableStateFlow(EngineUiState())
        val controller = controller(state, probes, sweeps)

        controller.scan(deep = true, dispatcher = Dispatchers.Unconfined)

        assertEquals(listOf("192.168.1"), sweeps)
        assertEquals("192.168.1.1-254", state.value.localModelDetectorState.scannedSubnet)
        assertFalse(state.value.localModelDetectorState.isScanning)
        assertTrue(state.value.statusMessage.orEmpty().contains("Wi-Fi (192.168.1.1-254)"))
    }

    private fun controller(
        state: MutableStateFlow<EngineUiState>,
        probes: MutableList<LocalModelEndpoint>,
        sweeps: MutableList<String>,
    ) = AiStudioController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        currentSettings = { DawSettings() },
        updateSettings = {},
        uiState = state,
        lanSubnetPrefix = { "192.168.1" },
        discoverLanEndpoints = { prefix ->
            sweeps.add(prefix)
            emptyList()
        },
        probeEndpoint = { endpoint, _, _ ->
            probes.add(endpoint)
            EndpointProbeResult(endpoint = endpoint, isOnline = false)
        },
    )
}