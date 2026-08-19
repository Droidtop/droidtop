plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.droidtop.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.droidtop.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-dev"
        // Target hardware (Retroid Pocket 5 class devices) is arm64-v8a
        // only — matches host-bridge/runtime-remote-stream's own
        // abiFilters, whose cross-compiled deps only exist for this ABI.
        ndk {
            abiFilters += "arm64-v8a"
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
}
