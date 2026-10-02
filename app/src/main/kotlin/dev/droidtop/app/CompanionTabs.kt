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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.display.SecondaryDisplayContent
import dev.droidtop.runtime.tasks.TaskManager

/**
 * The companion's tabs (docs/SPEC.md "The companion's tabs", Droidtop/tracker#247): Home (the widgets
 * and info surface, [CompanionSurface]), Tasks, Performance, System and, in Desktop mode or when the
 * user chose it as this mode's second-screen role, the keyboard and trackpad Input surface (section 6c).
 * Touch only: every host denies focus to this whole tree (`focusProperties { canFocus = false }`), so
 * nothing here ever takes a controller or a key from the shell on the other screen (#186, #265).
 */
internal enum class CompanionTab(val label: String) {
    HOME("Home"),
    TASKS("Tasks"),
    PERFORMANCE("Performance"),
    SYSTEM("System"),
    INPUT("Input"),
}

/** The tabs a mode offers, in strip order. Input is there where the user can want it: Desktop, or a mode set to the input role. */
internal fun companionTabs(mode: SecondaryDisplayContent.Mode, role: SecondScreenInputPrefs.Role): List<CompanionTab> =
    buildList {
        add(CompanionTab.HOME)
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
    val tabs = companionTabs(mode, role)
    var selected by remember(mode, role) { mutableStateOf(defaultCompanionTab(role)) }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab -> CompanionPill(tab.label, selected = tab == selected) { selected = tab } }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selected) {
                CompanionTab.HOME -> home()
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
    val snapshot by TaskManager.snapshot.collectAsState()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        CompanionTasks()
        if (snapshot?.apps.isNullOrEmpty()) {
            Text(
                "No other apps are running.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
