plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The controls engine (Droidtop/tracker#618 C3): pure Kotlin, no Android types, so the same sources can move to the
// shared library Enginehost also builds (C9) without a change.
android {
    namespace = "dev.droidtop.mapping"
    compileSdk = 36

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
}

dependencies {
    testImplementation(libs.junit)
}
