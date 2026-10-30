package dev.droidtop.library

import android.content.Context

/**
 * droidtop's own pick among a record's downloads (Droidtop/tracker#838; the owner: "Downloads still don't auto-pick
 * links"): the mirrors to try, in the order to try them, so an update starts with no link pressed by hand. The person
 * can still pick any row themselves on the record page.
 *
 * - Only the game itself: a `build` that is official. Patches and extras are offered, never taken.
 * - The system droidtop runs it with ([systems], best first): a link for any system counts for all of them.
 * - The build of the copy installed: a Steam copy takes the "Steam" build, any other copy the build without it.
 * - The host: a link droidtop serves without a page ([served]: a debrid account or the host's own API, #638) first, then
 *   the person's host order ([HostOrder]), then the rest in the source's order.
 */
object DownloadPick {
    /** What a link that started no download comes back as: the one sentence the pick reads as "try the next mirror". */
    const val NOTHING_STARTED = "Nothing was downloaded from that page"

    /** The systems droidtop runs [entry] with, best first: an Android app's own, else Windows (through Wine) then Linux. */
    fun systemsFor(entry: LibraryEntry): List<String> =
        if (entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP) listOf("android") else listOf("windows", "linux")

    /** Whether [entry] is a Steam copy, whose update is the source's "Steam" build. */
    fun isSteamCopy(entry: LibraryEntry): Boolean = entry.id.startsWith("steam:")

    /** Whether [downloads] hold a build droidtop could pick itself, so the record page offers Get the update. Pure. */
    fun offers(downloads: List<UpdateSources.Download>): Boolean = downloads.any { it.role == "build" && it.official }

    /** [downloads], the record's, as the mirrors to try, best first; empty when none fits. Pure. */
    fun candidates(
        downloads: List<UpdateSources.Download>,
        systems: List<String>,
        steamCopy: Boolean,
        order: List<String>,
        served: (UpdateSources.Download) -> Boolean,
    ): List<UpdateSources.Download> {
        val builds = downloads.withIndex().filter { (_, d) -> d.role == "build" && d.official && (d.url != null || d.link.isNotBlank() || d.parts.isNotEmpty()) }
        val fitting = builds.filter { (_, d) -> d.systems.isEmpty() || systems.isEmpty() || d.systems.any { it in systems } }
        fun systemRank(d: UpdateSources.Download): Int =
            if (d.systems.isEmpty()) systems.size else systems.indexOfFirst { it in d.systems }.let { if (it < 0) systems.size + 1 else it }
        fun buildRank(d: UpdateSources.Download): Int = if (d.section.contains("steam", ignoreCase = true) == steamCopy) 0 else 1
        fun hostRank(d: UpdateSources.Download): Int {
            if (served(d)) return -1
            val host = d.host.lowercase()
            return order.indexOfFirst { host.contains(it) }.let { if (it < 0) order.size else it }
        }
        return fitting
            .sortedWith(compareBy({ buildRank(it.value) }, { systemRank(it.value) }, { hostRank(it.value) }, { it.index }))
            .map { it.value }
    }
}

/**
 * The person's host order for droidtop's pick (Droidtop/tracker#838): host names, most wanted first, matched against a
 * link's host by name. A link droidtop serves without a page always comes first, whatever the order says.
 */
object HostOrder {
    /** The order before the person sets one: hosts that serve a file straight away before those that show a page. */
    val DEFAULT = listOf("pixeldrain", "gofile", "buzzheavier", "datanodes", "mega", "mediafire", "workupload", "mixdrop", "uploadhaven")

    private const val PREFS = "download_host_order"
    private const val KEY = "order"

    /** The person's order, or [DEFAULT]. A preferences read. */
    fun get(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
            ?: DEFAULT

    fun set(context: Context, order: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, order.joinToString(",") { it.trim().lowercase() }).apply()
    }
}
