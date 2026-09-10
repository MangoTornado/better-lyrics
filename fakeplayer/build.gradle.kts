plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * A stand-in music player, for development only.
 *
 * Melisma reads whatever media session the phone is playing, which makes the
 * interesting half of it impossible to exercise without a second app publishing one.
 * This is that second app: it publishes a real `MediaSession` with real metadata and a
 * playhead that actually advances, so the detection, the playhead extrapolation and the
 * transport controls can all be tested on an emulator with no music service installed.
 *
 * Kept in a separate module — and therefore a separate APK — so nothing here can leak
 * into the shipped app.
 */
android {
    namespace = "com.melisma.fakeplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.melisma.fakeplayer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
