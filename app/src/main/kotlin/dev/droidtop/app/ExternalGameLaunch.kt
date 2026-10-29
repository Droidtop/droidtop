package dev.droidtop.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import dev.droidtop.pluginhost.GrantAnswer
import dev.droidtop.pluginhost.GrantPromptRequest
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PermissionTier
import dev.droidtop.pluginhost.PluginGrantPrompts
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginRefusals
import java.io.File

/**
 * Other apps asking droidtop to start a game (Droidtop/tracker#124; docs/SPEC.md 7 "Other apps that start games").
 *
 * Each calling app holds one permission, [PERMISSION], in the plugin API's grant model (docs/plugin-api.md 4.3): the same
 * [PluginGrants] store (in its own folder, so an app is never mistaken for a plugin of the same id), the same three
 * states, the same first-use sheet ([PluginGrantPrompts]) with Allow / Not now / Never, and Not now lasting this run of
 * droidtop ([PluginRefusals]). An app nobody has answered for is asked. The person changes an answer under Accounts and
 * sources > Plugins > Apps that start games.
 */
object ExternalGameLaunch {
    const val PERMISSION = "launch.games"
    private const val LABEL = "start games from your library"

    /** What a request from an app does. */
    enum class Decision { LAUNCH, BLOCKED, NOT_NOW, ASK }

    /** The rule. Pure. No answer yet is asked; a Not now from this run is not asked again. */
    fun decide(state: GrantState?, notNowThisRun: Boolean): Decision = when (state) {
        GrantState.GRANTED -> Decision.LAUNCH
        GrantState.DENIED -> Decision.BLOCKED
        GrantState.ASK, null -> if (notNowThisRun) Decision.NOT_NOW else Decision.ASK
    }

    fun store(context: Context): PluginGrants = PluginGrants(File(context.filesDir, "app-grants"))

    /** The key Not now is kept under, apart from any plugin whose id is the same as an app's package name. */
    fun refusalKey(packageName: String): String = "app:$packageName"

    /** The stored answer for [packageName], or null when it was never asked. A file that cannot be read counts as unanswered. */
    fun stateOf(context: Context, packageName: String): GrantState? {
        val snapshot = store(context).read(packageName)
        return if (snapshot.corrupt) null else snapshot.states[PERMISSION]
    }

    /** Every app that has asked, by package name. Reads a folder: off the main thread. */
    fun callers(context: Context): List<String> =
        File(context.filesDir, "app-grants").listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            .orEmpty()

    /** The person's own choice for [packageName], from Settings: it replaces a Not now from this run. */
    fun set(context: Context, packageName: String, state: GrantState) {
        PluginRefusals.forgetNotNow(refusalKey(packageName), PERMISSION)
        store(context).set(packageName, PERMISSION, state)
    }

    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * Whether [packageName] may start a game now, asking the person when it has no answer. Blocks while the sheet is up:
     * off the main thread only. A refusal says why in a flash.
     */
    fun allowed(context: Context, packageName: String): Boolean {
        val name = label(context, packageName)
        val key = refusalKey(packageName)
        return when (decide(stateOf(context, packageName), PluginRefusals.isNotNow(key, PERMISSION))) {
            Decision.LAUNCH -> true
            // The screen that changes it opens from the notice, not a path to look for (Droidtop/tracker#759).
            Decision.BLOCKED -> refuse(context, "$name may not start your games. Open Apps that start games to change it") {
                dev.droidtop.app.settings.AppSettingsCatalogs.externalLaunchScreen()
            }
            Decision.NOT_NOW -> refuse(context, "$name may not start your games until you allow it")
            Decision.ASK -> when (PluginGrantPrompts.ask(GrantPromptRequest(key, name, PERMISSION, LABEL, null, PermissionTier.DANGEROUS))) {
                GrantAnswer.ALLOW -> {
                    set(context, packageName, GrantState.GRANTED)
                    true
                }
                GrantAnswer.NEVER -> {
                    set(context, packageName, GrantState.DENIED)
                    false
                }
                GrantAnswer.NOT_NOW -> {
                    // Kept as unanswered, so the app is listed in Settings and can be allowed there.
                    set(context, packageName, GrantState.ASK)
                    PluginRefusals.rememberNotNow(key, PERMISSION)
                    false
                }
            }
        }
    }

    private fun refuse(context: Context, message: String, opens: (() -> dev.droidtop.library.settings.CatalogScreen?)? = null): Boolean {
        android.util.Log.w("droidtop.ExternalLaunch", message)
        dev.droidtop.library.settings.Notices.flash(message, context = context, opens = opens)
        return false
    }
}

/**
 * The exported way in for another app: `dev.droidtop.app.action.LAUNCH_GAME` with the entry's id in
 * [GameLaunchActivity.EXTRA_ENTRY_ID]. droidtop must know who asks, which Android tells it reliably only when the app
 * started this with `startActivityForResult` ([getCallingPackage]) or, on Android 14 and later, shared its identity
 * (`ActivityOptions.setShareIdentityEnabled`, [getLaunchedFromPackage]). A caller it cannot name is refused; the
 * referrer is not used, since an app can set it to anything. Translucent rather than no-display: it stays on screen
 * until the answer, so the sheet it may open is started from a visible activity.
 */
class ExternalGameLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent?.getStringExtra(GameLaunchActivity.EXTRA_ENTRY_ID)
        val caller = callingPackage ?: if (Build.VERSION.SDK_INT >= 34) launchedFromPackage else null
        val app = applicationContext
        when {
            entryId.isNullOrBlank() -> {
                dev.droidtop.library.settings.Notices.flash("No game asked for", context = app)
                finish()
            }
            caller == null -> {
                dev.droidtop.library.settings.Notices.flash(
                    "droidtop starts a game for another app only when it can tell which app asked",
                    context = app,
                )
                finish()
            }
            caller == packageName -> {
                GameLaunchActivity.dispatch(app, entryId)
                finish()
            }
            else -> Thread({
                if (ExternalGameLaunch.allowed(app, caller)) GameLaunchActivity.dispatch(app, entryId)
                runOnUiThread { finish() }
            }, "droidtop-external-launch").start()
        }
    }
}
