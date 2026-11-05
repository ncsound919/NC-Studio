# NC Studio — Deep execution plan (post-v1.3.1)

Scope: all remaining features except **multiband compressor**, **sidechain**, **time stretch/elastic audio**. Features: Tier1 competitive parity, Tier2 differentiators, Tier3 parity (minus cuts), Tier4 AI/product, plus device verification.

Conventions: Kotlin + Jetpack Compose; DSP real time in `AudioDsp.kt`; plugins are data fields on TrackData; undo is `SessionCommand`; AI via `MixEngineerBrain.plan`. Tests co-located with module. Defaulting LOD (low-density) uses `AudioSourceFormat`.

---

## Phase 0 — Lock baseline

- Tag current HEAD `v1.3.1-baseline`; export fixture file `app/src/test/resources/fixtures/project_131.json` from a real saved session; load test asserts `sourceFormat` + clip count.
- Add `schemaVersion: Int` to ProjectSession (default 131). Migration test from an absent-version file.
- `ProjectPersistence`: write `schemaVersion` to JSON; on read, if absent treat 131.
- Tests: `ProjectPersistenceSchemaMigrationTest`.

## Phase 1 — Recording workflow parity (3 weeks)

### E01 Take lanes + comping
- Files:
  - model/AudioModels.kt: add `TakeLane(takeId,row,t Rating:Int=3,color: Int? =null, offsetFrames: Long =0, active: Boolean=false)`; `TrackData.takeLanes: List<TakeLane> =emptyList()`; `ProjectSession.compRegions: List<CompRegion(startFrame,endFrame,takeLaneId)>`.
  - history/ProjectPersistence: serialize lanes and regions; backwards default.
  - ui/TimelineComponents.kt: `TakeLaneRow` composable under track row; comp region overlays.
  - ui/TimelineEditController.kt: `switchActiveTake(trackIndex,laneId)`, `addCompRegion(...)`.
  - engine/AudioTrackPlaybackEngine: choose active/comp lane at read time; pre-cache lanes' buffers (reuse per-track buffer caching).
  - DawViewModel: wrappers + undo.
- Steps:
  1. Add model classes.
  2. Persist to JSON; migration.
  3. Load takes from recording into new lane instead of replacing clip; keep original.
  4. Render lanes under track; selected lane highlights.
  5. Play button auditions only lane into A/B buffer.
  6. Comp tool: drag region → pick active lane per region.
  7. Undo commands: `SessionCommand.SetTakeLanes`, `SetCompRegions`.
- Tests: TimelineEditing has lane count; engine playback comp stitch continuity; persistence roundtrip.

### E02 Input monitoring + USB
- Files: DawViewModel with `updateSettings(...)` already; add `inputMonitoringEnabled`; service layer AudioEngineService expose `monitorMic()`; USB helper in audio/engine + ui.
- Steps:
  1. Add setting `inputMonitorRoute: Enum(WIRED,USB,AUTO)`.
  2. Android lifecycle? Add broadcast receiver (Activity-scoped) on USB attach/detach → `refreshUsbDevices()`.
  3. In engine, when armed + monitor enabled, connect mic→output bus with latency subtract.
  4. Auto-disable if no wired headset (query AudioManager.isWiredHeadsetOn).
- Tests: unit binding settings; device evidence.

### E03 Punch in/out + overdub
- DawViewModel add `punchPreRollFrames`, countdown state; engine punchIn/out hooks to region.
- Steps: countdown animation (Compose) with 1 bar; on punch end, stop only if `autoStopAtOut`.
- Overdub default writes take lane vs replacing: in `record` call, create new lane, keep old active.

### E04 Noise reduction
- New module `audio/dsp/NoiseReductionModule.kt` in AudioDsp.kt? Add DSP module alongside other modules in AudioDsp plugin module array.
- `audio/model/AudioModels.kt` noiseProfile data; `TrackData.noiseProfileId`.
- UI `ModuleEditors.kt`: learn capture button; preview toggle.
- Steps: capture 1s ring buffer → FFT magnitude → store threshold; DSP apply spectral gate via FFT blocks? Minimal: implement time-domain spectral subtraction with STFT (complexity). First milestone: noise-gate style simpler? Noise reduction minimal acceptable: capture profile and subtract RMS. Plan: implement STFT-based spectral subtraction N=1024 with 50% overlap.
- Tests: analyze energy reduction thresholds.

