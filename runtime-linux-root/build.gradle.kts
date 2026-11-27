plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.droidtop.runtime.linux.root"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // DroidSpaces' native core (vendor/droidspaces) is a standalone musl-libc
    // C binary, not built via this Gradle module — it's packaged as a prebuilt
    // asset per-ABI. See vendor/droidspaces' own build for cross-compiling it.
}

dependencies {
    implementation(project(":runtime-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

// AGP 9 built-in Kotlin uses the same bytecode target as Java.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
