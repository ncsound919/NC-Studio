# Changelog

All entries below describe changes that were actually run, not planned work.
Earlier history was not recorded; this file starts at the first verified baseline.

## 1.5.0 — 2026-11-03 — take lanes on the timeline + comping

Alternate takes are now visible while you work, and you can borrow a phrase
from any of them into the performance you are hearing.

- `TrackTakeLanes` draws one slim row per take directly under the track lane,
  on the same horizontal scroll and zoom as the clips above, so take timing is
  readable at a glance. Only tracks that actually overdubbed grow a strip; the
  audible take is marked and the rest are dimmed with their star rating.
- Tap a row's name to audition that take. Tap a clip on another take to comp
  that whole phrase in; drag across a row to comp just the dragged range, with
  a live band showing the range that will be used.
- Comps are destructive on purpose: the borrowed audio becomes ordinary clips of
  the audible take, so playback, export, fades and every existing clip edit keep
  working with no second audio path. One undo entry restores the previous take.
- `TakeComping.kt`: a comp only replaces the spans the other take actually
  covers, splits the audible clip around the borrowed range, resets fades at the
  new edit boundaries (sample-accurate cut, no fading out on the seam), and is a
  no-op when the other take has nothing there — tapping empty space must never
  erase audio.
- `compTakeRange()` refuses comping the audible take into itself and ranges
  under 10 ms, and extends the session when a comp reaches past the project end.
- Fixed while testing: the strip measured its lanes against an infinite width
  (scroll applied above `BoxWithConstraints`), which pushed every take clip off
  screen. Scroll now sits on an inner box, matching the track lane.
- Fixed a restore race that could silently discard work: `applyLoadedProject`
  checked its "has the engineer edited since loading?" guard and then assigned
  the restored session in two separate steps, so an edit landing in that window
  was overwritten. Publishing is now one atomic `StateFlow.update`, and the
  restore abandons its write entirely when superseded.
- Tests: `TakeCompingTest` (7 cases: split, fill, no-op, partial coverage, fade
  reset, trimming, empty range), three new `TakeLaneViewModelTest` cases
  (comp + undo, active-take refusal, too-short range), and a timeline UI test
  that renders the strip and asserts the comp range reported by a clip tap.
- `sh gradlew :app:testDebugUnitTest`: BUILD SUCCESSFUL in 14m31s.
- 57 test suites, 408 tests, 0 failures, 0 errors, 2 skipped (JUnit XML).

Still open: lane trim/offset, preview-solo per lane, and take colours are not yet
in the timeline beyond the track tint. No device audio run was performed.

## 1.4.0 — 2026-11-03 — take lanes (v1)

Recording no longer replaces what is already on the track. Each pass becomes a
take you can audition, rate and delete from the track inspector.

- `TakeLane` model; `TrackData.takeLanes` / `activeTakeId` with `withNewTake()`
  and `withActiveTake()`. A legacy track's existing clips are promoted to
  "Take 1" on the first overdub, so no recorded material is lost.
- `stopRecording()` writes each pass into its own lane.
- ViewModel: `selectTakeLane`, `rateTakeLane` (1–5 stars), `renameTakeLane`,
  `deleteTakeLane`. Take actions are discrete undo entries and are never
  coalesced into a following fader move; deleting the last remaining take is
  refused with a message.
- Undo/redo of a take switch now rebuilds the audible track (previously only an
  auto-pitch change did).
- Track inspector gains a TAKES row: tap a take to make it audible, tap the star
  to rate, tap the cross to delete.
- New tests (`TakeLaneTest`): take promotion, switching round-trip, unknown lane,
  legacy session load, and crash-journal persistence round-trip.
- `TakeLaneViewModelTest` drives recording through a fake recorder seam
  (`useRecorderForTest`): overdub creates a second take, switching is undoable,
  deleting the active take falls back, the last take cannot be deleted, and
  ratings survive take switches.

Audit of the first cut found and fixed:

- Restoring a project only reloaded audio for the *active* take's files, so
  switching to an alternate take after a restart played silence. Restore now
  loads every take via `TrackData.audioSourcePaths`.
- Recording never extended `totalLengthFrames` (or the engine's session length),
  so a take longer than the current project was cut off in playback and export.
- The TAKES section was drawn on all 16 tracks, including ones with no takes;
  it now appears only once an overdub exists.
- Recorded clip names used the clip count while takes used the lane count, so the
  two disagreed after the first overdub; both now use the same take number.
- `AudioRecorder` is `open` and the ViewModel has a test-only recorder seam,
  because AudioRecord produces no usable frames under Robolectric.
- `sh gradlew :app:testDebugUnitTest`: BUILD SUCCESSFUL in 9m02s.
- 56 test suites, 397 tests, 0 failures, 0 errors, 2 skipped (JUnit XML).

Not covered: comp regions, lane trim/preview-solo, and take colours in the
timeline are still open. No device audio run was performed.

## 1.3.0 — 2026-10-28 — first verified test run

No feature code was changed in this entry. It records the first full unit run
of the DAW as a baseline.

- `sh gradlew :app:testDebugUnitTest`: BUILD SUCCESSFUL in 6m13s.
- 51 test suites, 356 tests, 0 failures, 0 errors (read from the JUnit XML).
- Existing engine, transport, project and UI-state tests all pass under the
  Gradle unit-test JVM.

Not covered by this entry: no release APK was built and no audio path has been
exercised on a physical device. See `docs/DEPLOY-READINESS.md`.
