package dev.droidtop.library.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The controls pinned to the companion's Home (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414): stored by
 * catalog id (later also a plugin tile id, an app or a shortcut), surface-neutral, so any surface that draws catalog
 * items can draw them. Read once with [load] off the main thread, then kept in [pins].
 */
object PinnedControls {
    private const val PREFS = "companion_pins"
    private const val KEY = "pins"
    private const val KEY_PROVIDER_LINE = "provider_line_dismissed"

    /** Volume, brightness, Wi-Fi (the network row), Do Not Disturb and microphone mute. */
    val DEFAULT: List<String> = listOf(
        GamingSettingsCatalog.ID_SYSTEM_VOLUME,
        GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS,
        GamingSettingsCatalog.ID_SYSTEM_NETWORK,
        GamingSettingsCatalog.ID_SYSTEM_DND,
        GamingSettingsCatalog.ID_SYSTEM_MIC_MUTE,
    )

    /**
     * A pin's other names: a pinned control whose catalog row is the grant row while the grant is missing (brightness
     * before Modify system settings, Do Not Disturb before its access) shows that row, so the pin leads to the grant.
     */
    val STAND_INS: Map<String, String> = mapOf(
        GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS to GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS_GRANT,
        GamingSettingsCatalog.ID_SYSTEM_DND to GamingSettingsCatalog.ID_SYSTEM_DND_GRANT,
    )

    /**
     * Live stat tiles: not catalog items but readings, pinnable like one. The battery's heat and draw come from the
     * battery broadcast Home already follows; the fastest core's clock needs the performance sampler, which runs only
     * while a pin needs it and Home is on screen ([needsSampler]).
     */
    const val STAT_PREFIX = "stat:"
    const val STAT_TEMPERATURE = "stat:temperature"
    const val STAT_CLOCK = "stat:clock"
    const val STAT_WATTS = "stat:watts"
    val STATS: List<Pair<String, String>> = listOf(
        STAT_TEMPERATURE to "Battery temperature",
        STAT_CLOCK to "Fastest core clock",
        STAT_WATTS to "Battery draw",
    )

    /** Whether Home must run the performance sampler for its pins. */
    fun needsSampler(pins: List<String>): Boolean = STAT_CLOCK in pins

    data class State(val pins: List<String> = DEFAULT, val providerLineDismissed: Boolean = false)

    private val state = MutableStateFlow(State())
    val pins: StateFlow<State> = state

    fun load(context: Context) {
        val prefs = prefs(context)
        state.value = State(
            pins = prefs.getString(KEY, null)?.let(::decode) ?: DEFAULT,
            providerLineDismissed = prefs.getBoolean(KEY_PROVIDER_LINE, false),
        )
    }

    fun decode(stored: String): List<String> = stored.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun setPinned(context: Context, id: String, pinned: Boolean) {
        val now = state.value.pins
        val next = if (pinned) (now - id) + id else now - id
        state.value = state.value.copy(pins = next)
        prefs(context).edit().putString(KEY, next.joinToString(",")).apply()
    }

    fun dismissProviderLine(context: Context) {
        state.value = state.value.copy(providerLineDismissed = true)
        prefs(context).edit().putBoolean(KEY_PROVIDER_LINE, true).apply()
    }

    fun reset(context: Context) {
        state.value = State()
        prefs(context).edit().clear().apply()
    }

    /**
     * The pins Home draws now, in pin order, as the catalog ids to look up: only what [mode] lets a person pin
     * ([ControlAccess.pinnable]: volume and brightness in Kid and Kiosk), each as its stand-in where only that is in
     * the catalog, and only what the catalog has right now ([available]): a control that needs the helper app is not
     * in the catalog without it, so its pin is not drawn either. Pure.
     */
    fun visible(pins: List<String>, mode: UiMode, available: Set<String>): List<String> =
        pins.filter { ControlAccess.pinnable(mode, it) }.mapNotNull { id ->
            when {
                id in available -> id
                id.startsWith(STAT_PREFIX) && STATS.any { it.first == id } -> id
                STAND_INS[id]?.let { it in available } == true -> STAND_INS.getValue(id)
                else -> null
            }
        }

    /**
     * Whether Home ends with "More controls need the helper app. Set up": only without a provider, only where the
     * mode shows the provider line (not Kid or Kiosk), and not once the person dismissed it for good.
     */
    fun showsProviderLine(hasProvider: Boolean, mode: UiMode, dismissed: Boolean): Boolean =
        !hasProvider && !dismissed && ControlAccess.shows(mode, ControlRow.PROVIDER_LINE)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The status line's privacy and audio indicators, each a few spoken words (docs/SPEC.md "The companion's tabs"):
 * "Mic muted", "VPN", and "Mic in use" or "Camera in use" while any app holds them. Pure.
 */
object StatusIndicators {
    fun lines(micMuted: Boolean, vpn: Boolean, activeRecordings: Int, camerasInUse: Int): List<String> = buildList {
        if (micMuted) add("Mic muted")
        if (vpn) add("VPN")
        if (activeRecordings > 0) add("Mic in use")
        if (camerasInUse > 0) add("Camera in use")
    }
}
