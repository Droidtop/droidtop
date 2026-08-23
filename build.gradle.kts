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
