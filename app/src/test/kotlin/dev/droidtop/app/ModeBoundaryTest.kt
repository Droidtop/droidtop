package dev.droidtop.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A mode never opens another (docs/SPEC.md 2c, "A mode never opens another"; owner, 2026-10-24, Droidtop/tracker#639:
 * "while sections can share code, we absolutely should NOT be seeing jumps between modes"). Every place in droidtop's
 * own sources that starts, or builds the intent for, a mode's own screen (MainActivity, Standard's games screen, the
 * Android home screen, a named mode) is listed here with why it may: the person's own switch, an entry from outside
 * droidtop, or a start that stays in the mode it is made from. A new one anywhere, or one more in a listed file, fails
 * until it is looked at and listed with its reason.
 */
class ModeBoundaryTest {

    /** What counts as starting a mode's screen. */
    private val starts = Regex(
        listOf(
            """\bMainActivity::class""",
            """\bLauncherGamesActivity::class""",
            """\bLauncherGamesActivity\.pageIntent\(""",
            """addCategory\(Intent\.CATEGORY_HOME\)""",
            """putExtra\((?:BackButtonMenu\.|dev\.droidtop\.shell\.standard\.BackButtonMenu\.)?EXTRA_MODE\b""",
            """\bBackButtonMenu\.openHome\(""",
            """\bAPP_MAIN_ACTIVITY\b""",
            """SHOW_GAMES"\)""",
            """\bappModeIntent\(""",
        ).joinToString("|"),
    )

