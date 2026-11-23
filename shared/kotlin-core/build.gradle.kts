plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.10"
    `java-library`
}

group = "com.overlay"
version = "1.0.0"

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // Android provides org.json at runtime; this is compile-time only.
    compileOnly("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    // Parity fixtures are written only when explicitly requested; normal test
    // runs compare against the committed file so a drifted DSP fails the build.
    systemProperty("audio.fx.fixtures", System.getProperty("audio.fx.fixtures", file("../audio-fx").absolutePath))
    systemProperty("audio.fx.emit", System.getProperty("audio.fx.emit", "false"))
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
