package com.example

import com.example.observability.StudioAnalytics
import com.example.observability.StudioDeepLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioObservabilityTest {

    @After
    fun tearDown() {
        StudioAnalytics.reset()
    }

    @Test
    fun countsEventsWithoutLosingConcurrentIncrements() {
        repeat(5) { StudioAnalytics.track("app.open") }
        StudioAnalytics.track("deeplink.open")
        StudioAnalytics.track("")
        assertEquals(mapOf("app.open" to 5, "deeplink.open" to 1), StudioAnalytics.analyticsSnapshot())
    }

    @Test
    fun resetClearsTheSnapshot() {
        StudioAnalytics.track("app.open")
        StudioAnalytics.reset()
        assertTrue(StudioAnalytics.analyticsSnapshot().isEmpty())
    }

    @Test
    fun acceptsTheOpenDeepLinkAndItsSurface() {
        assertTrue(StudioDeepLink.isOpenLink("ncstudio://open"))
        assertTrue(StudioDeepLink.isOpenLink("NCStudio://open/arrangement"))
        assertEquals("arrangement", StudioDeepLink.surface("ncstudio://open/arrangement"))
        assertNull(StudioDeepLink.surface("ncstudio://open"))
    }

    @Test
    fun rejectsForeignOrMalformedLinks() {
        assertFalse(StudioDeepLink.isOpenLink(null))
        assertFalse(StudioDeepLink.isOpenLink(""))
        assertFalse(StudioDeepLink.isOpenLink("https://open/arrangement"))
        assertFalse(StudioDeepLink.isOpenLink("ncstudio://mixer"))
        assertFalse(StudioDeepLink.isOpenLink("not a uri"))
        assertNull(StudioDeepLink.surface("ncstudio://mixer"))
    }
}
