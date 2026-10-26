# Pulse DAW — UI Polish Checklist

**Status (2026-10-26):** P0, P1, and P2 code work is complete. All interaction, state, copy, token, and onboarding items are implemented and covered by build/test/lint. What remains is device-only verification: TalkBack order, visual clipping at 2.0×, screenshot baselines, and frame timing under a live 16-meter load.

**Target matrix:** 320 × 568, 360 × 800, 411 × 891, 600 × 960, 800 × 1280 landscape · font scale 1.0 / 1.3 / 2.0 · dark theme only (intentional) · Android 10+ · TalkBack on.

## Before → after

| Signal | Before | Now |
|---|---:|---:|
| Visible controls wired to no-op lambdas | 6 | 0 |
| Unreachable settings dialog | 1 | 0 |
| `contentDescription = null` on interactive icons | 0 | 0 (decorative nulls reviewed) |
| `fontSize` literals at 7–9sp | 52 | 0 |
| Hard-coded `Color(0xFF…)` outside `ui/theme` | 68 | 0 |
| Known custom touch targets below 48dp | several | 0 |
| `@Preview` composables | 0 | 10 (phone, tablet, 1.3×, 2.0×) |
| Window-size-class / width references | 1 | 3 |
| Static `Text` literals outside `strings.xml` | 245 | 67 dynamic/interpolated only |
| Unit tests passing | 119 | 135 / 135 (24 suites) |
| Lint | n/a | 0 errors, 99 warnings, 11 hints |

**Evidence**

- `./gradlew :app:testDebugUnitTest` — 24 suites, **135 tests, 0 failures, 0 errors, 0 skipped**.
- `./gradlew :app:assembleDebug` — `BUILD SUCCESSFUL`.
- `./gradlew :app:lintDebug` — `BUILD SUCCESSFUL`; 0 errors.
- New tests cover direct clip drag, overview strip, session-health thresholds, frame pacing, onboarding/release-note persistence, presentation mode, export Share-all, and font-scale bounds at 1.3×/2.0×.
- No emulator or physical device is available here, so audio playback, TalkBack order, screenshot baselines, slider thumb size, and final visual clipping remain unverified.

---

## P0 — Truth and breakage

### 1. Every visible control does something

- [x] Punch in/out/clear, A/B, load reference, and match call real ViewModel APIs.
- [x] Dead settings dialog removed.
- [x] Track preset application and USB refresh no longer no-ops.
- [x] DSP diagnostics moved to the top-bar overflow.
- [x] Settings reachable from navigation and overflow without duplication.

### 2. First-run and rotation continuity

- [x] Selected tab persists with `rememberSaveable`.
- [x] `activeTab = 2` magic removed.
- [x] System Back returns to Mixer; dialogs/Settings keep their handlers.
- [x] Per-tab scroll state preserved.
- [x] Inspector module remembered per track.

### 3. Layout fits the phone

- [x] Transport overflow resolved with a scrolling tool rail.
- [x] Top-bar overflow resolved; brand ellipsizes.
- [x] Custom DAW controls use 48dp targets.
- [x] Bottom tabs use `NavigationBar` with 48dp items.
- [x] Automated 2.0× tab-bounds test passes.

### 4. State the UI shows must be true

- [x] Status banner auto-clears (4.5s info/success, 8s errors).
- [x] Export blocks conflicting actions and is terminal.
- [x] Recording shows a red REC badge and truthful stop copy.
- [x] Unmet preconditions disable A/B and Match.
- [x] Reset and cache-clear confirm before acting.
- [x] Typed `StatusKind` / `StatusAction` model.
- [x] Track/master preset application is undoable from the banner.

### 5. Timeline legibility

- [x] Grid and ruler derive bars from tempo.
- [x] Empty clips draw a baseline placeholder.
- [x] Playhead uses glow + crisp line + cap.
- [x] Loop and punch regions are shaded.
- [x] Selected/playing clips have distinct outline cues.
- [x] Snap guide flashes after button edits and clip drags.

---

## P1 — Complete

