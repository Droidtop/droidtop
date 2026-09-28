package dev.droidtop.pluginhost

/**
 * Where one installed plugin sits in the approval flow (docs/SPEC.md
 * 12a's trust-boundary checklist, point 4). Mirrors enginehost's own
 * bundle trust states so the two projects' users read the same three
 * words for the same idea, even though the mechanisms are now separate
 * (12a withdrew routing plugins through enginehost).
 */
enum class PluginTrustState {
    /** Installed and validated, waiting on the approval screen. Never run. */
    PENDING,

    /** The user approved this plugin under the key recorded in [PluginRecord.approvedKeySha256] -- an update whose signature verifies against that same pinned key carries the approval over to its new digest (docs/SPEC.md 12a, "Trust over updates"), a different key or a first install does not. Runs, unless [PluginRecord.enabled] is false or [PluginRecord.rootApproved] is required and missing. */
    APPROVED,

    /** The user explicitly declined it. The state never carries over: an update (a new digest) re-enters at PENDING and needs a fresh approval, whatever key signed it. */
    DENIED,
}
