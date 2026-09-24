plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.runtime.linux.noroot"
    compileSdk = 36

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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":runtime-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Reads the flat image tarball crane exports (RootfsTarExtractor).
    // The same library gamenative's own archive code uses, so the APK
    // carries one copy of it.
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
