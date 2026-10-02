package dev.droidtop.runtime

/**
 * Whether an app is in front on the screen the companion lives on, so the
 * orchestration must not start the companion (or re-front the shell) over it
 * (Droidtop/tracker#265).
 *
 * The companion is an Activity on the screen the shell is NOT on. When the
 * shell is on the add-on and the user launches an emulator onto the other,
 * built-in screen, the emulator covers the companion, which stops, and "no
 * companion is visible" is exactly what the orchestration's relaunch looks
 * for: it started the companion again over the emulator and re-fronted the
 * shell, every few seconds. Each of those starts takes the system's focused
 * display, so the pad went to the shell (or to a window that takes no keys)
 * and never to the emulator. [DualScreenOrchestration.userAppDisplayId]
 * covers the add-on side of that; this is the same rule for the companion's
 * own screen.
 *
 * Two signals, the same pair as there: an app droidtop launched there
 * ([parkedDisplayId]), and the companion paused there by something else
 * ([companionCoveredDisplayId], which `CompanionCover` in :display keeps and
 * clears when the companion resumes or the user asks for the screens back).
 */
object CompanionScreenGuard {
    fun appInFrontOfCompanion(
        shellDisplayId: Int,
        companionDisplayId: Int,
        parkedDisplayId: Int?,
        companionCoveredDisplayId: Int?,
    ): Boolean {
        if (companionDisplayId == shellDisplayId) return false
        return parkedDisplayId == companionDisplayId || companionCoveredDisplayId == companionDisplayId
    }
}