### E05 AutoPitch
- New data `ClipPitchCorrection(scale,speed,amount,automation)`; add to AudioClip as optional.
- Detection in `audio/reference` or DSP utils: estimate F0 per 30 ms using YIN. Compute target snapped note cents.
- Playback: phase vocoder resampling. If no engine pitch path, render offline to a temp WAV on export? Need real playback effect. Plan: implement pitch-shift DSP (phase vocoder) in clip playback prepare, cached file.
- First milestone: detect on timeline and preview pitch-corrected temp file via clip source replacement; apply to new file with suffix.
- Files: AudioClip, AudioModels, DawViewModel, ProjectController import path, TimelineView.
- Tests: detection unit with sine.

## Phase 2 — Timeline + mixer workflow

### E06 Automation lanes
- Model: `AutomationLane(id,driver,points,enabled)`; `AutomationPoint(frame,value)`. TrackData.lanes.
- `audio/model/AudioModels.kt`; persistence.
- UI: AutomationLaneStrip composable with point add/drag/delete; lane menu.
- Engine: continuous per-block ramp.
- DawViewModel `addAutomationPoint`, `remove`, `setLaneEnabled`.
- Undo: `SessionCommand.SetLanePoints`.
- Tests: create/read point interpolation.

### E07 Group/bus routing
- Model: `Bus(id,name,color,muted,gainDb,pan,bypass?)`; Track output target busId. Bus has channel plugins currently? start with gain/pan/reverb maybe.
- `TrackData.busId: Int?`; ProjectSession.buses.
- Mixer UI select bus per track; mixer lanes per bus.
- Engine bus graph: process track→bus then buses sorted.
- Tests: underruns? sum.

### E08 Sends/returns
- TrackData sends Array up to 4 `{busId, gain, preFader}`; bus types.
- UI send knobs.
- Engine: runs.

### E09 Metronome/cue
- Settings expose sound packs; engine uses selected asset based on sample rate.
- Add count-in animation and cue level meter.

## Phase 3 — Export & delivery

### E10 Stems export wiring
- Read ExportController & ExportDialog. If stem export exists, add `testStemExport` integration; else implement renderTrackToWav loops over tracks: `audioEngine.renderSessionToWav(session, trackMask, path)`.
- File naming: `<project>_<trackIndex>_<trackname>.wav` (sanitize).
- Progress dialog per stem.

### E11 MP3/FLAC encoders
- Check current implementation in ExportController. If MediaCodec+fallback, verify on device by instrumented; add tests `ExportFormatDecodeTest`.
- If needed: add CMake/Ndk? Later decision. First try pure MediaCodec `audio/mpeg` profile; keep WAV fallback.

### E12 Project package
- `ProjectController.exportProject` → zip json + import content files with `androidx.zip`? Use `ZipOutputStream`.
- `importProject` unzip + re-link based on manifest map.
- Tests: roundtrip zip in unit via byte arrays.

## Phase 4 — UI observability & remote session

### E13 Device verification harness (checklist remains)
- No new coding beyond scripts.

### E14 WebRTC monitor
- Keep MonitorCodec interface; add `WebRtcMonitorTransport` optional; settings toggle. Need library dependency decision (webrtc-multipass Android). First provide transport interface empty implementation test harness; defer real dependency to research.
- Steps: research dependencies; POC behind interface.

### E15 Remote take import
- Session take files already stored. Add `TakeImporter` using `WavAudioIO`, create clip at session play position.
- Files: session/ui/SessionViewModel, DawViewModel inject? cross-module handoff via shared storage URI/JSON.
- Simplest: encoder take path stored; when returning to DAW, pass `takePath` via activity result and create AudioClip sourcing it.

### E16 Remote timeline sharing
- Add protocol extension `playheadFrame`, `selectedClip` etc (backwards safe).
- DawViewModel receives frames from SessionViewModel via shared service or activity singletons.
- First milestone: show read-only remote playhead marker in DAW timeline.

### E17 Mic foreground service
- Create `MicrophoneForegroundService` + manifest permission + start/stop; bind recording lifecycle. Handle runtime mic failure.

### E18 Spectrum analyzer
- DSP: FFT module N=2048 using `KissFFT`? Implement complex DFT in Kotlin N=1024 acceptable (60 fps maybe okay).
- Module meters band energies; UI overlays curve on EQ graph.
- Tests: frequency peak test.

### E19 De-esser
- Add `DeEsserModule` using band-pass ~6-9k detection and gain reduction; TrackData config.
- UI controls threshold/freq/depth.
- Tests: sibilant energy reduction.

