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

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/platformDatabase/assets"))

    // The scan code logs for real (one line per folder, one per root --
    // see ScanLog), and a JVM unit test that walks a temporary folder
    // therefore reaches android.util.Log, whose unmocked stub throws. The
    // library's per-provider isolation is exactly the behaviour a test
    // must be able to exercise, so the stubs return defaults here rather
    // than the production code growing a second, test-only log seam.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// --- The bundled databases are a SNAPSHOT of droidtop-platforms ----------
// docs/SPEC.md 7e2: the seed JSONs are NOT hand-copied into source control
// any more (four files that had drifted days apart from the repository they
// claim to mirror). vendor/droidtop-platforms is a submodule pinned to one
// commit; this task copies that commit's databases into a generated assets
// directory at build time and writes the commit beside them, so what a
// build ships is exactly one identifiable state of the platform repo and
// the settings screen can say which.
val platformsRepoDir = rootProject.file("vendor/droidtop-platforms")

val platformDatabaseSeedFiles = listOf(
    "engines-database.json",
    "platforms-database.json",
    "players-database.json",
    "bios-database.json",
)

/** The pinned submodule commit, or "unknown" when git cannot say (a source-zip build). */
val platformDatabaseSnapshotCommit: String = runCatching {
    providers.exec {
        commandLine("git", "-C", platformsRepoDir.absolutePath, "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("").ifEmpty { "unknown" }

val platformDatabaseSeedDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/platformDatabase/assets")

val platformDatabaseSeed = tasks.register<Copy>("platformDatabaseSeed") {
    description = "Copies the pinned droidtop-platforms snapshot into the bundled assets."
    from(platformsRepoDir) { include(platformDatabaseSeedFiles) }
    into(platformDatabaseSeedDir)
    inputs.property("snapshotCommit", platformDatabaseSnapshotCommit)
    doFirst {
        val missing = platformDatabaseSeedFiles.filterNot { File(platformsRepoDir, it).isFile }
        check(missing.isEmpty()) {
            "vendor/droidtop-platforms has no $missing -- run " +
                "`git submodule update --init vendor/droidtop-platforms`"
        }
    }
    doLast {
        platformDatabaseSeedDir.get().file("platform-database-snapshot.json").asFile
            .writeText("""{"commit": "$platformDatabaseSnapshotCommit"}""")
    }
}

tasks.named("preBuild") { dependsOn(platformDatabaseSeed) }
// The registry/player tests parse the SHIPPED seed, which is now generated.
tasks.withType<Test>().configureEach { dependsOn(platformDatabaseSeed) }

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
