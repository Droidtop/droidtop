plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The Compose pieces every droidtop surface draws alike (docs/SPEC.md 7k,
// "Notices"; Droidtop/tracker#485): the notice card and its palette. Gaming,
// Desktop, the companion and Standard (whose forked Launcher3 settings are
// Preference rows, through a ComposeView this module builds) all draw these,
// so none of them depends on another shell for them.
android {
    namespace = "dev.droidtop.ui.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    // The semantics tests run under Robolectric, as :display's do.
    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    // The Notice model and NoticeColors live there, next to the theme mapping they derive from.
    api(project(":runtime-common"))
    // The text-entry slab (Slab, SlabView) that TextEntry hosts (docs/SPEC.md 4c, Droidtop/tracker#485 S7).
    api(project(":input-keyboard"))
    implementation(libs.androidx.core.ktx)
    // The design tokens and the theme palette (docs/SPEC.md, Droidtop/tracker#827 F1b): GamingTheme holds the palette
    // for its host's lifecycle.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // The component set (Droidtop/tracker#827 F1b part 3): PadFocusHolder places the focus again on a return to its
    // window, and a catalog row's picture (CatalogGlyph) is drawn by the image loader, as it was in :shell-gamepad.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // The three tone glyphs (circle i, triangle !, octagon !): Material Symbols, as the settings glyphs are.
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}

// A failing check over the bundled themes names the theme and scheme in its message; print it in the CI log,
// where the HTML report is out of reach.
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
