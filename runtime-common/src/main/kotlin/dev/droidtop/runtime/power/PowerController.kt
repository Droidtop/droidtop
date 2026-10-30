package dev.droidtop.runtime.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dev.droidtop.runtime.tasks.TaskManager

/**
 * The person's power settings (docs/SPEC.md "Power policy", Droidtop/tracker#841 slice 3b): Auto power (off until
 * chosen, owner Q3), each game's pick, and their own heat limit. Disk: IO only.
 */
object PowerPrefs {
    private const val PREFS = "power_policy"
    private const val KEY_AUTO = "auto"
    private const val KEY_CAP = "personal_cap"
    private const val GAME = "game."

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun auto(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, false)

    fun setAuto(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
    }

    /** [packageName]'s pick: a level, or Auto (the default), which leaves it to the rules. */
    fun gamePick(context: Context, packageName: String): PowerChoice =
        prefs(context).getString(GAME + packageName, null)
            ?.let { name -> PowerLevel.entries.firstOrNull { it.name == name } }
            ?.let { PowerChoice.Fixed(it) } ?: PowerChoice.Auto

    fun setGamePick(context: Context, packageName: String, choice: PowerChoice) {
        prefs(context).edit().apply {
            if (choice is PowerChoice.Fixed) putString(GAME + packageName, choice.level.name) else remove(GAME + packageName)
        }.apply()
    }

    /** Every game pick, as the resolver's rules. */
    fun rules(context: Context): List<PowerRule> = prefs(context).all.mapNotNull { (key, value) ->
        if (!key.startsWith(GAME)) return@mapNotNull null
        val level = PowerLevel.entries.firstOrNull { it.name == value } ?: return@mapNotNull null
        PowerRule(PowerScope.GAME, key.removePrefix(GAME), PowerChoice.Fixed(level))
    }

    fun personalCap(context: Context): PowerLevel? =
        prefs(context).getString(KEY_CAP, null)?.let { name -> PowerLevel.entries.firstOrNull { it.name == name } }
}

/**
 * Puts the policy into effect through the standard layer (docs/SPEC.md "Power policy", slice 3b). A game droidtop
 * launches gets the resolved level when Auto power is on or the person picked one for it; droidtop's own screen
 * coming back puts every changed game back ([atRest]). Blocking: IO only.
 */
object PowerController {
    @Volatile private var app: Context? = null

    /** The Application, once, so catalog rows built without a Context can read the settings. */
    fun install(context: Context) {
        app = context.applicationContext
    }

    val context: Context? get() = app

    /** The decision for [packageName] now, from the settings, heat and the battery. */
    fun decide(context: Context, packageName: String): PowerDecision =
        PowerResolver.resolve(
            PowerInputs(
                target = PowerTarget(game = packageName),
                rules = PowerPrefs.rules(context),
                heat = heat(context),
                personalCap = PowerPrefs.personalCap(context),
                batteryPercent = battery(context)?.first,
                charging = battery(context)?.second ?: false,
                screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true,
            ),
        )

    /** Applies the policy to [packageName]: nothing unless Auto power is on or the person picked a level for it. */
    fun applyFor(context: Context, packageName: String): StandardPowerLayer.Outcome? {
        if (!PowerPrefs.auto(context) && PowerPrefs.gamePick(context, packageName) == PowerChoice.Auto) return null
        val decision = decide(context, packageName)
        return StandardPowerLayer.apply(context, TaskManager.shell, decision.effective, packageName)
    }

    /** droidtop's own screen is in front again: every game it changed goes back to what it had. */
    fun atRest(context: Context) {
        if (PowerJournal.entries(context).isEmpty()) return
        StandardPowerLayer.restoreAll(context, TaskManager.shell)
    }

    /** Android's heat reading, and the battery's temperature as the fallback (the RP5's thermal HAL is not ready). */
    internal fun heat(context: Context): HeatReading {
        val power = context.getSystemService(PowerManager::class.java)
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus else null
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            power?.getThermalHeadroom(HEADROOM_FORECAST_S)?.takeUnless { it.isNaN() }
        } else {
            null
        }
        val tenths = batteryIntent(context)?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return HeatReading(headroom, status, tenths?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f })
    }

    private fun battery(context: Context): Pair<Int, Boolean>? {
        val intent = batteryIntent(context) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return level * 100 / scale to plugged
    }

    private fun batteryIntent(context: Context): Intent? =
        runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()

    private const val HEADROOM_FORECAST_S = 10
}
