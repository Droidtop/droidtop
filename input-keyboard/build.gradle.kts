// Forked from Hacker's Keyboard (github.com/klausw/hackerskeyboard,
// Apache-2.0 — see LICENSE in this directory) rather than built from
// scratch, per the same "own and edit directly" reasoning as
// :shell-default's Murine Launcher fork: droidtop's second-screen
// persistent keyboard (docs/SPEC.md §4/§6) needs real, direct edits
// (persistent-not-popup presentation, trackpad-region integration,
// :input-seat wiring) that a vendor/+wrapper relationship wouldn't fit.
//
// The second-screen surface itself is now built (SecondScreenKeyboard.kt
// here, hosted by :app on the addon display). What Android permits for a
// secondary-display keyboard, and why it is an ordinary window rather than
// an IME window, is written up in that file and in docs/SPEC.md §6c.
plugins {
    alias(libs.plugins.android.library)
    // The second-screen additions are Kotlin; the forked-in upstream tree
    // stays Java and is untouched apart from the one hook in LatinIME.
    alias(libs.plugins.kotlin.android)
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
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
