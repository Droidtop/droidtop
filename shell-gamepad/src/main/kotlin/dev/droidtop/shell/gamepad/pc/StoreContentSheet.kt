package dev.droidtop.shell.gamepad.pc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.stores.StoreBranch
import dev.droidtop.library.stores.StoreContent
import dev.droidtop.library.stores.StoreContentChoice
import dev.droidtop.library.stores.StoreContentOptions
import dev.droidtop.library.stores.StoreExtra
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuHint
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.TextEditDialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "DLC and versions" (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313): the
 * owned DLC of a store game with what each one downloads, and the store's
 * branches (Steam's betas, a locked one asks its password). The choice is
 * remembered per game by the store; for an installed game, applying it also
 * starts the install job for the difference ([onApplied] with true), so the
 * files follow the choice.
 *
 * One list for pad and touch: A (or a tap) switches a DLC or picks a version,
 * the last row applies, B closes with nothing changed. Labels and values, no
 * sentences, except an error.
 */
@Composable
internal fun StoreContentSheet(
    entry: LibraryEntry,
    store: StoreLibrary,
    /** The choice was saved; true when the installed files now differ from it, so the caller starts the install job. */
    onApplied: (installNeeded: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gameId = (entry.pcInfo?.storeId ?: entry.id).substringAfter(':')
    // Bumped when a password was accepted, so the unlocked branch shows as open.
    var reload by remember { mutableIntStateOf(0) }
    val loaded by produceState<StoreContentOptions?>(null, entry.id, reload) {
        value = withContext(Dispatchers.IO) { runCatching { store.contentOptions(context, gameId) }.getOrNull() }
    }
    val options = loaded
    var choice by remember(options) { mutableStateOf(options?.let { StoreContent.current(it) }) }
    var selected by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<StoreBranch?>(null) }

    val rowCount = (options?.extras?.size ?: 0) + (options?.branches?.size ?: 0) + 1
    fun apply() {
        val picked = choice ?: return
        val shown = options ?: return
        if (!StoreContent.branchUsable(shown, picked)) {
            status = "Enter the password for that version first"
            return
        }
        if (!StoreContent.differs(shown, picked)) {
            onDismiss()
            return
        }
        working = true
        scope.launch {
            store.chooseContent(context, gameId, picked)
                .onSuccess { installNeeded ->
                    working = false
                    onApplied(installNeeded)
                }
                .onFailure {
                    working = false
                    status = userFacingErrorMessage(it)
                }
        }
    }

    fun press(index: Int) {
        val shown = options ?: return
        val picked = choice ?: return
        if (working) return
        selected = index
        status = null
        val extras = shown.extras.size
        when {
            index < extras -> choice = StoreContent.toggled(picked, shown.extras[index].id)
            index < extras + shown.branches.size -> {
                val branch = shown.branches[index - extras]
                if (branch.locked && !branch.unlocked) asking = branch else choice = picked.copy(branchId = branch.id)
            }
            else -> apply()
        }
    }

    BackHandler { onDismiss() }
    asking?.let { branch ->
        TextEditDialog(
            title = "Password for ${branch.title}",
            subtitle = null,
            initial = "",
            secret = true,
            onCommit = { typed ->
                asking = null
                working = true
                scope.launch {
                    store.unlockBranch(context, gameId, branch.id, typed)
                        .onSuccess {
                            working = false
                            choice = choice?.copy(branchId = branch.id)
                            reload++
                        }
                        .onFailure {
                            working = false
                            status = userFacingErrorMessage(it)
                        }
                }
            },
            onDismiss = { asking = null },
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(520.dp)),
            focusLabel = "Store content",
            onPad = { pad ->
                when (pad.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        val next = menuStep(selected, rowCount, if (pad.action == GamepadAction.UP) -1 else 1)
                        if (next != selected) {
                            selected = next
                            EsDeNavigationSounds.play("scroll")
                        }
                    }
                    GamepadAction.A -> press(selected)
                    GamepadAction.B -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                "DLC and versions",
                style = MaterialTheme.typography.titleMedium,
                color = MenuTokens.OnSurface,
            )
            Text(
                GameNaming.displayName(entry.title),
                style = MaterialTheme.typography.bodySmall,
                color = MenuTokens.OnSurfaceMuted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val shown = options
            val picked = choice
            if (shown == null || picked == null) {
                MenuRow(title = if (loaded == null && !working) "Reading" else "Nothing to choose")
            } else {
                if (shown.extras.isNotEmpty()) SectionLabel("DLC")
                shown.extras.forEachIndexed { index, extra ->
                    MenuRow(
                        title = extra.title,
                        value = extraValue(extra),
                        switchOn = extra.id in picked.extraIds,
                        selected = selected == index,
                        onClick = { press(index) },
                    )
                }
                if (shown.branches.size > 1) SectionLabel("Version")
                shown.branches.forEachIndexed { i, branch ->
                    val index = shown.extras.size + i
                    MenuRow(
                        title = branch.title,
                        subtitle = branch.build,
                        value = branchValue(branch, picked),
                        selected = selected == index,
                        onClick = { press(index) },
                    )
                }
                MenuRow(
                    title = if (shown.installed) "Apply" else "Save",
                    value = applyValue(shown, picked),
                    selected = selected == rowCount - 1,
                    onClick = { press(rowCount - 1) },
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MenuTokens.Value) }
            if (working) MenuHint("Working")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MenuTokens.OnSurfaceMuted, modifier = Modifier.padding(top = 6.dp))
}

/** A DLC's value: its size, and "On device" for one already installed. */
internal fun extraValue(extra: StoreExtra): String =
    listOf(StoreContent.sizeLabel(extra.bytes), "On device".takeIf { extra.installed }).filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ")

/** A branch's value: the one in force, a locked one, or nothing. */
internal fun branchValue(branch: StoreBranch, picked: StoreContentChoice): String = when {
    branch.id == picked.branchId -> "Selected"
    branch.locked && !branch.unlocked -> "Password"
    else -> ""
}

/** What Apply does, as a short value: the download it starts and the DLC it removes. */
internal fun applyValue(options: StoreContentOptions, picked: StoreContentChoice): String {
    if (!StoreContent.differs(options, picked)) return ""
    val parts = buildList {
        StoreContent.downloadBytes(options, picked).takeIf { it > 0 }?.let { add("+" + StoreContent.sizeLabel(it)) }
        StoreContent.removedExtras(options, picked).size.takeIf { it > 0 }?.let { add("-$it DLC") }
        if (picked.branchId != options.branches.firstOrNull { it.selected }?.id) add(picked.branchId.orEmpty())
    }
    return parts.joinToString(" · ")
}
