package com.example

import com.example.ui.STUDIO_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMetadataTest {

    @Test
    fun manifestVersionMatchesTheReleaseNotes() {
        assertEquals(STUDIO_VERSION, BuildConfig.VERSION_NAME)
        assertEquals("1.3.0", BuildConfig.VERSION_NAME)
        assertTrue(BuildConfig.VERSION_CODE >= 130)
        assertEquals("com.ncstudio.daw", BuildConfig.APPLICATION_ID)
    }
}
