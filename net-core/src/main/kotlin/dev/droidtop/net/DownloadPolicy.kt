package dev.droidtop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which networks a download may use (docs/SPEC.md, "Download rules"). */
enum class NetworkRule(val key: String, val label: String) {
    WIFI_ONLY("wifi", "Wi-Fi only"),
    SMALL_ON_MOBILE("small", "Wi-Fi, and mobile data for small downloads"),
    ANY("any", "Any connection");

    companion object {
        fun fromKey(key: String?): NetworkRule = values().firstOrNull { it.key == key } ?: SMALL_ON_MOBILE
    }
}

/** A game's own say on keeping itself up to date; [FOLLOW] reads the default. */
enum class AutoUpdate(val key: String, val label: String) {
    FOLLOW("follow", "Follow the default"),
    ON("on", "On"),
    OFF("off", "Off");

    companion object {
        fun fromKey(key: String?): AutoUpdate = values().firstOrNull { it.key == key } ?: FOLLOW
    }
}

/** What the person chose in Settings, Download rules. Defaults are what an unset install does. */
data class DownloadSettings(
    val network: NetworkRule = NetworkRule.SMALL_ON_MOBILE,
    /** Automatic downloads run only between [windowStartMinute] and [windowEndMinute] (minutes after midnight) when on. */
    val windowEnabled: Boolean = false,
    val windowStartMinute: Int = 2 * 60,
    val windowEndMinute: Int = 6 * 60,
    /** Whether automatic downloads may run while a game is running. */
    val whilePlaying: Boolean = false,
    /** The default for a game that follows it: keep installed store games up to date by themselves. */
    val autoUpdateDefault: Boolean = false,
)

enum class NetworkClass { OFFLINE, UNMETERED, METERED }

/** What the policy needs to know about one job. [sizeBytes] is 0 when nobody knows. */
data class JobFacts(val sizeBytes: Long = 0, val automatic: Boolean = false)

data class Conditions(val network: NetworkClass, val minuteOfDay: Int, val gameRunning: Boolean)

sealed interface Verdict {
    object Go : Verdict

    /** Not now; [reason] is the line the job shows ("Waiting for Wi-Fi"). */
    data class Hold(val reason: String) : Verdict
}

/**
 * The one download policy, read by every download job (docs/SPEC.md, "Download rules"): single-file downloads, store
 * installs and updates, and the jobs droidtop runs for itself. [decide] is the whole rule set and is pure; the object
 * keeps the person's choices in preferences (read once with [load], off the main thread) and says what network
 * the device is on ([networkNow]). The network rule governs every download; the update window and "while playing"
 * govern only automatic ones (a download the person did not just ask for).
 */
object DownloadPolicy {
    private const val PREFS = "download_policy"
    private const val KEY_NETWORK = "network"
    private const val KEY_WINDOW_ON = "window_on"
    private const val KEY_WINDOW_START = "window_start"
    private const val KEY_WINDOW_END = "window_end"
    private const val KEY_WHILE_PLAYING = "while_playing"
    private const val KEY_AUTO_DEFAULT = "auto_default"
    private const val GAME_PREFIX = "auto."

    /** Mobile data is allowed under this size when the rule is [NetworkRule.SMALL_ON_MOBILE]. */
    const val SMALL_BYTES = 50L * 1024 * 1024

    private val settingsFlow = MutableStateFlow(DownloadSettings())
    private val gamesFlow = MutableStateFlow<Map<String, AutoUpdate>>(emptyMap())

    val settings: StateFlow<DownloadSettings> = settingsFlow

    /** Per-game choices by store key ("gog:123"); a game not listed follows the default. */
    val games: StateFlow<Map<String, AutoUpdate>> = gamesFlow

