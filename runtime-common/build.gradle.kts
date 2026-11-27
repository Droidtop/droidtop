plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.droidtop.runtime"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 26 // Presentation API + DisplayManager multi-display needs are stable from here
    }

    // Lets a plain JVM unit test load the exact bundled catalog file via the
    // classpath (getResourceAsStream) instead of duplicating its contents as
    // a test fixture, or needing Robolectric just to reach AssetManager.
    sourceSets {
        // The notice colours and the colour arithmetic are the shared library droidtop and Enginehost both
        // compile (mytop-ui's notices module, vendor/mytop-ui, mytop-project/mytop-ui, pinned as a submodule; docs/SPEC.md 7k "Notices").
        getByName("main") {
            kotlin.srcDir("../vendor/mytop-ui/notices/src/commonMain/kotlin")
            // The palette model and neutral job states are also shared source, compiled only here. The Compose
            // components which use them are compiled by :ui-shared, so no UI code enters :runtime-common.
            kotlin.srcDir("../vendor/mytop-ui/tokens/src/commonMain/kotlin")
            kotlin.srcDir("../vendor/mytop-ui/components/src/commonMain/kotlin/dev/droidtop/library/settings")
            // The version and trust code droidtop and Enginehost share (vendor/mytop-net, mytop-project/mytop-net,
            // pinned as a submodule; docs/SPEC.md 12a "Shared trust code"): compiled in the same way as the notices.
            kotlin.srcDir("../vendor/mytop-net/version/src/main/kotlin")
            kotlin.srcDir("../vendor/mytop-net/trust/src/main/kotlin")
            // The names droidtop and Enginehost agree on to start each other (docs/SPEC.md 7d, mytop-project/tracker#204).
            kotlin.srcDir("../vendor/mytop-net/launch-contract/src/main/kotlin")
            // The pad model droidtop and Enginehost share: mytop-project/mytop-input's PadFamily, PadFamilies.classify (SDL's ids)
            // and FaceLayout (mytop-project/tracker#867 D4), compiled in the same way.
            kotlin.srcDir("../vendor/mytop-input/pads/src/main/kotlin")
            // A container's architecture and translator (mytop-project/mytop-containers :arch), and running x86 programs and
            // Ren'Py's Linux launcher through it (mytop-project/mytop-runners :launch): docs/SPEC.md 3c, mytop-project/tracker#962.
            kotlin.srcDir("../vendor/mytop-containers/arch/src/main/kotlin")
            kotlin.srcDir("../vendor/mytop-runners/launch/src/main/kotlin")
        }
        getByName("test") {
            resources.srcDirs("src/main/assets")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":net-core"))
    // zstd OCI layers (OciFlattener.uncompressed, mytop-project/tracker#950): the pure-Java decoder
    // :runtime-linux-noroot and :shell-default already use, at the same version.
    implementation("com.github.alesimula:murine-aircompressor:2.0.9")
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Real, moved-in dependency: the ES-DE theme engine (EsDeThemeParser/
    // EsDeTheme/SystemThemeColors/ThemeAssets/ThemePrefs/ThemeDownloader,
    // dev.droidtop.library.theme) now lives here, not :library-core --
    // both :library-core and :shell-default need real access to it
    // (:shell-default's SettingsHandheldFragment for its own real Theme/
    // Sync-theme-index preferences), and :library-core already has a
    // real, deliberate dependency on :shell-default (NativeAppProvider's
    // IconCache integration), so the theme engine can't live in
    // :library-core without a real circular dependency. This module has
    // no dependency on anything else in the repo (see this file's own
    // history), matching real ES-DE's own theme.xml parser having no
    // dependency on the rest of ES-DE's app logic either.
    implementation(libs.jgit)
    // Reads image layers and writes the flattened rootfs stream
    // (OciFlattener). api, not implementation: RootfsEntrySink hands both
    // container backends commons-compress's TarArchiveEntry. The same
    // library gamenative's own archive code uses, so the APK carries one
    // copy of it.
    api(libs.commons.compress)
    // 7z and RAR, through the libarchive build GameNative's mods importer used: ArchiveExtractor, shared by the itch.io
    // store's installs and a source's unpacked downloads.
    implementation(libs.libarchive.android)
    testImplementation(libs.junit)
    // The real org.json for JVM tests (Android's is a stub there), as :shell-gamepad has it.
    testImplementation(libs.json.v20240303)
    testImplementation(libs.kotlinx.coroutines.test)
    // Throwaway certificates for the signature tests (docs/SPEC.md 12a); test-only, never shipped.
    testImplementation(project(":test-pki"))
}

// AGP 9 built-in Kotlin uses the same bytecode target as Java.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
