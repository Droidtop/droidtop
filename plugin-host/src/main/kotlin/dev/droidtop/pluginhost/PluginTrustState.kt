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

    /** The user approved this exact archive digest. Runs, unless [PluginRecord.enabled] is false or [PluginRecord.rootApproved] is required and missing. */
    APPROVED,

    /** The user explicitly declined it. Never re-offered for the same digest; a new digest (an update) is a new approval. */
    DENIED,
}