## Phase 5 — AI differentiation

### E20 LLM robustness
- AudioAiAssistant/mesh: retry with exponential + bounded; cache models offline (SharedPreferences).
- Auto-select model based on RAM/device.
- Add UI lists and test mocks via connection state.

### E21 Auto-mix one button
- `MixEngineerBrain.autoMix(session)`: run analysis, suggests, genericMix/grammar, apply prepared plan as one transaction; present A/B toggle with temporary undo checkpoint.
- DawViewModel `runAutoMix()`.

### E22 Stem separation (optional)
- Model delivery: download ONNX model; plugin transforms track, output stems as lanes.
- Start with feasibility scaffold and user explanation; heavy.

### E23 Genre auto-detect/template apply
- Use measured spectral features provider to classify; map to existing templates.

### E24 Tap tempo
- Onset detector; update tempo only after user confirms.

## Phase 6 — Data hardening

### E25 Migration/versioning
- Already schema version; add migration functions tested with versioned fixture snapshots.

### E26 Custom templates
- Directory `files/library/templates`; CRUD dialog.

### E27 Library metadata
- Data model tag use count.

### E28 Accessibility policy
- Add semantics automation? Tests via Compose UI tests where already set; document.

## Phase 7 — Release
- Signing/CI, documentation, versioning.

---

## Cross-cutting testing
- Run `:app:testDebugUnitTest`, lint, assembleDebug, assembleRelease.
- Device certification scripts.
- Release notes update.

## Risk register additions
- DSP CPU + ramp interpolation on callback thread.
- Overdub file creation latency.
- Migration backward compatibility.
- Remote session protocols ext forward-compat.

Scope excludes multiband, sidechain, time stretch. On-device verification remains mandatory before release.
---

## OSS & tooling to leverage

### DSP / analysis / codec OSS
- FFT / analysis: JTransforms (BSD-like) for N-point transforms; avoid pure-Kotlin 2048 FFT on callback thread except previews.
- Pitch/analysis helpers: TarsosDSP for YIN/CEP pitch tracking and onset detection (Java, Apache).
- Noise suppression: WebRTC Audio Processing Module (AEC/NS/AGC) via Android WebRTC APM wrapper if source available; otherwise Speex DSP (BSD).
- Audio codecs:
  - Decode: Android MediaCodec (AAC/MP3/FLAC) generally available.
  - Encode: MediaCodec `audio/mpeg` is optional; if unavailable ship WAV fallback (existing). For guaranteed MP3/FLAC encoding embed `lame` / `libflac` compiled via CMake/NDK only if license build path acceptable; otherwise document "AAC primary".
- WebRTC: use libwebrtc Android (m100+) for the remote monitor stage when budget allows; heavy (~8MB) but provides low-latency AV1/opus path.
- Stem separation: offline by device isn't viable on phones; design `StemSeparationService` interface with remote (opt-in) + local ONNX Demucs depending on download. Keep optional to not bundle 60MB+.
- Zip/project packaging: Java `ZipOutputStream` + SHA-256; include resource copy helpers.

