package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.format.ValueFormat

/*
 * Home's strip (docs/SPEC.md 7i, "Home"; Droidtop/tracker#485 F7a, final design B.2): What's new, Friends and
 * Downloads under Recent games, stepped with L1/R1, each a row of cards. Everything here is pure and works on
 * what the shell already holds in memory (the library flow, the jobs, the friends), so the model is built off
 * the main thread in time linear in the library, and a JVM test holds it to its rules.
 */

/** A day, for "added" and its window. */
private const val DAY_MS = 24L * 60 * 60 * 1000

/** One card on Home's strip. [id] is stable across rebuilds, so the cursor stays on its card. */
internal sealed interface HomeCard {
    val id: String
    val title: String
    val line: String

    /** A game the person has, with a newer version known: "v0.9.6". Opens the game's page. */
    data class GameUpdate(val entry: LibraryEntry, val version: String) : HomeCard {
        override val id: String get() = "update:${entry.id}"
        override val title: String get() = entry.title
        override val line: String get() = version.let { if (it.firstOrNull()?.isDigit() == true) "v$it" else it }
    }

    /**
     * Games one scan added to one place, counted as the library counts them: "212 games added to SNES". A
     * single game reads by its own name, "Added 2 days ago". [systemId] is null for PC games and apps.
     */
    data class Added(val place: String, val systemId: String?, val count: Int, val newestAt: Long, val single: LibraryEntry?, val now: Long) : HomeCard {
        override val id: String get() = "added:${systemId ?: place}:${newestAt / DAY_MS}"
        override val title: String get() = single?.title ?: if (count == 1) "1 game added to $place" else "$count games added to $place"
        override val line: String get() = "Added ${ValueFormat.daysAgo(now, newestAt)}"
    }

    /** Plugin updates, all in one card: "3 plugin updates". Opens Plugins > Installed. */
    data class PluginUpdates(val count: Int) : HomeCard {
        override val id: String get() = "plugin-updates"
        override val title: String get() = if (count == 1) "1 plugin update" else "$count plugin updates"
        override val line: String get() = "Plugins"
    }

    /** What the twelve-card cap left out. Opens Updates. */
    data class More(val count: Int) : HomeCard {
        override val id: String get() = "more"
        override val title: String get() = "+$count more"
        override val line: String get() = "What's new"
    }

    /** The chores, one card, always last: "3 things need attention". Opens the Needs attention page. */
    data class Attention(val count: Int) : HomeCard {
        override val id: String get() = "attention"
        override val title: String get() = if (count == 1) "1 thing needs attention" else "$count things need attention"
        override val line: String get() = "Needs attention"
    }

    /** A friend: "Playing Hades", in game first. Opens the chat. */
    data class Friend(val key: String, val name: String, val activity: String, val unread: Int) : HomeCard {
        override val id: String get() = "friend:$key"
        override val title: String get() = name
        override val line: String get() = activity
    }

    /** A download that is running or paused: "45% · About 8 min left". Opens the job. */
    data class Download(val jobId: String, override val title: String, val percent: Int, val remainingSeconds: Long, val paused: Boolean) : HomeCard {
        override val id: String get() = "job:$jobId"
        override val line: String get() = when {
            paused -> "Paused"
            percent < 0 -> "Starting"
            else -> listOf(ValueFormat.percent(percent / 100f), ValueFormat.remaining(remainingSeconds)).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }
}

/** A tab of Home's strip. */
internal enum class HomeTabKind(val label: String) { WHATS_NEW("What's new"), FRIENDS("Friends"), DOWNLOADS("Downloads") }

internal data class HomeTab(val kind: HomeTabKind, val cards: List<HomeCard>, val count: Int = 0) {
    /** "Friends · 2", "Downloads · 1": the count says how many, What's new needs none. */
    val label: String get() = if (count > 0 && kind != HomeTabKind.WHATS_NEW) "${kind.label} · $count" else kind.label

    /** What the tab says with no cards. */
    val empty: String get() = when (kind) {
        HomeTabKind.WHATS_NEW -> "You're up to date"
        HomeTabKind.FRIENDS -> "No friends online"
        HomeTabKind.DOWNLOADS -> "Nothing downloading"
    }
}

/** One thing that needs the person (the Needs attention page): what, the outcome, and the one verb that fixes it. */
internal data class AttentionItem(
    val id: String,
    val title: String,
    val body: String,
    val verb: String,
    /** What the item's state is, so a seen item stays quiet until it changes. */
    val state: String,
    val target: AttentionTarget,
)

/** Where an attention item's verb goes. */
internal sealed interface AttentionTarget {
    data class Bios(val systemId: String) : AttentionTarget
    data object Artwork : AttentionTarget
}

internal object HomeModel {
    /**
     * Home's Random game (Droidtop/tracker#507): one of [entries] picked at random, hidden ones and repeats of one
     * id left out, so a game listed twice is not twice as likely. Null when there is none.
     */
    fun randomGame(entries: List<LibraryEntry>, random: kotlin.random.Random = kotlin.random.Random.Default): LibraryEntry? =
        entries.filter { !it.hidden }.distinctBy { it.id }.randomOrNull(random)

    /** What's new holds at most this many cards before "+N more" (the final design, B.2). */
    const val WHATS_NEW_CAP = 12

    /** How far back an addition is news. */
    const val ADDED_WINDOW_DAYS = 14

