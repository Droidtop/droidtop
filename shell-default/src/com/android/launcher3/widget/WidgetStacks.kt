package com.android.launcher3.widget

import android.content.ComponentName
import android.content.Context
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherFiles
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import dev.droidtop.runtime.StackMember
import dev.droidtop.runtime.WidgetStack
import dev.droidtop.runtime.WidgetStackTable

/**
 * droidtop (Droidtop/tracker#564): Standard's widget stacks. Several widgets share one place on the home
 * screen and a vertical swipe on it shows the next. The model keeps one widget per place, so the shown one is
 * the model's item and the rest are kept bound to the launcher's widget host and listed here
 * ([WidgetStackTable], one JSON string in the launcher's preferences). Showing another member swaps the
 * item's widget id and provider for that member's and binds it again; nothing else in the model changes.
 * The launcher's ghost-widget cleanup (DatabaseHelper.removeGhostWidgets) spares the hidden members of live
 * stacks and forgets the stacks that left the grid.
 */
object WidgetStacks {
    private const val KEY = "widget_stacks"

    @Volatile
    private var cache: List<WidgetStack>? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(LauncherFiles.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)

    @JvmStatic
    fun table(context: Context): List<WidgetStack> =
        cache ?: WidgetStackTable.decode(prefs(context).getString(KEY, null)).also { cache = it }

    private fun save(context: Context, stacks: List<WidgetStack>) {
        cache = stacks
        prefs(context).edit().putString(KEY, WidgetStackTable.encode(stacks)).apply()
    }

    /** The stack whose member the widget [appWidgetId] is, or null. */
    @JvmStatic
    fun stackOf(context: Context, appWidgetId: Int): WidgetStack? = WidgetStackTable.stackOf(table(context), appWidgetId)

    /**
     * For the ghost-widget cleanup: the ids it keeps although its database does not have them. Stacks with
     * no member left on the grid are forgotten here, so their widgets are freed.
     */
    @JvmStatic
    fun sparedFromCleanup(context: Context, inDatabase: Set<Int>): Set<Int> {
        val stacks = table(context)
        val live = WidgetStackTable.live(stacks, inDatabase)
        if (live != stacks) save(context, live)
        return WidgetStackTable.spared(live, inDatabase)
    }

    /** Adds the widget [added] to the stack of the widget [shown] (starting one), and shows it. Main thread. */
    @JvmStatic
    fun stack(launcher: Launcher, shown: Int, added: Int, provider: ComponentName) {
        val view = launcher.workspace.getWidgetForAppWidgetId(shown) ?: return
        val info = view.tag as? LauncherAppWidgetInfo ?: return
        val member = StackMember(added, provider.flattenToString())
        save(launcher, WidgetStackTable.add(table(launcher), StackMember(shown, info.providerName.flattenToString()), member))
        show(launcher, view, info, member)
    }

    /** A swipe on a stacked widget: shows the next member ([by] +1) or the one before (-1). False when not stacked. */
    @JvmStatic
    fun swipe(launcher: Launcher, view: LauncherAppWidgetHostView, by: Int): Boolean {
        val info = view.tag as? LauncherAppWidgetInfo ?: return false
        val stack = stackOf(launcher, info.appWidgetId) ?: return false
        val next = WidgetStackTable.next(stack, info.appWidgetId, by) ?: return false
        show(launcher, view, info, next)
        return true
    }

    /** The shown member's position in its stack and the stack's size, for the dots; null when not stacked. */
    @JvmStatic
    fun position(context: Context, appWidgetId: Int): IntArray? {
        val stack = stackOf(context, appWidgetId) ?: return null
        return intArrayOf(stack.members.indexOfFirst { it.id == appWidgetId }, stack.members.size)
    }

    private fun show(launcher: Launcher, view: LauncherAppWidgetHostView, info: LauncherAppWidgetInfo, member: StackMember) {
        val provider = ComponentName.unflattenFromString(member.provider) ?: return
        launcher.workspace.removeWorkspaceItem(view)
        info.appWidgetId = member.id
        info.providerName = provider
        launcher.modelWriter.updateItemInDatabase(info)
        launcher.bindAppWidget(info)
    }
}
