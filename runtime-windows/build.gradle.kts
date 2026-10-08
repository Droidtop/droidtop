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

        // GameNative's natives are prebuilt for arm64-v8a only (the
        // jniLibs source dirs below). The x86_64 half of the fat APK
        // (docs/SPEC.md 10b) is built here, from the fork's sources or a
        // shim per library; native/CMakeLists.txt says which and why.
        // arm64 stays upstream's prebuilt set, so CMake builds x86_64 only.
        externalNativeBuild {
            cmake {
                abiFilters += "x86_64"
                targets += listOf(
                    "virglrenderer", "patchelf", "asurface_renderer", "ahbimage", "xconnectorpatch",
                    "winlator", "winlator_11", "extras", "vulkan_renderer",
                    "hook_impl", "main_hook", "kgslshim", "vortekrenderer", "steambootstrap",
                    "lsfg-vk", "evshim", "openxr_loader",
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
            // The runtime's payloads and GameNative's prebuilt arm64 natives,
            // still read from the fork until they are droidtop's own (the
            // runtime lift, piece 3): common_dlls.json, gpu_cards.json,
            // wincomponents, wine_startmenu.json, redirect.tzst, the
            // box86_64/fexcore/wowbox64 translator payloads, and the modern
            // flavor's libredirect-bionic-wx.so.
            assets.srcDir("../vendor/gamenative/app/src/main/assets")
            assets.srcDir("../vendor/gamenative/app/src/modern/assets")
            jniLibs.srcDir("../vendor/gamenative/app/src/main/jniLibs")
            jniLibs.srcDir("../vendor/gamenative/app/src/modern/jniLibs")
        }
    }

    androidResources {
        // Excluding, not including, on purpose: if upstream adds a file
        // this bundles it (wasteful, harmless), whereas an include list
        // would silently drop something needed and fail at runtime.
        //
        //   dxwrapper       29 MB, and every entry in
        //                   dxwrapper_download.json resolves to
        //                   downloads.gamenative.app: fetched on demand.
        //   steampipe /     Steam-only (steam_api.dll, region lists).
        //   steaminput /    droidtop's Steam is :stores.
        //   steam_regions
        ignoreAssetsPatterns += listOf("dxwrapper", "steampipe", "steaminput", "steam_regions.json")
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
