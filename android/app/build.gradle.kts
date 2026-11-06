import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Bump for every release. CI can override it for test-only builds (-PzoomifyTestVersionCode=N); those are never published.
val zoomifyVersionCode = 2

android {
    namespace = "com.zoomify.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zoomify.camera"
        minSdk = 30          // Android 11: CONTROL_ZOOM_RATIO, i.e. seamless 0.5x <-> 1x on multi-lens phones
        targetSdk = 35
        versionCode = (project.findProperty("zoomifyTestVersionCode") as String?)?.toInt() ?: zoomifyVersionCode
        versionName = "1.1"
    }

    // LEGACY KEY - COMPROMISED. This key was committed publicly, so it is only used for local debug builds and as
    // the "old signer" in CI's key rotation (.github/workflows/android.yml). Public release APKs are built unsigned
    // here and signed in CI with the private key from the ZOOMIFY_KEYSTORE_* secrets plus a rotation proof.
    signingConfigs {
        create("sideload") {
            storeFile = file("zoomify-sideload.keystore")
            storePassword = "REDACTED"
            keyAlias = "zoomify"
            keyPassword = "REDACTED"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Unsigned on purpose: see the signing step in CI.
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val camerax = "1.5.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
