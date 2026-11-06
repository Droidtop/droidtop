// Chinese typing through RIME (Droidtop/tracker#903 H8b; docs/SPEC.md 6a, "Chinese through RIME").
//
// An engine behind :input-keyboard's InputEngine seam, kept out of that module, as :input-japanese is: :input-keyboard
// does not depend on this one, :app registers RimeChinese at start (Seams.kt), and so the keyboard, and the standalone
// Hacker Keyboard Reborn built from it, carry no engine. This module depends on :input-keyboard only for the seam.
//
// - librime.so (both ABIs) is rime/librime 56737cd2 with the lua, octagram and predict plugins, built by
//   Droidtop/tracker#903 H8a (.github/workflows/rime-engine.yml) and released on this repository as the prerelease
//   rime-engine-56737cd2. build-scripts/fetch-rime-engine.sh puts it, with rime_api.h, in rime/ (git-ignored) against
//   rime-engine.pin; the android-setup action runs it before Gradle. It exports only librime's C API.
// - droidtop's own JNI over it, src/main/cpp (libdroidtop_rime.so), is built here with CMake against that rime_api.h,
//   and links librime.so as an IMPORTED library, which AGP packages beside it. Both are packaged unstripped and AGP
//   strips them with the NDK (:app:strip*DebugSymbols).
// - RIME's data (luna_pinyin and OpenCC's, 15 MB) is not in the APK: the keyboard downloads it the first time Chinese
//   is used, checked against the same pin and rime-data-luna.files.sha256 (RimeData). The two files' text reaches the
//   code as BuildConfig.RIME_PIN and BuildConfig.RIME_DATA_FILES.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val rimePin: String = file("rime-engine.pin").readText()
val rimeDataFiles: String = file("rime-data-luna.files.sha256").readText()

fun javaString(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

android {
    namespace = "dev.droidtop.input.chinese"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        // RimeJni's natives are registered by its class and method names (rime_jni.cc).
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
        // The pin and the data's file list, parsed at run time (RimePin, RimeManifest), for the data's address and SHA-256s.
        buildConfigField("String", "RIME_PIN", javaString(rimePin))
        buildConfigField("String", "RIME_DATA_FILES", javaString(rimeDataFiles))
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // The engine seam (InputEngine, InputEngines) and the keyboard's worker threads.
    implementation(project(":input-keyboard"))

    testImplementation(libs.junit)
}
