plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.hostbridge"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        // arm64-v8a (real hardware, e.g. Retroid Pocket 5) + x86_64
        // (emulators/x86 devices) — a single fat APK covering both, rather
        // than separate per-ABI builds. build-scripts/build-vendor-deps.sh
        // cross-compiles this module's native deps for both.
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                // CMake never reads shell env vars on its own — ANDROID_DEPS_PREFIX
                // has to be handed across explicitly as a -D argument, or the
                // CMakeLists.txt's own hardcoded default (/opt/android-deps) wins
                // silently. That default happens to match this repo's local-dev
                // convention, which is exactly why this gap wasn't caught until
                // CI (which installs deps to $GITHUB_WORKSPACE/.android-deps
                // instead) failed on "wayland-client.h file not found" despite
                // the cross-compile step having already succeeded.
                System.getenv("ANDROID_DEPS_PREFIX")?.let { prefix ->
                    arguments += "-DANDROID_DEPS_PREFIX=$prefix"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("native/CMakeLists.txt")
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
    implementation(project(":runtime-common"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
