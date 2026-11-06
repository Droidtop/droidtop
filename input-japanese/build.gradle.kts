// Japanese typing through Google's Mozc (Droidtop/tracker#903 H7c; docs/SPEC.md 6a, "The engine seam").
//
// An engine behind :input-keyboard's InputEngine seam, kept out of that module: :input-keyboard does not depend on
// this one, :app registers MozcJapanese at start (Seams.kt), and so the keyboard, and the standalone Hacker Keyboard
// Reborn built from it, carry no engine. This module depends on :input-keyboard only for the seam.
//
// - libmozc.so (both ABIs) is Mozc 3.35.6354 built by Droidtop/tracker#903 H7b and released on this repository as
//   the prerelease mozc-engine-3.35.6354. build-scripts/fetch-mozc-engine.sh puts it in mozc/jniLibs (git-ignored)
//   against mozc-engine.pin, as fetch-lsfg-layer.sh does for the lsfg layer; the android-setup action runs it
//   before Gradle. It is packaged unstripped and AGP strips it with the NDK (:app:strip*DebugSymbols).
// - Mozc's dictionary (mozc.data, 19 MB) is not in the APK: the keyboard downloads it the first time Japanese is
//   used, checked against the same pin (MozcData). The pin's text reaches the code as BuildConfig.MOZC_PIN.
// - Mozc's protocol (src/protocol, BSD-3) is vendored unchanged under src/main/proto and generated as protobuf lite,
//   with the protobuf plugin shell-default uses for Launcher3's protos and protoc at the catalog's protobuf release.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.protobuf)
}

val mozcPin: String = file("mozc-engine.pin").readText()

android {
    namespace = "dev.droidtop.input.japanese"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // MozcJni's natives are registered by its class and method names (mozcjni.cc).
        consumerProguardFiles("consumer-rules.pro")
        // The pin, parsed at run time (MozcPin), for the dictionary's address and SHA-256s.
        buildConfigField("String", "MOZC_PIN", "\"" + mozcPin.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        named("main") {
            // libmozc.so for arm64-v8a and x86_64, fetched against mozc-engine.pin (build-scripts/fetch-mozc-engine.sh).
            jniLibs.srcDir("mozc/jniLibs")
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

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobufJavalite.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            // Java, lite, as shell-default generates Launcher3's: the java builtin is there by default and gets the option.
            task.builtins {
                maybeCreate("java").option("lite")
            }
        }
    }
}

dependencies {
    // The engine seam (InputEngine, InputEngines) and the keyboard's worker threads.
    implementation(project(":input-keyboard"))
    implementation(libs.protobuf.javalite)

    testImplementation(libs.junit)
}
