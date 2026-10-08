package dev.droidtop.library.stores

/*
 * What a store lets the person choose about a game's content (docs/SPEC.md 7g,
 * "Stores", Droidtop/tracker#313): which extras (DLC) to install with the game
 * and which branch (version track, Steam's "betas") it follows. The types are
 * the store-neutral shape the picker draws; a store that has nothing to choose
 * answers null from [StoreLibrary.contentOptions] and no row is drawn.
 */

/** One extra (a DLC) a store can install with a game: [bytes] is what turning it on downloads. */
data class StoreExtra(
    val id: String,
    val title: String,
    val bytes: Long,
    /** Whether the person's choice includes it now. */
    val selected: Boolean,
    /** Whether its files are on the device now. */
    val installed: Boolean,
)

/**
 * One branch of a game. [locked] says the store asks a password for it;
 * [unlocked] that a password this device holds was accepted, so it can be
 * picked without asking again.
 */
data class StoreBranch(
    val id: String,
    val title: String,
    val locked: Boolean,
    val unlocked: Boolean,
    /** The build the branch is at, as the store words it; null when it names none. */
    val build: String?,
    /** When the branch last changed, epoch milliseconds; null when unknown. */
    val updatedMs: Long?,
    val selected: Boolean,
)

/** Everything the picker draws for one game. */
data class StoreContentOptions(
    val extras: List<StoreExtra>,
    val branches: List<StoreBranch>,
    /** Whether the game is on the device; the picker then says what changing it will do. */
    val installed: Boolean,
) {
    /** Nothing to choose: one branch and no extras. */
    val isEmpty: Boolean get() = extras.isEmpty() && branches.size <= 1
}

/** What the person picked: the ids of the extras to have, and the branch. */
data class StoreContentChoice(val extraIds: Set<String>, val branchId: String?)

/** The picker's pure rules, so a screen and a test share them. */
object StoreContent {
    /** The choice that is in force now, read off [options]. */
    fun current(options: StoreContentOptions): StoreContentChoice = StoreContentChoice(
        extraIds = options.extras.filter { it.selected }.mapTo(LinkedHashSet()) { it.id },
        branchId = options.branches.firstOrNull { it.selected }?.id,
    )

    /** [choice] with extra [id] turned on or off. */
    fun toggled(choice: StoreContentChoice, id: String): StoreContentChoice =
        choice.copy(extraIds = if (id in choice.extraIds) choice.extraIds - id else choice.extraIds + id)

    /** The bytes [choice] adds over what is on the device: extras it has that are not installed yet. */
    fun downloadBytes(options: StoreContentOptions, choice: StoreContentChoice): Long =
        options.extras.filter { it.id in choice.extraIds && !it.installed }.sumOf { it.bytes }

    /** The extras [choice] drops that are on the device now: their files are removed. */
    fun removedExtras(options: StoreContentOptions, choice: StoreContentChoice): List<StoreExtra> =
        options.extras.filter { it.id !in choice.extraIds && it.installed }

    /** Whether the branch in [choice] can be used: it is open, or its password has been accepted. */
    fun branchUsable(options: StoreContentOptions, choice: StoreContentChoice): Boolean {
        val branch = options.branches.firstOrNull { it.id == choice.branchId } ?: return options.branches.isEmpty()
        return !branch.locked || branch.unlocked
    }

    /** Whether applying [choice] changes what is chosen now. */
    fun differs(options: StoreContentOptions, choice: StoreContentChoice): Boolean =
        choice != current(options)

    /** "1.2 GB" style size for the picker rows; blank for nothing. */
    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
        else -> "${(bytes / 1_000_000L).coerceAtLeast(1)} MB"
    }
}
