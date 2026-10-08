package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import dev.droidtop.display.secondScreenScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import dev.droidtop.runtime.keyboard.AddonKeyboard
import dev.droidtop.shell.gamepad.KeyboardTargets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.droidtop.display.SecondaryDisplayContent
import dev.droidtop.library.settings.SocialBadge
import dev.droidtop.library.settings.UiModePrefs
import dev.droidtop.library.social.SocialHub

/**
 * The companion's tabs (docs/SPEC.md "The companion's tabs", Droidtop/tracker#247): Home (the widgets
 * and info surface, [CompanionSurface]), Social (every provider's friends and conversations,
 * [CompanionSocialTab], Droidtop/tracker#327; not in Kiosk and Kid), Tasks, Performance, System and,
 * in Desktop mode or when the user chose it as this mode's second-screen role, the keyboard and
 * trackpad Input surface (section 6c).
 * Touch first: a key reaches the companion only while no shell is in front to take it (TouchOnlySurfaceFocus,
 * #186, #265), and then the D-pad moves between the companion's controls ([CompanionTile]). The
 * second-screen host still denies focus to its whole tree.
 */
internal enum class CompanionTab(val label: String) {
    HOME("Home"),
    SOCIAL("Social"),
    TASKS("Tasks"),
    PERFORMANCE("Performance"),
    SYSTEM("System"),
    INPUT("Input"),
}

/**
 * The tabs a mode offers, in strip order. Input is there where the user can want it: Desktop, or a mode set to the input
 * role. Social is left out where [social] is false: Kiosk and Kid, which hide the Social place too.
 */
internal fun companionTabs(mode: SecondaryDisplayContent.Mode, role: SecondScreenInputPrefs.Role, social: Boolean = true): List<CompanionTab> =
    buildList {
        add(CompanionTab.HOME)
        if (social) add(CompanionTab.SOCIAL)
        add(CompanionTab.TASKS)
        add(CompanionTab.PERFORMANCE)
        add(CompanionTab.SYSTEM)
        if (mode == SecondaryDisplayContent.Mode.DESKTOP || role == SecondScreenInputPrefs.Role.INPUT) add(CompanionTab.INPUT)
    }

/** Desktop mode keeps the input surface as its default (section 6c); everything else opens on Home. */
internal fun defaultCompanionTab(role: SecondScreenInputPrefs.Role): CompanionTab =
    if (role == SecondScreenInputPrefs.Role.INPUT) CompanionTab.INPUT else CompanionTab.HOME

/**
 * The one entry every companion host draws: a tab strip over the selected tab. [home] is that host's own
 * Home content (the activity's carries the widget add/remove controls, the registry's does not). Only the
 * selected tab is composed, so a tab that is not showing runs nothing and polls nothing.
 */
@Composable
internal fun CompanionTabs(mode: SecondaryDisplayContent.Mode, home: @Composable () -> Unit) {
    val context = LocalContext.current
    val role = SecondScreenInputPrefs.role(context, mode)
    val social = remember { !UiModePrefs.get(context).hidesSettings }
    val tabs = companionTabs(mode, role, social)
    var selected by remember(mode, role) { mutableStateOf(defaultCompanionTab(role)) }
    // The tab to go back to when the input controller was opened for a field or by the Keys button.
    var before by remember { mutableStateOf<CompanionTab?>(null) }
    // A conversation Home's Social section opened; the Social tab starts there.
    var socialStart by remember { mutableStateOf<OpenConversation?>(null) }
    // Keyed like [selected], whose state it writes.
    val nav = remember(mode, role) {
        CompanionNav(
            openTab = { tab -> socialStart = null; selected = tab },
            openConversation = { conversation -> socialStart = conversation; selected = CompanionTab.SOCIAL },
        )
    }
    val keysButton by AddonKeyboard.companionKeysButton.collectAsState()
    // While started, this companion is where droidtop's keyboard for a field on the other screen opens (SPEC 4c).
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, view) {
        val token = Any()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> KeyboardTargets.companionShown(token) { view.display?.displayId }
                Lifecycle.Event.ON_STOP -> KeyboardTargets.companionShown(token, null)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            KeyboardTargets.companionShown(token, null)
        }
    }
    // A field on the other screen wants keys: switch to the input controller, and back when it is done.
    val requested by KeyboardTargets.companion.collectAsState()
    LaunchedEffect(requested != null) {
        if (requested != null && selected != CompanionTab.INPUT) {
            before = selected
            selected = CompanionTab.INPUT
        } else if (requested == null && before != null) {
            selected = before ?: selected
            before = null
        }
    }
    // The Social tab's unread count over every provider, read when something changes, never polled.
    val unread by produceState(initialValue = SocialBadge.unread, social) {
        if (social) SocialHub.changes().collect { value = SocialHub.unread() }
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val label = if (tab == CompanionTab.SOCIAL && unread > 0) "${tab.label} $unread" else tab.label
                CompanionPill(label, selected = tab == selected) { socialStart = null; selected = tab }
            }
            // A keyboard over any tab, typing into whatever has focus on the other screen (tracker#314). The Input
            // tab already is one.
            if (requested != null) {
                CompanionPill("Hide", selected = true) { KeyboardTargets.hideCompanion() }
            } else if (keysButton && CompanionTab.INPUT !in tabs) {
                // A shortcut to the input controller where it is not a tab of its own (tracker#314).
                CompanionPill("Keys", selected = selected == CompanionTab.INPUT) {
                    if (selected == CompanionTab.INPUT) {
                        selected = before ?: defaultCompanionTab(role)
                        before = null
                    } else {
                        before = selected
                        selected = CompanionTab.INPUT
                    }
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selected) {
                CompanionTab.HOME -> CompositionLocalProvider(LocalCompanionNav provides nav) { home() }
                CompanionTab.SOCIAL -> CompanionSocialTab(socialStart)
                CompanionTab.TASKS -> CompanionTasksTab()
                CompanionTab.PERFORMANCE -> CompanionPerformanceTab()
                CompanionTab.SYSTEM -> CompanionSystemTab()
                CompanionTab.INPUT -> SecondScreenInputSurface(mode)
            }
        }
    }
}

/** The task manager's own row (reused from the first slice, not rebuilt) in a scrolling page, with a line for nothing running. */
@Composable
private fun CompanionTasksTab() {
    Column(modifier = Modifier.fillMaxSize().secondScreenScroll(rememberScrollState()).padding(16.dp)) {
        CompanionTasks()
    }
}

/**
 * The tabs' shared building blocks, so Performance and System look and lay out as one family: a pill (the
 * tab strip, the timeout choices, the small actions), a titled card, a muted note line, and a page that is one column in
 * portrait and two on a wide screen, scrolling either way.
 */
@Composable
internal fun CompanionPill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    CompanionTile(onClick = onClick, shape = RoundedCornerShape(50)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .background(if (selected) colors.primary else colors.surfaceVariant)
                .heightIn(min = 40.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimary else colors.onSurface)
        }
    }
}

@Composable
internal fun CompanionNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun CompanionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
internal fun CompanionPanels(panels: List<@Composable () -> Unit>) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 720.dp) 2 else 1
        Row(
            modifier = Modifier.fillMaxWidth().secondScreenScroll(rememberScrollState()).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(columns) { column ->
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    panels.forEachIndexed { index, panel -> if (index % columns == column) panel() }
                }
            }
        }
    }
}
