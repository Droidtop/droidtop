package dev.droidtop.library.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The companion Home's sections, in page order (docs/SPEC.md "The companion's tabs", Droidtop/tracker#328).
 * [key] is what the preferences store; [label] is the section heading and the settings row.
 */
enum class CompanionHomeSection(val key: String, val label: String) {
    NOW("now", "Now"),
    CONTINUE("continue", "Continue playing"),
    RECENTLY_ADDED("recently_added", "Recently added"),
    APPS("apps", "Recent apps"),
    ACTIVITY("activity", "Downloads and updates"),
    SOCIAL("social", "Social"),
    NOTIFICATIONS("notifications", "Notifications"),
    SYSTEM("system", "System"),
    WIDGETS("widgets", "Widgets"),
    ;

    companion object {
        fun fromKey(key: String): CompanionHomeSection? = entries.firstOrNull { it.key == key }
    }
}

/** Which sections the person turned off (Displays > Companion) and which they folded on Home itself. */
data class CompanionHomeLayout(
    val hidden: Set<CompanionHomeSection> = emptySet(),
    val collapsed: Set<CompanionHomeSection> = CompanionHomePrefs.DEFAULT_COLLAPSED,
) {
    fun shows(section: CompanionHomeSection): Boolean = section !in hidden
    fun isOpen(section: CompanionHomeSection): Boolean = section !in collapsed
}

/**
 * The companion Home's layout choices, one store for the settings rows and the page: every section is shown
 * by default, and Notifications starts folded to its one-line summary (the rest start open). Folding is done
 * on Home by tapping a section's heading; hiding is the settings toggle. Read once with [load] (off the main
 * thread on Home), then kept in [layout] so the page and the settings screen follow each other live.
 */
object CompanionHomePrefs {
    private const val PREFS = "companion_home"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_COLLAPSED = "collapsed"

    val DEFAULT_COLLAPSED: Set<CompanionHomeSection> = setOf(CompanionHomeSection.NOTIFICATIONS)

    /** The settings row id for a section's toggle. */
    fun itemId(section: CompanionHomeSection): String = "companion_home_show_" + section.key

    private val state = MutableStateFlow(CompanionHomeLayout())
    val layout: StateFlow<CompanionHomeLayout> = state

    fun load(context: Context) {
        val prefs = prefs(context)
        state.value = CompanionHomeLayout(
            hidden = decode(prefs.getStringSet(KEY_HIDDEN, null)),
            collapsed = prefs.getStringSet(KEY_COLLAPSED, null)?.let(::decode) ?: DEFAULT_COLLAPSED,
        )
    }

    fun setShown(context: Context, section: CompanionHomeSection, shown: Boolean) {
        val hidden = if (shown) state.value.hidden - section else state.value.hidden + section
        state.value = state.value.copy(hidden = hidden)
        prefs(context).edit().putStringSet(KEY_HIDDEN, encode(hidden)).apply()
    }

    fun setOpen(context: Context, section: CompanionHomeSection, open: Boolean) {
        val collapsed = if (open) state.value.collapsed - section else state.value.collapsed + section
        state.value = state.value.copy(collapsed = collapsed)
        prefs(context).edit().putStringSet(KEY_COLLAPSED, encode(collapsed)).apply()
    }

    /** Stored keys back to sections; a key from a later or earlier version that names nothing is dropped. */
    fun decode(keys: Set<String>?): Set<CompanionHomeSection> =
        keys.orEmpty().mapNotNull(CompanionHomeSection::fromKey).toSet()

    fun encode(sections: Set<CompanionHomeSection>): Set<String> = sections.mapTo(HashSet()) { it.key }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