    /** Reads the stored choices. Touches the preferences file: not for the main thread. */
    fun load(context: Context) {
        val p = prefs(context)
        val defaults = DownloadSettings()
        settingsFlow.value = DownloadSettings(
            network = NetworkRule.fromKey(p.getString(KEY_NETWORK, null)),
            windowEnabled = p.getBoolean(KEY_WINDOW_ON, defaults.windowEnabled),
            windowStartMinute = p.getInt(KEY_WINDOW_START, defaults.windowStartMinute),
            windowEndMinute = p.getInt(KEY_WINDOW_END, defaults.windowEndMinute),
            whilePlaying = p.getBoolean(KEY_WHILE_PLAYING, defaults.whilePlaying),
            autoUpdateDefault = p.getBoolean(KEY_AUTO_DEFAULT, defaults.autoUpdateDefault),
        )
        gamesFlow.value = p.all.entries
            .filter { it.key.startsWith(GAME_PREFIX) }
            .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix(GAME_PREFIX) to AutoUpdate.fromKey(it) } }
            .toMap()
    }

    /** Applies [change] to the settings at once and writes them in the background of the caller's thread choice (apply). */
    fun update(context: Context, change: (DownloadSettings) -> DownloadSettings) {
        val next = change(settingsFlow.value)
        settingsFlow.value = next
        prefs(context).edit()
            .putString(KEY_NETWORK, next.network.key)
            .putBoolean(KEY_WINDOW_ON, next.windowEnabled)
            .putInt(KEY_WINDOW_START, next.windowStartMinute)
            .putInt(KEY_WINDOW_END, next.windowEndMinute)
            .putBoolean(KEY_WHILE_PLAYING, next.whilePlaying)
            .putBoolean(KEY_AUTO_DEFAULT, next.autoUpdateDefault)
            .apply()
    }

    fun setGame(context: Context, key: String, choice: AutoUpdate) {
        gamesFlow.value = if (choice == AutoUpdate.FOLLOW) gamesFlow.value - key else gamesFlow.value + (key to choice)
        val edit = prefs(context).edit()
        if (choice == AutoUpdate.FOLLOW) edit.remove(GAME_PREFIX + key) else edit.putString(GAME_PREFIX + key, choice.key)
        edit.apply()
    }

    /** Whether the game [key] keeps itself up to date: its own choice, else the default. */
    fun keepsUpToDate(key: String): Boolean = keepsUpToDate(settingsFlow.value, gamesFlow.value[key] ?: AutoUpdate.FOLLOW)

    fun keepsUpToDate(settings: DownloadSettings, choice: AutoUpdate): Boolean = when (choice) {
        AutoUpdate.ON -> true
        AutoUpdate.OFF -> false
        AutoUpdate.FOLLOW -> settings.autoUpdateDefault
    }

    /** The network the device is on now. Cheap system calls; safe off the main thread. */
    fun networkNow(context: Context): NetworkClass {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkClass.OFFLINE
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return NetworkClass.OFFLINE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkClass.OFFLINE
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) NetworkClass.UNMETERED else NetworkClass.METERED
    }

    // ---- The rules: pure, unit tested --------------------------------------------------------

    /** Whether a download of [sizeBytes] may use a metered network at all under [settings]. */
    fun allowsMetered(settings: DownloadSettings, sizeBytes: Long): Boolean = when (settings.network) {
        NetworkRule.ANY -> true
        NetworkRule.WIFI_ONLY -> false
        NetworkRule.SMALL_ON_MOBILE -> sizeBytes in 1..SMALL_BYTES
    }

    fun decide(settings: DownloadSettings, conditions: Conditions, job: JobFacts): Verdict {
        when (conditions.network) {
            NetworkClass.OFFLINE -> return Verdict.Hold("Waiting for a network")
            NetworkClass.METERED -> if (!allowsMetered(settings, job.sizeBytes)) return Verdict.Hold("Waiting for Wi-Fi")
            NetworkClass.UNMETERED -> Unit
        }
        if (job.automatic) {
            if (settings.windowEnabled && !inWindow(conditions.minuteOfDay, settings.windowStartMinute, settings.windowEndMinute)) {
                return Verdict.Hold("Waiting for ${clock(settings.windowStartMinute)}")
            }
            if (!settings.whilePlaying && conditions.gameRunning) return Verdict.Hold("Waiting until you stop playing")
        }
        return Verdict.Go
    }

    /** Whether [minute] is inside the window from [start] to [end], which may run past midnight; equal ends are always open. */
    fun inWindow(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    fun clock(minuteOfDay: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, minuteOfDay / 60 % 24, minuteOfDay % 60)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