### Coding & agent tools
- Android Studio Arctic Fox+ with Compose tooling, Layout Inspector, Macrobenchmark, Energy Profiler.
- Static analysis: Detekt + KtLint for Kotlin, LintDebug strict; add Baseline profiles.
- Testing: Robolectric (done), Espresso instrumentation, Macrobenchmark for playback/edit flows.
- Dependency injection: not currently; avoid retrofit unless needed; use Ktor client for optional remote services (already standard).
- Build gates: GitHub Actions on pull requests: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`.
- Emulator/device lab: gradle managed devices (Pixel 2 API 28 min); screenshot baseline harness (Roborazzi if adding dependencies).
- Agent workflow: keep changes scoped, tests first, commit after each epic, update CHANGELOG.

### Integration notes
- Add dependencies only behind feature flags; keep build lite when OSS features optional.
- Provide build variants (`ossLight`, `ossFull`) maybe later; simplest: runtime detection and optional modules.
- Document each third-party license in `THIRD_PARTY.md`.


---

## Execution log & reality check (code audit, this pass)

Installed project skills (`.agents/skills/`): `android-cli`, `testing-setup`,
`jetpack-compose-performance`, `limrun-android-emulator`,
`android-jetpack-compose`. The limrun/android CLIs are not installed here yet.

Audit result — the codebase is further along than the epic list assumed:

| Epic | Real status in code |
|---|---|
| E02 input monitoring | UI state + toggle only; engine routing not connected |
| E04 noise reduction | `NoiseReductionConfig` + DSP + inspector editor exist |
| E05 auto-pitch | `AutoPitchConfig` + `AutoPitchProcessor` + inspector exist |
| E06 automation lanes | Model + persistence + **offline export** only; no UI, live engine ignores it |
| E07/E08 buses/sends | `BusData`, `outputBusId`, `sends` model + template routing; no routing UI |
| E10 stems export | `ExportController` exports stems with per-stem progress |
| E19 de-esser | Present in `AudioDsp` + vocal chain presets |
| E01 take lanes | **Absent — implemented now (v1 slice)** |
| E12 project package | Absent |
| E14 WebRTC | Absent (AAC/WS only) |

### Shipped this pass — Take lanes v1
- `TakeLane` model + `TrackData.takeLanes/activeTakeId`, with
  `withNewTake()` / `withActiveTake()` guarantees (no take is ever overwritten).
- Recording now creates a take lane; the first overdub promotes existing clips
  to "Take 1".
- ViewModel: `selectTakeLane`, `rateTakeLane` (1–5 stars), `renameTakeLane`,
  `deleteTakeLane`, each a discrete undo entry (never coalesced into a fader).
- Inspector "TAKES" row: tap to audition, star to rate, cross to delete.
- Tests: `TakeLaneTest` (promotion, switching round-trip, unknown lane, legacy
  session, Moshi persistence round-trip).

### Shipped this pass — Take lanes v2 (timeline lanes + comping)
- `TakeLanesStrip.kt`: one row per take under its track, same scroll/zoom as the
  clip area. Hidden until a track has two takes.
- Audition a take by tapping its row; comp a whole clip by tapping it on another
  take; comp a range by dragging across that take's row (live range band).
- `model/TakeComping.kt`: `withCompedRange()` splices the borrowed spans into the
  audible take, splits clips around them, resets fades at new boundaries, and is
  a no-op when the other take has no audio there.
- `DawViewModel.compTakeRange()`: one undo entry, guards for self-comp and
  sub-10 ms ranges, extends the session length when a comp runs past the end.
- Bug found by the UI test: `horizontalScroll` above `BoxWithConstraints` made
  the strip measure against infinite width, so every take clip was offscreen.
- `applyLoadedProject` published the restored session with a check-then-assign
  pair, so an edit made during a restore could be silently overwritten; the
  guard check and the publish are now one atomic `StateFlow.update`.
- Tests: `TakeCompingTest` (7), 3 new ViewModel comp cases, 1 timeline UI case.

Remaining for E01 v3: lane trim/offset, per-lane preview solo, comp-region
persistence as first-class regions (today a comp is baked into the take), and
migration of discarded takes into the bin instead of leaving orphan WAVs.

Verification after this slice: `:app:testDebugUnitTest` BUILD SUCCESSFUL —
57 suites, 408 tests, 0 failures, 0 errors, 2 skipped (after take lanes v2). Note for future runs:
`forkEvery = 1` makes the full suite ~14 min; a killed Gradle daemon mid-run
leaves bogus `ClassNotFoundException` results, so rerun after a clean rebuild
rather than chasing them.

### Audit pass — issues found and fixed after the first take-lane cut
1. **Restore only loaded the active take's audio.** `applyLoadedProject` built
   the source pool from `track.clips`; inactive takes had no samples, so
   switching takes after reopening a project played silence. Fixed with
   `TrackData.audioSourcePaths` (clips + all take lanes) and a test.
2. **Recording never extended the session.** A take longer than
   `totalLengthFrames` was truncated by playback/export. `stopRecording()` now
   raises the session length and calls `engine.setSessionLength`.
3. **TAKES row rendered on every track** (including 13 with no takes). Hidden
   until `takeLanes.isNotEmpty()`; `inspector_takes_empty` removed.
4. **Take/clip naming disagreed** (`Mic Take 2` inside `Take 3`). Both use the
   same computed take number.
5. **Take deletion keeps its WAV on disk.** Deliberate: undo restores the clip
   and would point at a deleted file. Revisit with reference counting in E01 v2.
6. **Recording was untestable.** `AudioRecorder` is now `open`, `DawViewModel`
   exposes `useRecorderForTest`, and `TakeLaneViewModelTest` covers the
   record → take → switch → undo → delete path. The older end-to-end recording
   test routinely fails because Robolectric's AudioRecord yields no frames —
   that is the same gap, now closable with the seam.
