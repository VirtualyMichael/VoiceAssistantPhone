import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.voiceassistant.phone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.voiceassistant.phone"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.15.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // the phone is 64-bit ARM; leaving the other chips out keeps the sherpa-onnx library from adding tens of MB
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    androidResources {
        noCompress += "onnx" // the models are read straight from the app package
    }

    // The release key lives outside the project (never in git): ~/.android-keys/voiceassistant-release.properties names the keystore and its passwords.
    // Without that file (another machine) the release build is simply left unsigned.
    val releaseKeyFile = File(System.getProperty("user.home"), ".android-keys/voiceassistant-release.properties")
    val hasReleaseKey = releaseKeyFile.exists()
    val releaseKey = Properties()
    if (hasReleaseKey) releaseKey.load(FileInputStream(releaseKeyFile))
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = File(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**") // Kotlin and kotlinx are left alone: shrinking them crashed the release build at start
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.biometric:biometric:1.1.0") // the fingerprint or PIN check for safety settings
    implementation("androidx.fragment:fragment:1.8.5") // biometric brings an old one, which a release build's checks refuse (activity results)
    implementation("com.google.zxing:core:3.5.3") // reads the pairing QR code (Apache-2.0)
    implementation("androidx.camera:camera-core:1.4.2") // the in-app camera
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2") // recording clips for "watch this"
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1") // experiment: an AI model on the phone itself (Apache-2.0), see OnDeviceModel
    implementation(files("libs/sherpa-onnx-2.0.0.aar")) // on-device wake word and voice detection (Apache-2.0)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // Android's own org.json is a stub in plain unit tests
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}