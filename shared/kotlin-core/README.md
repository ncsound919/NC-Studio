# kotlin-core

Pure Kotlin/JVM library shared by every Android app in `phone-apps/`.

Deliberately **not** an Android library: apps range from AGP 9/Gradle 9.3.1 to
Gradle 8.13, and a JVM 11 artifact is consumable by all of them. `org.json` is
`compileOnly` because the Android runtime provides it.

## What lives here

- `com.overlay.llmmesh.LlmMesh` — the canonical Android client for
  `shared/llm-mesh` (`/v1/chat/completions`, `/v1/route`, `/v1/models`,
  `/v1/decisions`, `/v1/recall`, ecosystem hops).
- `com.overlay.tondo.TondoResearch` — the client for Tondo's
  `/v1/research/assist` hand-off. Seven apps consume it (`nc-studio`, `mpc`,
  `concordance`, `concordance-oss`, `agent-support`, `marketing-studio`, and
  `tondo/android`).
  Cheat Code keeps a Kotlin 2.0-compatible local copy because its build cannot
  read this module's 2.2 metadata; the wire format is the same.

- `com.overlay.audio.sample.SliceFinder` — deterministic onset slicing for samplers:
  gate crossings plus sharp rises inside continuous material, cuts snapped to
  zero crossings, and an attack/decay/brilliance profile. It is the native,
  model-free part of what the open source `earshot` CLI does when it finds the
  moments in a recording (earshot itself is Python with models and does not
  ship here). Used by `phone-beat/mpc`'s THRESHOLD chop preset and by tap
  slicing, and available to every other app.

Next: audit DTOs (`AuditFinding`, `EvidenceTier`, `JevDecisionModel`, SARIF) and
ecosystem contract types.

## Use from an app

`settings.gradle.kts`:

```kotlin
includeBuild("../shared/kotlin-core")
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.overlay:kotlin-core:1.0.0")
}
```

The package stays `com.overlay.llmmesh`, so source imports do not change. Do not
keep a local `LlmMesh.kt` copy in the app.

`phone-beat/mpc` uses `includeBuild("../../shared/kotlin-core")` because it is one
directory deeper.

## Build

```bash
./gradlew build
```

The CI workflow is `.github/workflows/ci.yml`.
