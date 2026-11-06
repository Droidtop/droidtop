// Termux's terminal-emulator library (vendor/termux-terminal/README.md; Apache-2.0, see LICENSE beside it): the VT
// parser, the screen buffer and TerminalSession, which runs a program on a pseudo-terminal through its own JNI
// (src/main/jni/termux.c: open /dev/ptmx, fork, setsid, the slave as stdin/stdout/stderr and controlling terminal,
// TIOCSWINSZ on resize). droidtop's container terminal uses it (docs/SPEC.md 3d, "A terminal on Android").
//
// This build file is droidtop's: upstream's build.gradle reads properties of Termux's own root project and
// publishes to Maven. The sources under src/main are upstream's, unchanged.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
        externalNativeBuild {
            ndkBuild {
                // Upstream's flags, less -Werror: a newer NDK's new warnings must not stop droidtop's build.
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
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
