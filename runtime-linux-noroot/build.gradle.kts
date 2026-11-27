plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.droidtop.runtime.linux.noroot"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 26
    }

    // jniLibs/<abi>/libproot.so, libproot-loader.so and libproot-loader32.so
    // come from build-scripts/build-vendor-deps.sh (vendor/proot), not from
    // a CMake build here: they are executables that happen to be named
    // lib*.so so Android installs them into nativeLibraryDir, the one place
    // an app may exec from. The default src/main/jniLibs source dir picks
    // them up.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":runtime-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Pure-Java tar/zstd, already vendored and proven for :shell-default's
    // backup restore (BackupHelper.kt) -- HostAudioServer reuses it rather
    // than adding a second archive library, to unpack the same
    // pulseaudio-gamenative-*.tzst asset runtime-windows already ships
    // (docs/SPEC.md 3d, mytop-project/tracker#95).
    implementation("com.github.alesimula:murine-aircompressor:2.0.9")

    testImplementation(libs.junit)
}

// AGP 9 built-in Kotlin uses the same bytecode target as Java.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