    /**
     * What's new, newest first: game updates and grouped additions, interleaved by when they happened, then
     * one card for plugin updates, then "+N more" past [WHATS_NEW_CAP], and at most one Needs attention card,
     * always last. Chores are never news, so attention never takes a news slot.
     */
    fun whatsNew(updates: List<HomeCard.GameUpdate>, added: List<HomeCard.Added>, pluginUpdates: Int, attention: Int): List<HomeCard> {
        val news: List<Pair<Long, HomeCard>> =
            updates.map { (it.entry.lastPlayedEpochMs ?: it.entry.addedEpochMs()) to it } + added.map { it.newestAt to it }
        val ordered = news.sortedByDescending { it.first }.map { it.second } +
            listOfNotNull(HomeCard.PluginUpdates(pluginUpdates).takeIf { pluginUpdates > 0 })
        val shown = if (ordered.size > WHATS_NEW_CAP) ordered.take(WHATS_NEW_CAP - 1) + HomeCard.More(ordered.size - (WHATS_NEW_CAP - 1)) else ordered
        return shown + listOfNotNull(HomeCard.Attention(attention).takeIf { attention > 0 })
    }

    /** The games that have a newer version known, as cards. */
    fun updates(entries: List<LibraryEntry>): List<HomeCard.GameUpdate> =
        entries.mapNotNull { e -> e.availableUpdate?.takeIf { !e.hidden }?.let { HomeCard.GameUpdate(e, it) } }

    /**
     * Additions grouped by where they went and the day they were found (one scan), counted as the library
     * counts them: each entry is one game (a multi-disc game is already one entry), hidden ones are left out.
     * [placeOf] names the place ("SNES", "PC Games", "Apps"); [systemOf] gives a Retro game's system id.
     */
    fun added(
        entries: List<LibraryEntry>,
        now: Long,
        placeOf: (LibraryEntry) -> String,
        systemOf: (LibraryEntry) -> String?,
    ): List<HomeCard.Added> {
        val since = now - ADDED_WINDOW_DAYS * DAY_MS
        return entries.asSequence()
            .filter { !it.hidden && it.addedEpochMs() in (since + 1)..now }
            .groupBy { placeOf(it) to it.addedEpochMs() / DAY_MS }
            .map { (key, group) ->
                val newest = group.maxOf { it.addedEpochMs() }
                HomeCard.Added(key.first, systemOf(group.first()), group.size, newest, group.singleOrNull(), now)
            }
    }

    /**
     * The strip's tabs: What's new always; Friends while a social service is on; Downloads while a job runs or
     * waits. A tab the cursor is on stays (showing its empty state) even when its condition goes away, so the
     * strip never moves under the cursor ([keep]).
     */
    fun tabs(whatsNew: List<HomeCard>, friendsOn: Boolean, friends: List<HomeCard.Friend>, downloads: List<HomeCard.Download>, keep: HomeTabKind? = null): List<HomeTab> =
        buildList {
            add(HomeTab(HomeTabKind.WHATS_NEW, whatsNew))
            if (friendsOn || keep == HomeTabKind.FRIENDS) add(HomeTab(HomeTabKind.FRIENDS, friends, friends.size))
            if (downloads.isNotEmpty() || keep == HomeTabKind.DOWNLOADS) add(HomeTab(HomeTabKind.DOWNLOADS, downloads, downloads.size))
        }

    /** Friends as cards: in game first, then unread, then online, as the friends list orders them. */
    fun friends(contacts: List<Triple<String, dev.droidtop.library.social.SocialFriend, String>>): List<HomeCard.Friend> =
        contacts.filter { it.second.state != dev.droidtop.library.social.SocialState.OFFLINE || it.second.unread > 0 }
            .sortedWith(
                compareBy<Triple<String, dev.droidtop.library.social.SocialFriend, String>> { if (it.second.state == dev.droidtop.library.social.SocialState.IN_GAME) 0 else 1 }
                    .thenByDescending { it.second.unread }
                    .thenBy { it.second.name.lowercase() },
            )
            .map { (key, friend, line) -> HomeCard.Friend(key, friend.name, line, friend.unread) }

    /** The attention items not yet seen in their current state; seen ones stay listed but quiet. */
    fun unseen(items: List<AttentionItem>, seen: Map<String, String>): List<AttentionItem> = items.filter { seen[it.id] != it.state }

    /**
     * The BIOS items: one per system that has games in the library, only when a file it needs is missing or
     * does not match. Missing and wrong are separate items; a system without games is never mentioned.
     */
    fun biosItems(systemsWithGames: Map<String, String>, missing: Map<String, Int>, wrong: Map<String, Int>): List<AttentionItem> =
        systemsWithGames.entries.sortedBy { it.value.lowercase() }.flatMap { (id, name) ->
            listOfNotNull(
                missing[id]?.takeIf { it > 0 }?.let {
                    AttentionItem("bios-missing:$id", "$name needs a BIOS", "Some games won't start without it.", "Set up $name", "missing:$it", AttentionTarget.Bios(id))
                },
                wrong[id]?.takeIf { it > 0 }?.let {
                    AttentionItem("bios-wrong:$id", "$name BIOS doesn't match", "A file there isn't a known good copy.", "Set up $name", "wrong:$it", AttentionTarget.Bios(id))
                },
            )
        }

    /** One item for the games with no artwork: "34 games have no artwork". */
    fun artworkItem(count: Int): AttentionItem? = count.takeIf { it > 0 }?.let {
        AttentionItem(
            "artwork",
            if (it == 1) "1 game has no artwork" else "$it games have no artwork",
            "Find art for them from your scraper.",
            "Find artwork",
            "count:$it",
            AttentionTarget.Artwork,
        )
    }

    /** Whether a game shows no picture on its card: no box art, no screenshot, no fanart, no marquee. */
    fun lacksArt(entry: LibraryEntry): Boolean =
        entry.kind == LibraryEntryKind.CONSOLE_ROM && entry.artworkUri.isNullOrBlank()

}
