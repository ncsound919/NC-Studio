package com.overlay.audio.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundLibraryTest {

    @Test
    fun `folder layout is stable and covers every workflow stage`() {
        assertEquals(
            listOf("Samples", "Drums", "Loops", "Recordings", "Renders", "Stems", "Kits", "Presets", "Projects"),
            SoundLibraryLayout.folders,
        )
        assertEquals("NC Sound", SoundLibraryLayout.ROOT)
    }

    @Test
    fun `wav header parsing reads rate channels and frame count without decoding`() {
        val header = wavHeader(sampleRate = 48_000, channels = 2, bits = 24, dataBytes = 48_000 * 2 * 3)
        val facts = assertNotNull(SoundLibrary.readWavFacts(header))
        assertEquals(48_000, facts.sampleRate)
        assertEquals(2, facts.channels)
        assertEquals(48_000, facts.frames)
    }

    @Test
    fun `non wav and truncated headers are rejected`() {
        assertNull(SoundLibrary.readWavFacts("OggS....".toByteArray()))
        assertNull(SoundLibrary.readWavFacts(ByteArray(4)))
    }

    @Test
    fun `category comes from the shared folder not the filename`() {
        val kick = assertNotNull(SoundLibrary.entryFor(file("Drums/kick_909.wav")))
        assertEquals(LibraryCategory.DRUMS, kick.category)
        assertEquals(LibraryKind.AUDIO, kick.kind)
        val loop = assertNotNull(SoundLibrary.entryFor(file("Loops/warehouse_124bpm.wav")))
        assertEquals(LibraryCategory.LOOPS, loop.category)
        assertEquals(124f, loop.bpm)
        val take = assertNotNull(SoundLibrary.entryFor(file("Recordings/verse take 3.wav")))
        assertEquals(LibraryCategory.RECORDINGS, take.category)
        val preset = assertNotNull(SoundLibrary.entryFor(file("Presets/vocal_air.ncfx")))
        assertEquals(LibraryCategory.PRESETS, preset.category)
        val project = assertNotNull(SoundLibrary.entryFor(file("Projects/track one.json")))
        assertEquals(LibraryCategory.PROJECTS, project.category)
        assertNull(SoundLibrary.entryFor(file("elsewhere/readme.txt")))
    }

    @Test
    fun `search ranks exact and prefix names above path matches`() {
        val index = SoundLibrary.build(
            "NC Sound",
            listOf(
                file("Samples/kick 909.wav"),
                file("Samples/kick 909 layered.wav"),
                file("Drums/kick 808.wav"),
                file("Loops/warehouse kick loop.wav"),
                file("Recordings/take 1.wav"),
            ),
        )
        val hits = index.search("kick 909")
        assertEquals("kick 909", hits.first().name)
        assertTrue(hits.any { it.name == "kick 909 layered" })
        assertTrue(hits.none { it.name == "take 1" })
        // AND semantics: every query token has to match.
        assertTrue(index.search("kick 909").isNotEmpty())
        assertTrue(index.search("kick nonexistenttoken").isEmpty())
    }

    @Test
    fun `category filter and empty query use recency order`() {
        val index = SoundLibrary.build(
            "NC Sound",
            listOf(
                file("Drums/a.wav", mtime = 100),
                file("Loops/b.wav", mtime = 300),
                file("Drums/c.wav", mtime = 200),
            ),
        )
        val drums = index.search("", LibraryCategory.DRUMS)
        assertEquals(listOf("c", "a"), drums.map { it.name })
        assertEquals(listOf("b", "c", "a"), index.search("").map { it.name })
    }

    @Test
    fun `index round trips through the shared JSON format`() {
        val index = SoundLibrary.build(
            "NC Sound",
            listOf(
                file("Drums/kick.wav"),
                file("Presets/air.ncfx"),
            ),
        )
        val decoded = assertNotNull(SoundLibrary.decode(SoundLibrary.encode(index)))
        assertEquals(index.entries.size, decoded.entries.size)
        assertEquals(index.entries.first().path, decoded.entries.first().path)
        assertNull(SoundLibrary.decode("""{"schema":"wrong","entries":[]}"""))
    }

    @Test
    fun `a large library indexes and answers prefix queries quickly`() {
        val files = (0 until 4_000).map { i ->
            file("Samples/instrument_${i % 40}_take_$i.wav", mtime = i.toLong())
        }
        val start = System.currentTimeMillis()
        val index = SoundLibrary.build("NC Sound", files)
        val hits = index.search("instrument 7")
        val elapsed = System.currentTimeMillis() - start
        assertTrue(hits.isNotEmpty())
        assertTrue(elapsed < 1_000, "indexing 4k files took ${elapsed}ms")
    }

    private fun file(path: String, mtime: Long = 0L): LibraryFile =
        LibraryFile(path = path, name = path.substringAfterLast('/'), sizeBytes = 1024, modifiedAt = mtime)

    private fun wavHeader(sampleRate: Int, channels: Int, bits: Int, dataBytes: Int): ByteArray {
        val fmt = ByteArray(24)
        fmt[0] = 'f'.code.toByte(); fmt[1] = 'm'.code.toByte(); fmt[2] = 't'.code.toByte(); fmt[3] = ' '.code.toByte()
        le32(fmt, 4, 16)
        le16(fmt, 8, 1)
        le16(fmt, 10, channels)
        le32(fmt, 12, sampleRate)
        le32(fmt, 16, sampleRate * channels * bits / 8)
        le16(fmt, 20, channels * bits / 8)
        le16(fmt, 22, bits)
        val data = ByteArray(8)
        data[0] = 'd'.code.toByte(); data[1] = 'a'.code.toByte(); data[2] = 't'.code.toByte(); data[3] = 'a'.code.toByte()
        le32(data, 4, dataBytes)
        val out = ByteArray(12 + fmt.size + data.size)
        "RIFF".toByteArray().copyInto(out, 0)
        le32(out, 4, out.size - 8)
        "WAVE".toByteArray().copyInto(out, 8)
        fmt.copyInto(out, 12)
        data.copyInto(out, 12 + fmt.size)
        return out
    }

    private fun le16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun le32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
