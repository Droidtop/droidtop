// Records droidtop's own baseline profile (docs/SPEC.md 10b). Without one, Android compiles droidtop from nothing on
// the first start after every update: 22.7 s on the test emulator against a few seconds after (Droidtop/tracker#954
// item 13). The libraries' own profiles were already installed (profileinstaller); this adds droidtop's. It runs on a
// device only, from the Baseline profile workflow, and writes app/src/release/generated/baselineProfiles.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "dev.droidtop.baselineprofile"
    compileSdkVersion("android-37.0")

    defaultConfig {
        // What the profile rule needs on the device that records it.
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    targetProjectPath = ":app"
}

baselineProfile {
    // The emulator the workflow starts, not a Gradle-managed one: the same one the emulator tests use.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
