plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.ksp)
    // Hilt and Room's own Gradle plugins come from gamenative's catalog
    // (registered as "gn" in settings.gradle) so their versions track the
    // fork, matching how the source itself is consumed.
    alias(gn.plugins.dagger.hilt)
    alias(gn.plugins.room)
}

// Room validates its schemas against gamenative's own real, versioned
// schema history — the databases ARE gamenative's, so their migration
// history is too.
room {
    schemaDirectory("$projectDir/../vendor/gamenative/app/schemas")
}

android {
    // Real, deliberate choice, not droidtop's usual dev.droidtop.*: this
    // module compiles the ENTIRE vendored gamenative tree directly (see
    // sourceSets below), and that tree references `app.gamenative.R` and
    // its own generated BuildConfig throughout, so the namespace has to
    // BE app.gamenative for both to resolve without editing forked files.
    namespace = "app.gamenative"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        // The whole vendored tree compiles here, so all of gamenative's
        // BuildConfig surface has to exist. Values mirror its "modern"
        // product flavor: droidtop targets SDK 34, and Android blocks
        // exec() of extracted binaries above targetSdk 28 — the legacy
        // flavor's whole reason to exist — so MODERN_ANDROID=true and the
        // W^X preload aren't a preference, they're the only values that
        // can work. (This corrects the old hand-written shim, which chose
        // false as "the conservative default" without noticing the
        // targetSdk constraint that rules it out.)
        buildConfigField("boolean", "MODERN_ANDROID", "true")
        buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
        buildConfigField("boolean", "XR_BUILD", "false")
        buildConfigField("boolean", "MODERN_XR", "false")
        buildConfigField("boolean", "GOLD", "false")
        // Empty on purpose, not missing secrets plumbing: PostHog is
        // gamenative's telemetry, and an empty key keeps it inert — the
        // same stance as ripping Play Integrity out of the fork. Wiring
        // real keys here would be a decision, not a default.
        buildConfigField("String", "POSTHOG_API_KEY", "\"\"")
        buildConfigField("String", "POSTHOG_HOST", "\"\"")
        buildConfigField("String", "STEAMGRIDDB_API_KEY", "\"\"")
        buildConfigField("String", "CLOUD_PROJECT_NUMBER", "\"\"")
        // Application-module-only fields AGP does not generate for a
        // library, defined by hand because vendored code reads them:
        // VERSION_* feed HTTP headers and gamenative's own self-update
        // checker (dead in droidtop), APPLICATION_ID an intent-action
        // string and its updater's fileprovider authority, FLAVOR a
        // telemetry tag. All checked before choosing values — none is
        // load-bearing for droidtop's own runtime paths.
        buildConfigField("String", "VERSION_NAME", "\"1.2.0-droidtop\"")
        buildConfigField("int", "VERSION_CODE", "21")
        buildConfigField("String", "APPLICATION_ID", "\"app.gamenative\"")
        buildConfigField("String", "FLAVOR", "\"droidtop\"")
    }

    // Real, direct reference into vendor/gamenative — not a copy, not a
    // symlink. This used to point at java/com/winlator alone, with the
    // app.gamenative.* files it touches recreated locally as shims and
    // stale snapshots; reviewing those against the vendor tree showed
    // every one traced to a single wholesale fork-in commit (stale, not
    // droidtop-modified) while the fork had moved on — and each shim was
    // a place droidtop re-learned something gamenative already does (a
    // null downloader stub was the reason no Wine container could ever
    // be created). Direction (2026-08-31): compile ALL of gamenative in.
    sourceSets {
        getByName("main") {
            java.srcDir("../vendor/gamenative/app/src/main/java")
            // The non-XR flavor source root: main code calls straight
            // into it (MainActivity -> installLaunchReadiness), and
            // upstream selects nonXr vs modernXr per flavor. droidtop is
            // flavorless and mirrors the modern (non-XR) flavor's
            // BuildConfig values, so nonXr is the matching half.
            java.srcDir("../vendor/gamenative/app/src/nonXr/java")
            res.srcDir("../vendor/gamenative/app/src/main/res")
            // The tree reads real assets by name — common_dlls.json the
            // moment a container is created, gpu_cards.json,
            // wincomponents/wincomponents.json, wine_startmenu.json,
            // redirect.tzst, and the box86_64/fexcore/wowbox64 translator
            // payloads.
            assets.srcDir("../vendor/gamenative/app/src/main/assets")
        }
    }

    androidResources {
        // Excluding, not including, on purpose: if upstream adds a file
        // this bundles it (wasteful, harmless), whereas an include list
        // would silently drop something needed and fail at runtime.
        //
        //   dxwrapper       29 MB, and every entry in
        //                   dxwrapper_download.json resolves to
        //                   downloads.gamenative.app — it is fetched on
        //                   demand, so bundling it buys nothing.
        //   steampipe /     Steam-only (steam_api.dll, region lists).
        //   steaminput /    droidtop is not a Steam client.
        //   steam_regions
        //
        // box86_64, fexcore and wowbox64 deliberately stay (~56 MB): they
        // are read straight off getAssets() with no download manifest
        // anywhere, so a missing version is an unrecoverable failure, not
        // a slow first launch.
        ignoreAssetsPatterns += listOf("dxwrapper", "steampipe", "steaminput", "steam_regions.json")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // The full tree is Compose (gamenative's UI) plus the older
        // View-based com.winlator dialogs; both compile here.
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":runtime-common"))

    // GameNativeMigrationSchemaTest pins the migration's supported Room
    // version to the vendored database's own.
    testImplementation(libs.junit)
    // PcGameProvider.kt's own real LibraryProvider/LibraryEntry/
    // LibraryEntryKind implementation — this module supplying "pc"-system
    // library entries to the same seam every other source already goes
    // through.
    implementation(project(":library-core"))

    // ------------------------------------------------------------------
    // gamenative's own dependency list, expressed through its own
    // catalog ("gn") so versions track the fork. Mirrors
    // vendor/gamenative/app/build.gradle.kts with three deliberate
    // deviations, each explained where it happens.
    // ------------------------------------------------------------------
    implementation(gn.material)
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // JavaSteam (the SNAPSHOT repo is declared in settings.gradle).
    implementation(gn.javasteam) { isChanging = true }
    implementation(gn.javasteam.depotdownloader) { isChanging = true }
    implementation(gn.spongycastle)
    implementation(gn.okhttp.dnsoverhttps)

    // Deviation 1: gamenative bundles feature-delivery WITH
    // play-integrity ("libs.bundles.google"). droidtop takes
    // feature-delivery alone — Play Integrity was deliberately ripped
    // out of the fork's code, and keeping its client library out of the
    // dependency tree makes that removal structural rather than
    // accidental. If a stray reference survived the ripout, this is
    // where the build will say so by name.
    implementation(gn.feature.delivery)

    // Winlator
    implementation(gn.bundles.winlator)
    implementation(gn.libarchive.android)
    implementation(gn.zstd.jni) { artifact { type = "aar" } }
    implementation(gn.xz)

    // Compose — gamenative's own BOM, not droidtop's: the vendored UI
    // uses material3-adaptive APIs that droidtop's older BOM predates.
    implementation(platform(gn.androidx.compose.bom))
    implementation(gn.bundles.compose)
    implementation(gn.landscapist.coil)
    implementation(gn.media3.exoplayer)
    implementation(gn.media3.exoplayer.hls)
    implementation(gn.media3.ui)
    debugImplementation(gn.androidx.ui.tooling)

    // Support
    implementation(gn.androidx.core.ktx)
    implementation(gn.androidx.lifecycle.runtime.ktx)
    implementation(gn.apng)
    implementation(gn.datastore.preferences)
    implementation(gn.jetbrains.kotlinx.json)
    implementation(gn.kotlin.coroutines)
    implementation(gn.timber)
    implementation(gn.zxing)
    implementation(gn.protobuf.java)

    // Hilt + Room (KSP for both)
    implementation(gn.bundles.hilt)
    ksp(gn.bundles.ksp)
    implementation(gn.bundles.room)

    // Deviation 2: PostHog stays a compile dependency (PluviaApp
    // references it unconditionally) but the empty POSTHOG_API_KEY above
    // keeps it inert. Removing the calls belongs in the fork, once,
    // rather than being patched around here.
    implementation("com.posthog:posthog-android:3.8.0")
    implementation("com.auth0.android:jwtdecode:2.0.2")

    // Real, proprietary Samsung Performance SDK jar, referenced directly
    // from the vendor tree (live reference, not a copy). On non-Samsung
    // devices its own runtime checks make it a no-op.
    implementation(files("../vendor/gamenative/app/src/main/lib/perfsdk-v1.0.0.jar"))

    // Deviation 3: gamenative's test dependencies are not mirrored —
    // its test sources live in the vendor tree's own test roots, which
    // this module does not reference, so the deps would be dead weight.
}
