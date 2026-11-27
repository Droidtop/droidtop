plugins {
    alias(libs.plugins.android.library)
}

// The controls engine (mytop-project/tracker#618 C3): pure Kotlin, no Android types, so the same sources can move to the
// shared library Enginehost also builds (C9) without a change.
android {
    namespace = "dev.droidtop.mapping"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}

// AGP 9 built-in Kotlin uses the same bytecode target as Java.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
