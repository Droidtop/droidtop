package dev.droidtop.runtime.requirements

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import dev.droidtop.runtime.R
import dev.droidtop.runtime.systemstatus.NotificationsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What something droidtop does cannot work without, as one model (docs/SPEC.md "Permissions: what droidtop and its
 * plugins may do", §8; Droidtop/tracker#830, slice R1: the model, no UI). The owner, 2026-10-30: "We also need a
 * better system than the 'needs something' UI". A requirement has a stable [id], a [kind], the [feature] it is for,
 * its words (resources, never "Needs …": [missing] is what does not work yet, [allowed] the granted thing as a noun,
 * [action] one verb, [exact] the Android name for Info), a cheap [check] run off the main thread, the [fix] that grants
 * it and the [wayBack] that takes it back. Nothing here asks for anything: the person always acts (§1).
 */
data class Requirement(
    val id: String,
    val kind: Kind,
    val feature: String,
    @StringRes val missing: Int,
    @StringRes val allowed: Int,
    @StringRes val action: Int,
    val exact: String,
    /** Whether droidtop has it. Cheap, but may touch a system service: called off the main thread only. */
    val check: (Context) -> Boolean,
    val fix: Route,
    val wayBack: Route,
) {
    /** The kinds §8 names; R1 builds [ANDROID_ACCESS] and [HOME_ROLE], the later slices the rest. */
    enum class Kind { ANDROID_ACCESS, HOME_ROLE, MORE_CONTROLS, PLUGIN, DOWNLOAD, FILES, ACCOUNT }

    /**
     * How a requirement is granted or taken back. An Android grant opens Android's exact screen for droidtop
     * ([AndroidScreen], started through SettingsLaunch by the renderer); an app cannot take back its own special
     * access, so its way back is the same screen and droidtop does not pretend otherwise (§6).
     */
    sealed interface Route {
        data class AndroidScreen(val intent: (Context) -> Intent) : Route
        data object None : Route
    }
}

/** One requirement as it stands now. */
data class RequirementState(val requirement: Requirement, val met: Boolean)

/**
 * The one registry of requirements and the one snapshot every list reads (§8: "a cheap check run off the main thread
 * into one snapshot that every list reads (no list touches the disk)"). [refresh] runs every check, on the caller's
 * thread, which must not be the main one; it logs one line per requirement whose state changed, tag
 * `droidtop.requirements`.
 */
object Requirements {
    private const val TAG = "droidtop.requirements"

    private val registered = LinkedHashMap<String, Requirement>()
    private val state = MutableStateFlow<List<RequirementState>>(emptyList())

    /** Every requirement, met or not, as last checked. */
    val snapshot: StateFlow<List<RequirementState>> get() = state

    init {
        AndroidAccess.all.forEach(::register)
    }

    @Synchronized
    fun register(requirement: Requirement) {
        registered[requirement.id] = requirement
    }

    fun byId(id: String): Requirement? = synchronized(this) { registered[id] }

    /** Checks every requirement and publishes the snapshot. Not on the main thread. */
    fun refresh(context: Context): List<RequirementState> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Requirements.refresh runs off the main thread" }
        val all = synchronized(this) { registered.values.toList() }
        val next = all.map { RequirementState(it, runCatching { it.check(context) }.getOrDefault(false)) }
        val before = state.value.associate { it.requirement.id to it.met }
        changes(before, next).forEach { (id, met) -> runCatching { Log.i(TAG, "$id ${if (met) "met" else "missing"}") } }
        state.value = next
        return next
    }

    /** One requirement's check now, for a feature that needs a single answer. Not on the main thread. */
    fun isMet(context: Context, id: String): Boolean = byId(id)?.let { runCatching { it.check(context) }.getOrDefault(false) } ?: false

    /** The ids whose state differs from [before] (a new one counts), with their new state. Pure. */
    internal fun changes(before: Map<String, Boolean>, after: List<RequirementState>): List<Pair<String, Boolean>> =
        after.filter { before[it.requirement.id] != it.met }.map { it.requirement.id to it.met }

    /** What To do lists: the requirements not met, in registration order. Pure. */
    fun toDo(states: List<RequirementState>): List<RequirementState> = states.filter { !it.met }
}

/**
 * droidtop's own Android grants, the table under §8 of the SPEC section, plus the Home role and the location that
 * names the Wi-Fi. Each check is the one this grant's feature already makes; the features move onto these ids in R2
 * and after, deleting their own wording.
 */
