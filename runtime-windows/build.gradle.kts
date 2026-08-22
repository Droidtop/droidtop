plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // Real, needed by the forked app.gamenative.powercontrol.* tree's own
    // @Serializable data classes (PowerBaseline, PowerBaselineEntry, ...)
    // -- the runtime library alone (kotlinx-serialization-json, already
    // depended on below) isn't enough; @Serializable codegen needs this
    // compiler plugin too, confirmed via a real CI failure this session
    // ("Unresolved reference 'serializer'") once traced back to its root
    // cause rather than guessed.
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Real, deliberate choice, not droidtop's usual dev.droidtop.* --
    // this module's src/main/java also contains a wholesale, unmodified
    // fork of vendor/gamenative's real com.winlator.* tree (247 real
    // files) plus the minimal app.gamenative.* surface it touches
    // (BuildConfig/PluviaApp compat shims -- see those files' own doc
    // comments). Real com.winlator source references `app.gamenative.R`
    // directly, so this module's own namespace has to BE app.gamenative
    // for AGP's generated R class to resolve those references without
    // editing the forked files -- "our stuff should be UI only, or
    // extensions" (direction this session) means the fork itself stays
    // byte-for-byte, not renamed to fit droidtop's own package convention.
    namespace = "app.gamenative"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // Real, needed by the forked com.winlator.* tree's own layout
        // inflation (ContentDialog, NavigationDialog, InputControlsView,
        // TouchpadView, FrameRating all inflate real XML layouts) --
        // NOT Compose; the forked tree is plain View-based, same as
        // upstream. buildConfig deliberately NOT enabled -- this module
        // hand-writes its own tiny app.gamenative.BuildConfig shim
        // instead (see that file's own doc comment), which would collide
        // with AGP's auto-generated one under the same namespace.
        viewBinding = false
    }

    // Wine + Box64 binaries/ImageFS come from vendor/gamenative, packaged as
    // prebuilt per-ABI assets — same as that project's own build, minus its
    // Android SurfaceView XServer rendering path, which this module does
    // not use (droidtop is a Wayland client instead, see docs/SPEC.md §2).
    // XServer's own protocol/input-handling code (com.winlator.xserver) IS
    // forked in wholesale regardless, per direction this session ("fork
    // ALL the code in, and just not wire it up until we're ready... it's
    // ok for stuff to not be there yet") -- real, unmodified, simply not
    // called from droidtop's own WineSession yet.
}

dependencies {
    implementation(project(":runtime-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Real, direct dependencies of the forked-in com.winlator.* +
    // app.gamenative.powercontrol.* trees (confirmed by reading their
    // actual imports this session, not guessed) -- Apache Commons
    // Compress (tar/xz/zstd ImageFS archive handling), Timber (logging),
    // kotlinx-serialization-json (ContainerData/power-profile
    // (de)serialization), AndroidX AppCompat + Material (the forked
    // View-based dialogs/widgets' own real resource dependencies,
    // e.g. simple_list_item_multiple_choice/NavigationView).
    implementation(libs.commons.compress)
    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // com.winlator.PrefManager.kt's own real Preferences DataStore usage.
    implementation(libs.androidx.datastore.preferences)
    // com.winlator.container.ContainerData.kt's own real
    // androidx.compose.runtime.saveable.mapSaver usage (Compose's state-
    // saving API) -- ui transitively pulls in runtime + runtime-saveable,
    // and this module has no @Composable functions of its own yet so the
    // Compose compiler plugin itself isn't needed, only the library.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)

    // Real, proprietary Samsung Performance SDK jar, copied unmodified from
    // vendor/gamenative's own src/main/lib/ (same real file, same real
    // dependency declaration pattern as upstream's own build.gradle.kts) --
    // SamsungPerformanceDriver.kt (forked in with the rest of powercontrol)
    // needs it to compile. A real, optional device-specific driver: on any
    // non-Samsung device this SDK's own runtime checks make it a no-op,
    // not something droidtop's build assumes will always be present.
    implementation(files("src/main/lib/perfsdk-v1.0.0.jar"))
}
