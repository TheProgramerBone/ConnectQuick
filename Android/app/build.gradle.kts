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
    // Release builds only talk to the relay over TLS (wss://). Plain ws:// is for local development and
    // is allowed by the debug build only (src/debug/res/xml/network_security_config.xml).

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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // CameraX + ML Kit on-device barcode scanning for the QR pairing flow.
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Protocol, cryptography, session state machine, touchpad engine and feature clients live in the
    // pure-JVM composite build ./controller-core (so they are unit-tested on any machine).
    implementation("dev.connectquick:controller-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Receive-only WebRTC for the live screen view (org.webrtc API; community build of libwebrtc).
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    testImplementation("junit:junit:4.13.2")
}
