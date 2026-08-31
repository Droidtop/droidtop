// Hilt's Gradle plugin (its AggregateDepsTask) needs javapoet >= 1.13
// (ClassName.canonicalName). Something older on this multi-project
// buildscript classpath -- the forked-in Murine/launcher tooling --
// resolves an older javapoet first, and the plugin dies with
// NoSuchMethodError at :app:hiltAggregateDepsDebug (real CI failure,
// round 6 of the full-gamenative compile). Forcing the version on the
// buildscript classpath is the documented fix for exactly this clash.
buildscript {
    dependencies {
        classpath("com.squareup:javapoet:1.13.0")
    }
}

// Root build file — no build logic here, plugins are applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Real use: library-core's own RomDatabase (Room, persistent ROM
    // scan cache) -- was already declared in the version catalog but
    // unused until now.
    alias(libs.plugins.google.ksp) apply false
}
