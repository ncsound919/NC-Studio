# Mode 2 remote session — MVP implementation guide

An artist records on their own phone; a real engineer joins the session, hears a
monitor stream, arms and schedules takes, talks back, and receives a
hash-verified copy of each take. This document describes what is implemented in
`app/src/main/java/com/example/session/**`, how to run it with two phones, and
exactly what has and has not been verified.

**The core rule holds:** the artist's phone is the recorder of record. Audio is
captured, chunked, hashed and stored locally. Nothing about the take depends on
the network, the server, or the engineer.

## 1. What was built

| Piece | Class / file | Notes |
|---|---|---|
| 24-bit chunked take recorder | `session/take/TakeRecorder.kt`, `ChunkedTakeWriter.kt` | 48 kHz / 24-bit mono, ~2 MB chunks (~14.5 s), float capture, tap for monitor |
| Crash-safe take store | `session/take/TakeStore.kt` | journal + manifest, recovery rebuilds a manifest from verified chunks only |
| Take models | `session/take/TakeModels.kt` | manifest matches `upload-api.yaml` exactly |
| Resumable uploader | `session/upload/UploadEngine.kt`, `UploadWorker.kt` | WorkManager, server is the resume authority, local bytes re-hashed before upload |
| Engineer download + verify | `session/upload/TakeDownloader.kt` | per-chunk and whole-take hash check before "verified" |
| Protocol + authority | `session/protocol/ArtistAuthority.kt`, `MessageCodec.kt` | envelope `{v,sid,seq,type,body}`, allow-lists per state, idempotent StartTake, reconnect generations |
| Session log | `session/protocol/SessionLog.kt` | append-only JSONL on both phones |
| Transport | `session/net/WebSocketTransport.kt` | reconnecting WS, 1 s heartbeat, RTT/jitter, degraded detection, **nothing queued across a reconnect** |
| Sessions + invites | `session/net/BackendApi.kt` | engineer creates, artist joins with a 6-character code |
| Preflight | `session/net/Preflight.kt` | mic, wired/USB output, storage, network quality, calibration |
| Loopback calibration | `session/calibration/*` | chirp + cross-correlation, stored per route, re-measured on route change |
| Monitor + talkback audio | `session/monitor/*` | AAC-LC 64 kbps frames over the session WebSocket, jitter buffer, push-to-talk |
| Local backing track + cue | `session/playback/CuePlayback.kt` | local playback, cue level from the engineer, count-in clicks |
| UI | `session/ui/SessionActivity.kt`, `SessionScreen.kt`, `SessionViewModel.kt` | role choice, preflight checklist, both live panels, take list, warnings, log |
| Reference backend | `session-server/` | Node, `ws`, disk storage, 10 passing tests |

Entry point: **Settings → Remote Session (Beta)**.

### Monitor stream: what it is, and what it is not

The design's step 4 is a WebRTC monitor stream. This MVP ships the audio path
over the **session WebSocket** instead, as AAC-LC at 64 kbps mono with a jitter
buffer, because that path is end-to-end testable today without a TURN server,
and the design already falls back to the WebSocket for control when WebRTC is
unavailable. Audio travels through `MonitorAudioEngine` behind a `MonitorCodec`
interface, so swapping in a WebRTC track (and moving control to a data channel)
is a transport change, not a rewrite. Expect roughly 250–450 ms monitor latency
on a LAN; a WebRTC/TURN stage is needed before this is usable over the internet.

## 2. Running it with two phones

1. **Server** (any laptop on the same Wi-Fi):
   ```bash
   cd session-server
   npm install
   npm start          # prints http://0.0.0.0:8787
   ```
2. **Engineer phone**: Settings → Remote Session → *I'm the engineer* → enter
   the laptop's LAN address (`192.168.x.x:8787`) → **Create session**. A 6
   character invite code appears; share it with the artist.
3. **Artist phone**: same entry point → *I'm the artist* → same server address,
   the invite code, and a name → **Join**.
4. **Artist preflight**: grant microphone, connect wired headphones or a USB
   interface, press **Calibrate** (loopback chirp through the current route),
   optionally choose a backing track, press **Test** for an upload-speed probe,
   then **Ready to record**.
5. **Engineer**: watch the link card (`RTT`, artist state), flip **Arm**, press
   **Start take** (schedules the start a few seconds ahead so the artist's
   phone can count in), **Stop** when done, adjust **Cue level**, hold
   **Talkback** to talk. The take appears in the list with upload progress, then
   **Download & verify** re-hashes every chunk and the whole take on the
   engineer's phone.
6. **Artist**: the monitor indicator is visible while the engineer is listening;
   the **Stop the take** button always wins over anything the engineer sends.

