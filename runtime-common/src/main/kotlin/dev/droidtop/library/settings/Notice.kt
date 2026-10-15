package dev.droidtop.library.settings

import android.content.Context

/**
 * How serious a [Notice] is (docs/SPEC.md 7k, "Notices"). Danger is for what
 * the person is about to lose or risk now: droidtop's danger dialog (an
 * install, an update from a different maker, protection failing to start)
 * and, since 2026-10-15 (Droidtop/tracker#521 item 1), a plugin's own danger
 * notice, still marked as from the plugin. Never a flash.
 */
enum class NoticeTone { Info, Caution, Danger }

/**
 * Where a [NoticeItem] sits: a row among rows, a notice under its group's
 * label (about that group), or the page banner under the header (about the
 * whole page, at most one per page).
 */
enum class NoticePlacement { Row, Group, Page }

/**
 * The one thing a notice offers to do about its condition ("Approve", "Turn on"). [label] is three words or fewer.
 * [run] is suspend and main-safe, so an action may ask first (a danger dialog) before it changes anything; the
 * surface runs it in its own scope and reads the screen again when it returns.
 */
class NoticeAction(val label: String, val run: suspend (Context) -> Unit)

/**
 * One condition the person should know about, in one shape every surface
 * draws the same way (docs/SPEC.md 7k, "Notices"; Droidtop/tracker#485).
 * The copy rules are SPEC's: [title] states the condition in at most
 * [TITLE_MAX] characters and no period, [body] the outcome in at most
 * [BODY_MAX], [details] the exact terms (copyable, shown in Info), and
 * [recommendation] is the danger dialog's one "We recommend <button>."
 * sentence. [source] is a plugin's display name from its approval record,
 * or null for droidtop's own notice. A notice cannot be dismissed: it is
 * state from code and goes away when its condition does.
 */
class Notice(
    val tone: NoticeTone,
    val title: String,
    val body: String? = null,
    val recommendation: String? = null,
    val details: String? = null,
    val action: NoticeAction? = null,
    val source: String? = null,
) {
    /**
     * Whether a pad can stop on it: only when pressing or Info does
     * something, which is an action, details, or a title or body cut on the
     * row ([cut]) whose full text Info then shows. A notice with none of
     * those is passed over, so the cursor never rests where A does nothing.
     */
    fun takesFocus(cut: Boolean = false): Boolean = action != null || !details.isNullOrBlank() || cut

    /**
     * What A does on it. The action when there is one; otherwise Info when
     * there is more to read, since on a screen where Y is already bound to
     * something else (Games: Sort By) A is the only way to Info; else nothing.
     */
    fun aPress(): NoticePress = when {
        action != null -> NoticePress.ACTION
        !details.isNullOrBlank() -> NoticePress.INFO
        else -> NoticePress.NONE
    }

    /**
     * The words a screen reader says for it, the tone first ("Caution,
     * Unofficial plugin, Approve it only if ..."), then the plugin it is
     * from. Shared by every renderer so the order cannot drift.
     */
    fun spoken(): String = buildList {
        add(tone.spokenName)
        add(title)
        body?.let { add(it) }
        recommendation?.let { add(it) }
        source?.let { add("from $it") }
    }.joinToString(", ")

    /** The custom accessibility actions it offers: Info when there is anything more to read, then its action. */
    fun accessibilityActions(): List<String> = buildList {
        if (!details.isNullOrBlank()) add(INFO_ACTION)
        action?.let { add(it.label) }
    }

    companion object {
        const val TITLE_MAX = 40
        const val BODY_MAX = 90
        const val INFO_ACTION = "Info"
    }
}

/** What A on a notice does ([Notice.aPress]). */
enum class NoticePress { ACTION, INFO, NONE }

/** The word a screen reader says first for a tone. */
val NoticeTone.spokenName: String
    get() = when (this) {
        NoticeTone.Info -> "Info"
        NoticeTone.Caution -> "Caution"
        NoticeTone.Danger -> "Danger"
    }

/**
 * A [Notice] as a catalog row (docs/SPEC.md 7k, "Notices"): every settings
 * renderer draws it as the one notice card, in the [placement] it asks for.
 * Its subtitle is the body, so settings search and the Info sheet read the
 * same words the card shows.
 */
class NoticeItem(
    override val id: String,
    val notice: Notice,
    val placement: NoticePlacement = NoticePlacement.Row,
    /**
     * A screen the notice's action opens in the same surface, as a [NestedScreenItem] would (a plugin notice whose
     * action is a `view`, a "not allowed" notice opening Permissions). When set, the action's [NoticeAction.run] is
     * not called; the label still names the press.
     */
    val opens: CatalogScreen? = null,
) : CatalogItem {
    override val title: String get() = notice.title
    override val subtitle: String? get() = notice.body
}
