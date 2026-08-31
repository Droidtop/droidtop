plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.droidtop.shell.gamepad"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    androidResources {
        // Real, confirmed-live packaging bug this fixes: AAPT's DEFAULT
        // ignore-assets pattern includes `<dir>_*` -- every asset
        // DIRECTORY whose name starts with an underscore is silently
        // stripped from the build. Art Book Next (bundled under
        // src/main/assets/themes/) keeps its entire include tree --
        // fonts, per-system art, per-system metadata, per-fontSize
        // variable files -- under `_inc/`, a common real ES-DE theme
        // convention, so the theme shipped with its whole asset tree
        // missing and rendered near-black on device (confirmed by
        // listing the extraction cache: no _inc at all). This pattern is
        // AAPT's own default minus the `<dir>_*` rule, nothing else
        // changed.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
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
    // APNG4Android's FrameAnimationDrawable publicly extends
    // vectordrawable-animated's Animatable2Compat, and Kotlin requires
    // supertypes on the compile classpath to touch the subtype at all
    // ("Cannot access 'Animatable2Compat'", real CI failure). The apng
    // artifact declares it only transitively-invisibly, so it is named
    // here directly.
    implementation("androidx.vectordrawable:vectordrawable-animated:1.2.0")
    implementation(project(":library-core"))
    // Real, direct dependency: the ES-DE theme engine (dev.droidtop.library.theme
    // -- EsDeThemeParser/EsDeTheme/ThemeAssets/ThemePrefs/ThemeDownloader/
    // SystemThemeColors) lives in :runtime-common, not :library-core (see
    // that module's own build.gradle.kts for why) -- library-core's own
    // dependency on it is `implementation`, not `api`, so it doesn't leak
    // transitively here; this shell needs its own explicit dependency.
    implementation(project(":runtime-common"))
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    // BackHandler (system-back dispatcher) -- see GamepadShell's back
    // handling: B doubles as KEYCODE_BACK on this hardware and arrives via
    // the dispatcher, not as a key event.
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.android)
    // Real system logos (see theme/ThemeAssets.kt) are the bundled DEcaffe
    // theme's own SVGs -- coil3's default decoders are raster-only, so this
    // is needed for AsyncImage to actually render them rather than fail.
    implementation(libs.coil.svg)
    // Real "video" theme element playback -- see
    // theme/EsDeThemeRenderer.kt's own EsDeThemedVideo doc comment.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Real "animation" theme element playback (GIF + APNG frame
    // animation, APNG4Android -- the same library, at the same version,
    // the vendored gamenative catalog already pins as `gn.apng`) -- see
    // theme/EsDeThemeRenderer.kt's own EsDeThemedAnimation doc comment.
    implementation(libs.apng)
    implementation(libs.apng.gif)

    testImplementation(libs.junit)
}
