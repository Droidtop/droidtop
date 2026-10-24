package dev.droidtop.library.integrations

import dev.droidtop.library.settings.StatusMark
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginTiers
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.RequiredApi
import dev.droidtop.pluginhost.RuntimeNeed

/**
 * A plugin's state, the one answer every place that lists plugins gives (Droidtop/tracker#512, #644): a word or two
 * for its card and page ("Needs approval", "Running") and the status glyph for a row that has room for one only
 * ([mark]: none when it runs as it should). Pure.
 */
object PluginStatus {
    data class Status(val word: String, val mark: StatusMark?)

    fun of(
        record: PluginRecord,
        grants: PluginGrants.Snapshot,
        runtimeNeed: RuntimeNeed?,
        updateAvailable: Boolean,
        waiting: List<RequiredApi>?,
        working: Boolean = false,
    ): Status {
        val word = when {
            record.trust == PluginTrustState.PENDING -> "Needs approval"
            record.trust == PluginTrustState.DENIED -> "Denied"
            // A plugin that stopped has a way forward on the list too: a newer version to install, or its page's "Try again".
            record.disabledReason != null -> if (updateAvailable) "Needs an update" else "Stopped"
            // Written before it could ask for the access it needs (PluginTiers.refusal): only a newer version can run.
            record.trust == PluginTrustState.APPROVED && PluginTiers.refusal(record, grants) != null &&
                !PluginTiers.declaresFullTrust(record.manifest) -> "Needs an update"
            record.trust == PluginTrustState.APPROVED && !record.enabled -> "Off"
            // Approved but its runtime is not on the device: it cannot run, so it is never "Running".
            runtimeNeed != null -> "Needs setup"
            // Waiting is not disabled: the plugin resumes by itself when a provider returns (docs/plugin-api.md 2.3).
            waiting != null -> "Waiting"
            record.trust == PluginTrustState.APPROVED && grants.wantsNewAccess -> "Wants new access"
            record.trust == PluginTrustState.APPROVED && updateAvailable -> "Update available"
            record.trust == PluginTrustState.APPROVED -> "Running"
            else -> "Unknown"
        }
        return Status(word, markOf(word, working))
    }

    /** The glyph a state word takes: the person's attention outranks work under way, and a plugin that runs well has none. Pure. */
    internal fun markOf(word: String, working: Boolean): StatusMark? = when (word) {
        "Denied", "Stopped" -> StatusMark.ERROR
        "Needs approval", "Needs an update", "Needs setup", "Wants new access", "Update available" -> StatusMark.ATTENTION
        else -> if (working) StatusMark.WORKING else null
    }
}
