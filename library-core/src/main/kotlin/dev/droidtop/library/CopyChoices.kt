package dev.droidtop.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which copy of a game its button acts on (docs/SPEC.md 7i, "Which copy",
 * Droidtop/tracker#397 slice H). A card can stand for several copies (a
 * Steam row and a GOG row of one game, a GOG offline install and the GOG
 * account's row); highest first, the button acts on:
 *  1. an installed copy, the chosen one when it is installed;
 *  2. the copy the person chose for the game ("Use this copy for this game");
 *  3. the stores' order ([OWNERSHIP_STORES], the registry's).
 * So Play always plays something installed, and a chosen copy sticks once it
 * is. The choice is kept per card, by the card's identity ([cardKey]: the
 * game's name as [StoreIdentity] compares it), and is dropped when the copy
 * it names is no longer one of the card's ([stale]). Preferences reads and
 * writes: off the main thread.
 */
object CopyChoices {
    private const val PREFS = "droidtop_pc_copy_choices"

    private val changed = MutableStateFlow(0)

    /** Moves whenever a choice is made or dropped, so the library is folded again. */
    val changes: StateFlow<Int> get() = changed

    /** A card's identity: its name as [StoreIdentity.titleKey] reads it, so two spellings of one game are one key. */
    fun cardKey(name: String): String = "card:" + StoreIdentity.titleKey(name).ifEmpty { name.trim().lowercase() }

    fun all(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    /** [entryId] is the copy [cardKey]'s button acts on from now on; null lets the rule above choose again. */
    fun choose(context: Context, cardKey: String, entryId: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (entryId == null) edit.remove(cardKey) else edit.putString(cardKey, entryId)
        edit.apply()
        changed.value++
    }

    /** Drops the choices of [keys]. */
    fun forget(context: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        keys.forEach(edit::remove)
        edit.apply()
    }

    /**
     * The choices that name a copy their card no longer has: a store signed
     * out, or a store row gone after a sync. Only asked of a card that holds a
     * store row, so a library still loading its store rows drops nothing. Pure.
     */
    fun stale(groups: List<LibraryGameGroup>, choices: Map<String, String>): List<String> =
        groups.mapNotNull { group ->
            val key = cardKey(group.game.name)
            val chosen = choices[key] ?: return@mapNotNull null
            key.takeIf { chosen !in group.entriesByPath && group.entriesByPath.values.any { it.ownership() != null } }
        }

    /**
     * The copy the button acts on among [copies] ([GameCopy.installed] says
     * which are on the device), given the one [chosen]; null when [chosen]
     * gives no answer and the card's own order decides. Pure.
     */
    fun acting(copies: List<GameCopy>, chosen: String?): GameCopy? {
        val pick = chosen?.let { id -> copies.firstOrNull { it.path == id } } ?: return null
        return pick.takeIf { it.installed || copies.none { copy -> copy.installed } }
    }
}
