plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.droidtop.input"
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
    implementation(project(":runtime-common"))
    api(project(":host-bridge"))
    // The desktop's pad chords are a controller layout the one mapping engine plays (mytop-project/tracker#383).
    api(project(":input-mapping"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}

// AGP 9 built-in Kotlin uses the same bytecode target as Java.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
