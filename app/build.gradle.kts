plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.droidtop.app"
    compileSdk = 34

    // A plain incrementing integer, not a git SHA — CI passes its own
    // run number (github.run_number, monotonically increasing per
    // workflow run) via VERSION_REVISION; local builds fall back to "0"
    // since there's no meaningful revision counter outside CI.
    val versionRevision = System.getenv("VERSION_REVISION") ?: "0"

    defaultConfig {
        applicationId = "dev.droidtop.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-dev-$versionRevision"
        // arm64-v8a (real hardware, e.g. Retroid Pocket 5) + x86_64
        // (emulators/x86 devices), matching host-bridge/runtime-remote-
        // stream's own abiFilters — one fat APK covering both rather than
        // separate per-ABI builds.
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":runtime-common"))
    implementation(project(":host-bridge"))
    implementation(project(":runtime-windows"))
    implementation(project(":runtime-linux-root"))
    implementation(project(":runtime-linux-noroot"))
    implementation(project(":input-seat"))
    implementation(project(":runtime-remote-stream"))
    implementation(project(":library-core"))
    implementation(project(":shell-default"))
    implementation(project(":shell-gamepad"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
}
