package dev.droidtop.app

import androidx.compose.ui.res.stringResource
import dev.droidtop.shell.gamepad.labelCase
import dev.droidtop.library.format.ValueFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.droidtop.display.secondScreenScroll
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.PlatformsDatabase
import dev.droidtop.library.consoles.resolvePlayer
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.displayName
import dev.droidtop.library.settings.CompanionHomeLayout
import dev.droidtop.library.settings.CompanionHomePrefs
import dev.droidtop.library.settings.CompanionHomeSection
import dev.droidtop.library.settings.UiModePrefs
import dev.droidtop.library.social.SocialContact
import dev.droidtop.library.social.SocialHub
import dev.droidtop.library.social.SocialOrder
import dev.droidtop.library.social.SocialState
import dev.droidtop.pluginhost.PluginJobsCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.droidtop.ui.shared.tabularFigures

/**
 * The companion Home's sections and the pieces they share (docs/SPEC.md "The companion's tabs",
 * Droidtop/tracker#328): the second screen as the dashboard for whatever the main screen is doing. Every
 * section reads a store something else already keeps (the running launch, the focused game, the library feed,
 * the jobs center, the social hub, the system status) and runs no scan or query of its own; [CompanionSurface]
 * puts them in order on one scrolling page.
 */

/** Lets a Home section open another companion tab: Social at a conversation, System for the device controls. */
internal class CompanionNav(val openTab: (CompanionTab) -> Unit, val openConversation: (OpenConversation) -> Unit)

internal val LocalCompanionNav = staticCompositionLocalOf<CompanionNav?> { null }

/**
 * The companion's one touch target: clipped to [shape] with a ripple. The companion is touch only and never
 * receives pad keys, so there is no focus ring. Pills, cards, rows and section headings are all this.
 */
@Composable
internal fun CompanionTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        content = content,
    )
}

/**
 * A section's heading: the label in small tracked capitals, a one-line [summary] beside it, and the fold
 * mark. The whole row is the fold control (48dp tall).
 */
