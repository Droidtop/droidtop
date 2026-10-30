plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The sandbox library's program-tree binding (docs/SPEC.md 5b, Droidtop/tracker#584): the broker listener a tree of
// processes such as Wine's attaches to, with libsbx_child.so and sbx-exec packaged beside it.
android {
    namespace = "dev.droidtop.sandboxhost"
    compileSdk = 36
    // The same NDK pin as :plugin-host and :input-keyboard, so CI's one NDK install serves all of them.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
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
    testImplementation(libs.junit)
}
