package dev.droidtop.library

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import dev.droidtop.library.consoles.KnownPlayers
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import kotlinx.serialization.Serializable

/**
 * What droidtop knows about an installed app beyond its launcher entry,
 * read in the one package pass [NativeAppProvider] already makes (so a
 * list never asks the package manager per row): the facts the Apps
 * facets and sorts read (docs/SPEC.md 7j, "Filters, sort and the hint
 * bar"). Null on every entry that is not an installed app.
 */
@Serializable
data class InstalledAppFacts(
    /** When the app was first installed; 0 when the package manager does not say. */
    val firstInstalledEpochMs: Long = 0L,
    /** Android's own statement that this is a game (the manifest's game flag or its game category). */
    val flaggedGame: Boolean = false,
    /** A preinstalled system app. */
    val system: Boolean = false,
    /** The package that installed it, or null for a sideload. */
    val installer: String? = null,
)

/** The categories the Apps view groups by. */
enum class AppCategory(val label: String) {
    GAMES("Games"),
    EMULATORS("Emulators"),
    SYSTEM("System"),
    OTHER("Other"),
}

/**
 * Which [AppCategory] an app is in: a default plus the person's override
 * (docs/SPEC.md 7j, "Mark as game"). The default is seeded from Android's
 * own game flag, droidtop's player database (every emulator it knows how
 * to launch) and a short list of game stores and launchers; the override
 * is "Mark as game" and "Not a game", which beat every default. Pure, so
 * the rules are unit-tested.
 */
class AppCategoryRules(
    private val emulatorPackages: Set<String> = emptySet(),
    private val markedGames: Set<String> = emptySet(),
    private val markedNotGames: Set<String> = emptySet(),
) {
    /** The category of [packageName], or null when [facts] is null (not an installed app). */
    fun categoryOf(packageName: String, facts: InstalledAppFacts?): AppCategory? {
        if (facts == null) return null
        if (packageName in markedGames) return AppCategory.GAMES
        if (packageName in emulatorPackages) return AppCategory.EMULATORS
        if (facts.system) return AppCategory.SYSTEM
        val seeded = facts.flaggedGame || packageName in GAME_STORE_PACKAGES
        return if (seeded && packageName !in markedNotGames) AppCategory.GAMES else AppCategory.OTHER
    }

    fun isGame(packageName: String, facts: InstalledAppFacts?): Boolean =
        categoryOf(packageName, facts) == AppCategory.GAMES

    companion object {
        /**
         * Game stores and launchers an app's own game flag does not cover.
         * A starter list, not a claim of completeness: "Mark as game"
         * covers the rest.
         */
        val GAME_STORE_PACKAGES: Set<String> = setOf(
            "com.valvesoftware.android.steam.community",
            "com.valvesoftware.steamlink",
            "com.epicgames.portal",
            "com.gog.galaxy",
            "com.nvidia.geforcenow",
            "com.limelight",
            "com.winlator",
        )

        /** Reads the player database and the person's marks: disk work, never on the main thread. */
        fun load(context: Context): AppCategoryRules {
            val emulators = runCatching { KnownPlayers.all(context).mapTo(HashSet()) { it.pkg } }.getOrDefault(emptySet())
            val (games, notGames) = AppGameMarks.read(context)
            return AppCategoryRules(emulators, games, notGames)
        }
    }
}

/**
 * The person's "Mark as game" and "Not a game" answers, one set each in
 * the launcher preferences. A package is in at most one of them.
 */
object AppGameMarks {
    private const val KEY_GAMES = "droidtop_app_marked_games"
    private const val KEY_NOT_GAMES = "droidtop_app_marked_not_games"

    private fun prefs(context: Context) =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)

    /** (marked games, marked not-games). A preference read: not for the main thread. */
    fun read(context: Context): Pair<Set<String>, Set<String>> {
        val p = prefs(context)
        return (p.getStringSet(KEY_GAMES, emptySet()) ?: emptySet()).toSet() to
            (p.getStringSet(KEY_NOT_GAMES, emptySet()) ?: emptySet()).toSet()
    }

    /** Records [game] as the answer for [packageName]. */
    fun set(context: Context, packageName: String, game: Boolean) {
        val (games, notGames) = read(context)
        prefs(context).edit()
            .putStringSet(KEY_GAMES, if (game) games + packageName else games - packageName)
            .putStringSet(KEY_NOT_GAMES, if (game) notGames - packageName else notGames + packageName)
            .apply()
    }
}

/**
 * Usage access: the optional special permission that lets droidtop read
 * when each app was last used, including apps opened outside droidtop.
 * Without it "Recently used" reads only droidtop's own launch log. The
 * permission is asked for with a plain explanation, never required.
 */
object AppUsageAccess {
    private const val OP_GET_USAGE_STATS = "android:get_usage_stats"
    private const val LOOKBACK_MS = 30L * 24 * 60 * 60 * 1000

    /** Whether the person has granted Usage access to droidtop. */
    @Suppress("DEPRECATION")
    fun granted(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return ops.checkOpNoThrow(OP_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** The system screen where the person grants it. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Package to last-used time over the last 30 days; empty without the permission. A system query: not for the main thread. */
    fun lastUsed(context: Context, now: Long = System.currentTimeMillis()): Map<String, Long> {
        if (!granted(context)) return emptyMap()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
        return runCatching {
            manager.queryAndAggregateUsageStats(now - LOOKBACK_MS, now)
                .mapNotNull { (pkg, stats) -> stats.lastTimeUsed.takeIf { it > 0L }?.let { pkg to it } }
                .toMap()
        }.getOrDefault(emptyMap())
    }
}

/** A store's name for the package that installed an app; no installer is a sideload. Pure. */
fun appSourceLabel(facts: InstalledAppFacts): String {
    if (facts.system) return "System"
    val installer = facts.installer ?: return "Sideloaded"
    return KNOWN_INSTALLERS[installer] ?: "Other store"
}

private val KNOWN_INSTALLERS = mapOf(
    "com.android.vending" to "Play Store",
    "com.amazon.venezia" to "Amazon Appstore",
    "org.fdroid.fdroid" to "F-Droid",
    "com.sec.android.app.samsungapps" to "Galaxy Store",
    "com.android.packageinstaller" to "Sideloaded",
    "com.google.android.packageinstaller" to "Sideloaded",
    "com.android.shell" to "Sideloaded",
)
