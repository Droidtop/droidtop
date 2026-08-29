plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.droidtop.runtime"
    compileSdk = 34

    defaultConfig {
        minSdk = 26 // Presentation API + DisplayManager multi-display needs are stable from here
    }

    // Lets a plain JVM unit test load the exact bundled catalog file via the
    // classpath (getResourceAsStream) instead of duplicating its contents as
    // a test fixture, or needing Robolectric just to reach AssetManager.
    sourceSets {
        getByName("test") {
            resources.srcDirs("src/main/assets")
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
    testImplementation(libs.junit)
}
