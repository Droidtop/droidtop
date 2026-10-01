plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.net"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { buildConfig = true }
    val revision = System.getenv("VERSION_REVISION") ?: "0"
    defaultConfig { buildConfigField("String", "VERSION_NAME", "\"0.2.0-dev.$revision\"") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
