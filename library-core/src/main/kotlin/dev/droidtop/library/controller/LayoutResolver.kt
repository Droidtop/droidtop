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
 * 1. a capture the person made for this pad that is still current;
 * 2. the console table, for the pad built into a known console, with its
 *    toggle read live (a value the table does not list is not guessed:
 *    the family is known but the layout is not);
 * 3. the glyph family of an external pad, from SDL's ids;
 * 4. unknown: Xbox-style, flagged as not established.
 */
object LayoutResolver {
    fun resolve(facts: PadFacts): FaceLayout {
        facts.capture?.takeIf { it.isCurrent(facts.signature) }?.let { return FaceLayout.captured(it.confirmKeyCodeIsB) }
        val console = facts.console
        if (facts.builtIn && console != null) {
            val family = console.glyphFamily ?: GlyphFamily.XBOX
            val toggle = console.toggle ?: return FaceLayout.forFamily(family, LayoutSource.CONSOLE)
            val value = facts.toggleValue?.let { toggle.values[it] }
                ?: return FaceLayout.forFamily(family, LayoutSource.UNKNOWN)
            return FaceLayout(family, value.confirmOnRight, value.keysSwapped, LayoutSource.CONSOLE)
        }
        facts.externalFamily?.let { return FaceLayout.forFamily(it, LayoutSource.FAMILY) }
        return FaceLayout.DEFAULT
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
