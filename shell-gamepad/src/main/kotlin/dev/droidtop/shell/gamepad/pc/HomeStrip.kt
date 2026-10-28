package dev.droidtop.shell.gamepad.pc

import dev.droidtop.shell.gamepad.padRow
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.BiosDatabase
import dev.droidtop.library.social.SocialHub
import dev.droidtop.library.social.SocialOrder
import dev.droidtop.library.social.SocialPresence
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.shell.gamepad.Corners
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.STATUS_INSET
import dev.droidtop.shell.gamepad.StatusPill
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.ViewStrip
import dev.droidtop.shell.gamepad.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Home's top band and strip (docs/SPEC.md 7i, "Home"; Droidtop/tracker#485 F7a, final design B.2). The band
 * is touch only (in the accessibility tree, never a stop of the pad): Downloads while a job runs, Search, the
 * bell, Friends, then the status facts on a plate. The strip is What's new, Friends and Downloads, stepped
 * with L1/R1; its pills are not a stop, the cards are.
 */

/** What Home's strip and band show, built off the main thread from what the shell holds. */
internal data class HomeStripData(
    val tabs: List<HomeTab> = listOf(HomeTab(HomeTabKind.WHATS_NEW, emptyList())),
    val attention: List<AttentionItem> = emptyList(),
    val friendsOn: Boolean = false,
    /** The person's own presence on the first service that reports one, for the avatar's ring. */
    val presence: SocialPresence? = null,
    val unreadMessages: Int = 0,
)

/**
 * Builds Home's strip from the library, the jobs and the friends, again whenever one of them changes. The
 * library work is one pass over the entries (linear in the library); the one disk read is the BIOS check,
 * made once per change of the set of systems that have games, on the IO dispatcher.
 */
@Composable
internal fun rememberHomeStrip(all: List<LibraryEntry>, systemNames: Map<String, String>, keep: HomeTabKind?): HomeStripData {
    val context = LocalContext.current
    val jobs by remember { PluginJobsCenter.entries() }.collectAsState()
    var friends by remember { mutableStateOf(Triple(false, emptyList<HomeCard.Friend>(), null as SocialPresence?)) }
    LaunchedEffect(Unit) {
        // The friends list is re-read when a service says it changed, never polled.
        SocialHub.changes().collectLatest {
            friends = withContext(Dispatchers.IO) {
                runCatching {
                    val online = SocialHub.online(context)
                    val contacts = SocialHub.contacts(context).map { c -> Triple(c.key, c.friend, SocialOrder.value(c, online.size > 1)) }
                    Triple(online.isNotEmpty(), HomeModel.friends(contacts), online.firstNotNullOfOrNull { it.presence(context) })
                }.getOrDefault(Triple(false, emptyList(), null))
            }
        }
    }
    // The systems that have games, and the BIOS each is missing or has wrong: a disk read, once per set of systems.
    val systemsWithGames = remember(all, systemNames) {
        all.asSequence().filter { it.kind == LibraryEntryKind.CONSOLE_ROM && !it.hidden }.mapNotNull { it.systemId }.distinct()
            .associateWith { systemNames[it] ?: it.uppercase() }
    }
    var bios by remember { mutableStateOf<List<AttentionItem>>(emptyList()) }
    LaunchedEffect(systemsWithGames.keys) {
        bios = withContext(Dispatchers.IO) { runCatching { biosAttention(context, systemsWithGames) }.getOrDefault(emptyList()) }
    }
    // Installed plugins with a newer release in a catalog as last fetched (the Updates page's own count): read once off
    // the main thread. It was passed as 0, so the card never showed (Droidtop/tracker#570).
    var pluginUpdates by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        pluginUpdates = withContext(Dispatchers.IO) {
            runCatching {
                dev.droidtop.library.integrations.PluginCatalog.offersFor(
                    dev.droidtop.pluginhost.PluginStore.installed(context),
                    dev.droidtop.library.integrations.PluginCatalog.listings(context),
                    dev.droidtop.pluginhost.UserOriginKeys.load(dev.droidtop.pluginhost.UserOriginKeys.storeFile(context)),
                ).size
            }.getOrDefault(0)
        }
    }
    var built by remember { mutableStateOf(HomeStripData()) }
    LaunchedEffect(all, systemNames, jobs, friends, bios, keep, pluginUpdates) {
        built = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val artwork = HomeModel.artworkItem(all.count { !it.hidden && HomeModel.lacksArt(it) })
            val attention = bios + listOfNotNull(artwork)
            val added = HomeModel.added(
                all, now,
                placeOf = { e -> e.systemId?.let { systemNames[it] ?: it.uppercase() } ?: if (e.kind == LibraryEntryKind.NATIVE_ANDROID_APP) "Apps" else "PC Games" },
                systemOf = { it.systemId },
            )
            val whatsNew = HomeModel.whatsNew(HomeModel.updates(all), added, pluginUpdates = pluginUpdates, attention = attention.size)
            val downloads = jobs.filter { !it.done && PluginJobsCenter.isDownloadKind(it.nativeKind) }.map { job ->
                HomeCard.Download(
                    job.jobId, job.title, job.percent,
                    said = dev.droidtop.library.integrations.PluginJobsScreen.oneLine(job),
                    look = dev.droidtop.library.integrations.PluginJobsScreen.lookOf(job),
                )
            }
            HomeStripData(
                tabs = HomeModel.tabs(whatsNew, friends.first, friends.second, downloads, keep),
                attention = attention,
                friendsOn = friends.first,
                presence = friends.third,
                unreadMessages = SocialHub.unread(),
            )
        }
    }
    return built
}

