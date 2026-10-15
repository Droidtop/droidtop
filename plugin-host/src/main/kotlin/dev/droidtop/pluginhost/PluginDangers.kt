package dev.droidtop.pluginhost

import dev.droidtop.library.settings.ConfirmAnswer
import dev.droidtop.library.settings.DangerConfirm
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticeDialogs
import dev.droidtop.library.settings.NoticeTone
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The moments a plugin would run without droidtop's protection, and the one red dialog that asks first
 * (docs/SPEC.md 12a, "Full access asks in red"; Droidtop/tracker#481, final design on tracker#485, D1-D4).
 * Owner, 2026-10-10: "Running plugins unsandboxed should be possible, but big red warnings and a recommendation
 * that users not install unsandboxed plugins". Cancel is first and focused, so a mashed A never allows anything;
 * the risky button is a deliberate move and a press. Nothing is silently best-effort: a contained plugin whose
 * protection did not start is not run until the person chooses, and only for this run of droidtop.
 */
object PluginDangers {
    /** The load error a contained process gives when its protection did not start; the rest is the layer's report. */
    const val PROTECTION_FAILED = "protection-failed: "

    /** The manifest key :app adds (and strips from the plugin's own file) to let one load run without protection. */
    internal const val RUN_UNPROTECTED_KEY = "_droidtopRunUnprotected"

    private const val FULL_ACCESS_BODY = "It can use your files and go online freely."

    /** D1: allowing full access at install or update. [update] picks the proceed label. */
    fun fullAccess(label: String, origin: String, permissionIds: List<String>, update: Boolean): DangerConfirm = DangerConfirm(
        notice = Notice(
            tone = NoticeTone.Danger,
            title = "Allow full access?",
            body = FULL_ACCESS_BODY,
            recommendation = "We recommend Cancel unless you trust where it came from.",
            details = buildString {
                append("Permissions: ").append(permissionIds.joinToString(", "))
                append("\nSource: ").append(origin)
                append("\nWith full access the plugin runs as droidtop does, outside the sandbox: it can read and change droidtop's files and use the network without droidtop's checks.")
            },
            source = label,
        ),
        proceedLabel = if (update) "Update anyway" else "Install anyway",
        items = listOf(label),
    )

    /** D2: one plugin whose protection did not start. */
    fun protectionFailed(label: String, report: String): DangerConfirm = DangerConfirm(
        notice = Notice(
            tone = NoticeTone.Danger,
            title = "Protection didn't start",
            body = "This time it would run with full access to your files and the internet.",
            recommendation = "We recommend Cancel.",
            details = "Layer: the system-call filter (sandbox lockdown).\nReason: $report\n\"Run anyway\" applies to this run of droidtop only; next time protection is tried again.",
            source = label,
        ),
        proceedLabel = "Run anyway",
        items = listOf(label),
    )

    /** D4: several plugins whose protection did not start on this device, in one dialog. */
    fun protectionFailedAll(failures: List<Failure>): DangerConfirm = DangerConfirm(
        notice = Notice(
            tone = NoticeTone.Danger,
            title = "Protection didn't start",
            body = "${failures.size} plugins would run with full access on this device.",
            recommendation = "We recommend Cancel.",
            details = failures.joinToString("\n") { "${it.label}: the system-call filter (sandbox lockdown) did not start: ${it.report}" } +
                "\n\"Run all anyway\" applies to this run of droidtop only; next time protection is tried again.",
        ),
        proceedLabel = "Run all anyway",
        items = failures.map { it.label },
    )

    /** D3: an update whose maker is not the maker of the installed version. */
    fun differentMaker(label: String, installedFingerprint: String, updateFingerprint: String, installedOrigin: String, updateOrigin: String): DangerConfirm =
        DangerConfirm(
            notice = Notice(
                tone = NoticeTone.Danger,
                title = "Made by someone else",
                body = "This update doesn't come from the maker of the version you have.",
                recommendation = "We recommend Cancel.",
                details = "Installed version: signed by ${groupsOfFour(installedFingerprint)} ($installedOrigin)\n" +
                    "This update: signed by ${groupsOfFour(updateFingerprint)} ($updateOrigin)",
                source = label,
            ),
            proceedLabel = "Update anyway",
            items = listOf(label),
        )

    /** A fingerprint in groups of four, comparable by eye. */
    fun groupsOfFour(fingerprint: String): String = fingerprint.replace(":", "").chunked(4).joinToString(" ")

    /** One contained plugin that did not run because its protection did not start. */
    class Failure(val pluginId: String, val label: String, val report: String)

    private val allowed = ConcurrentHashMap.newKeySet<String>()
    private val _failures = MutableStateFlow<Map<String, Failure>>(emptyMap())

    /** Plugins held back this run because their protection did not start (for the D4 notice on the Plugins page). */
    val failures: StateFlow<Map<String, Failure>> = _failures.asStateFlow()

    /** Whether the person chose Run anyway for [pluginId] in this run of droidtop. */
    fun allowedThisRun(pluginId: String): Boolean = pluginId in allowed

    internal fun recordFailure(failure: Failure) = _failures.update { it + (failure.pluginId to failure) }

    internal fun loaded(pluginId: String) = _failures.update { it - pluginId }

    /** The layer's report when [loadError] says protection did not start, else null. */
    fun protectionReport(loadError: String?): String? = loadError?.takeIf { it.startsWith(PROTECTION_FAILED) }?.removePrefix(PROTECTION_FAILED)

    /** D2 for one plugin; true when the person chose Run anyway (for this run only). */
    suspend fun askRunUnprotected(pluginId: String, label: String, report: String): Boolean {
        val answer = NoticeDialogs.confirmDanger(protectionFailed(label, report))
        if (answer == ConfirmAnswer.Proceed) allowed += pluginId
        return answer == ConfirmAnswer.Proceed
    }

    /** D4 for every plugin held back; true when the person chose Run all anyway (for this run only). */
    suspend fun askRunAllUnprotected(): Boolean {
        val list = _failures.value.values.toList()
        if (list.isEmpty()) return false
        val answer = if (list.size == 1) {
            NoticeDialogs.confirmDanger(protectionFailed(list.single().label, list.single().report))
        } else {
            NoticeDialogs.confirmDanger(protectionFailedAll(list))
        }
        if (answer != ConfirmAnswer.Proceed) return false
        list.forEach { allowed += it.pluginId }
        _failures.value = emptyMap()
        return true
    }

    /** D3 for a pending update from a different maker; true when the person chose Update anyway. */
    suspend fun askDifferentMaker(record: PluginRecord): Boolean {
        val replaced = record.replacedKeySha256 ?: return true
        val confirm = differentMaker(record.manifest.label, replaced, record.approvedKeySha256, record.replacedOrigin ?: record.manifest.origin, record.manifest.origin)
        return NoticeDialogs.confirmDanger(confirm) == ConfirmAnswer.Proceed
    }

    /** D1, asked by every place that would grant full access. */
    suspend fun askFullAccess(record: PluginRecord, update: Boolean): Boolean {
        val ids = record.manifest.v2.permissions.map { it.id }.ifEmpty { listOf(PluginTiers.FULL_TRUST) }
        return NoticeDialogs.confirmDanger(fullAccess(record.manifest.label, record.manifest.origin, ids, update)) == ConfirmAnswer.Proceed
    }
}