object AndroidAccess {
    const val ALL_FILES = "android.all_files"
    const val NOTIFICATIONS = "android.notification_access"
    const val USAGE = "android.usage_access"
    const val INSTALL_APPS = "android.install_unknown_apps"
    const val WRITE_SETTINGS = "android.write_settings"
    const val NOTIFICATION_POLICY = "android.notification_policy"
    const val POST_NOTIFICATIONS = "android.post_notifications"
    const val OVERLAY = "android.draw_over_apps"
    const val WIFI_NAME = "android.location_for_wifi_name"
    const val HOME = "android.home_role"

    private fun appScreen(action: String): Requirement.Route = Requirement.Route.AndroidScreen { context ->
        Intent(action, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun screen(action: String): Requirement.Route = Requirement.Route.AndroidScreen {
        Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val appInfo = appScreen(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)

    private fun access(
        id: String,
        feature: String,
        missing: Int,
        allowed: Int,
        exact: String,
        fix: Requirement.Route,
        check: (Context) -> Boolean,
        kind: Requirement.Kind = Requirement.Kind.ANDROID_ACCESS,
        action: Int = R.string.requirement_action_allow,
    ) = Requirement(id, kind, feature, missing, allowed, action, exact, check, fix, wayBack = fix)

    val all: List<Requirement> = listOf(
        access(
            ALL_FILES, "Reading game folders", R.string.requirement_all_files_missing, R.string.requirement_all_files_allowed,
            if (Build.VERSION.SDK_INT >= 30) "All files access (MANAGE_EXTERNAL_STORAGE)" else "Storage (READ_EXTERNAL_STORAGE)",
            fix = if (Build.VERSION.SDK_INT >= 30) appScreen(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) else appInfo,
            check = { context ->
                if (Build.VERSION.SDK_INT >= 30) {
                    Environment.isExternalStorageManager()
                } else {
                    context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            },
        ),
        access(
            NOTIFICATIONS, "Notifications on the Quick Menu and the companion", R.string.requirement_notifications_missing,
            R.string.requirement_notifications_allowed, "Notification access (BIND_NOTIFICATION_LISTENER_SERVICE)",
            fix = screen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            check = { NotificationsStore.isGranted(it) },
        ),
        access(
            USAGE, "Recent apps beyond droidtop's own launches", R.string.requirement_usage_missing, R.string.requirement_usage_allowed,
            "Usage access (PACKAGE_USAGE_STATS)", fix = screen(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            check = { context ->
                @Suppress("DEPRECATION")
                context.getSystemService(AppOpsManager::class.java)
                    ?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
            },
        ),
        access(
            INSTALL_APPS, "Updating droidtop itself", R.string.requirement_install_missing, R.string.requirement_install_allowed,
            "Install unknown apps (REQUEST_INSTALL_PACKAGES)", fix = appScreen(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            check = { it.packageManager.canRequestPackageInstalls() },
        ),
        access(
            WRITE_SETTINGS, "Brightness, screen timeout and auto-rotate", R.string.requirement_write_settings_missing,
            R.string.requirement_write_settings_allowed, "Modify system settings (WRITE_SETTINGS)",
            fix = appScreen(Settings.ACTION_MANAGE_WRITE_SETTINGS),
            check = { Settings.System.canWrite(it) },
        ),
        access(
            NOTIFICATION_POLICY, "The Do Not Disturb tile", R.string.requirement_dnd_missing, R.string.requirement_dnd_allowed,
            "Do Not Disturb access (ACCESS_NOTIFICATION_POLICY)", fix = screen(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            check = { (it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted },
        ),
        access(
            POST_NOTIFICATIONS, "Download progress and the desktop's session", R.string.requirement_post_notifications_missing,
            R.string.requirement_post_notifications_allowed, "Notifications (POST_NOTIFICATIONS)",
            fix = Requirement.Route.AndroidScreen { context ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            check = { context ->
                Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
            },
        ),
        access(
            OVERLAY, "The performance overlay and the keyboard over other apps", R.string.requirement_overlay_missing,
            R.string.requirement_overlay_allowed, "Display over other apps (SYSTEM_ALERT_WINDOW)",
            fix = appScreen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            check = { Settings.canDrawOverlays(it) },
        ),
        access(
            WIFI_NAME, "Remembering a metered Wi-Fi by name", R.string.requirement_wifi_name_missing,
            R.string.requirement_wifi_name_allowed, "Location (ACCESS_FINE_LOCATION), used only for the Wi-Fi's name",
            fix = appInfo,
            check = { it.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED },
        ),
        access(
            HOME, "Android mode and the Home button", R.string.requirement_home_missing, R.string.requirement_home_allowed,
            "Home app (ROLE_HOME)", fix = screen(Settings.ACTION_HOME_SETTINGS), kind = Requirement.Kind.HOME_ROLE,
            action = R.string.requirement_action_set_up,
            check = { context ->
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
            },
        ),
    )
}
