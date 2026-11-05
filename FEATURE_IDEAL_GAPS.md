# NC Studio — Feature inventory, ideal use, and gaps (v1.3.1)

Companion to COMPARISON.md. One row per feature: **How it works now**, then **Ideal user use**, then **To get there**. Status legend: Done, Partial, Gap, Future.

---

## 0. Shell & navigation

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Tabs (Mixer/​Timeline/Inspector/AI Studio/Settings) | Tab state persists via rememberSaveable; ProjectState persists settings; Back returns to Mixer. | Always land where the artist left off; instant tab switch even during playback. | Nothing major — add tests for tab switch under playing state. |
| Phone / tablet layouts | ≥700 dp two-pane, ≥840 dp three-pane; edge-to-edge padding consumed. | Tablet shows mixer+timeline at once; phone stays one tab. | Only 10 previews; capture tablet screenshots. |
| Presentation mode | Hides top-bar metadata, engine telemetry, DSP diagnostics, menu. | One tap for “show for client”. | Add persists + restore test. |
| Onboarding + “What's new” | First-run pointers persisted; shown once per version. | Never repeated annoyances. | Verify across reinstalls. |
| Presentation / shortcut keys | Space/R/L/S ignored with dialogs. | Full transport shortcuts while not typing. | Document shortcuts; add hot help. |
| Deep links ncstudio://open | Validated; path never maps to file. | Shareable link to a surface. | Map to project open (safe) without arbitrary path. |

## 1. Project & session

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| New / Save / Save As / Open | ProjectController flows + recovery journal; save restores project. | One clear file-extension format; share between phones. | Define exportable project package (.ncproj zip) tested end-to-end. |
| Crash recovery | Crash-safe journal + manifest; restores on launch. | Invisible to user; never loses clips. | Add “discard recovery?” prompt + telemetry counter. |
| Last-session snapshot | Saved/restored. | Open directly to mix view where left. | Already ~done. |
| Import source format | Dialog chooses Stems / 2-track beat / Premaster; persisted on session + track. | Smart-detect stems vs finished mix; never desync. | Auto-detect based on track count/clipping. |
| Undo/redo | Clip edits + param coalescing; one entry per gesture; MoveClipAcrossTracks preserves both. | Universal undo including AI actions and routing. | Include AI preset-load in undo journal (currently undoable banner). |
| Multi-project management | None (single working session). | Library of projects with search. | Projects list + sort; preview snapshot per project. |

## 2. Transport & playback

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Play / pause / stop | Engine controls; status updates. | Sample-accurate; keyboard Space. | Prove on device audio. |
| Rewind / FF one bar (hold) | New hold-to-repeat in TransportStepButton. | Smooth for long sessions. | Tune repeat cadence on device. |
| Loop region | Shade + draggable edges target 48 dp; snap guide flashes. | Marker bar numbers on ruler; region follows tempo. | Link grid to bars from tempo (done); test tempo changes. |
| Punch in/out | UI+logic, clearable; shaded region. | Pre-roll count-in; big ARM visual. | Add countdown animation; verify audio arm routing. |
| Metronome | Engine-level sample-locked engine. | Audible count clicks; editor available. | Expose sound selection. |
| Count-in | Engine-level. | Pre-count before record with lights. | Verify alignment ±1 frame. |
| Tempo / time signature | Stored; grid derives bars. | Change-safe without shifting clips; tap tempo. | Add tempo change stability test. |
| Playhead drag | Draggable PlayheadHandle on ruler; seek. | Exact seek with haptic at bars/beats. | Add fine drag ramp above 4× zoom. |
| A/B comparison | Toggleable; disables when preconditions unmet. | Quick A/B with level matched loudness. | Verified on device still pending. |

## 3. Recording

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Multi-track audio recording | Per-track arm; clip appears; red REC badge. | Always-ready armed track; record counts. | Device verify capture quality. |
| Input monitoring | State + toggle only; engine routing partial. | Toggle Input monitor hears mic; no feedback if headphones detected. | Connect toggle to engine monitor path; wired headphones check. |
| Overdubbing | Supported. | Punch-style overdub. | Same as punch. |
| USB audio interface | USB audio manager + settings dialog + refresh. | Plug-and-play device selector with sample rate display. | Refresh + detect hook validated on device. |
| Take management / comping | Single take per record; no lanes. | Swipe takes, comp regions, rename, color. | Add take lanes data model + UI; reuse session take models. |
| Noise reduction / cleanup | None. | One-click denoise before mix. | Add spectral noise gate module + presets. |
| AutoPitch | None. | Basic monophonic correction. | Integrate pitch module; tune scale/speed UI. |

