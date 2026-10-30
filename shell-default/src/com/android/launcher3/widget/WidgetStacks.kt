package com.android.launcher3.widget

import android.content.ComponentName
import android.content.Context
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherFiles
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.widget.util.WidgetSizes
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

    // One lock for the table: the ghost-widget cleanup rewrites it on the model thread while a stack can be added on
    // the main thread, and each change reads the table and saves it whole (Droidtop/tracker#771).
    @JvmStatic
    @Synchronized
    fun table(context: Context): List<WidgetStack> =
        cache ?: WidgetStackTable.decode(prefs(context).getString(KEY, null)).also { cache = it }

    @Synchronized
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
    @Synchronized
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
        synchronized(this) {
            save(launcher, WidgetStackTable.add(table(launcher), StackMember(shown, info.providerName.flattenToString()), member))
        }
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
        stackOf(launcher, member.id)?.let { sizeMembers(launcher, it, info.spanX, info.spanY) }
    }

    /**
     * Every member of [stack] gets the place's size ([spanX] by [spanY]) as its widget options. A member is bound
     * without size options and no add, drop or load path sizes it, so a provider that lays out from them (the system
     * Digital clock) drew for 0x0, blank (rig on dev.2070: "sized once with widgetSizePx=0x0"). Sizing by widget id
     * rather than through the shown view reaches the hidden members too, before any is first shown, and does not
     * depend on the view being attached yet. Off the main thread; WidgetSizes sends only what changed.
     */
    @JvmStatic
    fun sizeMembers(context: Context, stack: WidgetStack, spanX: Int, spanY: Int) {
        val app = context.applicationContext
        com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR.execute {
            val manager = android.appwidget.AppWidgetManager.getInstance(app)
            stack.members.forEach { member ->
                val provider = manager.getAppWidgetInfo(member.id) ?: return@forEach
                WidgetSizes.updateWidgetSizeRangesAsync(member.id, provider, app, spanX, spanY)
            }
        }
    }

    /** A stacked widget's view came on screen (the launcher's load, a page change): its stack's members are sized. */
    @JvmStatic
    fun onShown(context: Context, appWidgetId: Int, spanX: Int, spanY: Int) {
        stackOf(context, appWidgetId)?.let { sizeMembers(context, it, spanX, spanY) }
    }
}
