plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // Real use: RomDatabase (persistent ROM-scan cache, see
    // consoles/RomDatabase.kt's own doc comment).
    alias(libs.plugins.google.ksp)
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
    // RomDatabase's own real persistent ROM-scan cache (Room). `api`, not
    // `implementation` -- real, confirmed-necessary: :app calls
    // ConsoleSystemsDatabase.get(context) directly (ConsoleSystemsActivity's
    // PlatformsScreen), and Kotlin needs RoomDatabase (its declared
    // supertype) resolvable on :app's own compile classpath for that, not
    // just library-core's internal one. `implementation` alone produced a
    // real CI failure: "Cannot access 'RoomDatabase' which is a supertype
    // of 'ConsoleSystemsDatabase'."
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // The registry parser (EngineRegistryParser) runs in JVM unit tests
    // against the real shipped seed JSON; android.jar's org.json is a
    // throwing stub there, so the real library backs the tests.
    testImplementation("org.json:json:20240303")
}
