// Termux's terminal-view library (vendor/termux-terminal/README.md; Apache-2.0, see LICENSE beside it): the Android
// View that draws a terminal-emulator screen and turns the soft keyboard's and a hardware keyboard's input into the
// bytes a terminal expects. droidtop's container terminal hosts it (docs/SPEC.md 3d, "A terminal on Android").
//
// This build file is droidtop's: upstream's build.gradle reads properties of Termux's own root project and
// publishes to Maven. The sources under src/main are upstream's, unchanged.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        named("main") {
            manifest.srcFile("droidtop/AndroidManifest.xml")
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
    api(project(":terminal-emulator"))
    implementation(libs.annotation)
}
