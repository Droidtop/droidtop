// Termux's terminal-emulator library (termux-terminal/README.md; Apache-2.0): the VT parser, the screen buffer and
// TerminalSession, which runs a program on a pseudo-terminal through its own JNI (jni/termux.c: open /dev/ptmx, fork,
// setsid, the slave as stdin/stdout/stderr and controlling terminal, TIOCSWINSZ on resize). droidtop's container
// terminal uses it (docs/SPEC.md 3d, "A terminal on Android").
//
// The sources are upstream's, unchanged, from the vendor/termux-app submodule (termux/termux-app v0.118.3). Only this
// build file and droidtop/AndroidManifest.xml are droidtop's: upstream's build.gradle reads properties of Termux's own
// root project and publishes to Maven.
val UPSTREAM = "../../vendor/termux-app/terminal-emulator/src/main"

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
            path = file("$UPSTREAM/jni/Android.mk")
        }
    }

    sourceSets {
        named("main") {
            java.srcDir("$UPSTREAM/java")
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
