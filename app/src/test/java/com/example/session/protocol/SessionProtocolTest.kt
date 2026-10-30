package com.example.session.protocol

import com.example.session.take.ChunkInfo
import com.example.session.take.TakeManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionProtocolTest {

    private val codec = MessageCodec()
    private val sid = "session-1"

    private fun roundTrip(message: SessionMessage, seq: Long = 1L): SessionMessage {
        val text = codec.encode(message, sid, seq)
        val result = codec.decode(text)
        assertTrue("decode failed: $result for $text", result is DecodeResult.Ok)
        return (result as DecodeResult.Ok).message
    }

    private fun envelopeFor(
        message: SessionMessage,
        seq: Long,
        sessionId: String = sid,
    ): Pair<Envelope, SessionMessage> {
        val text = codec.encode(message, sessionId, seq)
        val decoded = codec.decode(text) as DecodeResult.Ok
        return decoded.envelope to decoded.message
    }

    // ------------------------------------------------------------- codec

    @Test
    fun commandsAndEventsRoundTrip() {
        assertEquals(Arm(true), roundTrip(Arm(true)))
        assertEquals(StartTake("t1", 960_000), roundTrip(StartTake("t1", 960_000)))
        assertEquals(StopTake, roundTrip(StopTake))
        assertEquals(MarkTake("t1"), roundTrip(MarkTake("t1")))
        assertEquals(SetCueLevel(-12.5f), roundTrip(SetCueLevel(-12.5f)))
        assertEquals(Talkback(true), roundTrip(Talkback(true)))
        assertEquals(Seek(1234L), roundTrip(Seek(1234L)))
        assertEquals(PlayBacking(0L), roundTrip(PlayBacking(0L)))
        assertEquals(Pause, roundTrip(Pause))
        assertEquals(StateChanged("RECORDING", 4), roundTrip(StateChanged("RECORDING", 4)))
        assertEquals(TakeStarted("t1", 480_000), roundTrip(TakeStarted("t1", 480_000)))
        assertEquals(TakeStopped("t1", 96_000, "stop"), roundTrip(TakeStopped("t1", 96_000, "stop")))
        assertEquals(UploadProgress("t1", "uploading", 2, 5), roundTrip(UploadProgress("t1", "uploading", 2, 5)))
        assertEquals(Warning("CLIPPING", "peak 1.02"), roundTrip(Warning("CLIPPING", "peak 1.02")))
        assertEquals(Hello(1, "engineer", listOf("talkback"), 3, "Sam"), roundTrip(Hello(1, "engineer", listOf("talkback"), 3, "Sam")))
        assertEquals(Ping(1000L), roundTrip(Ping(1000L)))
        assertEquals(Pong(1000L, 7L), roundTrip(Pong(1000L, 7L)))
        assertEquals(Bye("done"), roundTrip(Bye("done")))
        assertEquals(Ack(12, false, "WRONG_STATE", "recording"), roundTrip(Ack(12, false, "WRONG_STATE", "recording")))
    }

    @Test
    fun nestedTakeManifestRoundTrips() {
        val manifest = TakeManifest(
            takeId = "t1",
            sessionId = sid,
            trackId = 4,
            sampleRate = 48_000,
            bitDepth = 24,
            channels = 1,
            totalFrames = 480_000,
            startFrame = 96_000,
            calibrationOffsetFrames = -12,
            chunks = listOf(ChunkInfo(0, 2_097_150, "ab".repeat(32))),
            takeSha256 = "cd".repeat(32),
        )
        val decoded = roundTrip(TakeAvailable("t1", manifest)) as TakeAvailable
        assertEquals(manifest, decoded.manifest)
    }

    @Test
    fun snapshotRoundTrips() {
        val snapshot = StateSnapshot(
            state = "ARMED",
            revision = 9,
            armed = true,
            cueLevelDb = -9.5f,
            activeTakeId = "t2",
            monitoring = true,
            storageFreeBytes = 800_000_000,
            lastTakeId = "t1",
            lastTakeFrames = 48_000,
        )
        assertEquals(snapshot, roundTrip(snapshot))
    }

    @Test
    fun malformedInputsAreRejectedWithoutThrowing() {
        val bad = listOf(
            "",
            "not json",
            "{}",
            "[]",
            "null",
            """{"v":2,"sid":"s","seq":1,"type":"Arm","body":{}}""",
            """{"v":1,"sid":"","seq":1,"type":"Arm","body":{}}""",
            """{"v":1,"sid":"s","seq":-3,"type":"Arm","body":{}}""",
            """{"v":1,"sid":"s","seq":1,"body":{}}""",
            """{"v":1,"sid":"s","seq":1,"type":"Arm"}""",
            """{"v":1,"sid":"s","seq":1,"type":"Arm","body":{"armed":"yes"}}""",
            """{"v":1,"sid":"s","seq":1,"type":"StartTake","body":{"takeId":42,"atFrame":"later"}}""",
        )
        for (text in bad) {
            val result = codec.decode(text)
            assertTrue("expected malformed for: $text but got $result", result is DecodeResult.Malformed)
        }
    }

    @Test
    fun unknownTypesDecodeAsUnknownAndCarryTheirRawBody() {
        val text = """{"v":1,"sid":"s","seq":5,"type":"SetCueFlange","body":{"depth":0.7}}"""
        val result = codec.decode(text) as DecodeResult.Ok
        assertEquals("SetCueFlange", (result.message as Unknown).rawType)
        assertEquals("""{"depth":0.7}""", result.envelope.bodyJson)
    }

    @Test
    fun fuzzedInputNeverThrows() {
        val random = Random(42)
        repeat(600) {
            val junk = buildString {
                val length = random.nextInt(0, 80)
                repeat(length) { append((random.nextInt(32, 127)).toChar()) }
            }
            codec.decode(junk)
        }
        repeat(200) {
            val bytes = ByteArray(random.nextInt(0, 40)) { random.nextInt(0, 256).toByte() }
            codec.decode(bytes.toString(Charsets.ISO_8859_1))
        }
        // Truncations of a valid message must not crash the decoder either.
        val valid = codec.encode(StartTake("t1", 10L), sid, 1)
        for (cut in 0 until valid.length) {
            codec.decode(valid.substring(0, cut))
        }
    }

    // ---------------------------------------------------------- authority

    private class Fixture {
        var frame = 0L
        val authority = ArtistAuthority(sid = "session-1", currentFrame = { frame })
        var generation = 0L

        fun connect(): Long {
            generation = authority.newTransportGeneration()
            val hello = Hello(
                protocolVersion = PROTOCOL_VERSION,
                role = "engineer",
                capabilities = Capabilities.all,
            )
            val result = authority.onHello(hello, generation)
            assertTrue(result.accepted)
            return generation
        }

        fun send(message: SessionMessage, seq: Long): AuthorityResult {
            val (envelope, decoded) = MessageCodec().encode(message, "session-1", seq).let {
                val d = MessageCodec().decode(it) as DecodeResult.Ok
                d.envelope to d.message
            }
            return authority.handleCommand(envelope, decoded, generation)
        }

        fun ready() {
            authority.preflightPassed(1_000_000_000L)
        }
    }

    @Test
    fun armThenScheduleStartMovesThroughCountInToRecording() {
        val fixture = Fixture()
        fixture.ready()
        fixture.connect()

        val armed = fixture.send(Arm(true), 1)
        assertTrue(armed.accepted)
        assertEquals(SessionState.ARMED, fixture.authority.state)

        val start = fixture.send(StartTake("take-1", 480_000), 2)
        assertTrue(start.accepted)
        assertEquals(SessionState.COUNT_IN, fixture.authority.state)
        assertEquals("take-1", fixture.authority.activeTakeId)

        val started = fixture.authority.engineTakeStarted("take-1", 480_001)
        assertEquals(TakeStarted("take-1", 480_001), started.first())
        assertEquals(SessionState.RECORDING, fixture.authority.state)

        val stopped = fixture.authority.engineTakeFinalized(240_000, "stop")
        assertEquals(TakeStopped("take-1", 240_000, "stop"), stopped.first())
        assertEquals(SessionState.UPLOADING, fixture.authority.state)
    }

    @Test
    fun duplicateStartTakeCreatesExactlyOneTake() {
        val fixture = Fixture()
        fixture.ready()
        fixture.connect()
        fixture.send(Arm(true), 1)

        val first = fixture.send(StartTake("take-1", 480_000), 2)
        val retry = fixture.send(StartTake("take-1", 480_000), 3)

        assertTrue(first.accepted)
        assertTrue("a retry must be acknowledged", retry.accepted)
        assertTrue("a retry must not emit another state change", retry.events.isEmpty())
        assertEquals("take-1", fixture.authority.activeTakeId)

        val startedEvents = fixture.authority.engineTakeStarted("take-1", 480_000)
        assertEquals(2, startedEvents.size)
        assertEquals(SessionState.RECORDING, fixture.authority.state)

        // After the take finishes, the same id cannot start a second take.
        fixture.authority.engineTakeFinalized(48_000, "stop")
        val replay = fixture.send(StartTake("take-1", 1_000_000), 4)
        assertFalse(replay.accepted)
        assertEquals(RejectionReason.BAD_VALUE.name, replay.ack.reason)
    }

    @Test
    fun commandsInTheWrongStateAreRejected() {
        val fixture = Fixture()
        fixture.ready()
        fixture.connect()

        val startWhileIdle = fixture.send(StartTake("take-1", 480_000), 1)
        assertFalse(startWhileIdle.accepted)
        assertEquals(RejectionReason.NOT_ARMED.name, startWhileIdle.ack.reason)

        val stopWhileIdle = fixture.send(StopTake, 2)
        assertFalse(stopWhileIdle.accepted)
        assertEquals(RejectionReason.WRONG_STATE.name, stopWhileIdle.ack.reason)

        fixture.send(Arm(true), 3)
        fixture.send(StartTake("take-1", 480_000), 4)
        val armWhileCountIn = fixture.send(Arm(false), 5)
        assertFalse(armWhileCountIn.accepted)
        assertEquals(RejectionReason.WRONG_STATE.name, armWhileCountIn.ack.reason)
    }

    @Test
    fun startInsideSafetyMarginIsRejected() {
        val fixture = Fixture()
        fixture.ready()
        fixture.connect()
        fixture.send(Arm(true), 1)

        fixture.frame = 400_000
        val tooSoon = fixture.send(StartTake("take-1", 420_000), 2)

        assertFalse(tooSoon.accepted)
        assertEquals(RejectionReason.BAD_VALUE.name, tooSoon.ack.reason)
        assertEquals(SessionState.ARMED, fixture.authority.state)
    }

    @Test
    fun reconnectNeverReplaysCommandsFromTheOldLink() {
        val fixture = Fixture()
        fixture.ready()
        val firstGeneration = fixture.connect()
        fixture.send(Arm(true), 1)

        // Link drops and comes back: old generation commands are dead.
        fixture.authority.onTransportLost()
        val startAfterDrop = fixture.send(StartTake("take-1", 480_000), 2)
        assertFalse(startAfterDrop.accepted)
        assertEquals(RejectionReason.NOT_AUTHORIZED.name, startAfterDrop.ack.reason)

        val secondGeneration = fixture.connect()
        assertTrue(secondGeneration > firstGeneration)

        // A stale StartTake replayed with the previous generation is refused...
        val staleEnvelope = Envelope(
            v = PROTOCOL_VERSION,
            sid = "session-1",
            seq = 3,
            type = "StartTake",
            bodyJson = """{"takeId":"take-1","atFrame":480000}""",
        )
        val stale = fixture.authority.handleCommand(
            staleEnvelope,
            StartTake("take-1", 480_000),
            firstGeneration,
        )
        assertFalse(stale.accepted)
        assertEquals(RejectionReason.NOT_AUTHORIZED.name, stale.ack.reason)

        // ...while the same command reissued after the fresh Hello is fine.
        val fresh = fixture.send(StartTake("take-1", 480_000), 4)
        assertTrue(fresh.accepted)
        assertEquals("take-1", fixture.authority.activeTakeId)
    }

    @Test
    fun theAuthorityMustAdoptATransportGenerationBeforeHello() {
        val authority = ArtistAuthority(sid = "session-1")
        val generation = authority.newTransportGeneration()

        val accepted = authority.onHello(
            Hello(PROTOCOL_VERSION, "engineer", Capabilities.all),
            generation,
        )
        assertTrue(accepted.accepted)

        // A Hello from a socket the authority has not adopted is not trusted.
        val stale = authority.onHello(
            Hello(PROTOCOL_VERSION, "engineer", Capabilities.all),
            generation + 1,
        )
        assertFalse(stale.accepted)
    }

    @Test
    fun commandsBeforeHelloAreNotAuthorized() {
        val fixture = Fixture()
        fixture.ready()
        fixture.generation = fixture.authority.newTransportGeneration()

        val result = fixture.send(Arm(true), 1)

        assertFalse(result.accepted)
        assertEquals(RejectionReason.NOT_AUTHORIZED.name, result.ack.reason)
    }

    @Test
    fun helloCarriesAFullSnapshotAndVersionMismatchIsRefused() {
        val fixture = Fixture()
        fixture.ready()
        val generation = fixture.authority.newTransportGeneration()

        val ok = fixture.authority.onHello(
            Hello(PROTOCOL_VERSION, "engineer", Capabilities.all),
            generation,
        )
        assertTrue(ok.accepted)
        val snapshot = ok.events.filterIsInstance<StateSnapshot>().single()
        assertEquals("IDLE", snapshot.state)
        assertEquals(fixture.authority.revision, snapshot.revision)

        val bad = fixture.authority.onHello(
            Hello(PROTOCOL_VERSION + 1, "engineer", Capabilities.all),
            generation,
        )
        assertFalse(bad.accepted)
        assertTrue(bad.events.filterIsInstance<Bye>().isNotEmpty())
    }

    @Test
    fun unknownCommandTypesAreExplicitlyRejected() {
        val fixture = Fixture()
        fixture.ready()
        fixture.connect()

        val envelope = Envelope(
            v = PROTOCOL_VERSION,
            sid = "session-1",
            seq = 3,
            type = "SetCueFlange",
            bodyJson = """{"depth":0.7}""",
        )
        val result = fixture.authority.handleCommand(envelope, Unknown("SetCueFlange"), fixture.generation)

        assertFalse(result.accepted)
        assertEquals(RejectionReason.UNSUPPORTED.name, result.ack.reason)
    }

    @Test
    fun lowStorageRefusesNewTakesWithLOW_STORAGE() {
        val fixture = Fixture()
        fixture.authority.preflightPassed(10L * 1024 * 1024)
        fixture.connect()
        fixture.send(Arm(true), 1)

        val result = fixture.send(StartTake("take-1", 480_000), 2)

        assertFalse(result.accepted)
        assertEquals(RejectionReason.LOW_STORAGE.name, result.ack.reason)
    }

    @Test
    fun cueLevelOutOfRangeIsRejected() {
        val fixture = Fixture()
        fixture.ready()
        fixture.connect()

        val high = fixture.send(SetCueLevel(12f), 1)
        val low = fixture.send(SetCueLevel(-90f), 2)
        val good = fixture.send(SetCueLevel(-12f), 3)

        assertEquals(RejectionReason.BAD_VALUE.name, high.ack.reason)
        assertEquals(RejectionReason.BAD_VALUE.name, low.ack.reason)
        assertTrue(good.accepted)
        assertEquals(-12f, fixture.authority.cueLevelDb)
    }

    @Test
    fun artistStopWinsAndIsReportedToTheEngineer() {
        val fixture = Fixture()
        fixture.ready()
        fixture.connect()
        fixture.send(Arm(true), 1)
        fixture.send(StartTake("take-1", 480_000), 2)
        fixture.authority.engineTakeStarted("take-1", 480_000)

        val stopEvents = fixture.authority.artistStop("artist unplugged headphones")
        assertTrue(stopEvents.filterIsInstance<StateChanged>().any { it.state == "FINALIZING" })
        val finalized = fixture.authority.engineTakeFinalized(12_000, "artist")
        assertTrue(finalized.filterIsInstance<TakeStopped>().any { it.takeId == "take-1" })

        // Commands arriving after the artist's stop cannot restart anything.
        val late = fixture.send(StartTake("take-2", 900_000), 3)
        assertFalse(late.accepted)
    }

    @Test
    fun snapshotReportsTheTruthAfterEveryTransition() {
        val fixture = Fixture()
        fixture.ready()
        fixture.connect()
        fixture.send(Arm(true), 1)
        fixture.authority.setMonitoring(true)

        val snapshot = fixture.authority.snapshot()

        assertNotNull(snapshot)
        assertEquals("ARMED", snapshot.state)
        assertTrue(snapshot.armed)
        assertTrue(snapshot.monitoring)
        assertEquals(fixture.authority.revision, snapshot.revision)
    }
}
