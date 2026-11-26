package dev.droidtop.library.controller

/** What the person said by pressing the button labelled A on one pad, and whether it can still be trusted. */
data class PadCapture(val confirmKeyCodeIsB: Boolean, val signature: String, val stale: Boolean)

/** Everything the resolver knows about the active pad. Plain data, so the whole rule is unit-tested. */
data class PadFacts(
    /** Console table row of this device, when it has one. */
    val console: ConsoleDef?,
    /** The active pad is the pad built into [console]. */
    val builtIn: Boolean,
    /** From the pad's vendor and product id, or its name. */
    val externalFamily: GlyphFamily?,
    /** The layout toggle's current value, null when it could not be read. */
    val toggleValue: String?,
    val capture: PadCapture?,
    /** The watched-property signature as it is now. */
    val signature: String,
)

/**
 * The one rule from facts to [FaceLayout] (docs/SPEC.md 7b, "Console and
 * controller detection"). Strongest first:
 *
 * 1. the console's own layout switch, for the pad built into a known
 *    console whose toggle reads a value the table lists: it alone decides,
 *    every time it changes, and no capture overrides it (owner,
 *    2026-11-06, mytop-project/tracker#935: a stored capture kept confirm on
 *    the bottom button in Retro mode, "the opposite of what I'd ever want");
 * 2. a capture the person made for this pad that is still current, for a
 *    pad droidtop cannot read (a toggle it cannot read or does not list, an
 *    external pad);
 * 3. the console table without a readable toggle (a value the table does
 *    not list is not guessed: the family is known but the layout is not);
 * 4. the glyph family of an external pad, from SDL's ids;
 * 5. unknown: Xbox-style, flagged as not established.
 */
object LayoutResolver {
    fun resolve(facts: PadFacts): FaceLayout {
        val console = facts.console
        val family = console?.glyphFamily ?: GlyphFamily.XBOX
        if (facts.builtIn && console != null) {
            val switched = console.toggle?.let { toggle -> facts.toggleValue?.let { toggle.values[it] } }
            if (switched != null) return FaceLayout(family, switched.confirmOnRight, switched.keysSwapped, LayoutSource.CONSOLE)
        }
        facts.capture?.takeIf { it.isCurrent(facts.signature) }?.let { return FaceLayout.captured(it.confirmKeyCodeIsB) }
        if (facts.builtIn && console != null) {
            if (console.toggle == null) return FaceLayout.forFamily(family, LayoutSource.CONSOLE)
            return FaceLayout.forFamily(family, LayoutSource.UNKNOWN)
        }
        facts.externalFamily?.let { return FaceLayout.forFamily(it, LayoutSource.FAMILY) }
        return FaceLayout.DEFAULT
    }

    /** Why [isBuiltIn] answered as it did, for the one log line per decision. */
    enum class BuiltInReason(val builtIn: Boolean) {
        NO_CONSOLE(false),
        NO_PAD_YET(true),
        TABLE_IDENTITY(true),
        ONLY_PAD(true),
        OTHER_PAD(false),
    }

    /**
     * Whether the active pad is the console's own pad. By the table's ids or
     * name; and, on a console in the table, also when it is the ONE gamepad
     * attached and no attached pad has the table's identity. A handheld's own
     * pad is always attached, and a handheld can re-present it under another
     * identity when its layout toggle changes: the Retroid Pocket 5 with
     * `persist.sys.gamepad.type=1` reports its pad as "Xbox Wireless
     * Controller" (console, build 1386), which the table's "Retroid Pocket
     * Controller" identity did not match, so the external-pad rule took over
     * and the console table, the only thing that can follow the toggle, was
     * never consulted. With a second pad attached and neither matching, nothing
     * is assumed.
     */
    fun isBuiltIn(
        console: ConsoleDef?,
        activeMatchesTable: Boolean?,
        attachedGamepads: Int,
        anyAttachedMatchesTable: Boolean,
    ): BuiltInReason = when {
        console == null -> BuiltInReason.NO_CONSOLE
        activeMatchesTable == null -> BuiltInReason.NO_PAD_YET
        activeMatchesTable -> BuiltInReason.TABLE_IDENTITY
        attachedGamepads == 1 && !anyAttachedMatchesTable -> BuiltInReason.ONLY_PAD
        else -> BuiltInReason.OTHER_PAD
    }

    /**
     * A capture that changed under the person: flagged by a device change or
     * recorded against other property values than now. It is then ignored,
     * and an unknown pad is asked again lightly ([needsRecapture]).
     */
    fun PadCapture.isCurrent(signature: String): Boolean = !stale && this.signature == signature

    fun needsRecapture(facts: PadFacts, resolved: FaceLayout): Boolean {
        val capture = facts.capture ?: return false
        return resolved.source == LayoutSource.UNKNOWN && !capture.isCurrent(facts.signature)
    }
}
