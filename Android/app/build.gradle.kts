plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.connectquick.controller"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.connectquick.controller"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Single-ABI debug build to keep the sideloadable APK small — covers the vast majority
        // of real Android phones. A release build would ship split APKs/an AAB for all ABIs instead.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    sourceSets {
        getByName("main").kotlin.srcDirs("src/main/kotlin")
        getByName("test").kotlin.srcDirs("src/test/kotlin")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Points JVM unit tests at the repo root so they can read Protocol/test-vectors/crypto-vectors.json
// and spawn the real Backend/src/server.ts directly — never a platform-local copy of either.
tasks.withType<Test> {
    systemProperty("connectquick.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    testLogging { events("passed", "skipped", "failed") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // CameraX + ML Kit on-device barcode scanning for the QR pairing flow.
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Protocol transport: WebSocket envelope framing + JSON, same shape as WindowsHost's BackendClient.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Ed25519/X25519 (RFC 8032/7748) — pure JVM, so the crypto+protocol layer is unit-testable
    // without a device/emulator. See Crypto/CryptoUtil.kt for why this replaces lazysodium here.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
}
