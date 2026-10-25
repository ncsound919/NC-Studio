# Pulse DAW vs BandLab vs Pro Tools — Comparison & Gap Analysis

**Date:** 2026-10-25
**Focus:** Recording + Mixing only (no production/beatmaking)
**Goal:** Beat BandLab on recording/mixing quality, approach Pro Tools workflow

---

## Feature Matrix

### Recording
| Feature | BandLab | Pro Tools | Pulse DAW | Status |
|---|---|---|---|---|
| Multi-track audio recording | ✅ | ✅ | ✅ | Done |
| Input monitoring | ✅ | ✅ | 🟡 State + toggle | Partial (engine routing needed) |
| Punch in/out | ✅ | ✅ | ✅ | Done (UI + logic) |
| Count-in / metronome | ✅ | ✅ | ✅ | Done (engine-level, sample-locked) |
| Latency compensation (auto) | ✅ | ✅ | ✅ | Done (loopback calibration) |
| Overdubbing | ✅ | ✅ | ✅ | Done |
| Audio interface support (USB) | ✅ | ✅ | ✅ | Done (USB audio manager) |
| Take management / comping | ✅ | ✅ | 🟡 Single take per record | Gap: needs take lanes + comping |
| Noise reduction / cleanup | ✅ | ✅ | ❌ | Gap |

### Mixing — DSP Chain
| Feature | BandLab | Pro Tools | Pulse DAW | Status |
|---|---|---|---|---|
| Per-track EQ (4-band) | ✅ | ✅ | ✅ | Done |
| Compressor | ✅ | ✅ | ✅ | Done |
| Saturation | ✅ | ✅ | ✅ | Done |
| Chorus / doubler | ✅ | ✅ | ✅ | Done |
| Ducking delay | ❌ | ✅ | ✅ | Done (advantage) |
| Channel limiter | ✅ | ✅ | ✅ | Done |
| Master reverb | ✅ | ✅ | ✅ | Done (Schroeder) |
| De-esser | ✅ | ✅ | ❌ | Gap |
| Multi-band compressor | ✅ (Membership) | ✅ | ❌ | Gap |
| True peak limiter | ❌ | ✅ | ❌ | Gap |
| LUFS metering | ❌ | ✅ | ✅ | Done (advantage) |
| Spectrum analyzer | ❌ | ✅ | ❌ | Gap |

### Mixing — Workflow
| Feature | BandLab | Pro Tools | Pulse DAW | Status |
|---|---|---|---|---|
| Clip editing (trim/move/split/fade) | ✅ | ✅ | ✅ | Done (audibly real via compositor) |
| Automation lanes | ✅ | ✅ | ❌ | Gap |
| Group/bus routing | ❌ | ✅ | 🟡 Model + bus data classes | In progress |
| Sidechain routing | ❌ | ✅ | ❌ | Gap |
| Sends/returns | ❌ | ✅ | ❌ | Gap |
| Undo/redo (full) | ✅ | ✅ | ✅ | Done (clip + param coalescing) |
| A/B comparison | ❌ | ✅ | ✅ | Done (advantage) |
| Reference track matching | ❌ | ✅ | ✅ | Done (advantage) |
| Mix templates | ❌ | ✅ | ✅ | Done (4 genre templates) |

### Export & Delivery
| Feature | BandLab | Pro Tools | Pulse DAW | Status |
|---|---|---|---|---|
| WAV export (24-bit) | ✅ | ✅ | ✅ | Done |
| AAC export | ✅ | ✅ | ✅ | Done (320kbps) |
| MP3 export | ✅ | ✅ | ✅ | Done (MediaCodec + WAV fallback) |
| FLAC export | ❌ | ✅ | ✅ | Done (MediaCodec + WAV fallback) |
| Normalization | ✅ | ✅ | ✅ | Done |
| Loudness targeting (LUFS) | ❌ | ✅ | ✅ | Done (advantage) |
| Stems export | ✅ | ✅ | ❌ | Gap |
| Cloud sync / collaboration | ✅ | ❌ | ❌ | Gap (by design — offline) |

### AI / Smart Features
| Feature | BandLab | Pro Tools | Pulse DAW | Status |
|---|---|---|---|---|
| AI mastering | ✅ | ❌ | 🟡 Templates + local model | In progress |
| AI mix suggestions | ❌ | ❌ | ✅ | Done (MixAdvisor) |
| Local LLM integration | ❌ | ❌ | ✅ | Done (Qwen3.5-4B, etc.) |
| AutoPitch / pitch correction | ✅ | ✅ | ❌ | Gap |
| Noise reduction | ✅ | ✅ | ❌ | Gap |