Wired headphones are required on both sides: the artist's mic would otherwise
pick up the backing track, and talkback would feed back on the engineer's side.

**TLS in release builds:** debug builds may use plain `http://`/`ws://` on the
LAN. Release builds reject cleartext endpoints and require `https://`/`wss://`,
so terminate TLS in front of the Node server (a reverse proxy is enough) for a
production session. This keeps session tokens and monitor audio off the wire in
clear text.

## 3. Take format and upload contract

Chunks are raw 24-bit little-endian PCM, frame-aligned, in
`files/session/takes/<takeId>/`:

```
journal.jsonl        take_started / chunk / finalized / abandoned (fsync'd)
chunk-0000.part      finalized, hashed chunks
chunk-0000.part.tmp  chunk still being written (never referenced by the journal)
manifest.json        written once, at finalize
upload-state.json    local upload progress (never part of the wire manifest)
recovered.json       present when a take was rebuilt after a crash
```

The manifest is exactly the wire contract from `upload-api.yaml`:
`takeId, sessionId, trackId, sampleRate, bitDepth, channels, totalFrames,
startFrame, calibrationOffsetFrames, chunks[], takeSha256`, where `takeSha256`
is the SHA-256 of the chunk bytes concatenated in index order.

## 4. Verification status

**Automated and passing** (62 JVM tests in `app/src/test/java/com/example/session/**`
plus the full 356-test app suite, and 10 Node tests in `session-server/test/`;
`:app:assembleDebug`, `:app:assembleRelease` and `:app:lintDebug` (0 errors) are
 green as well). `SessionViewModelTest` covers role selection, input
normalization and join validation on the orchestrator itself;
`ConnectionSettingsSecurityTest` pins the release-TLS requirement.
`SessionServerIntegrationTest` starts the
real Node server on loopback and drives the app's own client classes through it:
create/join, WebSocket relay, take upload with per-chunk and whole-take hashes,
server verification, engineer download and re-verification.

| Gate from the design | Covered by |
|---|---|
| Kill the network mid-take: the take is intact | `TakePipelineTest.crashRecoveryRebuildsManifestFromCompletedChunksOnly`, `UploadPipelineTest.resumeOnlySendsChunksTheServerIsMissing`, transport tests |
| Kill the app mid-take: recovery to the last chunk | `TakePipelineTest.crashRecovery*`, journal-with-partial-line test |
| Downloaded take hash matches the original | `UploadPipelineTest.downloaderVerifiesEveryChunkAndTheWholeTake`, `downloaderRejectsCorruptedServerBytes`, `SessionServerIntegrationTest`, server `complete` tests |
| Duplicate `StartTake` creates one take | `SessionProtocolTest.duplicateStartTakeCreatesExactlyOneTake` |
| Wrong-state command is rejected | `SessionProtocolTest.commandsInTheWrongStateAreRejected`, `armThenScheduleStart...` |
| Reconnect never replays old commands | `SessionProtocolTest.reconnectNeverReplaysCommandsFromTheOldLink` |
| Malformed/out-of-order input is safe | `SessionProtocolTest.fuzzedInputNeverThrows`, `MonitorTest.malformedFramesAreDropped`, `WebSocketTransportTest.malformedInboundDataDoesNotKillTheConnection` |
| Telemetry loss does not disturb control | `WebSocketTransportTest.lostTelemetryDoesNotBlockControlTraffic`, `MonitorTest.jitterBuffer*` |
| Overdub alignment (target ±2 ms) | `CalibrationTest.offsetEstimateStaysInsideTheTwoMillisecondTolerance` |

**Not verified — needs two real phones (no device or audio hardware was
available here):** capture quality and volume, monitor latency/quality in both
directions, talkback feedback behaviour, count-in and scheduled start accuracy
against the backing track, route-change recalibration, actual WorkManager resume
after a device reboot, and the artist/engineer UI on a small screen. Follow section 2
as the on-device checklist.

**Known gaps by design decision:**

* Monitor audio is AAC over WebSocket, not WebRTC — see section 1.
* The engineer's verified take is stored under `files/session/takes/<takeId>/`
  but is not yet imported onto a DAW track; importing is a small follow-up once
  the import path is chosen (WAV writer already exists: `WavAudioIO`).
* The engineer has no DAW timeline view yet, so `StartTake` schedules with a
  fixed lead from the last take's end frame; a timeline/transport share is the
  natural next step.
* Recording runs while the session screen is in the foreground. A microphone
  foreground service is needed before the artist can lock the screen mid-take;
  recovery already covers the process being killed.
* One artist and one engineer per session; the server refuses a second artist.