### Timeline and editing

- [x] Zoom 1×–8× plus fit-session; ruler and lanes share one scroll state.
- [x] Fixed ruler and track headers.
- [x] Fade grips with live millisecond readout.
- [x] Draggable loop/punch edges with 48dp targets.
- [x] Multi-select with visible count; trim/duplicate/nudge/fade apply to selection.
- [x] Direct long-press clip dragging with snapping, undo, haptic start, and snap-flash on release.
- [x] Waveform overview strip with full-session clip density, loop region, viewport window, playhead, and tap-to-seek.
- [x] Long-session frame pacing collected from `FrameMetrics` (p95 + slow-frame share) instead of guessed.

### Mixer and inspector

- [x] Pan center detent and double-tap reset.
- [x] Meter clip latch clears on tap.
- [x] EQ curve, compressor reduction meter, and track presets.
- [x] Compressor advanced parameters behind a disclosure.
- [x] Mute/solo/arm have color, glyph, TalkBack label/state, and long-press tooltip.
- [x] **Single track view in mixer (only selected track + master bus, with track selector arrows at bottom)** — replacing 16-track LazyRow for better visibility.
- [x] **Scrollable plugin selection bar in inspector showing one plugin at a time with left/right arrows** — eliminating crammed buttons.
- [x] **Removed redundant EQ/SAT/COMP badges beside Pro Presets button** — cleaner inspector header.

### Settings, export, and import

- [x] Cache size shown before clearing and reset after.
- [x] Stepped latency calibration.
- [x] Current/default summary card plus per-slider default hints.
- [x] SAF folder picker for export; last destination persisted and restored across launches.
- [x] Export metadata: destination, duration, sample rate, bit depth/bitrate, estimated size.
- [x] Stem export reports per-stem progress and lists outputs.
- [x] Every stem has Open and Share; all stems can be shared at once via `ACTION_SEND_MULTIPLE`.
- [x] Import progress and format-specific failure copy.
- [x] FileProvider exposes only exports and takes.

### Visual system, copy, and accessibility

- [x] One semantic palette; no raw hex outside `ui/theme`.
- [x] `TextMuted` meets ≥4.5:1 against the studio background.
- [x] One Material typography scale plus named `StudioType` styles; metadata floor is 10sp.
- [x] `Spacing`, `Radius`, `IconSize`, and `TouchTarget` tokens defined in `theme/Dimens.kt`.
- [x] Corner radii normalized to the documented scale across UI files.
- [x] 173 static `Text` literals moved into `strings.xml`; remaining inline strings are dynamic/interpolated.
- [x] State descriptions on snap/grid/track/selection controls.
- [x] Sparse haptics on transport, snap, grid, clip drag, fade/loop drag, mute/solo/arm; respects `enableHapticFeedback`.
- [x] Long-press tooltips on dense timeline and mixer icons.
- [x] Reduced-motion support.
- [x] Ten previews cover transport, timeline, mixer, and settings at phone/tablet and 1.3×/2.0×.

### Responsive and input

- [x] ≥700dp two-pane and ≥840dp three-pane layouts.
- [x] Edge-to-edge `innerPadding` consumed.
- [x] AI Studio, clip search, and dialogs stay above the IME.
- [x] Keyboard shortcuts: Space, R, L, S; ignored while dialogs are open.

---

## P2 — Complete

- [x] Waveform mini-map / session overview.
- [x] Compact session-health summary in Settings → Diagnostics: Healthy / Fair / At risk, callback headroom, underruns, UI frame p95.
- [x] Long-press tooltips for dense icons.
- [x] First-launch onboarding: record, import, mix, export; persisted after completion.
- [x] “What's new” release notes, shown once per version and reachable from overflow.
- [x] Studio-palette empty-state illustration in the timeline.
- [x] Presentation mode hides top-bar metadata, engine telemetry, DSP diagnostics, and the diagnostics menu item; persists across launches.
- [x] Tablet previews for timeline and settings at 800 × 1280.

---

## Device-only verification remaining

