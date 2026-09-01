package dev.droidtop.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray

/**
 * Reader for enginehost's installed-bundles ContentProvider — the
 * emulator's "installed cores" list, exposed exactly as requested and
 * confirmed implemented (Codex response 2026-08-31 02:10, request 3):
 *
 *   content://dev.enginehost.capabilities/installed
 *   columns: bundleId, engine, engineContext, pluginVersion,
 *            runtimeVersion, supportedVersions (JSON),
 *            supportedRanges (JSON), runtimeRequirements (JSON), origin
 *
 * ADVISORY by contract: launch resolution stays enginehost's own —
 * droidtop uses this to INFORM (a runtimes list in settings, a
 * "bundle installed / will auto-install" annotation), never to gate a
 * launch it would otherwise attempt.
 *
 * Version matching implements the series rule from the same response:
 * a dotted `supportedSeries` entry like "8.2" covers every `8.2.*`
 * version regardless of component count while never matching `8.3` —
 * component-prefix equality, not string-prefix ("8.2" must not match
 * "8.20.1").
 */
object EnginehostCapabilities {

    // Lazy on purpose: android.net.Uri is a stub on the JVM, and an
    // eager parse at object-init turns every unit test that touches
    // this object (the pure-logic seriesCovers ones included) into an
    // ExceptionInInitializerError. Real round-13 CI failure.
    val PROVIDER_URI: Uri by lazy { Uri.parse("content://dev.enginehost.capabilities/installed") }

    data class InstalledBundle(
        val bundleId: String,
        val engine: String,
        val engineContext: String?,
        val pluginVersion: String?,
        val runtimeVersion: String?,
        val supportedVersions: List<String>,
        val supportedSeries: List<String>,
        val origin: String?,
    )

    fun installedBundles(context: Context): List<InstalledBundle> = runCatching {
        val bundles = mutableListOf<InstalledBundle>()
        context.contentResolver.query(PROVIDER_URI, null, null, null, null)?.use { cursor ->
            fun col(name: String): Int = cursor.getColumnIndex(name)
            while (cursor.moveToNext()) {
                fun str(name: String): String? =
                    col(name).takeIf { it >= 0 }?.let { cursor.getString(it) }?.ifBlank { null }
                val bundleId = str("bundleId") ?: continue
                val engine = str("engine") ?: continue
                bundles += InstalledBundle(
                    bundleId = bundleId,
                    engine = engine,
                    engineContext = str("engineContext"),
                    pluginVersion = str("pluginVersion"),
                    runtimeVersion = str("runtimeVersion"),
                    supportedVersions = jsonStrings(str("supportedVersions")),
                    // supportedRanges exists in the schema; series is the
                    // form the responses describe for real bundles (the
                    // 8.2/8.3 Ren'Py lines). Ranges parse into the same
                    // list until a real bundle ships one to verify the
                    // shape against.
                    supportedSeries = jsonStrings(str("supportedSeries")) + jsonStrings(str("supportedRanges")),
                    origin = str("origin"),
                )
            }
        }
        bundles
    }.getOrDefault(emptyList())

    /**
     * Every distinct way [engineFamily] could actually be run right now,
     * built from installed bundles so droidtop only ever offers something
     * that resolves. Used by the manual path when detection cannot name a
     * context or a version -- see [EnginehostManualChoicePrefs].
     *
     * Advisory still holds: this decides what droidtop OFFERS, never what
     * enginehost resolves.
     */
    fun runOptionsFor(context: Context, engineFamily: String): List<EnginehostRunOption> =
        installedBundles(context)
            .filter { it.engine.equals(engineFamily, ignoreCase = true) }
            .mapNotNull { bundle ->
                // A bundle with no runtime version cannot answer the
                // contract's required engineVersion, so it is not offerable.
                val version = bundle.runtimeVersion ?: return@mapNotNull null
                EnginehostRunOption(
                    engineContext = bundle.engineContext,
                    engineVersion = version,
                    label = buildString {
                        append(bundle.engineContext ?: "default")
                        append(" - ")
                        append(version)
                        bundle.pluginVersion?.let { append(" (plugin ").append(it).append(')') }
                    },
                    bundleId = bundle.bundleId,
                )
            }
            .distinctBy { it.engineContext to it.engineVersion }

    private fun jsonStrings(raw: String?): List<String> = runCatching {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        buildList { for (i in 0 until array.length()) add(array.getString(i)) }
    }.getOrDefault(emptyList())

    /**
     * Whether [bundle] covers a game of ([engine], [engineContext],
     * [version]) — advisory, for display. Null [version] matches only on
     * family/context (the CONFIGURE flow owns the rest).
     */
    fun covers(bundle: InstalledBundle, engine: String, engineContext: String?, version: String?): Boolean {
        if (!bundle.engine.equals(engine, ignoreCase = true)) return false
        if (engineContext != null && bundle.engineContext != null &&
            !bundle.engineContext.equals(engineContext, ignoreCase = true)
        ) return false
        if (version == null) return true
        if (bundle.supportedVersions.any { it == version }) return true
        return bundle.supportedSeries.any { seriesCovers(it, version) }
    }

    /**
     * The series rule, exactly as specified: "8.2" covers every `8.2.*`
     * (any component count, `8.2` itself included) and never `8.3` —
     * and by components, not characters, so "8.2" does not cover
     * "8.20.1".
     */
    fun seriesCovers(series: String, version: String): Boolean {
        val seriesParts = series.split('.')
        val versionParts = version.split('.')
        if (seriesParts.isEmpty() || versionParts.size < seriesParts.size) return false
        return seriesParts.indices.all { seriesParts[it] == versionParts[it] }
    }
}
