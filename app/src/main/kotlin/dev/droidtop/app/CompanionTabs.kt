package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.droidtop.display.SecondaryDisplayContent
import dev.droidtop.library.settings.Keyboards
import dev.droidtop.library.settings.SocialBadge
import dev.droidtop.library.settings.UiModePrefs
import dev.droidtop.library.social.SocialHub
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.shell.gamepad.DroidtopKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The companion's tabs (docs/SPEC.md "The companion's tabs", Droidtop/tracker#247): Home (the widgets
 * and info surface, [CompanionSurface]), Social (every provider's friends and conversations,
 * [CompanionSocialTab], Droidtop/tracker#327; not in Kiosk and Kid), Tasks, Performance, System and,
 * in Desktop mode or when the user chose it as this mode's second-screen role, the keyboard and
 * trackpad Input surface (section 6c).
 * Touch only: every host denies focus to this whole tree (`focusProperties { canFocus = false }`), so
 * nothing here ever takes a controller or a key from the shell on the other screen (#186, #265).
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
    var keys by remember { mutableStateOf(false) }
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
                CompanionPill(label, selected = tab == selected) { selected = tab }
            }
            // A keyboard over any tab, typing into whatever has focus on the other screen (tracker#314). The Input
            // tab already is one.
            if (selected != CompanionTab.INPUT) CompanionPill("Keys", selected = keys) { keys = !keys }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selected) {
                CompanionTab.HOME -> home()
                CompanionTab.SOCIAL -> CompanionSocialTab()
                CompanionTab.TASKS -> CompanionTasksTab()
                CompanionTab.PERFORMANCE -> CompanionPerformanceTab()
                CompanionTab.SYSTEM -> CompanionSystemTab()
                CompanionTab.INPUT -> SecondScreenInputSurface(mode)
            }
        }
        if (keys && selected != CompanionTab.INPUT) CompanionKeys()
    }
}

/**
 * The companion's Keys panel (docs/SPEC.md 4c, "Typing on the add-on display", Droidtop/tracker#314): droidtop's one
 * keyboard typing into the focused field on the other screen, through droidtop's input method when it is the selected
 * one, else through the elevated helper's `input` command. With neither, a row offers the one switch that makes it
 * work. The companion stays touch-only: typing here never moves focus off the other screen.
 */
@Composable
private fun CompanionKeys() {
    val context = LocalContext.current
    val view = LocalView.current
    // Both reads ask the system (the input-method list, the helper's binder): off the main thread.
    val access by produceState(initialValue = KeysAccess(imeActive = true, elevated = false)) {
        value = withContext(Dispatchers.IO) {
            KeysAccess(Keyboards.ownKeyboardActive(context), runCatching { TaskManager.shell.capabilities().shellCommand }.getOrDefault(false))
        }
    }
    var noRoute by remember { mutableStateOf(false) }
    val currentAccess by rememberUpdatedState(access)
    val sink = remember(view) {
        RoutedKeyboardSink(
            displayId = { otherDisplay(context, view.display?.displayId) },
            elevated = { currentAccess.elevated },
            onNoRoute = { noRoute = true },
            onRouted = { noRoute = false },
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (noRoute || (!access.imeActive && !access.elevated)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompanionNote(if (access.imeActive || access.elevated) "No text field" else "Typing")
                if (!access.imeActive) CompanionPill("Use droidtop keyboard") { Keyboards.showPicker(context) }
            }
        }
        DroidtopKeyboard(sink, suppressImeView = true)
    }
}

private data class KeysAccess(val imeActive: Boolean, val elevated: Boolean)

/** The screen the companion types into: the shell's when it is elsewhere, else the first other display. */
private fun otherDisplay(context: android.content.Context, own: Int?): Int? {
    val shell = ForegroundShell.current()?.window?.decorView?.display?.displayId
    if (shell != null && shell != own) return shell
    return TaskManager.displayIds(context).firstOrNull { it != own }
}

/** The task manager's own row (reused from the first slice, not rebuilt) in a scrolling page, with a line for nothing running. */
@Composable
private fun CompanionTasksTab() {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.primary else colors.surfaceVariant)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimary else colors.onSurface)
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
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
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
