# Deploy readiness — NC Studio (Pulse DAW)

**Status: unit-test verified, not device verified.**
Last verified: 2026-10-28.

## What was verified

| Check | Command | Result |
|---|---|---|
| Unit tests | `sh gradlew :app:testDebugUnitTest` | 51 suites, 356 tests, 0 failures |
| Build | Gradle task graph completed | BUILD SUCCESSFUL |

Covered by the suite: engine controls, transport lifecycle, project state,
track/clip editing, and the Compose UI state that can be tested off-device.

## What was not verified

- No release APK/AAB was produced in this run.
- The audio path (engine start, low-latency callback, recording) has not been
  measured on a physical phone. Off-device tests cannot prove audio timing.
- No install/launch smoke test on a device.
- No store listing is planned, so signing and metadata were not checked.

## Beta gate remaining

1. Build a debug APK and install it on the phone.
2. Run one session end to end: create a project, add two tracks, record a few
   seconds, play back, export.
3. Record the result as one sentence in this file, including any audio glitch
   observed.

## Reproduce

```bash
cd "phone-apps/nc-studio"
sh gradlew :app:testDebugUnitTest --console=plain
```
