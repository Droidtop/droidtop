package dev.droidtop.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One widget in a stack: its app widget id, bound to the launcher's host, and its provider's component. */
@Serializable
data class StackMember(val id: Int, val provider: String)

/** A widget stack: its widgets in the order a swipe goes through them. */
@Serializable
data class WidgetStack(val members: List<StackMember>)

/**
 * Standard's widget stacks (docs/SPEC.md "Standard", Droidtop/tracker#564), as data. The launcher's model
 * holds one widget per place on the grid, so a stack's visible widget is the model's item and the others are
 * only here, bound to the launcher's widget host but in no database. Which member shows is whichever one's id
 * the model item has; a swipe swaps the item's id and provider for the next member's, so this table does not
 * change on a swipe.
 *
 * Pure, so the launcher's ghost-widget cleanup and the swap are tested without Android.
 */
object WidgetStackTable {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WidgetStack.serializer())

    /** Throws on a damaged table: the store ([CachedPref]) keeps the text before anything is saved over it. */
    fun decode(text: String): List<WidgetStack> = json.decodeFromString(serializer, text)

    fun encode(stacks: List<WidgetStack>): String = json.encodeToString(serializer, stacks)

    /** The stack that has the widget [id], or null. */
    fun stackOf(stacks: List<WidgetStack>, id: Int): WidgetStack? = stacks.firstOrNull { stack -> stack.members.any { it.id == id } }

    /**
     * [added] joins the stack whose widget [shown] is, after the last member; a widget that was in no stack
     * starts one with [added].
     */
    fun add(stacks: List<WidgetStack>, shown: StackMember, added: StackMember): List<WidgetStack> {
        val existing = stackOf(stacks, shown.id)
            ?: return stacks + WidgetStack(listOf(shown, added))
        return stacks.map { if (it === existing) it.copy(members = it.members + added) else it }
    }

    /** The member a swipe shows after [currentId]: [by] +1 is the next, -1 the one before, going round. */
    fun next(stack: WidgetStack, currentId: Int, by: Int): StackMember? {
        val at = stack.members.indexOfFirst { it.id == currentId }
        if (at < 0 || stack.members.size < 2) return null
        val size = stack.members.size
        return stack.members[((at + by) % size + size) % size]
    }

    /** Takes [id] out of its stack; a stack left with one widget is no stack. */
    fun remove(stacks: List<WidgetStack>, id: Int): List<WidgetStack> =
        stacks.map { stack -> stack.copy(members = stack.members.filter { it.id != id }) }.filter { it.members.size >= 2 }

    /**
     * The stacks still on the grid: those with a member the launcher's database has ([inDatabase], the
     * shown widget). A stack whose shown widget was removed has none.
     */
    fun live(stacks: List<WidgetStack>, inDatabase: Set<Int>): List<WidgetStack> =
        stacks.filter { stack -> stack.members.any { it.id in inDatabase } }

    /**
     * The widget ids the launcher's ghost-widget cleanup keeps although they are not in its database: the
     * hidden members of live stacks. A removed stack's members are not kept, so their ids are freed.
     */
    fun spared(stacks: List<WidgetStack>, inDatabase: Set<Int>): Set<Int> =
        live(stacks, inDatabase).flatMap { stack -> stack.members.map { it.id } }.filterTo(mutableSetOf()) { it !in inDatabase }
}
