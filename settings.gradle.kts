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
include(":newdsp")
// The sibling DSP project lives in a folder whose name contains a space, so
// the default `newdsp/` project dir does not exist and configuration fails.
project(":newdsp").projectDir = file("../new dsp")