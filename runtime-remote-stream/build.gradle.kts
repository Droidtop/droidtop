plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.runtime.remotestream"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        // arm64-v8a (real hardware) + x86_64 (emulators/x86 devices) — a
        // single fat APK covering both. No external cross-compiled deps
        // needed here (mbedTLS/moonlight-common-c build self-contained via
        // add_subdirectory), so both ABIs "just work" through Gradle's own
        // per-ABI CMake invocation, unlike :host-bridge which needs
        // build-scripts/build-vendor-deps.sh run per ABI first.
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
    implementation(project(":runtime-common"))
    implementation(project(":library-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Pairing (salted-PIN AES challenge/response) and app-list retrieval are
    // an HTTPS+XML REST layer, not part of vendor/moonlight-common-c (that's
    // streaming-protocol only) -- ported from moonlight-android's real,
    // public NvHTTP/PairingManager. OkHttp for the mutual-TLS HTTP client;
    // BouncyCastle only for generating the self-signed client certificate
    // (java.security has no public API to build a *new* X509 cert, only to
    // parse existing ones -- the actual AES/signature crypto below uses
    // plain JCE, no BC needed there).
    implementation(libs.okhttp)
    implementation(libs.bouncycastle.bcpkix)
}