/** The BIOS items for the systems that have games: every game folder's bios files checked against the registry. */
private fun biosAttention(context: Context, systems: Map<String, String>): List<AttentionItem> {
    if (systems.isEmpty()) return emptyList()
    val roots = dev.droidtop.library.GamesRoots.current(context)
    val missing = HashMap<String, Int>()
    val wrong = HashMap<String, Int>()
    for (systemId in systems.keys) {
        val spec = BiosDatabase.forSystem(context, systemId) ?: continue
        if (spec.files.isEmpty()) continue
        // A file counts as there when any game folder has it; wrong only when every copy found is wrong.
        val byFile = roots.flatMap { root -> BiosDatabase.check(root, spec) }.groupBy { it.spec.file }
        missing[systemId] = byFile.count { (_, statuses) -> statuses.none { it.present } }
        wrong[systemId] = byFile.count { (_, statuses) -> statuses.any { it.present } && statuses.filter { it.present }.all { it.md5Ok == false } }
    }
    return HomeModel.biosItems(systems, missing, wrong)
}

/**
 * The top band (Home only): a scrim from the ground at 60 percent to clear, then, right to left, only what carries
 * state (the owner, 2026-10-29, Droidtop/tracker#742: "2 is my choice. Have a contextual spot that shows an icon for
 * whatever's currently happening"): the status pill on a solid plate, with the activity spot ([ActivitySpot]) on it, a
 * divider, Friends while a service reports the person's presence (the avatar in a ring), and the bell while something
 * is unread, with the count. Search is not here: Y is Search everywhere, and the icon said nothing. 48dp targets with
 * content descriptions; not a stop of the pad.
 */
