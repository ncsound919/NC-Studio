package com.example.audio.history

import com.example.audio.model.StemCategory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Tests the NC Phone Beat -> NC Studio interchange reader against the golden fixture
 * produced by NC Phone Beat's own writer (`NcbeatFixtureTest` in the Phone Beat repo),
 * plus the hostile-input rules from NCBEAT_FORMAT.md.
 */
@RunWith(RobolectricTestRunner::class)
class NcbeatImporterTest {

    private fun readFixture(): NcbeatImporter.Package {
        val stream = javaClass.classLoader!!.getResourceAsStream("ncbeat_v1.ncbeat")
            ?: error("golden fixture ncbeat_v1.ncbeat missing from test resources")
        return stream.use { NcbeatImporter.read(it) }
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun readBytes(bytes: ByteArray): NcbeatImporter.Package =
        NcbeatImporter.read(ByteArrayInputStream(bytes))

    private val validManifest =
        """{"schema":"ncbeat.v1","app":"nc-phone-beat","appVersion":"0.3.0",""" +
            """"exportedAt":"2026-10-27T00:00:00Z","tempoBpm":120,"sampleRate":48000,"channels":2,""" +
            """"patternSchema":"drum.v1","patternName":"unit beat","grid":"1/16","lengthSteps":16,""" +
            """"loopLengthFrames":96000,"trackCount":1}"""

    private val validPattern =
        """{"schema":"drum.v1","name":"unit beat","grid":"1/16","length_steps":16,"swing":0,"steps":[]}"""

    private val validMixer =
        """{"version":2,"bpm":120,"master":1.0,"fx":[0,0,0,0,0,0,0,0,0],"song":[],"bank":0,""" +
            """"tracks":[{"id":0,"type":0,"gain":1.0,"pan":0.0,"mute":false,"solo":false,"params":[]}]}"""

    private fun readExpectingFailure(vararg entries: Pair<String, ByteArray>): NcbeatException {
        try {
            readBytes(zipBytes(*entries))
        } catch (e: NcbeatException) {
            return e
        }
        fail("expected NcbeatException")
        throw AssertionError("unreachable")
    }

    // ------------------------------------------------------- golden fixture

    @Test
    fun `reads the golden package written by NC Phone Beat`() {
        val pkg = readFixture()

        assertEquals("ncbeat.v1", pkg.manifest.schema)
        assertEquals("nc-phone-beat", pkg.manifest.app)
        assertEquals("0.3.0", pkg.manifest.appVersion)
        assertEquals(300f, pkg.manifest.tempoBpm, 1e-6f)
        assertEquals("1/32", pkg.manifest.grid)
        assertEquals(4, pkg.manifest.lengthSteps)
        assertEquals(4_800L, pkg.manifest.loopLengthFrames)
        assertEquals("fixture beat", pkg.manifest.patternName)
        assertEquals("drum.v1", pkg.manifest.patternSchema)

        assertTrue(pkg.patternJson.contains("\"drum.v1\""))
        assertEquals(2, pkg.mixer.tracks.size)
        assertEquals(0, pkg.mixer.tracks[0].id)
        assertEquals(4, pkg.mixer.tracks[1].id)
        assertEquals(0.75f, pkg.mixer.tracks[1].gain, 1e-6f)
        assertEquals(-0.2f, pkg.mixer.tracks[1].pan, 1e-6f)
        assertEquals(24, pkg.mixer.tracks[1].params.size)

        assertEquals(
            setOf("stems/master.wav", "stems/track_0.wav", "stems/track_4.wav"),
            pkg.stems.keys,
        )
        assertEquals(setOf("samples/pad03.wav"), pkg.samples.keys)
        // Stem payloads are real WAV headers, not placeholders.
        assertTrue(String(pkg.stems["stems/master.wav"]!!.copyOfRange(0, 4)) == "RIFF")
    }

    @Test
    fun `plans the native session mapping from the fixture`() {
        val plan = NcbeatImporter.importPlan(readFixture())

        assertEquals(300f, plan.tempoBpm, 1e-6f)
        assertEquals(0f, plan.masterGainDb, 1e-6f)       // master gain 1.0 -> 0 dB
        assertNull(plan.masterFallbackEntry)             // real track stems exist
        assertEquals(0, plan.droppedTracks)

        assertEquals(2, plan.tracks.size)
        val kit = plan.tracks[0]
        assertEquals(0, kit.sessionTrackIndex)
        assertEquals("Kit", kit.name)
        assertEquals(StemCategory.DRUMS, kit.category)
        assertEquals(0f, kit.gainDb, 1e-6f)
        assertEquals("stems/track_0.wav", kit.stemEntry)

        val bass = plan.tracks[1]
        assertEquals(1, bass.sessionTrackIndex)
        assertEquals("Bass", bass.name)
        assertEquals(StemCategory.BASS, bass.category)
        assertEquals(-2.4987f, bass.gainDb, 0.001f)      // 20*log10(0.75)
        assertEquals(-0.2f, bass.pan, 1e-6f)
        assertEquals("stems/track_4.wav", bass.stemEntry)

        val src = plan.beatSource
        assertEquals("ncbeat.v1", src.schema)
        assertEquals("fixture beat", src.patternName)
        assertEquals(300f, src.tempoBpm, 1e-6f)
        assertTrue(src.patternJson.contains("\"drum.v1\""))
        assertTrue(src.mixerJson.contains("\"tracks\""))
        assertEquals("2026-10-27T00:00:00Z", src.exportedAt)
    }

    // ------------------------------------------------------- rejection rules

    @Test
    fun `rejects a foreign schema and a foreign producer app`() {
        val wrongSchema = readExpectingFailure(
            "manifest.json" to validManifest.replace("ncbeat.v1", "ncbeat.v9").toByteArray(),
            "pattern.json" to validPattern.toByteArray(),
            "mixer.json" to validMixer.toByteArray(),
        )
        assertTrue(wrongSchema.message!!.contains("Unsupported package schema"))

        val wrongApp = readExpectingFailure(
            "manifest.json" to validManifest.replace("nc-phone-beat", "other-app").toByteArray(),
            "pattern.json" to validPattern.toByteArray(),
            "mixer.json" to validMixer.toByteArray(),
        )
        assertTrue(wrongApp.message!!.contains("other-app"))
    }

    @Test
    fun `rejects missing required entries`() {
        val missingMixer = readExpectingFailure(
            "manifest.json" to validManifest.toByteArray(),
            "pattern.json" to validPattern.toByteArray(),
        )
        assertTrue(missingMixer.message!!.contains("mixer.json"))

        val missingManifest = readExpectingFailure(
            "pattern.json" to validPattern.toByteArray(),
            "mixer.json" to validMixer.toByteArray(),
        )
        assertTrue(missingManifest.message!!.contains("manifest.json"))
    }

    @Test
    fun `rejects path traversal and absolute entry names`() {
        val traversal = readExpectingFailure(
            "../evil.json" to "{}".toByteArray(),
            "manifest.json" to validManifest.toByteArray(),
            "pattern.json" to validPattern.toByteArray(),
            "mixer.json" to validMixer.toByteArray(),
        )
        assertTrue(traversal.message!!.contains("Unsafe entry name"))

        val absolute = readExpectingFailure(
            "/etc/passwd" to "x".toByteArray(),
            "manifest.json" to validManifest.toByteArray(),
        )
        assertTrue(absolute.message!!.contains("Unsafe entry name"))
    }

    @Test
    fun `rejects oversized json entries before parsing`() {
        val huge = ByteArray(4 * 1024 * 1024 + 1024) { 'a'.code.toByte() }
        val e = readExpectingFailure(
            "manifest.json" to huge,
            "pattern.json" to validPattern.toByteArray(),
            "mixer.json" to validMixer.toByteArray(),
        )
        assertTrue(e.message!!.contains("larger than"))
    }

    @Test
    fun `rejects malformed manifest and mixer json`() {
        val badJson = readExpectingFailure(
            "manifest.json" to "not json".toByteArray(),
            "pattern.json" to validPattern.toByteArray(),
            "mixer.json" to validMixer.toByteArray(),
        )
        assertTrue(badJson.message!!.contains("manifest.json"))

        val badMixer = readExpectingFailure(
            "manifest.json" to validManifest.toByteArray(),
            "pattern.json" to validPattern.toByteArray(),
            "mixer.json" to "[1,2,3]".toByteArray(),
        )
        assertTrue(badMixer.message!!.contains("mixer.json"))
    }

    // ------------------------------------------------------- planning rules

    @Test
    fun `clamps insane manifest values instead of trusting them`() {
        val pkg = readBytes(
            zipBytes(
                "manifest.json" to validManifest
                    .replace("\"tempoBpm\":120", "\"tempoBpm\":9999")
                    .replace("\"lengthSteps\":16", "\"lengthSteps\":500")
                    .toByteArray(),
                "pattern.json" to validPattern.toByteArray(),
                "mixer.json" to validMixer.toByteArray(),
            ),
        )
        assertEquals(300f, pkg.manifest.tempoBpm, 1e-6f)
        assertEquals(64, pkg.manifest.lengthSteps)
        assertEquals(300f, NcbeatImporter.importPlan(pkg).tempoBpm, 1e-6f)
    }

    @Test
    fun `falls back to the master stem when a package carries no track stems`() {
        val twoTracks =
            """{"version":2,"bpm":100,"master":0.5,"tracks":""" +
                """[{"id":0,"type":0,"gain":1.0,"pan":0.0,"mute":false,"solo":false,"params":[]},""" +
                """{"id":4,"type":4,"gain":1.0,"pan":0.0,"mute":true,"solo":false,"params":[]}]}"""
        val pkg = readBytes(
            zipBytes(
                "manifest.json" to validManifest.toByteArray(),
                "pattern.json" to validPattern.toByteArray(),
                "mixer.json" to twoTracks.toByteArray(),
                "stems/master.wav" to ByteArray(64),
            ),
        )
        val plan = NcbeatImporter.importPlan(pkg)
        assertEquals("stems/master.wav", plan.masterFallbackEntry)
        assertEquals(2, plan.tracks.size)
        assertNull(plan.tracks[0].stemEntry)
        assertNull(plan.tracks[1].stemEntry)
        // Mixer semantics survive even without audio: names, mute, master level.
        assertEquals("Kit", plan.tracks[0].name)
        assertEquals("Bass", plan.tracks[1].name)
        assertTrue(plan.tracks[1].muted)
        assertEquals(-6.0206f, plan.masterGainDb, 0.001f)   // 20*log10(0.5)
    }

    @Test
    fun `more than 16 mixer tracks are capped and counted`() {
        val many = (0 until 20).joinToString(",") { i ->
            """{"id":$i,"type":0,"gain":1.0,"pan":0.0,"mute":false,"solo":false,"params":[]}"""
        }
        val pkg = readBytes(
            zipBytes(
                "manifest.json" to validManifest.toByteArray(),
                "pattern.json" to validPattern.toByteArray(),
                "mixer.json" to """{"version":2,"bpm":120,"master":1.0,"tracks":[$many]}""".toByteArray(),
                "stems/master.wav" to ByteArray(64),
            ),
        )
        val plan = NcbeatImporter.importPlan(pkg)
        assertEquals(16, plan.tracks.size)
        assertEquals(4, plan.droppedTracks)
        // Duplicate names get disambiguated instead of colliding.
        assertEquals("Kit", plan.tracks[0].name)
        assertEquals("Kit 15", plan.tracks[15].name)
    }

    @Test
    fun `manifest file table is not trusted for entry existence`() {
        // files{} claiming stems that are not in the archive must not break reading.
        val lying = validManifest.replace(
            "\"loopLengthFrames\":96000",
            "\"loopLengthFrames\":96000,\"files\":{\"stems\":[\"stems/ghost.wav\"]}",
        )
        val pkg = readBytes(
            zipBytes(
                "manifest.json" to lying.toByteArray(),
                "pattern.json" to validPattern.toByteArray(),
                "mixer.json" to validMixer.toByteArray(),
            ),
        )
        assertTrue(pkg.stems.isEmpty())
        assertNull(NcbeatImporter.importPlan(pkg).masterFallbackEntry)
        assertNotNull(NcbeatImporter.importPlan(pkg).beatSource)
    }
}