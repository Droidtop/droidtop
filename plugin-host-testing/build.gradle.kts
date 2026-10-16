// Test-only: the fake host (docs/plugin-api.md 3 I2, Droidtop/tracker#71). A plugin's host without a device: the real
// broker over an in-memory droidtop, and the conformance cases every plugin kind's test fixture must pass. Never a
// dependency of anything shipped.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.pluginhost.testing"
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
    api(project(":plugin-host"))
    // FakeEnv answers the broker's library calls with runtime-common's own types (PathChange).
    api(project(":runtime-common"))
    testImplementation(libs.junit)
    // android.jar's org.json is a set of stubs that throw at runtime; a plain JVM unit test needs a real jar.
    testImplementation(libs.json.v20240303)
}
