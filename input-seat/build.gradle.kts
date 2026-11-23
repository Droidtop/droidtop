plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.input"
    compileSdkVersion("android-37.1")

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(project(":runtime-common"))
    api(project(":host-bridge"))
    // The desktop's pad chords are a controller layout the one mapping engine plays (Droidtop/tracker#383).
    api(project(":input-mapping"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
