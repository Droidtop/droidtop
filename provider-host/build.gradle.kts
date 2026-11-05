// The enhancement provider host (docs/SPEC.md "Enhancement providers", Droidtop/tracker#852): the one app that holds
// elevated access (su through the root manager, Shizuku, wireless debugging) so droidtop's own code holds none. It is
// a TEMPLATE: built here unsigned and carried in droidtop's assets, then signed on the device with a key droidtop made
// in Android's Keystore and installed by droidtop (ProviderHostSetup). Nothing signed leaves CI, and nothing leaves the
// device. Its user id, not droidtop's, is what the person allows in the root manager and in Shizuku.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.providerhost"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.droidtop.providerhost"
        minSdk = 26
        targetSdk = 34
        // droidtop's own revision (VERSION_REVISION, app/build.gradle.kts): each droidtop build carries a host that is
        // newer than the one before, so droidtop updates it over itself.
        val versionRevision = System.getenv("VERSION_REVISION") ?: "0"
        versionCode = versionRevision.toIntOrNull()?.coerceAtLeast(1) ?: 1
        versionName = "0.2.0-dev.$versionRevision"
    }

    buildTypes {
        // No signing config: the release APK is the unsigned template droidtop signs on the device. Not shrunk: the
        // provider plugins it hosts load against droidtop's plugin classes in it (dev.droidtop.pluginhost, rikka.shizuku).
        release {
            isMinifyEnabled = false
        }
    }

    // The template is linted as part of droidtop's own lint (:app), not on its own release build.
    lint {
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs {
            // The ABIs droidtop ships, as app/build.gradle.kts.
            excludes += listOf("lib/armeabi/**", "lib/armeabi-v7a/**", "lib/x86/**", "lib/mips/**", "lib/mips64/**", "lib/riscv64/**")
            // Extracted, so a command the host starts as root or as the shell user can name a library by its path.
            useLegacyPackaging = true
        }
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
    // droidtop's plugin runtime: the same loader and plugin API the hosted providers are written against, and the
    // IPluginRuntime binder droidtop binds (docs/plugin-api.md 1.3, app_interface).
    implementation(project(":plugin-host"))
}
