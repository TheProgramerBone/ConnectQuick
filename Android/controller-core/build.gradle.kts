plugins {
    `java-library` // `api` dependencies are re-exported to the app
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
}

// Pure-JVM module: the protocol, cryptography, session state machine, touchpad gesture engine and
// every feature client (clipboard, media, files, power). It has NO Android dependency, so it
// compiles and its tests run on any machine with a JDK. The Android app (:app) adds only what is
// genuinely platform-specific: Keystore, camera, views, WebRTC rendering.
group = "dev.connectquick"
version = "0.2.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        // The shared contract is bundled as a resource: the very same JSON Schemas the Backend and
        // the Windows Host validate against.
        resources.srcDir("../../Protocol/schemas")
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Ed25519/X25519 (RFC 8032/7748): pure JVM, so the crypto + protocol layer is unit-testable without a device.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// Points JVM tests at the repo root so they can read Protocol/test-vectors/* and spawn the real
// Backend/src/server.ts — never a platform-local copy of either.
tasks.withType<Test> {
    systemProperty("connectquick.repoRoot", rootProject.projectDir.parentFile.parentFile.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Prints the classpath of the headless Controller harness (src/test/.../harness/ControllerHarness.kt),
// which Tests/ launches as a separate process:  gradle -q printHarnessClasspath
tasks.register("printHarnessClasspath") {
    dependsOn("testClasses")
    doLast {
        val cp = sourceSets.test.get().runtimeClasspath.asPath
        println(cp)
    }
}
