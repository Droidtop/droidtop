package dev.droidtop.shell.gamepad.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import dev.droidtop.library.settings.words
import dev.droidtop.shell.gamepad.R

/**
 * What a screen reader says for a themed list's entry under the cursor (Droidtop/tracker#728): its name, whether it is
 * a favourite, the system it comes from in a collection, and where it is in the list, as one sentence. The ES-DE
 * views drew every entry as pictures and text with no meaning attached, so TalkBack, which SPEC 7k says works through
 * Gaming's menus, had nothing to read on the main game lists. Empty for an empty list. Pure.
 */
internal fun esDeSpokenEntry(items: List<EsDeListItem>, index: Int): String {
    val item = items.getOrNull(index) ?: return ""
    return listOfNotNull(
        item.label,
        words(R.string.gamepad_spoken_favourite).takeIf { item.favorite },
        item.sourceSystemName,
        words(R.string.gamepad_spoken_position, index + 1, items.size),
    ).filter { it.isNotBlank() }.joinToString(", ")
}

/**
 * The list widget's one node for a screen reader: [esDeSpokenEntry] for the entry at [index], said again whenever the
 * cursor moves (a polite live region), in place of the pictures and text it is drawn with; a screen reader's double
 * tap opens it, as A does. The widget keeps its focus and its keys; only what is read changes.
 */
internal fun Modifier.esDeSpokenList(items: List<EsDeListItem>, index: Int): Modifier {
    val spoken = esDeSpokenEntry(items, index)
    return clearAndSetSemantics {
        contentDescription = spoken
        liveRegion = LiveRegionMode.Polite
        items.getOrNull(index)?.let { item ->
            onClick {
                item.onSelect()
                true
            }
        }
    }
}
