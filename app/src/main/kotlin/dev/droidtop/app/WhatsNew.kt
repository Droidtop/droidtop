package dev.droidtop.app

import android.app.Activity
import android.content.Context
import dev.droidtop.app.update.AppSelfUpdate
import dev.droidtop.library.settings.FeatureStep
import dev.droidtop.library.settings.FeatureSteps
import dev.droidtop.runtime.util.AppScopes
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** One build's changelog entry: its version, and its lines under Added, Changed and Fixed, in that order. */
internal class ChangelogBuild(
    val versionCode: Long,
    val versionName: String,
    val sections: List<Pair<String, List<String>>>,
)

/**
 * What changed, shown after an update (docs/SPEC.md 7b, "Update onboarding", Droidtop/tracker#727). The changelog is
 * the build's own `changelog.json`, bundled by CI (docs/SPEC.md 10b, "Every build carries its changelog"): every
 * build's entry, newest first, the same text as its release notes. So a person who skipped builds sees every one
 * since the version they had, without the network.
 *
 * The first time one of droidtop's own screens comes to the front after an update, [WhatsNewActivity] opens over it
 * with the builds newer than the last one the person saw, then the app-wide [FeatureSteps]. A fresh install sees
 * neither: onboarding marks this version and every step seen (OnboardingPrefs).
 */
object WhatsNew {
    private const val PREFS = "whats_new"
    private const val KEY_LAST_SEEN = "last_seen_version_code"
    private const val ASSET = "changelog.json"

    private val checked = AtomicBoolean(false)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The bundled changelog, newest first; empty for a build CI did not make. Reads the APK: off the main thread. */
    internal fun builds(context: Context): List<ChangelogBuild> = runCatching {
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList())

    /** A changelog.json's builds, newest first, as release_channel.py writes them. */
    internal fun parse(json: String): List<ChangelogBuild> {
        val builds = JSONObject(json).optJSONArray("builds") ?: return emptyList()
        return (0 until builds.length()).mapNotNull { index ->
            val build = builds.optJSONObject(index) ?: return@mapNotNull null
            val sections = build.optJSONObject("sections")
            ChangelogBuild(
                versionCode = build.optLong("versionCode"),
                versionName = build.optString("versionName"),
                sections = SECTION_ORDER.mapNotNull { name ->
                    val lines = sections?.optJSONArray(name) ?: return@mapNotNull null
                    (name to (0 until lines.length()).map { lines.optString(it) }.filter { it.isNotBlank() })
                        .takeIf { it.second.isNotEmpty() }
                },
            )
        }
    }

    /**
     * The builds to show after an update from [lastSeen] to [current]: every one newer than [lastSeen] and not newer
     * than [current]. With no [lastSeen] (droidtop was updated from a build before What's new), only [current]'s own.
     * Pure.
     */
    internal fun since(builds: List<ChangelogBuild>, lastSeen: Long?, current: Long): List<ChangelogBuild> =
        builds.filter { it.versionCode <= current && it.versionCode > (lastSeen ?: (current - 1)) }

    /** This version counts as seen: What's new was closed, or onboarding set droidtop up on it. */
    fun markCurrentSeen(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_SEEN, AppSelfUpdate.installedVersionCode(context)).apply()
    }

    /** The last version whose What's new was seen, or null before there was one. Reads preferences. */
    internal fun lastSeen(context: Context): Long? =
        prefs(context).takeIf { it.contains(KEY_LAST_SEEN) }?.getLong(KEY_LAST_SEEN, 0L)

    /**
     * Called as one of droidtop's own screens comes to the front ([activity]): the first time in this process, after
     * an update, it opens [WhatsNewActivity]. Not over onboarding or a game, and not before onboarding finished: a
     * first run is the setup, not an update.
     */
    fun onShellResumed(activity: Activity) {
        if (!checked.compareAndSet(false, true)) return
        val app = activity.applicationContext
        // Opened from the screen in front, in its task, so closing it lands back there.
        val from = java.lang.ref.WeakReference(activity)
        AppScopes.io.launch {
            if (!GamesRootPrefs.isOnboardingComplete(app)) {
                // Onboarding marks this version seen when it finishes; check again at the next start.
                checked.set(false)
                return@launch
            }
            val current = AppSelfUpdate.installedVersionCode(app)
            val last = lastSeen(app)
            if (last == current) return@launch
            if (since(builds(app), last, current).isEmpty() && !FeatureSteps.anyAppWide(app)) {
                markCurrentSeen(app)
                return@launch
            }
            AppScopes.main.launch {
                val screen = from.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                if (screen != null) {
                    screen.startActivity(WhatsNewActivity.intent(screen, all = false))
                } else {
                    // The screen went away meanwhile: the next one in front asks again.
                    checked.set(false)
                }
            }
        }
    }

    /** The steps droidtop registers, at its start (DroidtopApplication), before onboarding or What's new reads them. */
    fun registerSteps() {
        // The first step (Droidtop/tracker#740): HOME + R2, L2 or Start over any game needs the accessibility service,
        // which Android leaves off. App-wide, right after What's new, with the button that turns it on.
        FeatureSteps.register(
            FeatureStep(
                id = "home_chords",
                arrived = "740-home-button-combos",
                place = null,
                title = "HOME button combos over games",
                body = "HOME + R2 now opens the Quick Menu over any game, and HOME + L2 or Start droidtop's other menus. " +
                    "Allow droidtop to see button presses over other apps to use them",
                actionLabel = "Allow",
                action = { context ->
                    val line = dev.droidtop.runtime.keyboard.AccessibilityKeyboard.turnOn(context)
                    dev.droidtop.library.settings.Notices.flash(line, context = context)
                },
                applies = { context -> !dev.droidtop.runtime.keyboard.AccessibilityKeyboard.homeChords(context).first },
            ),
        )
        // Download sites and the TorBox hand-off (docs/SPEC.md 12a "Download sites"): shown where they are set up.
        FeatureSteps.register(
            FeatureStep(
                id = "download_sites",
                arrived = null,
                place = dev.droidtop.app.settings.AppSettingsCatalogs.SCREEN_ACCOUNTS_AND_SOURCES,
                title = "Download sites",
                body = "droidtop can sign in to file hosts and download sites itself, under Download sites here, so " +
                    "downloads from them use your account. With a TorBox account, links it can serve come from TorBox",
            ),
        )
    }

    private val SECTION_ORDER = listOf("Added", "Changed", "Fixed")
}
