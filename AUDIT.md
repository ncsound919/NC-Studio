# NC Studio audit — 2026-10-28

Scope: the Android DAW in this checkout. Verdict: **PASS at the JVM unit layer,
not device-verified.**

## What was checked

| Area | Result |
|---|---|
| Unit suite | 54 suites, 360 tests expected in the forced run |
| Module coverage | 94.8% of source modules have a matching test |
| Build | `:app:testDebugUnitTest` compiles and runs |
| Deep links | `ncstudio://open[/surface]` validated and counted |
| Analytics | local aggregate counters, reset and snapshot tested |

## Findings

1. **No way for a sibling app to open the DAW (fixed).** Added a narrow
   `ncstudio://open` entry with a validated scheme/host. The path is
   informational only; it never becomes a file path or project id, so a hostile
   link cannot open arbitrary storage.
2. **No local failure/usage signal (fixed).** Added in-memory analytics
   (`StudioAnalytics`) with no project names, paths or audio content. Counts are
   available for a bug report; nothing is sent over the network.
3. **The audio path is not proven.** Unit tests cover state and control logic,
   not the engine callback, latency, underruns or routing.
4. **No release build.** No APK/AAB was produced, and sideload signing is not
   configured in this checkout.
5. **No device install.** Cold start, background service behaviour and file
   import from a real share sheet are unverified.

## Evidence

- `evidence/2026-10-28-unit-tests.md`
- `docs/DEPLOY-READINESS.md`
