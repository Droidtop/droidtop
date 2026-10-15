package dev.droidtop.library.settings

import android.content.Context

/**
 * A catalog screen as a PAGE (docs/SPEC.md 7k, "Page anatomy"; Droidtop/tracker#485 F2a, final design C.1
 * and C.2): a header that says what the page is about, a bar of at most three buttons that do the page's
 * job (plus a "More" overflow for the rest), then the sections. The header is never a stop; the button bar
 * is the first stop and has the cursor when the page opens; destructive buttons live in the overflow or
 * after a rule and always confirm.
 *
 * A page is built once per refresh by [CatalogScreen.page]. Every renderer reads it through the same
 * [CatalogScreen.groups] it always read: the header and the bar become the first group's two items
 * ([PageHeaderItem], [ButtonBarItem]), so the settings search, the companion and Standard's Preference
 * rows need no second loading path, and a renderer that does not know them yet still draws the rest.
 */
class CatalogPage(
    val header: PageHeader? = null,
    val buttons: List<PageButton> = emptyList(),
    /** The rest of the page's actions, behind the bar's last button ("More"); destructive ones go here. */
    val more: List<PageButton> = emptyList(),
    val groups: List<CatalogGroup>,
) {
    /** The page as the groups every renderer reads: the header and the bar first, in a group of their own. */
    fun asGroups(): List<CatalogGroup> {
        val top = listOfNotNull(
            header?.let { PageHeaderItem(it) },
            ButtonBarItem(buttons, more).takeIf { buttons.isNotEmpty() || more.isNotEmpty() },
        )
        return if (top.isEmpty()) groups else listOf(CatalogGroup(id = TOP_GROUP_ID, title = null, items = top)) + groups
    }

    companion object {
        const val TOP_GROUP_ID = "page_top"
    }
}

/**
 * What a page is about: its [title] (the thing's name), a [byline] ("Droidtop · v1.4.0"), its fact [chips]
 * (a trust badge, a state), and either a glyph or the thing's own [imagePath] (a plugin's icon, a game's
 * art). Not focusable; a screen reader hears it as a heading.
 */
class PageHeader(
    val title: String,
    val byline: String? = null,
    val chips: List<CatalogChip> = emptyList(),
    val icon: CatalogIcon? = null,
    val imagePath: String? = null,
)

/** A button's weight on the bar: one primary at most (the page's job), secondaries, and danger (only in More or after a rule). */
enum class ButtonRole { PRIMARY, SECONDARY, DANGER }

/**
 * One button of a page's bar. A disabled button's label IS its reason, in full ink ("Needs RetroArch");
 * only its chrome dims, so the person never sees a greyed word they cannot read. [confirmTitle] asks for
 * an explicit yes first, as on [ActionItem] (every danger button has one).
 */
class PageButton(
    val id: String,
    val label: String,
    val role: ButtonRole = ButtonRole.SECONDARY,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
    val icon: CatalogIcon? = null,
    val confirmTitle: String? = null,
    val run: (Context) -> Unit,
) {
    /** What the button says: its label, or while it cannot act, why. */
    val shownLabel: String get() = if (!enabled && !disabledReason.isNullOrBlank()) disabledReason else label
}

/** The page's header as a catalog row ([CatalogPage]). Never a stop of the cursor. */
class PageHeaderItem(val header: PageHeader) : CatalogItem {
    override val id: String get() = ID
    override val title: String get() = header.title
    override val subtitle: String? get() = header.byline

    companion object {
        const val ID = "page_header"
    }
}

/**
 * The page's button bar as a catalog row ([CatalogPage]): one stop, Left and Right move along it, A presses
 * the button under the cursor. [slots] is what the bar draws: the buttons, then "More" when [more] has any.
 */
class ButtonBarItem(val buttons: List<PageButton>, val more: List<PageButton> = emptyList()) : CatalogItem {
    init {
        require(buttons.size <= MAX_BUTTONS) { "A page bar holds at most $MAX_BUTTONS buttons; the rest go in More" }
        require(buttons.count { it.role == ButtonRole.PRIMARY } <= 1) { "A page has at most one primary button" }
        require(buttons.none { it.role == ButtonRole.DANGER && it.confirmTitle == null }) { "A danger button always confirms" }
    }

    override val id: String get() = ID
    override val title: String get() = buttons.joinToString(" · ") { it.shownLabel }
    override val subtitle: String? get() = null

    /** The bar's stops in order: each button, then null for "More" when there is anything in it. */
    val slots: List<PageButton?> get() = buttons + (if (more.isNotEmpty()) listOf(null) else emptyList())

    /** Where the cursor starts on the bar: the primary button, else the first one that can act, else the first. */
    fun initialSlot(): Int = buttons.indexOfFirst { it.role == ButtonRole.PRIMARY && it.enabled }.takeIf { it >= 0 }
        ?: buttons.indexOfFirst { it.enabled }.takeIf { it >= 0 }
        ?: 0

    companion object {
        const val ID = "page_buttons"
        const val MAX_BUTTONS = 3
        const val MORE_LABEL = "More"
    }
}

/**
 * How a catalog screen is laid out (docs/SPEC.md 7k, "Page anatomy"): one [LIST] of sections; [CATEGORIES],
 * the two-pane category column a page of three or more sections uses; or a [STRIP] of views of one list
 * (a store's Store / Installed / Lists), stepped with L1 and R1.
 */
enum class CatalogLayout { LIST, CATEGORIES, STRIP }

/** A [CatalogScreen] whose content is a [CatalogPage]: built once per refresh, read by every renderer as groups. */
fun pageScreen(
    id: String,
    title: String,
    layout: CatalogLayout = CatalogLayout.LIST,
    indexGroups: (suspend (Context) -> List<CatalogGroup>)? = null,
    onLeave: (() -> Unit)? = null,
    live: kotlinx.coroutines.flow.Flow<Any?>? = null,
    page: suspend (Context) -> CatalogPage,
): CatalogScreen = CatalogScreen(
    id = id,
    title = title,
    groups = { page(it).asGroups() },
    indexGroups = indexGroups,
    onLeave = onLeave,
    live = live,
    layout = layout,
)