## 4. Timeline & clip editing

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Trim/move/split/fade | Real DSP compositing; handles multiply when grouping. | Smooth handles; audible feedback. | Device FPS pass. |
| Split at frame | Split-at-frame tool; shortcut. | Hover line, marker. | Tiny-clip mapping should use clip max(28dp) carefully. |
| Cross-track drag + ghost | One-move with undo; source ghost. | Keep source until released. | Already shipped; polish haptic. |
| Multi-select + operations | Visible count, apply to selection. | Rectangular marquee; shift modifier. | Touch-friendly drag-box. |
| Nudge / duplicate | Buttons + multi-select. | Arrow keys. | Keyboard mapping. |
| Waveform overview strip | Clip density + loop + viewport + tap-to-seek. | Drum-level detail at long zoom. | Amplitude cache test. |
| Frame pacing evidence | p95 + slow-frame share. | Auto-reduce wave detail when slow. | Policy: reduce detail automatically. |
| Automation lanes | None. | Per-track volume/pan/effects lane with nodes. | Add automation data classes + render nodes; undo wiring. |

## 5. Mixer

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Single-track + master | Selected track + master bus view. | Swipe/tap arrows; level meter. | Verify master mute/solo. |
| Pan center detent | Snap-to-center path. | Visual tick at 0. | Add detent sound tick? |
| Meters clip latch | Tap clears latch. | Latch persistence toggle. | Nothing. |
| EQ curve + compressor meter | Visual curve + comp GR meter. | Resizable graph. | — |
| Track + master presets | Apply from banner; undoable. | Save custom presets. | Add custom preset store. |
| A/B master | Toggle mix state. | Compare against master bounce. | Wire bounce output path. |

## 6. Inspector & plugin chain

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Plugin modules | EQ 4-band, comp, sat, chorus, ducking delay, limiter, send, master reverb, master limiter. | Section by flow (signal chain); clearly labeled. | Keep disclosure under comp; readability tests. |
| Scrollable plugin bar | One at a time with arrows. | Swiping nav drawer. | — |
| Mix templates (4 genre) | Genre templates with real params. | Editable + save as. | Custom template persistence. |
| Vocal chain preset | Canonical order incl. delay→reverb. | One-click chain. | Add genre variants. |
| De-esser / multiband / true peak | None. | Module cards. | Add DSP kernels and metering. |

## 7. DSP & metering

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Meter (RMS dBFS) | dBFS RMS meter; no LUFS deception. | Add true-peak + integrated LUFS if CTN. | Implement stream LUFS + ITP. |
| Session health diagnostics | Healthy / Fair / At risk; headroom/underruns/p95. | In-app badge; export report. | Connect to bug report. |
| Spectrum analyzer | None. | Real-time FFT. | Add FFT module + view. |
| Master LUFS targeting | Target for export. | Track-side loudness meters. | Broadcast master meter values. |

## 8. AI Studio

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| MixAdvisor (local) | Rules based; skips stem surgery on 2-track; gentle bus moves. | Pointer per track; never silent. | More intents. |
| MxEngineerBrain planner | Natural-language → tool calls; vocal bus glue. | Typed controls flow into AI planning. | Boost measured-LUFS fallback. |
| enforceSourceFormat | Removes stem tools + clamps gains. | Robustly safe; missing args pass-through fixed (open). | Fix missing-arg clamp (minor). |
| Templates apply via AI | Plans apply chains. | Auto-apply genre template w/ measured match. | Auto-detect genre. |
| LLM backends | Mesh + 120 s timeout; reasoning_content fallback. | Reliable offline model resolution. | Cache model list offline; handle missing. |
| Reference matching | Loads reference, analysis; finds mismatched features. | Drag reference file or Spotify/Apple (clip permission). | Export analysis; online search disabled by design. |
| A/B + auto match | Applies to session. | Side-by-side compare after match. | Add compare-playback. |
| Local model detector | List models. | Auto-select best available. | Add adaptive resource settings. |

