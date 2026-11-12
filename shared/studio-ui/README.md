# shared/studio-ui

One implementation of the studio controls used by NC Studio and NC Phone Beat,
with a strict desktop mirror in NC Sound Lab.

| File | Role |
|---|---|
| `spec/studio-ui.v1.json` | Palette, knob/fader/meter geometry, button and waveform rules. Source of truth. |
| `src/main/kotlin/com/overlay/studio/ui/StudioControls.kt` | Compose implementation: `StudioKnob`, `StudioFader` (vertical/horizontal), `StudioLevelMeter`, `StudioButton`, `StudioSection`, `StudioChoiceChip`, `StudioWaveformPeaks`. |
| `new dsp/src/components/studio/tokens.ts` | Sound Lab token mirror, validated against the spec by `tokens.test.ts`. |
| `new dsp/src/components/studio/StudioControls.tsx` | Sound Lab knob, fader, meter bar and button. |

## How the apps consume it

NC Studio and NC Phone Beat add the Kotlin source directory in their app
`build.gradle.kts`:

```kotlin
sourceSets {
    getByName("main") {
        kotlin.srcDir("../../shared/studio-ui/src/main/kotlin")
    }
}
```

Sound Lab imports the adapter components (`Knob`, `Fader`, `MasterMeter`) which
delegate to `components/studio`, so call sites do not change.

## Interaction rules

- **Knob** — 270° arc starting at 135°, 240 px full-range vertical drag,
  double-tap reset, OLED readout. Shift is fine, Ctrl/Cmd coarse, Alt snaps to
  integers on desktop; arrow keys and typed values work as before.
- **Fader** — absolute positioning: a tap or drag lands on the value under the
  finger. Double-tap resets. Vertical for channel strips, horizontal for NC
  Studio's inline mixers.
- **Meter** — -60..0 dB, fixed -20 dB reference, green below -12 dB, amber to
  -3 dB, red above, peak-hold decaying at 0.006 per tick.
- **Button** — 48 dp minimum on Android, 36 px on desktop, monospace uppercase
  label, accent border, `selected` acts as a toggle.

## Adding or changing a control

1. Edit the spec first.
2. Implement it in `StudioControls.kt`.
3. Mirror the token change in `tokens.ts` and the behaviour in
   `StudioControls.tsx`.
4. Run `npx vitest run src/components/studio/tokens.test.ts` in `new dsp`; it
   reads the spec from this directory and fails on any drift.
