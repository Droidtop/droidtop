package dev.droidtop.pluginhost

/** docs/plugin-api.md 5.3: where a plugin's code runs, and so what it can reach without asking droidtop. */
enum class PluginTier {
    /** An isolated process of its own: no permissions, no network, no files. Everything goes through the broker. */
    CONTAINED,

    /** A process of its own under droidtop's UID: anything droidtop can do, beyond the broker's sight. */
    FULL_TRUST,
}
