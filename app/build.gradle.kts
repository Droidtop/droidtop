plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
}
