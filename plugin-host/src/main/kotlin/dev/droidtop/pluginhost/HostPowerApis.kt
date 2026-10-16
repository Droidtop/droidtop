package dev.droidtop.pluginhost

import org.json.JSONObject

/** The battery as a plugin reads it (D7): [level] 0 to 100, or null on a device without one. */
data class PowerState(val level: Int?, val charging: Boolean, val saver: Boolean)

/**
 * D7 (docs/plugin-api.md 3, `power.state`, Droidtop/tracker#553): the battery level, whether it charges and whether
 * Battery Saver is on, so a plugin defers heavy work (a sync engine waits for the charger) without reading `/sys`
 * itself. Under `power.state` (normal).
 */
internal object HostPowerApis {
    const val PERMISSION = "power.state"

    val ops: List<HostOp> = listOf(
        HostOp("power", "get_state", permission = PERMISSION) { env, _, _ -> toJson(env.powerState()) },
    )

    /** `{level, charging, saver}`; `level` is null without a battery. Pure. */
    fun toJson(state: PowerState): JSONObject = JSONObject()
        .put("level", state.level ?: JSONObject.NULL)
        .put("charging", state.charging)
        .put("saver", state.saver)

    /** The level from the battery broadcast's `level` and `scale`: 0 to 100, or null when it reports none. Pure. */
    fun level(level: Int, scale: Int): Int? = if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
}
