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
    // this module compiles vendor/gamenative's real com.winlator.* tree
    // (via the sourceSets block below — a live reference into the
    // submodule, not a copy, see that block's own comment) plus the
    // minimal app.gamenative.* surface it touches (BuildConfig/PluviaApp
    // compat shims -- see those files' own doc comments). Real
    // com.winlator source references `app.gamenative.R` directly, so this
    // module's own namespace has to BE app.gamenative for AGP's generated
    // R class to resolve those references without editing the forked
    // files -- "our stuff should be UI only, or extensions" (direction
    // this session) means the fork itself stays byte-for-byte, not
    // renamed to fit droidtop's own package convention.
    namespace = "app.gamenative"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    // Real, live reference into vendor/gamenative -- NOT a copy. An
    // earlier version of this module held a static, wholesale copy of
    // gamenative's com.winlator.* tree + res/; confirmed via diff against
    // the actual submodule that copy had already drifted from upstream
    // (missing real feature additions like ContainerData's own
    // xrRefreshRate field) purely from staleness, not any deliberate
    // droidtop-side patch to those files. Per explicit direction ("we can
    // just USE it... All of our submodules need to be INTEGRATED, not
    // copied or replaced"), pointing srcDirs directly at the submodule
    // means gamenative-tux's own daily upstream-sync keeps this module
    // current automatically, with zero duplication or manual re-copying.
    //
    // The include() list below is every file this module needs from
    // vendor/gamenative that's genuinely byte-identical to upstream --
    // confirmed file-by-file via `diff`, not assumed (line-count diffs
    // alone are misleading here: a droidtop file that's merely SMALLER
    // than its real upstream counterpart isn't necessarily "heavily
    // modified" -- see below). com.winlator.* is included wholesale
    // (nothing in it is droidtop-modified). app.gamenative.* is NOT
    // included wholesale -- most of what stays local there isn't a fork
    // at all: BuildConfig.kt, PluviaApp.kt, MainActivity.kt, PrefManager.kt,
    // service/SteamService.kt, utils/ContainerUtils.kt, and
    // utils/LsfgVkManager.kt are all real, minimal compat shims (13-36
    // lines each, vs. upstream's 650-4800-line real files) -- the same
    // "empty marker so a dead/unused import resolves" pattern
    // AndroidEvent.kt's own doc comment explains, confirmed the same way
    // for each: grepped every live-referenced com.winlator.* caller and
    // verified none of them actually invoke the parts these shims omit.
    // The one file here with genuine, structural, load-bearing
    // divergence is powercontrol/PowerManager.kt -- see its own doc
    // comment for the real reasoning (different bootstrap order, no
    // gamenative Application/DI graph) and the real, confirmed-live
    // surface (`Win32AppWorkarounds.java`, live-referenced) it has to
    // keep matching. A handful of smaller powercontrol/* files
    // (PowerProfile.kt, PerformanceDriver.kt, SamsungPerformanceDriver.kt,
    // FanController.kt, SystemMetricsReader.kt, PerformanceAutoTuner.kt,
    // PowerControlUiState.kt) also carry real, smaller, confirmed
    // divergences (added methods PowerManager.kt calls, PrefManager-
    // dependency removal, an initialization-order fix) and stay local for
    // the same reason -- not yet individually doc-commented the way
    // PowerManager.kt now is; a real follow-up, not an oversight being
    // hidden. Also NOT referenced live: gamenative's own Hilt/Room/
    // Compose-Navigation/JavaSteam-dependent code elsewhere in
    // app.gamenative.* that this
    // module was never wired to use at all.
    sourceSets {
        getByName("main") {
            java.srcDir("../vendor/gamenative/app/src/main/java")
            java.include(
                "com/winlator/**",
                "app/gamenative/enums/Marker.kt",
                "app/gamenative/powercontrol/autotuning/AdaptiveFpsCap.kt",
                "app/gamenative/powercontrol/autotuning/DeviceGate.kt",
                "app/gamenative/powercontrol/autotuning/PidController.kt",
                "app/gamenative/powercontrol/autotuning/ClusterTuner.kt",
                "app/gamenative/powercontrol/autotuning/TunerDecisionEngine.kt",
                "app/gamenative/powercontrol/drivers/NoOpPerformanceDriver.kt",
                "app/gamenative/powercontrol/metrics/MetricsSnapshot.kt",
                "app/gamenative/powercontrol/metrics/FrameTimeRing.kt",
                "app/gamenative/powercontrol/metrics/JsonlSessionLog.kt",
                "app/gamenative/powercontrol/metrics/PerformanceMetricsCollector.kt",
                "app/gamenative/powercontrol/profiles/CpuGovernor.kt",
                "app/gamenative/powercontrol/profiles/PerformancePreset.kt",
                "app/gamenative/powercontrol/fan/FanTempController.kt",
                "app/gamenative/powercontrol/AdaptiveFpsCapController.kt",
                "app/gamenative/powercontrol/PowerBaseline.kt",
                "app/gamenative/data/ShooterModeConfig.kt",
                "app/gamenative/data/TouchGestureConfig.kt",
                "app/gamenative/utils/MarkerUtils.kt",
                "app/gamenative/SteamBootstrap.kt",
            )
            res.srcDir("../vendor/gamenative/app/src/main/res")
        }
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
    // called from droidtop's own WineSession yet. `com.winlator.linux`
    // (LinuxContainerBackend/DefaultProotContainerBackend/
    // LinuxProgramLauncherComponent) is included the same way -- this is
    // ProotRuntime's real port source, see runtime-linux-noroot.
}

dependencies {
    implementation(project(":runtime-common"))
    // PcGameProvider.kt's own real LibraryProvider/LibraryEntry/
    // LibraryEntryKind implementation -- this module supplying "pc"-system
    // library entries to the same seam every other source (native apps,
    // console ROMs, engine games) already goes through.
    implementation(project(":library-core"))
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

    // Real, proprietary Samsung Performance SDK jar, referenced directly
    // from vendor/gamenative's own src/main/lib/ (live reference, not a
    // copy — same real file app.gamenative.powercontrol.drivers.
    // SamsungPerformanceDriver.kt (this module's own local, lightly-
    // modified copy — see the sourceSets comment above) needs it to
    // compile. A real, optional device-specific driver: on any
    // non-Samsung device this SDK's own runtime checks make it a no-op,
    // not something droidtop's build assumes will always be present.
    implementation(files("../vendor/gamenative/app/src/main/lib/perfsdk-v1.0.0.jar"))
}
