import java.util.Properties

/**
 * Release signing.
 *
 * Locally: a gitignored `keystore.properties` beside this file's project root, pointing at
 * a keystore kept *outside* the repository. In CI: the same four values as environment
 * variables, with the keystore written to disk by the workflow from a base64 secret.
 *
 * With neither, `assembleRelease` still succeeds and produces an unsigned APK — a fresh
 * clone must build without anybody's key.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "BL_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "BL_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "BL_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "BL_KEY_PASSWORD")
val canSignRelease = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
).all { it != null } && file(releaseStoreFile!!).exists()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.betterlyrics.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.betterlyrics.app"
        minSdk = 29
        targetSdk = 36
        // The release workflow derives both from the git tag; a local build gets the
        // defaults, so nothing depends on the environment being set.
        versionCode = (System.getenv("BL_VERSION_CODE")?.toIntOrNull()) ?: 1
        versionName = System.getenv("BL_VERSION_NAME") ?: "0.1.0"

        // ML Kit's translation engine ships a ~16 MB native library per ABI. Keeping
        // arm64 (every phone made in the last decade) and x86_64 (emulators) drops
        // ~29 MB without losing a device anyone runs this on.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    if (canSignRelease) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the real android.jar, not the stub that throws on every
            // call — the TTML parser runs entirely through android.util.Xml.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // kuromoji-core and kuromoji-ipadic each ship their own copies of these.
            excludes += "/META-INF/{CONTRIBUTORS.md,LICENSE.md,NOTICE.md,README.md}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.palette)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // Japanese morphological analysis -> reading -> romaji.
    implementation(libs.kuromoji.ipadic)

    // On-device translation (models are downloaded on demand, per language).
    implementation(libs.mlkit.translate)

    testImplementation(libs.junit)
    // The TTML parser goes through android.util.Xml, so its tests need a real
    // Android runtime rather than the stubbed one.
    testImplementation(libs.robolectric)
}
