plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // Room's annotation processor for the store tables (StoresDatabase).
    alias(libs.plugins.google.ksp)
    // The list columns' converter encodes List<String> as JSON (StringListConverter).
    alias(libs.plugins.kotlin.serialization)
}

/*
 * The PC stores droidtop runs itself (docs/SPEC.md 7g, "Stores"): Epic,
 * GOG, Amazon Games and itch.io, lifted out of vendor/gamenative into
 * droidtop's own module behind library-core's StoreLibrary. GameNative is
 * GPL-3.0 like droidtop; NOTICE.md credits it. Nothing here depends on the
 * vendored tree: a store that still needed a GameNative type would be the
 * dependency this module exists to remove.
 */
android {
    namespace = "dev.droidtop.stores"
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

    // The stores log through android.util.Log and Timber; a JVM unit test
    // reaching either gets the stub's defaults instead of an exception.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // StoreLibrary, StoreLibraries and the install job (and, through it,
    // plugin-host's one jobs list) are library-core's.
    implementation(project(":library-core"))
    // SafeDelete, the one symlink-safe delete an uninstall goes through.
    implementation(project(":runtime-common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.timber)
    // 7z and RAR uploads (itch.io), through the libarchive build GameNative's mods importer used.
    implementation(libs.libarchive.android)
    // LZMA and XZ, which Amazon's manifests are compressed with.
    implementation(libs.xz)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // org.json is a throwing stub in android.jar; the parsers' tests need the real one.
    testImplementation(libs.json.v20240303)
}
