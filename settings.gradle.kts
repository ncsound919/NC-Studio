pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

// Shared libs resolve in this order:
//   1. `shared/kotlin-core` vendored in this repo (clean clones + CI — pinned
//      in `shared/VENDOR_SHA.txt`, see `shared/README.md`).
//   2. `../shared/kotlin-core` workspace checkout (local dev when this repo
//      sits next to an `overlay-shared` checkout as `the system/shared`).
// The vendored copy wins so release/CI builds never float with upstream HEAD.
if (file("shared/kotlin-core").isDirectory) {
    includeBuild("shared/kotlin-core")
} else if (file("../shared/kotlin-core").isDirectory) {
    includeBuild("../shared/kotlin-core")
} else {
    throw GradleException(
        "Missing shared/kotlin-core: expected vendored `shared/kotlin-core` " +
        "or workspace `../shared/kotlin-core`. " +
        "See shared/README.md for re-vendoring."
    )
}

rootProject.name = "NC Studio"

include(":app")
// `../new dsp` holds an older Kotlin plugin prototype (`com.overlay.dsp`). It is
// deliberately not part of this build: nothing imports it, and the shipping
// plugins come from shared/kotlin-core. To build it again, restore
// include(":newdsp") and point its projectDir at "../new dsp" (the folder name
// has a space, so the default directory does not resolve).