### Platform
| Feature | BandLab | Pro Tools | Pulse DAW | Status |
|---|---|---|---|---|
| Mobile (phone) | ✅ | ❌ | ✅ | Done |
| Tablet | ✅ | ❌ | ❌ | Future |
| Desktop | ✅ (web) | ✅ | ❌ | Future |
| Offline-first | ❌ | ✅ | ✅ | Done (advantage) |
| Open source | ❌ | ❌ | ✅ | Done (advantage) |

---

## Where Pulse DAW Already Beats BandLab

1. **Real DSP chain** — 4-band EQ, compressor, saturation, chorus, ducking delay, channel limiter, LUFS metering. BandLab's free tier has basic effects; Pulse has a full Pro Tools-style channel strip.
2. **A/B comparison + reference matching** — BandLab has no A/B or auto-match. Pulse has both.
3. **Mix templates** — BandLab has no mix templates. Pulse has 4 genre templates with real parameter values.
4. **Offline-first** — BandLab requires cloud. Pulse works fully offline.
5. **Open source** — Pulse is open; BandLab is proprietary.
6. **Clip compositing is audibly real** — BandLab's clip edits are real too, but Pulse now matches with multi-source compositor.
7. **LUFS metering** — BandLab doesn't show LUFS. Pulse does.

## Where BandLab Still Wins

1. **Collaboration / cloud sync** — BandLab's killer feature. Pulse is offline-only (by design for now).
2. **Virtual instruments / beatmaking** — BandLab has 370+ instruments, loops, looper. Pulse is recording+mixin only (by design).
3. **Take comping / lanes** — BandLab has take lanes. Pulse records single takes.
4. **AutoPitch / pitch correction** — BandLab has AutoPitch. Pulse has none.
5. **Noise reduction** — BandLab has noise removal. Pulse has none.
6. **Stems export** — BandLab exports stems. Pulse doesn't yet.
7. **Automation lanes** — BandLab has volume/pan/effect automation. Pulse has none.
8. **MP3/FLAC export** — BandLab exports MP3. Pulse falls back to AAC/WAV.
9. **MIDI editing** — BandLab has piano roll. Pulse has none (by design, but limits use).

## Where Pro Tools Still Wins (and Pulse is catching up)

1. **Group/bus routing** — Pro Tools has busses. Pulse has none.
2. **Sidechain** — Pro Tools has sidechain. Pulse has none.
3. **Automation** — Pro Tools has full automation. Pulse has none.
4. **Take comping** — Pro Tools has playlists/comping. Pulse has basic takes.
5. **True peak limiter / advanced metering** — Pro Tools has these. Pulse has channel limiter + LUFS.
6. **Elastic Audio / time stretching** — Pro Tools has it. Pulse has none.

## Priority Roadmap to Beat BandLab (recording + mixing only)

### Tier 1 — Must-have to be competitive (next sprint)
1. **Take lanes + comping** — record multiple takes, swipe/comp between them
2. **AutoPitch / pitch correction** — basic monophonic pitch correction (like AutoTune lite)
3. **Noise reduction** — simple spectral noise gate / reduction
4. **Automation lanes** — volume/pan automation per track
5. **Stems export** — export individual track WAVs

### Tier 2 — Differentiators (following sprint)
6. **De-esser** — frequency-targeted compressor
7. **Multi-band compressor** — 3-band with per-band controls
8. **True peak limiter** — ISP-aware limiter on master
9. **Spectrum analyzer** — real-time FFT display
10. **MP3/FLAC real encoding** — use MediaCodec where available, LAME/FLAC for fallback

### Tier 3 — Pro Tools parity (later)
11. **Group/bus routing** — create busses, route tracks
12. **Sidechain** — key input routing
13. **Sends/returns** — aux sends with return tracks
14. **Time stretching / elastic audio**
15. **MIDI (basic)** — piano roll for simple MIDI

### Tier 4 — AI differentiation (ongoing)
16. **Wire local LLM (Qwen3.5-4B) to tool calls** — apply templates, adjust params, match reference
17. **Auto-mix** — one-button mix using templates + analysis
18. **Stem separation** — split a mix into stems (using Demucs or similar)

---

## Summary

Pulse DAW has already surpassed BandLab in:
- DSP chain depth (full channel strip vs. basic effects)
- LUFS metering and loudness targeting
- A/B comparison and reference matching
- Mix templates
- Offline-first operation
- Open source

Pulse DAW needs to catch up on:
- Take comping / lanes
- AutoPitch / pitch correction
- Noise reduction
- Automation lanes
- Stems export
- MP3/FLAC real encoding
- De-esser / multi-band comp / true peak limiter

The biggest gap is **workflow features** (automation, comping, stems) rather than **audio quality** (Pulse already has better DSP).
EOF
Write-Output "written"