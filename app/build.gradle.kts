plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.droidtop.app"
    // shell-default (the forked-in Murine Launcher) and its sub-modules
    // compile against 36 — several of their AndroidX dependencies
    // (recyclerview 1.4.0, compose material3/animation 1.8.1+) require
    // consumers to compile against 35+ too, confirmed via a real
    // :app:assembleDebug AAR-metadata check failure. Every other droidtop
    // module stays on its own existing compileSdk; only the final
    // linking module (:app) has to be >= the highest compileSdk among
    // everything it depends on.
    compileSdk = 36

    // A plain incrementing integer, not a git SHA — CI passes its own
    // run number (github.run_number, monotonically increasing per
    // workflow run) via VERSION_REVISION; local builds fall back to "0"
    // since there's no meaningful revision counter outside CI.
    val versionRevision = System.getenv("VERSION_REVISION") ?: "0"

    defaultConfig {
        applicationId = "dev.droidtop.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-dev-$versionRevision"
        // arm64-v8a (real hardware, e.g. Retroid Pocket 5) + x86_64
        // (emulators/x86 devices), matching host-bridge/runtime-remote-
        // stream's own abiFilters — one fat APK covering both rather than
        // separate per-ABI builds.
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }
    }

    // Only configured when the signing secrets are present (real CI runs,
    // via SIGNING_KEYSTORE_PATH/SIGNING_STORE_PASSWORD/SIGNING_KEY_ALIAS/
    // SIGNING_KEY_PASSWORD in the workflow) — local/contributor builds fall
    // back to Android's default auto-generated debug keystore instead of
    // failing when these aren't set.
    //
    // Without this, every CI run signs with a brand-new ephemeral debug
    // keystore (GitHub Actions runners are fresh VMs with no persisted
    // ~/.android/debug.keystore across runs), so every past "latest"
    // release had a different signing identity and users had to fully
    // uninstall before installing any newer build. One persistent keystore
    // (generated once, stored only as a GitHub Actions secret — see
    // .signing/ in .gitignore) fixes that.
    val signingKeystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (signingKeystorePath != null) {
            create("droidtop") {
                storeFile = file(signingKeystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (signingKeystorePath != null) {
                signingConfig = signingConfigs.getByName("droidtop")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":runtime-common"))
    implementation(project(":host-bridge"))
    implementation(project(":runtime-windows"))
    implementation(project(":runtime-linux-root"))
    implementation(project(":runtime-linux-noroot"))
    implementation(project(":input-seat"))
    implementation(project(":input-keyboard"))
    implementation(project(":library-core"))
    implementation(project(":shell-default"))
    implementation(project(":shell-desktop"))
    implementation(project(":shell-gamepad"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ViewTreeLifecycleOwner/ViewTreeSavedStateRegistryOwner (SecondScreenPresentation
    // hosting Compose inside an android.app.Presentation, which isn't a LifecycleOwner/
    // SavedStateRegistryOwner on its own the way an Activity is) -- not resolvable
    // transitively via lifecycle-runtime-ktx alone, needs the base artifacts explicitly.
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // Real fix (DroidtopApplication.kt): coil-svg was already a
    // shell-gamepad dependency, but nothing ever registered
    // SvgDecoder.Factory() with a real ImageLoader -- adding the
    // dependency alone doesn't make Coil3 use it, it needs a
    // SingletonImageLoader.Factory, which has to live in the actual
    // Application class (this module, not a library module).
    implementation(libs.coil.compose)
    implementation(libs.coil.android)
    implementation(libs.coil.svg)
}
