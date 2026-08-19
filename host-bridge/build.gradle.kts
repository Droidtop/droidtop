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
        // Target hardware (Retroid Pocket 5 class devices) is arm64-v8a only.
        // Also: only arm64-v8a's cross-compiled deps exist yet (see
        // build-scripts/build-vendor-deps.sh) — the other ABIs fail for a
        // trivial, uninteresting reason (deps not built for them), not a
        // real bug, so keep them out of the default build entirely.
        ndk {
            abiFilters += "arm64-v8a"
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
}