@Composable
internal fun HomeTopBand(
    data: HomeStripData,
    onActivity: () -> Unit,
    onBell: () -> Unit,
    bellCount: Int,
    onFriends: () -> Unit,
    onQuickMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = LocalShellWindow.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = HOME_BAND_HEIGHT)
            .focusProperties { canFocus = false }
            .background(Brush.verticalGradient(0f to MenuTokens.Ground.copy(alpha = 0.6f), 1f to Color.Transparent)),
    ) {
        // The person (Droidtop/tracker#586): their picture or initial and their name, from Quick Menu > Profile; a tap
        // opens the Quick Menu. Nothing while droidtop knows neither.
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.droidtop.library.profile.ProfileStore.refresh(context) }
        }
        val profile by dev.droidtop.library.profile.ProfileStore.current.collectAsState()
        if (profile.name != null || profile.picture != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = window.edgePadding)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = profile.name ?: "Profile" }
                    .padRow(onClick = onQuickMenu),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.Sm),
            ) {
                val picture = profile.picture
                if (picture != null) {
                    coil3.compose.AsyncImage(
                        // The file's time in the key, so a new picture under the same name is read again.
                        model = coil3.request.ImageRequest.Builder(context).data(picture).memoryCacheKey("profile:${picture.lastModified()}").build(),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                    )
                } else {
                    Box(Modifier.size(32.dp).background(MenuTokens.Selected, CircleShape), contentAlignment = Alignment.Center) {
                        Text(profile.name.orEmpty().take(1).uppercase(), color = MenuTokens.OnSelected, style = TypeRole.rowTitle)
                    }
                }
                profile.name?.let { Text(it, color = MenuTokens.OnSurface, style = TypeRole.rowTitle, maxLines = 1) }
            }
        }
        Row(
            // The same inset from the top edge as the status pill floating over every other page, so the pill keeps
            // its rounded top in portrait (Droidtop/tracker#675).
            modifier = Modifier.align(Alignment.CenterEnd).padding(horizontal = window.edgePadding, vertical = STATUS_INSET),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Xs),
        ) {
            if (bellCount > 0) {
                BandButton(Icons.Outlined.Notifications, "Notifications, $bellCount new", onBell) {
                    Text(
                        bellCount.coerceAtMost(99).toString(),
                        color = MenuTokens.OnSelected,
                        style = TypeRole.supporting,
                        modifier = Modifier.align(Alignment.TopEnd).background(MenuTokens.Selected, CircleShape).padding(horizontal = 4.dp),
                    )
                }
            }
            val presence = data.presence?.takeIf { data.friendsOn }
            if (presence != null) {
                BandButton(Icons.Outlined.AccountCircle, "Friends", onFriends) {
                    val ring = if (presence == SocialPresence.OFFLINE) MenuTokens.OnSurfaceMuted else MenuTokens.Affirmative
                    Box(Modifier.size(34.dp).border(2.dp, ring, CircleShape))
                }
            }
            Box(Modifier.padding(horizontal = Space.Xs).width(1.dp).height(24.dp).background(MenuTokens.CardOutline))
            // The facts, on the one status pill, here on a solid plate in full ink.
            StatusPill(onClick = onQuickMenu, plate = MenuTokens.Surface, onActivity = onActivity)
        }
    }
}

@Composable
private fun BandButton(icon: ImageVector, label: String, onClick: () -> Unit, overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .padRow(onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = MenuTokens.OnSurface, modifier = Modifier.size(24.dp))
        overlay()
    }
}

/**
 * The strip: the tab pills (L1/R1 step them; not a stop) and the active tab's cards. [selected] is the card
 * under the cursor, null when the cursor is elsewhere.
 */
@Composable
internal fun HomeStripRow(
    tabs: List<HomeTab>,
    active: Int,
    selected: Int?,
    state: LazyListState,
    onTab: (Int) -> Unit,
    onTapCard: (Int) -> Unit,
) {
    val window = LocalShellWindow.current
    val tab = tabs.getOrNull(active) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Space.Sm)) {
        ViewStrip(
            labels = tabs.map { it.label },
            active = active,
            focused = null,
            pill = null,
            onSelect = onTab,
            onClearPill = {},
        )
        if (tab.cards.isEmpty()) {
            Text(
                tab.empty,
                color = MenuTokens.OnSurfaceMuted,
                style = TypeRole.body,
                modifier = Modifier
                    .padding(horizontal = window.edgePadding)
                    .heightIn(min = HOME_CARD_HEIGHT)
                    .focusRing(selected != null, Corners.Tile),
            )
        } else {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = window.edgePadding),
                horizontalArrangement = Arrangement.spacedBy(Space.Md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(tab.cards, key = { _, card -> card.id }) { index, card ->
                    HomeCardView(card, selected == index, onClick = { onTapCard(index) })
                }
            }
        }
    }
}

@Composable
private fun HomeCardView(card: HomeCard, selected: Boolean, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
        modifier = Modifier
            .width(HOME_CARD_WIDTH)
            .heightIn(min = HOME_CARD_HEIGHT)
            .focusRing(selected, Corners.Tile)
            .background(if (selected) MenuTokens.CardFocused else MenuTokens.Card, Corners.Tile)
            .padRow(onClick = onClick)
            .padding(Space.Md),
    ) {
        Text(card.title, color = MenuTokens.OnSurface, style = TypeRole.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f, fill = false))
        Text(card.line, color = MenuTokens.OnSurfaceMuted, style = TypeRole.supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
        (card as? HomeCard.Download)?.let {
            dev.droidtop.shell.gamepad.ShellProgressBar(if (it.percent >= 0) it.percent / 100f else -1f, Modifier.fillMaxWidth(), it.look)
        }
    }
}

internal val HOME_BAND_HEIGHT: Dp = 48.dp
internal val HOME_CARD_WIDTH: Dp = 220.dp
internal val HOME_CARD_HEIGHT: Dp = 110.dp
