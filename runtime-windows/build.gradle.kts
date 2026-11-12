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

        // Both halves of the fat APK (docs/SPEC.md 10b) are built here, from
        // GameNative's native sources (native/upstream), libadrenotools
        // (vendor/libadrenotools, arm64) or a shim per library (x86_64);
        // native/CMakeLists.txt says which and why. Every shared library
        // that project defines ships, so it names no targets.
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a", "x86_64")
                // The NDK's own libc++_shared.so is packaged for each ABI.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        // Android refuses exec() of an extracted binary above targetSdk 28,
        // so the runtime only has GameNative's "modern" path (its legacy
        // branches are deleted, Droidtop/tracker#765): every guest process
        // runs through /system/bin/linker64 and LD_PRELOADs the W^X redirect
        // library that ImageFsInstaller copies out of the assets
        // (PRELOAD_BIONIC_SO).
        // GameNative's Meta Quest (XR) build is not carried either.
        buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
    }

    sourceSets {
        getByName("main") {
            // The files droidtop does not build: the arm64 libvortekrenderer
            // and libkgslshim (no source exists) and the asset payloads
            // (Box64, FEXCore and WowBox64 builds, the input DLLs, the
            // redirect libraries). Re-hosted unmodified in
            // Droidtop/droidtop-components and fetched by
            // build-scripts/fetch-windows-runtime-components.sh against
            // components.pin (docs/SPEC.md 9).
            jniLibs.srcDir("components/jniLibs")
            // The lsfg-vk Vulkan layer, both ABIs, built by
            // .github/workflows/lsfg-vk-layer.yml and fetched against
            // lsfg-layer.pin (build-scripts/fetch-lsfg-layer.sh).
            jniLibs.srcDir("lsfg/jniLibs")
            assets.srcDir("components/assets")
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
    implementation(project(":net-core"))
    // Wine's process tree under the sandbox broker (docs/SPEC.md 5b, Droidtop/tracker#584).
    implementation(project(":sandbox-host"))
    // PcGameProvider's LibraryProvider and the store rows of PcLibrary.
    implementation(project(":library-core"))
    implementation(project(":input-mapping"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.timber)
    implementation(libs.okhttp)
    // TarCompressorUtils' .tzst and .txz streams (commons-compress comes
    // from :runtime-common, which exposes it).
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)

    testImplementation(libs.junit)
    // A real org.json for tests of what is kept as JSON (Android's is a stub off the device).
    testImplementation(libs.json.v20240303)
}
