# NC Studio result — 2026-10-28

| Metric | Value |
|---|---|
| Version | 1.3.0 (versionCode 130) |
| Test suites | 54 |
| Tests | 360 |
| Failures | 0 (forced run, see evidence) |
| Source modules exercised | 94.8% |
| Source LoC | 32,093 |
| Test LoC | 7,841 |
| Device install | not performed |
| Audio-path evidence | none |

Command:

```bash
cd "phone-apps/nc-studio"
sh gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-build-cache --console=plain
```

The suite covers engine controls, transport lifecycle, project/edit state,
serialization, deep-link validation and local analytics. It does not prove audio
latency, routing changes, an installed APK or a release-signed build.
