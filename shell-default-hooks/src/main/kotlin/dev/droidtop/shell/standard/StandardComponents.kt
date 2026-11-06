package dev.droidtop.shell.standard

/**
 * The class names Standard starts by name across the one-way `:app` -> shells edge, written once
 * (Droidtop/tracker#771): `:app`'s main and companion activities, Launcher3's own activity, droidtop's HOME trampoline
 * and the Alternative forwarder. Start a mode's screen with [BackButtonMenu.appModeIntent].
 */
internal object StandardComponents {
    /** `:app`'s MainActivity, every app-hosted mode's screen. */
    const val MAIN = "dev.droidtop.app.MainActivity"

    /** `:app`'s companion, drawn on the companion's screen. */
    const val COMPANION = "dev.droidtop.app.CompanionActivity"

    /** Launcher3's own activity, Standard's home screen. */
    const val LAUNCHER = "com.android.launcher3.Launcher"

    /** droidtop's HOME component for Standard ([HomeTrampolineActivity]). */
    const val HOME_TRAMPOLINE = dev.droidtop.library.settings.Modes.LAUNCHER_ACTIVITY

    /** The HOME component that forwards to another launcher ([AlternativeLauncherActivity]). */
    val ALTERNATIVE: String = AlternativeLauncherActivity::class.java.name
}
