# NC Studio unit-test run — 2026-10-28

Environment: Windows 11, Gradle wrapper, Android unit-test task.

Command:

```bash
cd "phone-apps/nc-studio"
sh gradlew :app:testDebugUnitTest --console=plain
```

Result:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 6m 13s
30 actionable tasks: 5 executed, 25 up-to-date
```

JUnit XML totals:

```text
suites=51 tests=356 failures=0 errors=0
```

Warnings observed: one deprecated `getReceiversForIntent` call in
`TransportLifecycleTest.kt`. It does not fail the build.

Limitation: unit tests only. No device install, and no audio-timing evidence.
