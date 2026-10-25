import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // The watch app must have the same application id as the phone app (and be signed with the same key): that is how Wear OS knows
    // the two belong together and delivers messages between them.
    namespace = "com.voiceassistant.phone.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.voiceassistant.phone"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "0.16.0"
    }
    sourceSets {
        getByName("main").kotlin.directories.add("../watchlink/src/main/java") // the same protocol file the phone app uses
    }

    // The release key is the phone app's (see app/build.gradle.kts); without it the release build is left unsigned.
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
                packageScope = setOf("androidx.**")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation("androidx.wear.compose:compose-material:1.4.1") // Compose for Wear OS: round-screen buttons, lists and text
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0") // the link to the phone
    implementation("androidx.wear.tiles:tiles:1.4.1") // the one-tap Ask tile
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1") // the watch-face slot
    testImplementation(libs.junit)
}