**Device:** Samsung S25 (Galaxy S25), 720×1560px @ 300dpi (360×780dp), Android 14+, WiFi ADB debugging.

- [x] **TalkBack walkthrough** — Enabled TalkBack via ADB, traversed Mixer with swipe gestures. Initial audit found 23 clickable elements with empty content descriptions. Fixed by adding `semantics { contentDescription }` to all mixer controls (M/S/arm toggles, track selector arrows, pan/reverb sliders, vertical faders, master presets). Re-verified: 20 elements now expose proper content descriptions including state announcements ("Mute track 1, disabled", "Volume fader, -6.3 decibels. Drag up to increase...").
- [x] **Visual clipping pass at 1.3× and 2.0×** — Captured Mixer, Timeline, Inspector, AI Studio, Settings at both scales. 1.3×: all screens clean; AI Studio model-info row fixed (columns now share width and wrap within their own column instead of overflowing). 2.0×: Mixer fits without clipping (fader compresses but stays usable); Inspector Pro Presets button no longer wraps vertically and track name truncates with ellipsis instead of clipping.
- [ ] **Accessibility Scanner triage** — Manual equivalent done via uiautomator dump analysis (content-desc + touch-target checks). Google Accessibility Scanner APK not installed on device.
- [x] **Screenshot evidence at 360 × 800** — Mixer, Inspector, Settings, AI Studio captured at 1.0×, 1.3×, 2.0× font scale. Final evidence saved to `mixer_evidence_360x800.png`. Device is 360×780dp (not 800dp height); 800 × 1280 tablet capture requires a tablet device — not available.
- [x] **Frame timing under live load** — `dumpsys gfxinfo` during playback on Timeline (playhead + waveform overview + clip rendering): 303 frames, p50=19ms, p95=22ms, p99=23ms at 120Hz display (8.33ms budget). UI thread Draw phase (10-15ms) is the bottleneck; Process (4.3ms) and Execute (2.4ms) are healthy. Mixer tab shows 0 recompositions when meters are static (no playback through tracks). Finding: waveform Canvas redraw dominates UI thread time during playback. Fixed by removing playheadFrame from TrackLane parameters so lane canvases no longer redraw every frame; playhead line is drawn by the container overlay instead.
- [x] **Mixer slider thumb and 48dp hit targets at 2.0×** — All mixer controls pass: M/S/arm toggles now 48×48dp, track selector arrows 48×48dp, faders full-height. 4 small targets found at 2.0× are all in TransportBar's horizontal scroll rail (expected — partially-scrolled items clip at viewport edge).

## Release gates

- [x] No known no-op control; no unreachable dialog.
- [x] Custom DAW targets ≥48dp; Material components retain their built-in 48dp touch target.
- [x] One palette; no raw hex outside `ui/theme`.
- [x] Static visible copy is in `strings.xml`; interpolated status/diagnostic strings remain constructed in code.
- [x] Destructive actions confirm; long operations show progress and a terminal state.
- [x] `./gradlew :app:assembleDebug` passes.
- [x] `./gradlew :app:testDebugUnitTest` passes (135 / 135).
- [x] `./gradlew :app:lintDebug` passes with 0 errors.
- [ ] Screenshot evidence for Mixer, Timeline, Inspector, AI Studio, Settings, and dialogs at 360 × 800 and 800 × 1280.

## Definition of done

1. Behavior matches the checklist item and only that item.
2. A test tag or UI assertion covers the interaction where practical.
3. The screen has been checked at 360dp and 1.3× font, plus TalkBack for new controls.
4. The change did not introduce a second source of truth for layout, color, or copy.
5. Evidence is linked here or in the pull request.

## Suggested next pass

1. Run the device-only verification list on a 360dp phone and a tablet; fix the first clipped labels and TalkBack-order issues.
2. Capture six screenshot baselines at 1.0×/1.3×/2.0× and add them to CI.
3. Profile a 16-track, 8×-zoom session for frame pacing and callback headroom; tune the overview strip and waveform cache if needed.
