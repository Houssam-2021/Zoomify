import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Bump for every release. CI can override it for test-only builds (-PzoomifyTestVersionCode=N); those are never published.
val zoomifyVersionCode = 3

android {
    namespace = "com.zoomify.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zoomify.camera"
        minSdk = 30          // Android 11: CONTROL_ZOOM_RATIO, i.e. seamless 0.5x <-> 1x on multi-lens phones
        targetSdk = 35
        versionCode = (project.findProperty("zoomifyTestVersionCode") as String?)?.toInt() ?: zoomifyVersionCode
        versionName = "1.2-beta.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Unsigned on purpose: CI signs releases with the private key from repository secrets
            // (.github/workflows/android.yml). No signing key or password is stored in this repository.
        }
        // Debug builds use Android's standard local debug key (~/.android/debug.keystore), never committed.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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

    testImplementation("junit:junit:4.13.2")
}
