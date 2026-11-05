package dev.droidtop.runtime

/**
 * The app of the game droidtop launched and still runs, for a part that cannot see the library (Standard's taskbar,
 * Droidtop/tracker#700): `LaunchDisplay` in library-core sets it as it sets its own `runningPackageName`. The display
 * it runs on is [HomeDisplays.lastUsedDisplayId], which every launch sets just before. Null while no game runs.
 */
object RunningGame {
    @Volatile
    var packageName: String? = null
}
