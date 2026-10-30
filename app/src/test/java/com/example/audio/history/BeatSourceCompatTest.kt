package com.example.audio.history

import com.example.audio.model.BeatSource
import com.example.audio.model.ProjectSession
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ProjectSession` gained an optional `beatSource` when NC Phone Beat import landed.
 * Sessions written by older builds have no such key: they must keep loading, and the
 * new field must round-trip. Uses the exact Moshi setup ProjectPersistence uses.
 */
class BeatSourceCompatTest {

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(ProjectSession::class.java)

    @Test
    fun `session json without beatSource still loads`() {
        val oldJson = """
            {"version":1,"projectName":"Old Session","revision":7,"sampleRate":48000,
             "tempoBpm":128.0,"totalLengthFrames":48000,"masterGainDb":-1.5,
             "tracks":[{"index":0,"name":"Kick","colorHex":16711680,"clips":[]}]}
        """.trimIndent()
        val session = adapter.fromJson(oldJson)
        assertNotNull(session)
        assertEquals("Old Session", session!!.projectName)
        assertEquals(128f, session.tempoBpm, 1e-6f)
        assertEquals(1, session.tracks.size)
        assertNull("old sessions must load without a beat source", session.beatSource)
    }

    @Test
    fun `beat source round-trips through the session adapter`() {
        val source = BeatSource(
            schema = "ncbeat.v1",
            appVersion = "0.3.0",
            patternName = "round trip",
            tempoBpm = 140f,
            patternJson = """{"schema":"drum.v1"}""",
            mixerJson = """{"version":2}""",
            exportedAt = "2026-10-27T00:00:00Z",
        )
        val session = ProjectSession(projectName = "Beat Session", beatSource = source)
        val restored = adapter.fromJson(adapter.toJson(session))

        assertNotNull(restored)
        val beat = restored!!.beatSource
        assertNotNull(beat)
        assertEquals(source, beat)
        assertEquals("Beat Session", restored.projectName)
    }
}