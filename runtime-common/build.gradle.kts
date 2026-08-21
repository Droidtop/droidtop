plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.droidtop.runtime"
    compileSdk = 34

    defaultConfig {
        minSdk = 26 // Presentation API + DisplayManager multi-display needs are stable from here
    }

    // Lets a plain JVM unit test load the exact bundled catalog file via the
    // classpath (getResourceAsStream) instead of duplicating its contents as
    // a test fixture, or needing Robolectric just to reach AssetManager.
    sourceSets {
        getByName("test") {
            resources.srcDirs("src/main/assets")
        }
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
