package dev.droidtop.pluginhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * `ui.main` (docs/plugin-api.md 1.7, docs/SPEC.md 12a): a plugin that has a
 * full user interface of its own declares it and droidtop opens it
 * full-screen, in the plugin's own process, from the plugin's page in
 * Settings (all three modes) and from the Gaming search's source rows.
 * The host-drawn views (docs/plugin-api.md 1.6) stay the way a plugin adds
 * rows to droidtop's own screens; this is the way a plugin offers its whole
 * app.
 *
 * Only `flutter_embed` can host one: [PluginMainActivity] starts the
 * plugin's declared Dart entrypoint on a second `FlutterEngine` from the
 * plugin's own `libapp.so`. A `native_bundle` has no activity droidtop could
 * start (a dex-loaded activity is not in droidtop's manifest) and `python`
 * has no UI toolkit, so [PluginManifest.structuralProblems] refuses `ui.main`
 * for them rather than leaving a row that does nothing.
 */
object PluginMainUi {
    const val POINT = "ui.main"

    /** The Dart function to run, and the library URI it lives in when it is not the root library of the plugin's build target. */
    data class Entry(val entrypoint: String, val library: String?)

    /** Reads the `entrypoint` (required) and `library` (optional) fields of a declared `ui.main` entry. */
    fun entryOf(point: ProvidedPoint): Entry? {
        val extra = runCatching { JSONObject(point.extra) }.getOrNull() ?: return null
        val function = extra.optString("entrypoint").trim().takeIf { it.isNotEmpty() } ?: return null
        return Entry(function, extra.optString("library").trim().takeIf { it.isNotEmpty() })
    }

    /** The entry [manifest] declares, or null when it declares none or its kind cannot host one. */
    fun declared(manifest: PluginManifest): Entry? {
        if (manifest.kind != PluginKind.FLUTTER_EMBED || manifest.contractVersion < 2) return null
        return manifest.v2.provides.firstOrNull { it.point == POINT }?.let { entryOf(it) }
    }

    /** Whether to offer "Open <plugin>" at all: declared, approved and on. No I/O, so it is safe while drawing a screen. */
    fun offered(record: PluginRecord): Boolean = record.runnable() && declared(record.manifest) != null

    /**
     * Opens the plugin's main UI. Returns null once the activity was started,
     * otherwise a sentence saying why not (shown to the person as is). Reads
     * and hashes the installed payload, so call it off the main thread.
     */
    fun open(context: Context, record: PluginRecord): String? {
        val m = record.manifest
        val entry = declared(m) ?: return "${m.label} has no screen of its own"
        if (!record.runnable()) return "${m.label} is not approved and turned on"
        // The same per-item approval every other call honours (#164): the user may have unticked this one.
        if (PluginGrants.provideState(record, PluginGrants.forContext(context).read(m.id), POINT) != GrantState.GRANTED) {
            return "${m.label} has not been allowed to open its own screen. Allow it under Permissions"
        }
        PluginRuntimeNeeds.missing(context, m)?.let { return "${m.label} ${it.message}" }
        val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
        PluginBundleInstaller.verifyInstalled(PluginStore.root(context), record, userKeys)?.let {
            return "${m.label} failed its check: ${it.reason}"
        }
        // A plugin without its own activity draws into a surface droidtop owns (contained in software, gpu.render with
        // hardware); only a full-access plugin runs its own activity in its own process (docs/plugin-api.md 5.3).
        val tier = PluginTiers.of(record, PluginGrants.forContext(context).read(m.id))
        val intent = if (tier == PluginTier.FULL_TRUST) {
            PluginMainActivity.intentFor(context, m.id, entry)
        } else {
            // A contained plugin is isolated and cannot present into the surface itself: its frames come through droidtop.
            PluginScreenActivity.intentFor(context, m.id, entry, bridged = tier == PluginTier.CONTAINED)
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.fold(onSuccess = { null }, onFailure = { "Could not open ${m.label}: ${it.message}" })
    }
}