@Composable
internal fun CompanionSectionHeader(label: String, open: Boolean, summary: String? = null, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    CompanionTile(onClick = onToggle, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        ) {
            Text(
                label.labelCase(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
                color = if (open) colors.onBackground else colors.onSurfaceVariant,
            )
            if (summary != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(if (open) "−" else "+", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** One Home section: its heading (tap to fold, the fold remembered) over its content while open. */
@Composable
internal fun CompanionHomeSectionFrame(
    section: CompanionHomeSection,
    layout: CompanionHomeLayout,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val open = layout.isOpen(section)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        CompanionSectionHeader(section.label, open, summary.takeIf { !open }) {
            CompanionHomePrefs.setOpen(context, section, !open)
        }
        if (open) content()
    }
}

/** Small tracked capitals in the accent: the line over a card's title. */
@Composable
private fun Eyebrow(text: String) {
    Text(
        text.labelCase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A game's capsule art at [width] (2:3), or its title in the same frame when it has no art. */
@Composable
internal fun CompanionCapsuleArt(entry: LibraryEntry, width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(width * 1.5f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val art = entry.artworkUri ?: entry.heroUri
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art,
                contentDescription = entry.title,
                modifier = Modifier.width(width).height(width * 1.5f),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp).align(Alignment.Center),
            )
        }
    }
}

/**
 * Now: the game running on the other screen (art, name, this session's time, Resume, Quit and the device
 * controls), else the game focused in the Gaming shell (its facts and Play). Nothing to show, no section.
 */
@Composable
internal fun CompanionNowSection(focused: LibraryEntry?, layout: CompanionHomeLayout) {
    val running by LaunchDisplay.running.collectAsState()
    val entries by CompanionState.libraryEntries.collectAsState()
    val runningId = running?.context?.gameId
    val runningEntry = remember(runningId, entries) { runningId?.let { id -> entries.firstOrNull { it.id == id } } }
    // A game that ended unseen ends the session in SessionMonitor, which `running` already follows.
    val session = running
    val entry = runningEntry ?: focused ?: return
    CompanionHomeSectionFrame(CompanionHomeSection.NOW, layout, summary = entry.title) {
        if (runningEntry != null && session != null) {
            NowRunningCard(runningEntry, session.sinceEpochMs)
        } else {
            FocusedGameCard(entry)
        }
    }
}



@Composable
private fun NowCardFrame(entry: LibraryEntry, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CompanionCapsuleArt(entry, NOW_ART_WIDTH)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

private val NOW_ART_WIDTH = 88.dp

@Composable
internal fun NowRunningCard(entry: LibraryEntry, sinceEpochMs: Long) {
    val nav = LocalCompanionNav.current
    val context = LocalContext.current
    val stopStatus by CompanionState.stopStatus.collectAsState()
    val played by produceState(sessionLabel(sinceEpochMs, System.currentTimeMillis()), sinceEpochMs) {
        while (true) {
            value = sessionLabel(sinceEpochMs, System.currentTimeMillis())
            delay(30_000)
        }
    }
    NowCardFrame(entry) {
        Eyebrow("Now playing · $played")
        Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(systemName(entry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PillRow {
            // Resume is a relaunch of the entry, the Quick Menu's own resume (the one launch path).
            CompanionPill("Resume", selected = true) { CompanionState.onLaunchEntry?.invoke(entry) }
            CompanionPill("Quit") { CompanionState.quit(context, entry) }
            if (nav != null) CompanionPill("Controls") { nav.openTab(CompanionTab.SYSTEM) }
        }
        // What Quit came to, under the button that was pressed (Droidtop/tracker#412).
        stopStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
    }
}

/**
 * "Just started", "12 min", "1 h 05 min": how long the running game's session has lasted, from the moment
 * droidtop launched it. Pure.
 */
internal fun sessionLabel(sinceEpochMs: Long, nowEpochMs: Long): String {
    val minutes = ((nowEpochMs - sinceEpochMs).coerceAtLeast(0L) / 60_000L)
    return when {
        minutes < 1 -> "Just started"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h %02d min".format(minutes % 60)
    }
}

/** Real system name (Nintendo 64, PlayStation 2) for a console ROM; the shared kind grouping name otherwise. */
private fun systemName(entry: LibraryEntry): String =
    entry.systemId?.let { id -> PlatformsDatabase.displayNameOrNull(id) } ?: entry.kind.displayName()

/** The game focused on the other screen: what the shell cannot fit beside its list, and Play. */
@Composable
private fun FocusedGameCard(entry: LibraryEntry) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // Which player will run a console ROM: the same resolution Library.launch uses, read off the main thread
    // (it asks the package manager) and never re-implemented here.
    val player by produceState<String?>(null, entry.systemId, entry.altEmulator) {
        value = entry.systemId?.let { systemId ->
            withContext(Dispatchers.IO) {
                runCatching {
                    PlatformsDatabase.builtInsOrEmpty().firstOrNull { it.id == systemId }
                        ?.let { system -> resolvePlayer(context, system, entry.altEmulator)?.name }
                }.getOrNull()
            }
        }
    }
    NowCardFrame(entry) {
        Eyebrow(systemName(entry))
        Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // The publisher is named only when it is not the developer, which for most PC and engine games it is.
        val details = listOfNotNull(
            entry.developer,
            entry.publisher?.takeIf { it != entry.developer },
            entry.releaseDate?.take(4),
            entry.genre,
        ).joinToString("  ·  ")
        if (details.isNotEmpty()) FactLine(details)
        val play = listOfNotNull(
            entry.playtimeSeconds.takeIf { it >= 60 }?.let { "${ValueFormat.duration(it)} played" },
            entry.lastPlayedEpochMs?.let { lastPlayed ->
                "Last played ${ValueFormat.daysAgo(System.currentTimeMillis(), lastPlayed)}"
            },
            player?.let { "Runs with $it" },
        ).joinToString("  ·  ")
        if (play.isNotEmpty()) FactLine(play)
        // PC entries: the store, install state and size; compatibility is reference counts, never a verdict
        // (docs/SPEC.md section 7g).
        entry.pcInfo?.let { pc ->
            FactLine(
                buildList {
                    dev.droidtop.library.PcSource.of(entry)?.let { add(it.detail()) }
                    if (!pc.installed) add("Not installed")
                    if (pc.sizeBytes > 0) add(ValueFormat.size(pc.sizeBytes))
                    pc.compatibility?.let { add(it.summary()) }
                }.joinToString("  ·  "),
            )
        }
        // The existing update tracking (docs/SPEC.md 7g), never a second check.
        entry.availableUpdate?.let { latest ->
            // Opens the one Updates list, as the companion's other update line does (Droidtop/tracker#792).
            Text(
                stringResource(R.string.companion_home_update_available, latest),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.tertiary,
                modifier = Modifier.clickable { openUpdates(context) },
            )
        }
        entry.description?.let { description ->
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        PillRow {
            CompanionPill("Play", selected = true) { CompanionState.onLaunchEntry?.invoke(entry) }
        }
    }
}

@Composable
private fun FactLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun PillRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * Downloads and updates: the jobs running now (the one jobs center, the System tab's source) with their
 * progress, and how many games have an update waiting (the library's own update tracking). Neither, no section.
 */
@Composable
internal fun CompanionActivitySection(layout: CompanionHomeLayout) {
    val context = LocalContext.current
    val jobs by PluginJobsCenter.entries().collectAsState()
    val active = remember(jobs) { jobs.filter { !it.done } }
    // Everything the Updates page lists, not only games: the one count every entry to it reads (Droidtop/tracker#792).
    val updates by dev.droidtop.library.settings.UpdatesWaiting.count.collectAsState()
    LaunchedEffect(Unit) { dev.droidtop.library.settings.UpdatesWaiting.refresh?.invoke(context) }
    if (active.isEmpty() && updates == 0) return
    val summary = listOfNotNull(
        active.size.takeIf { it > 0 }?.let { "$it running" },
        updates.takeIf { it > 0 }?.let { if (it == 1) "1 update" else "$it updates" },
    ).joinToString("  ·  ")
    CompanionHomeSectionFrame(CompanionHomeSection.ACTIVITY, layout, summary = summary) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            active.take(MAX_JOB_ROWS).forEach { job ->
                // The bar says how far and the line the Downloads place's words ("About 4 min left"); a tap shows the
                // figures, as focus does on the pad (Droidtop/tracker#643: never a live percent on a touch surface).
                var figures by remember(job.jobId) { mutableStateOf(false) }
                Column(modifier = Modifier.fillMaxWidth().clickable { figures = !figures }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            PluginJobsScreen.oneLine(job),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    dev.droidtop.ui.shared.JobProgress(
                        job.percent.takeIf { it >= 0 }?.let { it / 100f } ?: -1f,
                        PluginJobsScreen.lookOf(job),
                        companionJobColors(),
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        height = 6.dp,
                    )
                    if (figures) {
                        PluginJobsScreen.detailLine(job, System.currentTimeMillis())?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall.tabularFigures(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            if (updates > 0) {
                Text(
                    "Updates · " + dev.droidtop.library.settings.UpdatesWaiting.line(updates),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Opens the one Updates list, where each has its Update and there is Update all (Droidtop/tracker#792).
                    modifier = Modifier.clickable { openUpdates(context) },
                )
            }
        }
    }
}

private const val MAX_JOB_ROWS = 4

/**
 * Social at a glance: unread conversations and friends who are in a game, from the same rows the Social tab
 * draws ([socialRows]). A tap opens that conversation on the Social tab. Not in Kiosk and Kid, which hide
 * Social; nothing to show, no section.
 */
@Composable
internal fun CompanionSocialSection(layout: CompanionHomeLayout) {
    val context = LocalContext.current
    val nav = LocalCompanionNav.current
    val social = remember { !UiModePrefs.get(context).hidesSettings }
    if (!social) return
    var rows by remember { mutableStateOf<SocialRows?>(null) }
    LaunchedEffect(Unit) { SocialHub.changes().collect { rows = withContext(Dispatchers.IO) { socialRows(context) } } }
    val current = rows ?: return
    val unread = current.conversations.filter { it.friend.unread > 0 }.take(MAX_SOCIAL_ROWS)
    val playing = current.friends.filter { it.friend.state == SocialState.IN_GAME }.take(MAX_SOCIAL_ROWS)
    if (unread.isEmpty() && playing.isEmpty()) return
    val summary = listOfNotNull(
        unread.sumOf { it.friend.unread }.takeIf { it > 0 }?.let { "$it new" },
        playing.size.takeIf { it > 0 }?.let { "$it playing" },
    ).joinToString("  ·  ")
    CompanionHomeSectionFrame(CompanionHomeSection.SOCIAL, layout, summary = summary) {
        Column(modifier = Modifier.fillMaxWidth()) {
            (unread + playing.filter { it !in unread }).forEach { contact ->
                SocialGlanceRow(contact, current.badged) { nav?.openConversation(openConversation(contact)) }
            }
        }
    }
}

private const val MAX_SOCIAL_ROWS = 3

@Composable
private fun SocialGlanceRow(contact: SocialContact, badged: Boolean, onClick: () -> Unit) {
    CompanionTile(onClick = onClick, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        ) {
            Text(contact.friend.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(
                SocialOrder.value(contact, badged),
                style = MaterialTheme.typography.bodySmall,
                color = if (contact.friend.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * System at a glance: storage, and the way to the System tab, where every device control is (the catalog's own
 * items, docs/SPEC.md "The companion's tabs"). The status line at the top of the page already says network and
 * battery.
 */
@Composable
internal fun CompanionSystemSection(layout: CompanionHomeLayout) {
    val nav = LocalCompanionNav.current
    CompanionHomeSectionFrame(CompanionHomeSection.SYSTEM, layout) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StorageLine()
            if (nav != null) PillRow { CompanionPill("All controls") { nav.openTab(CompanionTab.SYSTEM) } }
        }
    }
}

/** The one Updates list, from a companion line that says something has an update (Droidtop/tracker#792). */
private fun openUpdates(context: android.content.Context) {
    context.startActivity(
        PlaceLinks.intent(context, dev.droidtop.library.settings.Place.UPDATES).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
