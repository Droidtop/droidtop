plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * The Windows runtime (docs/SPEC.md 5b, 9): droidtop's own module, holding
 * Winlator's runtime as GameNative ships it (src/main/java/com/winlator, its
 * package and headers unchanged) and the GameNative-authored pieces it needs
 * (src/main/kotlin/dev/droidtop/runtime/windows/{utils,data,ui,...}, lifted
 * from app/gamenative/..., GPL-3.0 like droidtop). It no longer compiles the
 * vendored GameNative tree; Hilt, Room, JavaSteam (droidtop's Steam is
 * :stores), PostHog and GameNative's UI are not part of it.
 */
android {
    namespace = "dev.droidtop.runtime.windows"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26

        // The arm64-v8a natives are GameNative's prebuilt set, re-hosted
        // (prebuilt/, below). The x86_64 half of the fat APK (docs/SPEC.md
        // 10b) is built here, from GameNative's sources (native/upstream) or
        // a shim per library; native/CMakeLists.txt says which and why.
        externalNativeBuild {
            cmake {
                abiFilters += "x86_64"
                targets += listOf(
                    "virglrenderer", "asurface_renderer", "ahbimage", "xconnectorpatch",
                    "winlator", "winlator_11", "extras", "vulkan_renderer",
                    "hook_impl", "main_hook", "kgslshim", "vortekrenderer", "evshim",
                )
                // libc++_shared.so is in the arm64 set; the NDK's own copy
                // for x86_64 is packaged when the STL is the shared one.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        // The three build switches the runtime's code reads. Android
        // refuses exec() of an extracted binary above targetSdk 28, so the
        // runtime is GameNative's "modern" flavor: MODERN_ANDROID, and every
        // guest process LD_PRELOADs the W^X redirect library that
        // ImageFsInstaller copies out of the assets (PRELOAD_BIONIC_SO).
        // XR_BUILD is GameNative's Meta Quest build, which droidtop is not.
        buildConfigField("boolean", "MODERN_ANDROID", "true")
        buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
        buildConfigField("boolean", "XR_BUILD", "false")
    }

    sourceSets {
        getByName("main") {
            // GameNative's arm64 native libraries and the runtime's asset
            // payloads (box64/FEXCore/WowBox64 builds, input DLLs, the
            // redirect libraries, the JSON lists the runtime reads with
            // getAssets()), re-hosted unmodified as a release of this
            // repository and fetched by build-scripts/
            // fetch-windows-runtime-prebuilt.sh against prebuilt.pin
            // (docs/SPEC.md 9). What is packed and what is left out:
            // build-scripts/windows-runtime-prebuilt.sh.
            jniLibs.srcDir("prebuilt/jniLibs")
            assets.srcDir("prebuilt/assets")
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

    buildFeatures {
        // ContainerData and XServerState are Compose state holders upstream.
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":runtime-common"))
    // PcGameProvider's LibraryProvider and the store rows of PcLibrary.
    implementation(project(":library-core"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    // TarCompressorUtils' .tzst and .txz streams (commons-compress comes
    // from :runtime-common, which exposes it).
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)

    testImplementation(libs.junit)
}
