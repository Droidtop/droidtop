// Forked from Hacker's Keyboard (github.com/klausw/hackerskeyboard,
// Apache-2.0 — see LICENSE in this directory) rather than built from
// scratch, per the same "own and edit directly" reasoning as
// :shell-default's Murine Launcher fork: droidtop's second-screen
// persistent keyboard (docs/SPEC.md §4/§6) needs real, direct edits
// (persistent-not-popup presentation, trackpad-region integration,
// :input-seat wiring) that a vendor/+wrapper relationship wouldn't fit.
//
// Not implemented/wired up yet — this is the source brought in and made
// to compile as its own module; the actual second-screen persistent
// surface, trackpad region, and :input-seat integration are still TODO.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.pocketworkstation.pckeyboard"
    // androidx.core:core (via androidx-core-ktx, resolved from the shared
    // catalog) requires compiling against 36+ — same class of fix as
    // shell-default's own compileSdk bump.
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Upstream targets minSdk 14; bumped to match droidtop's own
        // baseline (every other module targets 26) rather than carrying
        // a decade of pre-26 compatibility code paths we'll never run.
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    sourceSets {
        named("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDirs("src/main/java")
            res.srcDirs("src/main/res")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}
