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

// Round 9 of the same ladder: with javapoet fixed, Hilt's processor
// itself fails reading our classes -- "Metadata instance has version
// 2.2.0, while maximum supported version is 2.1.0". Dagger 2.55 (the
// fork's pinned version, consumed via the gn catalog) bundles the
// kotlin-metadata-jvm for Kotlin 2.1 while droidtop compiles with
// 2.2.21. Forcing the metadata library to match our compiler is the
// standard fix that keeps the fork's Hilt version tracked instead of
// diverging it.
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-metadata-jvm") {
                useVersion("2.2.21")
                because("Hilt/Dagger must read Kotlin 2.2 metadata; see the comment above")
            }
        }
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
