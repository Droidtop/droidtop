plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.runtime.linux.root"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // DroidSpaces' native core (vendor/droidspaces) is a standalone musl-libc
    // C binary, not built via this Gradle module — it's packaged as a prebuilt
    // asset per-ABI. See vendor/droidspaces' own build for cross-compiling it.
}

dependencies {
    implementation(project(":runtime-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
