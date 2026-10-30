package dev.droidtop.shell.standard

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Log
import app.murinelauncher.widget.radio.RadioGroupBottomSheet
import app.murinelauncher.widget.radio.RadioListDialog
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.ConstantItem
import com.android.launcher3.R
import com.android.launcher3.touch.GestureAction
import com.android.launcher3.util.Executors
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.runtime.HomeAction

/**
 * Runs what a gesture slot or a home icon's swipe is set to (docs/SPEC.md, Launcher mode "Gestures" and "Icon
 * swipes"; Droidtop/tracker#564): one of Standard's [GestureAction]s, or starting an app. A slot keeps its action in
 * its own enum preference as before; when that is [GestureAction.LAUNCH_APP] the app is in the preference beside it,
 * [APP_SUFFIX]. Everything else stores a [HomeAction] in its encoded form.
 */
object HomeActions {
    private const val TAG = "droidtop.HomeActions"
    const val APP_SUFFIX = "_app"

    /** The preference beside a slot's that names the app it starts. Backed up like the slot's own. */
    private fun appItem(slot: ConstantItem<GestureAction>) = LauncherPrefs.backedUpItem(slot.sharedPrefKey + APP_SUFFIX, "")

    /** The app a slot starts when it is set to [GestureAction.LAUNCH_APP]. */
    fun slotApp(context: Context, slot: ConstantItem<GestureAction>): String? =
        appItem(slot).get(context).takeIf { it.isNotEmpty() }

    /** A slot's action as a [HomeAction]; null when it does nothing. */
    fun slotAction(context: Context, slot: ConstantItem<GestureAction>): HomeAction? = when (val action = slot.get(context)) {
        GestureAction.NONE -> null
        GestureAction.LAUNCH_APP -> slotApp(context, slot)?.let { HomeAction.Launch(it) }
        else -> HomeAction.Droidtop(action.id)
    }

    /** Sets a slot to [action]; null sets it to nothing. */
    fun setSlot(context: Context, slot: ConstantItem<GestureAction>, action: HomeAction?) {
        val (value, app) = when (action) {
            null -> GestureAction.NONE to null
            is HomeAction.Launch -> GestureAction.LAUNCH_APP to action.component
            is HomeAction.Droidtop -> (droidtopAction(action.action) ?: GestureAction.NONE) to null
        }
        LauncherPrefs.get(context).put(appItem(slot), app.orEmpty())
        LauncherPrefs.get(context).put(slot, value)
    }

    /** Whether a slot does anything, so its gesture is worth claiming. */
    fun isSet(context: Context, slot: ConstantItem<GestureAction>): Boolean = slotAction(context, slot) != null

    /** Runs a slot's action. Returns whether the gesture should be consumed. */
    fun runSlot(launcher: Launcher, slot: ConstantItem<GestureAction>): Boolean = run(launcher, slotAction(launcher, slot))

    /** Runs [action]. Returns whether the gesture should be consumed. */
    fun run(launcher: Launcher, action: HomeAction?): Boolean = when (action) {
        null -> false
        is HomeAction.Droidtop -> droidtopAction(action.action)?.perform(launcher) ?: false
        is HomeAction.Launch -> GestureAction.LAUNCH_APP.perform(launcher, action.component)
    }

    /** The droidtop action a saved id names. Opening an app is a [HomeAction.Launch], never a bare id. */
    private fun droidtopAction(id: String): GestureAction? = GestureAction.byId(id)?.takeIf { it != GestureAction.LAUNCH_APP }

    /**
     * Starts [component] through `LauncherApps` (a launcher activity only, never an arbitrary intent); says so when the
     * app is gone. Always consumes the gesture.
     */
    fun launchApp(launcher: Launcher, component: String): Boolean {
        val name = ComponentName.unflattenFromString(component)
        try {
            requireNotNull(name)
            launcher.getSystemService(LauncherApps::class.java).startMainActivity(name, Process.myUserHandle(), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "could not start $component", e)
            Notices.flash(launcher.getString(R.string.home_action_app_missing), tone = NoticeTone.Caution, context = launcher)
        }
        return true
    }

    /** What [action] is called, for a setting's summary and a menu. */
    fun label(context: Context, action: HomeAction?): String = when (action) {
        null -> GestureAction.NONE.getDisplayName(context)
        is HomeAction.Droidtop -> droidtopAction(action.action)?.getDisplayName(context) ?: GestureAction.NONE.getDisplayName(context)
        is HomeAction.Launch -> context.getString(R.string.home_action_open_app, appLabel(context, action.component))
    }

    private fun appLabel(context: Context, component: String): CharSequence {
        val name = ComponentName.unflattenFromString(component) ?: return component
        return try {
            context.getSystemService(LauncherApps::class.java).resolveActivity(android.content.Intent().setComponent(name), Process.myUserHandle())?.label
                ?: name.packageName
        } catch (_: Exception) {
            name.packageName
        }
    }

    /**
     * Lets the person pick an app: the launchable apps, by name, in the same focusable list dialog Standard's
     * settings use. The list and icons load off the main thread.
     */
    fun pickApp(context: Context, title: CharSequence, onPick: (String) -> Unit) {
        Executors.THREAD_POOL_EXECUTOR.execute {
            val apps = context.getSystemService(LauncherApps::class.java)
                .getActivityList(null, Process.myUserHandle())
                .map { Triple(it.componentName.flattenToString(), it.label.toString(), runCatching<Drawable?> { it.getIcon(0) }.getOrNull()) }
                .sortedBy { it.second.lowercase() }
            Executors.MAIN_EXECUTOR.execute {
                RadioListDialog.show(
                    context = context,
                    title = title,
                    entryCount = apps.size,
                    iconPosition = RadioGroupBottomSheet.IconPosition.START,
                    currentIndex = -1,
                    textProvider = { apps[it].second },
                    iconProvider = { apps[it].third },
                    iconTint = null,
                    isVisibleProvider = null,
                    isEnabledProvider = null,
                    onSelected = { onPick(apps[it].first) },
                )
            }
        }
    }

    /**
     * Lets the person pick any action: Standard's own, then "Open an app" which goes on to [pickApp]. Used where
     * there is no preference to hold the choice (an icon's swipes).
     */
    fun pickAction(context: Context, title: CharSequence, current: HomeAction?, onPick: (HomeAction?) -> Unit) {
        val actions = GestureAction.entries
        val currentIndex = when (current) {
            null -> GestureAction.NONE.ordinal
            is HomeAction.Launch -> GestureAction.LAUNCH_APP.ordinal
            is HomeAction.Droidtop -> droidtopAction(current.action)?.ordinal ?: GestureAction.NONE.ordinal
        }
        RadioListDialog.show(
            context = context,
            title = title,
            entryCount = actions.size,
            iconPosition = RadioGroupBottomSheet.IconPosition.START,
            currentIndex = currentIndex,
            textProvider = { i -> if (actions[i] == GestureAction.LAUNCH_APP && current is HomeAction.Launch) label(context, current) else actions[i].getDisplayName(context) },
            iconProvider = null,
            iconTint = null,
            isVisibleProvider = null,
            isEnabledProvider = null,
            onSelected = { i ->
                when (val action = actions[i]) {
                    GestureAction.NONE -> onPick(null)
                    GestureAction.LAUNCH_APP -> pickApp(context, title) { onPick(HomeAction.Launch(it)) }
                    else -> onPick(HomeAction.Droidtop(action.id))
                }
            },
        )
    }
}
