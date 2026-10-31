plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.ncstudio.daw"
    minSdk = 24
    targetSdk = 36
    versionCode = 131
    versionName = "1.3.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Tondo research assist. Empty by default: the local model remains the
    // only path until a build supplies both values.
    val tondoUrl = System.getenv("TONDO_RESEARCH_URL") ?: ""
    val tondoKey = System.getenv("TONDO_RESEARCH_KEY") ?: ""
    buildConfigField(
      "String",
      "TONDO_RESEARCH_URL",
      "\"${tondoUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    )
    buildConfigField(
      "String",
      "TONDO_RESEARCH_KEY",
      "\"${tondoKey.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    )
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  // One studio control implementation for NC Studio and NC Phone Beat. The
  // shared source directory is compiled into this app, not copied into it, so
  // a knob/meter/button change lands in both apps at once.
  sourceSets {
    getByName("main") {
      kotlin.srcDir("../../shared/studio-ui/src/main/kotlin")
    }
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.maxHeapSize = "2048m"
        // A blank session no longer allocates one song-length buffer per lane,
        // so 2 GB is enough for DSP fixtures plus Robolectric without masking
        // an allocation regression.
        it.forkEvery = 1
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Robolectric's Android SDK 36 runtime requires Java 21. Pin only the test
// worker JVM so `./gradlew test` works even when the daemon runs on JDK 17.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
  javaLauncher.set(
    javaToolchains.launcherFor {
      languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
    }
  )
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

dependencies {
  implementation("com.overlay:kotlin-core:1.0.0")
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.documentfile)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.androidx.work.runtime.ktx)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