    /** The reviewed places, by file under the repository, with how many there are and why they may. */
    private val allowed: Map<String, Pair<Int, String>> = mapOf(
        "app/src/main/kotlin/dev/droidtop/app/CatalogScreenActivity.kt" to (1 to "Back from the screen host returns to Standard only when it was opened in Standard"),
        "app/src/main/kotlin/dev/droidtop/app/CompanionWidgetProvider.kt" to (1 to "a home-screen widget's own button, the person's tap"),
        "app/src/main/kotlin/dev/droidtop/app/DesktopWidgetProvider.kt" to (2 to "the Desktop widget's own buttons, the person's tap"),
        "app/src/main/kotlin/dev/droidtop/app/DroidtopShortcuts.kt" to (4 to "the app icon's shortcuts, which the person picks"),
        "app/src/main/kotlin/dev/droidtop/app/ExportLaunch.kt" to (2 to "an exported launch, an entry from outside droidtop"),
        "app/src/main/kotlin/dev/droidtop/app/FullPageWidgetProvider.kt" to (2 to "the full-page widget's mode buttons, the person's switch"),
        "app/src/main/kotlin/dev/droidtop/app/GameLaunchActivity.kt" to (1 to "a pinned icon or widget on the Android home screen, which is Standard, opens Standard's games screen"),
        "app/src/main/kotlin/dev/droidtop/app/HomeOverlayKeys.kt" to (1 to "asks whether droidtop is the home screen, for the HOME chords' key filter; starts nothing"),
        "app/src/main/kotlin/dev/droidtop/app/InGameOverlays.kt" to (2 to "the in-game left menu offers Gaming's sections only while Gaming is the mode in use, and opens the one the person picked"),
        "app/src/main/kotlin/dev/droidtop/app/JobOpener.kt" to (1 to "a job row's game opens Standard's games screen only in Standard"),
        "app/src/main/kotlin/dev/droidtop/app/LauncherGamesActivity.kt" to (4 to "Standard's own games screen: its icon entry, its pinned icons, and Back to Standard's home only in Standard"),
        "app/src/main/kotlin/dev/droidtop/app/LauncherSearchActivity.kt" to (1 to "Standard's search opens Standard's games screen"),
        "app/src/main/kotlin/dev/droidtop/app/MainActivity.kt" to (2 to "a mode that was switched off goes home; the shell moving to another display stays in its mode"),
        "app/src/main/kotlin/dev/droidtop/app/ModeReturn.kt" to (2 to "back to the mode in use, never another"),
        "app/src/main/kotlin/dev/droidtop/app/ModeSwitcherActivity.kt" to (1 to "the mode switcher, the person's switch"),
        "app/src/main/kotlin/dev/droidtop/app/OnboardingActivity.kt" to (2 to "the end of onboarding opens the mode the person chose"),
        "app/src/main/kotlin/dev/droidtop/app/OnboardingHomeStep.kt" to (1 to "the Home step lists the installed home apps (a query, nothing is started)"),
        "app/src/main/kotlin/dev/droidtop/app/OpenWithActivity.kt" to (2 to "Open with droidtop from another app runs a program on the desktop: an entry from outside droidtop"),
        "app/src/main/kotlin/dev/droidtop/app/PlaceLinks.kt" to (2 to "a notification opens Gaming only when Gaming is the mode in use"),
        "app/src/main/kotlin/dev/droidtop/app/settings/ContainersCatalog.kt" to (2 to "the Open Desktop row, which says it switches"),
        "app/src/main/kotlin/dev/droidtop/app/settings/StoresCatalog.kt" to (1 to "a store's library row opens Standard's games screen only in Standard"),
        "app/src/main/kotlin/dev/droidtop/app/settings/UpdatesPage.kt" to (2 to "Get new games is listed only in Gaming, and opens Gaming's own page"),
        "app/src/main/kotlin/dev/droidtop/app/update/UpdateRelaunch.kt" to (1 to "after droidtop updates itself, it starts again where Home goes"),
        "plugin-host/src/main/kotlin/dev/droidtop/pluginhost/BrokerAskActivity.kt" to (1 to "its own extra of the same name, not a mode"),
        "plugin-host/src/main/kotlin/dev/droidtop/pluginhost/PluginBrokers.kt" to (1 to "asks which app is the home screen, starts nothing"),
        "runtime-common/src/main/kotlin/dev/droidtop/runtime/tasks/TaskManager.kt" to (2 to "an ended app leaves the screen by a Home press marked for the mode in use (Modes.EXTRA_MODE_IN_USE); and a query"),
        "shell-default/src/app/murinelauncher/settings/SettingsMiscFragment.kt" to (1 to "Standard's settings ask which app is the home screen"),
        "shell-default/src/com/android/launcher3/dragndrop/AddItemActivity.java" to (1 to "upstream Launcher3's own home check"),
        "shell-default/src/dev/droidtop/shell/standard/BackButtonMenu.kt" to (6 to "the mode menu, the person's switch"),
        "shell-default/src/dev/droidtop/shell/standard/HomeEntry.kt" to (1 to "the Home press, which goes where the person's Home choice says"),
        "shell-default/src/dev/droidtop/shell/standard/HomeRolePrefs.kt" to (1 to "asks Android which app opens on Home; starts nothing"),
        "shell-default/src/dev/droidtop/shell/standard/HomeTrampolineActivity.kt" to (1 to "the Home press, which goes where the person's Home choice says"),
        "shell-default/src/dev/droidtop/shell/standard/StandardTaskbar.kt" to (1 to "Standard's taskbar Home button, the person's press"),
    )

    @Test
    fun `nothing starts another mode's screen outside the reviewed places`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val skipped = setOf("vendor", "build", "test", "tests", "androidTest", ".git", ".gradle")
        val found = root.walkTopDown()
            .onEnter { it.name !in skipped }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .associate { file ->
                val count = file.readLines().filterNot { line -> line.trim().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") } }
                    .sumOf { line -> starts.findAll(line).count() }
                file.relativeTo(root).invariantSeparatorsPath to count
            }
            .filterValues { it > 0 }
        assertEquals(
            "A start of a mode's screen was added or removed. Look at it: it may stay in its mode, or be the person's own " +
                "switch, and then list it here with its reason (docs/SPEC.md 2c).",
            allowed.mapValues { it.value.first }.toSortedMap(),
            found.toSortedMap(),
        )
    }
}
