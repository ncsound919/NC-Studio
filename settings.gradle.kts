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

includeBuild("../shared/kotlin-core")

rootProject.name = "NC Studio"

include(":app")
// `../new dsp` holds an older Kotlin plugin prototype (`com.overlay.dsp`). It is
// deliberately not part of this build: nothing imports it, and the shipping
// plugins come from shared/kotlin-core. To build it again, restore
// include(":newdsp") and point its projectDir at "../new dsp" (the folder name
// has a space, so the default directory does not resolve).