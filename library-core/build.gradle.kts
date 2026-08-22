plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.library"
    // shell-default (below) compiles against 36 -- same real AAR-metadata
    // mismatch :app already hit and fixed once this session; any module
    // that depends on shell-default needs to match or exceed it.
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
}

dependencies {
    implementation(project(":runtime-common"))
    // Real integration, not a duplicate: NativeAppProvider sources its app
    // list and icons from shell-default's own real IconCache/LauncherApps
    // machinery (the same one Standard's app drawer uses) instead of a
    // second, separate PackageManager-based implementation. Safe
    // direction -- shell-default has no dependency back on library-core,
    // confirmed before adding this.
    implementation(project(":shell-default"))
    // shell-default's own IconCache/CacheLookupFlag/BitmapInfo classes
    // actually live in this separate module (shell-default itself depends
    // on it with `implementation`, not `api`, so it doesn't leak
    // transitively through the dependency above -- confirmed via a real
    // CI failure: "Cannot access class 'CacheLookupFlag'" until this was
    // added directly).
    implementation(project(":IconLoader"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Real android.support.v4.media (MediaBrowserCompat/MediaControllerCompat)
    // -- MediaAppBrowserClient's own local, credential-free control of an
    // installed media app (Spotify/YouTube/Jellyfin, per KnownMediaApps),
    // the same mechanism Android Auto/Wear/Assistant use.
    implementation(libs.androidx.media)
    // RomDetectUtils/SerialScanner's own real Timber.d/i logging, forked
    // from Lemuroid unmodified -- see SerialScanner.kt's own doc comment.
    implementation(libs.timber)

    testImplementation(libs.junit)
}
