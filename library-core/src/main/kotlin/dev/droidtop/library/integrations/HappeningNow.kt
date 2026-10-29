package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryRescanJob
import dev.droidtop.library.scraper.LibraryScrapeJob
import dev.droidtop.library.settings.JobLook
import dev.droidtop.library.stores.StoreSaves
import dev.droidtop.pluginhost.PluginJobsCenter

/**
 * What is happening now, for the one contextual spot in the status area (docs/SPEC.md 7i "Home", Droidtop/tracker#742;
 * the owner: "Have a contextual spot that shows an icon for whatever's currently happening"). Everything droidtop does
 * in the background is a job in the one jobs center, so this reads the jobs and nothing else: a download or install,
 * a library scan, a scrape, a save sync, an update check, a plugin's job. Pure.
 */
object HappeningNow {
    /** The update check's job kind (`:app`'s UpdateChecks registers it). */
    const val UPDATE_CHECK = "update_check"

    /** What kind of thing is happening, most important first: the spot shows the first that runs. */
    enum class Kind(val label: String) {
        DOWNLOAD("Downloading"),
        SYNC("Syncing saves"),
        SCAN("Scanning the library"),
        SCRAPE("Getting artwork and details"),
        UPDATE_CHECK("Checking for updates"),
        PLUGIN("A plugin is working"),
        OTHER("Working"),
    }

    /** The one thing the spot shows: its [kind], how far it is (0 to 1, negative when it does not know), its look, and how many run in all. */
    data class Now(val kind: Kind, val fraction: Float, val look: JobLook, val running: Int) {
        /** What TalkBack says for the spot. */
        val words: String get() = if (running > 1) "${kind.label}, $running things running" else kind.label
    }

    fun kindOf(entry: PluginJobsCenter.Entry): Kind = when (val native = entry.nativeKind) {
        null -> Kind.PLUGIN
        LibraryRescanJob.KIND -> Kind.SCAN
        LibraryScrapeJob.KIND -> Kind.SCRAPE
        StoreSaves.JOB_KIND -> Kind.SYNC
        UPDATE_CHECK -> Kind.UPDATE_CHECK
        else -> if (PluginJobsCenter.isDownloadKind(native)) Kind.DOWNLOAD else Kind.OTHER
    }

    /** The running jobs (not done, not paused) as one [Now], or null when nothing runs. */
    fun of(jobs: List<PluginJobsCenter.Entry>): Now? {
        val running = jobs.filter { !it.done && !it.paused }
        val lead = running.minByOrNull { kindOf(it).ordinal } ?: return null
        return Now(
            kind = kindOf(lead),
            fraction = if (lead.percent < 0) -1f else lead.percent / 100f,
            look = PluginJobsScreen.lookOf(lead),
            running = running.size,
        )
    }
}
