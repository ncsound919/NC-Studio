# Vendored shared libraries (clean-clone build)

This directory vendors the two `overlay-shared` subsets that NC Studio
compiles, so a clean clone builds with **no extra checkout and no network
fetch**:

- `kotlin-core/` — `com.overlay:kotlin-core:1.0.0` sources + tests
  (composite build, see `../settings.gradle.kts`)
- `studio-ui/` — Compose studio controls, compiled as a source dir
  (see `../app/build.gradle.kts`)

## Pinned upstream

- Upstream repo: `https://github.com/ncsound919/overlay-shared`
- Pinned commit: `2528abd98ad5aeba54dd07f69230dfdc42b06dd1`
- Pinned date: `2026-11-07`
- Pinned subject: `chore: snapshot uncommitted work before desktop-ops bootstrap`

CI enforces this pin: `.github/workflows/android-ci.yml` runs
`sh shared/verify-vendor.sh`, which checks every vendored file against
`shared/VENDOR_MANIFEST.sha256` and fails the build on any drift.

`.gitattributes` forces `shared/**` to LF line endings so the hashes are
byte-identical on every OS; do not change that rule.

## What is vendored (and what is not)

Vendored: `kotlin-core/build.gradle.kts`, `kotlin-core/settings.gradle.kts`,
`kotlin-core/README.md`, `kotlin-core/src/`, `studio-ui/README.md`,
`studio-ui/spec/`, `studio-ui/src/`.

Deliberately **not** vendored: `kotlin-core/build/`, `.gradle/`, the Gradle
wrapper (`gradlew*`, `gradle/`), `kotlin-core/.github/`, and the
`audio-fx/` parity fixtures (only needed to run kotlin-core's own DSP
fixture tests upstream via `audio.fx.fixtures`).

## Updating the vendor

From the workspace root (`the system/`, where `shared/` is the
`overlay-shared` checkout):

```bash
VENDOR=nc-studio/shared
cp shared/kotlin-core/build.gradle.kts shared/kotlin-core/settings.gradle.kts \
   shared/kotlin-core/README.md $VENDOR/kotlin-core/
rm -rf $VENDOR/kotlin-core/src && cp -r shared/kotlin-core/src $VENDOR/kotlin-core/src
cp shared/studio-ui/README.md $VENDOR/studio-ui/
rm -rf $VENDOR/studio-ui/spec $VENDOR/studio-ui/src
cp -r shared/studio-ui/spec $VENDOR/studio-ui/spec
cp -r shared/studio-ui/src $VENDOR/studio-ui/src
git -C shared rev-parse HEAD > $VENDOR/VENDOR_SHA.txt
# Normalize to LF (must match .gitattributes), drop Gradle caches,
# then regenerate + verify the manifest:
find $VENDOR -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.md' \
  -o -name '*.json' -o -name '*.txt' -o -name '*.sh' \) \
  -exec sed -i 's/\r$//' {} +
rm -rf $VENDOR/kotlin-core/.gradle $VENDOR/kotlin-core/build \
       $VENDOR/studio-ui/.gradle $VENDOR/studio-ui/build
( cd nc-studio && \
  sha256sum shared/VENDOR_SHA.txt shared/README.md shared/verify-vendor.sh \
    $(LC_ALL=C find shared/kotlin-core shared/studio-ui -type f | LC_ALL=C sort) \
    > shared/VENDOR_MANIFEST.sha256 && \
  sh shared/verify-vendor.sh )
```

Then update the "Pinned upstream" block above and commit.

Any hand-edit to a file under `shared/kotlin-core/` or `shared/studio-ui/`
breaks the manifest on purpose — re-vendor (or deliberately regenerate the
manifest) instead of editing the copy in place.