## 9. Export & delivery

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| WAV 24-bit | Full render. | Loudness gate passed. | — |
| AAC 320 | MediaCodec+fallback. | Real encoded MP3/FLAC too. | Swap fallback helpers; tests. |
| FLAC export | MediaCodec+ WAV fallback. | Native FLAC encoder. | Verify output decoding on device. |
| Normalization + LUFS targeting | Both supported. | Modal preview of loudness. | Add preview waveform. |
| Stems export | Reports per-stem. | No issue if exists? COMPARISON says gap, UI list says done; verify ExportController output. | Resolve conflict: confirm actual stem export implementation & tests. |
| Export metadata | Dest, duration, SR, bits/bitrate, estimated size. | Track-realtime progress. | Add progress callbacks. |
| SAF picker + persist dest | Last destination restored. | Undo export restore project. | — |
| FileProvider | Exports+takes. | Share links to cloud opt. | — |
| Project package | Not yet. | zip project w/ assets. | Add zip bundler. |

## 10. Audio I/O & latency

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Latency calibration | Step-by-step; loopback per route; re-measure on change. | One-tap. | Edge: wrong route detect. |
| Input monitoring toggle | State only (routing partial). | Robust. | Finish routing. |
| USB manager | Detect/refresh. | Hot-plug announcement. | Device verify. |
| Foreground service | AudioEngineService runs. | Keep running in background. | Add foreground notification. |
| Remote session recording runs foreground screen. | Mono take recording foreground. | Lock-screen record. | Mic foreground service. |

## 11. Remote session (Beta)

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Chunk recorder / takes | Robust chunked take store. | Smooth remote session. | Device verify C. |
| Engineer flow | Create session, code; arm, record, download. | Real devices. | Two-phone proof. |
| Monitor / talkback | AAC over WS jitter buffer. | WebRTC/TURN low latency. | WebRTC stage. |
| Import verified take onto DAW track | Gap, writer exists. | Auto-import. | Small wiring. |
| Timeline sharing | Gap; fixed lead schedule. | Engineer timeline sync. | Share transport state. |
| Reconnect resiliency | Strong test coverage. | — | Field test. |

## 12. Settings & diagnostics

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Settings sections | Engine, Metering, Recording, Storage, USB, AI, Diagnostics. | Compact. | Reduce scrolling. |
| Cache management | Size shown; clear w/ confirm. | Per-project cache. | Cache labels. |
| Shared sound library | SharedSoundLibrary; import sounds. | Cross-project. | Shelf with tags. |
| Analytics | Local counts; bug report snapshot. | Never transmit. | Keep. |
| DSP diagnostics top-bar | Moved to overflow; run DSP tests. | — | — |

## 13. Accessibility / visual

| Feature | How it works now | Ideal use | To get there |
|---|---|---|---|
| Strings in XML (430+) | All UI copy. | Localizable languages. | Add locale resources. |
| Themes/tokens | Single palette; no raw hex. | Optional mid-dark themes? | — |
| Touch targets 48 dp | Verified for custom. | Tablet/tablet orientation. | — |
| TalkBack/Semantics | onClick, descriptions, state descriptions, reduced motion. | Full audit passes. | Device TalkBack pass. |
| Screenshots | No baselines in CI yet. | Automated regression. | Capture + CI gates. |

## 14. Cross-cutting gaps (priority)

**Tier 1 (competitive parity, next sprint):**
1. Take lanes + comping
2. AutoPitch / pitch correction
3. Noise reduction
4. Automation lanes
5. Stems export verification
6. Input monitoring routing
7. MP3/FLAC real encoding verification

**Tier 2 (differentiators):**
8. De-esser, multiband comp, true-peak limiter
9. Spectrum analyzer
10. Chip load improvements? Keyboard shortcuts expansion
11. Custom mix templates save

**Tier 3 (parity):**
12. Group/bus routing
13. Sidechain
14. Sends/returns
15. Time stretch/elastic audio
16. MIDI basic
17. WebRTC monitor stage (remote session)

**Tier 4 (AI/product):**
18. Local LLM reliable offline model resolution + model auto-select
19. Auto-mix one-button
20. Stem separation (source separated)
21. Genre auto-detect / template application
22. Loop pack & sound library metadata

**Verification (instrumental tests needed):**
- Device pass: audio playback/record/punch/monitor/talkback/take flow.
- TalkBack walkthrough all screens.
- Visual clipping 1.3×/2.0×.
- Frame pacing 16-track at 8× zoom.
- Release signing + install.
- Two-phone remote session.
- Golden screenshot baselines in CI.