# Changelog

All entries below describe changes that were actually run, not planned work.
Earlier history was not recorded; this file starts at the first verified baseline.

## 1.3.0 — 2026-10-28 — first verified test run

No feature code was changed in this entry. It records the first full unit run
of the DAW as a baseline.

- `sh gradlew :app:testDebugUnitTest`: BUILD SUCCESSFUL in 6m13s.
- 51 test suites, 356 tests, 0 failures, 0 errors (read from the JUnit XML).
- Existing engine, transport, project and UI-state tests all pass under the
  Gradle unit-test JVM.

Not covered by this entry: no release APK was built and no audio path has been
exercised on a physical device. See `docs/DEPLOY-READINESS.md`.
