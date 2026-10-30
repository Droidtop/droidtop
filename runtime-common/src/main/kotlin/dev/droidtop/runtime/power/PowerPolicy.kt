package dev.droidtop.runtime.power

/**
 * The automatic power policy's core (docs/SPEC.md "Power policy", Droidtop/tracker#841 slice 2): what level to ask
 * for, from the person's rules, the device's heat and the battery. Pure: nothing here applies anything; the adapters
 * of slice 3 on do, with what [PowerResolver] and [PowerGovernor] decide.
 */
enum class PowerLevel { SAVER, STANDARD, PERFORMANCE, MAX }

/** What a rule or a fixed pick says: a level, or [Auto], which means "keep looking down the rules". */
sealed interface PowerChoice {
    data object Auto : PowerChoice
    data class Fixed(val level: PowerLevel) : PowerChoice
}

/** Where a rule applies, most specific first; the resolver takes the first rule that names a level. */
enum class PowerScope { GAME, CORE, EMULATOR, PLATFORM }

/** One rule: [scope] and [key] (a library entry id, a core or engine id, an emulator package, a platform id). */
data class PowerRule(val scope: PowerScope, val key: String, val choice: PowerChoice)

/** What is in front, as the rules see it; null fields don't apply (Home has none of them). */
data class PowerTarget(
    val game: String? = null,
    val core: String? = null,
    val emulator: String? = null,
    val platform: String? = null,
) {
    val playing: Boolean get() = game != null || core != null || emulator != null || platform != null

    fun keyFor(scope: PowerScope): String? = when (scope) {
        PowerScope.GAME -> game
        PowerScope.CORE -> core
        PowerScope.EMULATOR -> emulator
        PowerScope.PLATFORM -> platform
    }
}

/**
 * The heat reading the cap uses, from whichever source answers (SPEC "Power policy", heat): Android's headroom and
 * status where the thermal HAL is ready, else measured temperatures. All null: no reading at all.
 */
data class HeatReading(
    /** `PowerManager.getThermalHeadroom`, 1.0 being the severe threshold; null when Android gives none (NaN). */
    val headroom: Float? = null,
    /** `PowerManager.getCurrentThermalStatus` (0 none to 6 shutdown); null when not read. */
    val status: Int? = null,
    /** Battery temperature in degrees C. */
    val batteryC: Float? = null,
    /** How fast the hottest measured temperature rose over the last two minutes, degrees C a minute. */
    val risePerMinute: Float? = null,
) {
    val known: Boolean get() = headroom != null || (status != null && status > 0) || batteryC != null
}

/** The battery-temperature thresholds of the measured source; a device row can give its own (slice 5). */
data class HeatThresholds(val noMaxC: Float = 40f, val oneDownC: Float = 42f, val standardC: Float = 45f, val risePerMinute: Float = 1f)

/** Everything the resolver reads at one moment. */
data class PowerInputs(
    val target: PowerTarget,
    val rules: List<PowerRule>,
    /** Level at rest (Home, Library, an idle Desktop): Saver unless the person chose otherwise. */
    val atRest: PowerLevel = PowerLevel.SAVER,
    /** Level for a game no rule names: Standard, so no game runs worse than without the policy. */
    val playingDefault: PowerLevel = PowerLevel.STANDARD,
    /** What the Desktop's load asks for (slice 6); null while no Desktop session runs or it is quiet. */
    val desktopLoad: PowerLevel? = null,
    /** Whether the device has a level above Performance at all. */
    val hasMax: Boolean = false,
    val heat: HeatReading = HeatReading(),
    val thresholds: HeatThresholds = HeatThresholds(),
    /** The person's own heat cap: never above droidtop's, only lower (owner, 2026-10-30, Q4). Null: none. */
    val personalCap: PowerLevel? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val screenOn: Boolean = true,
)

/**
 * What to ask for: [requested] by the rules, [effective] after the caps, and [why] in plain words. [heatCapped] says
 * heat set [effective], which [PowerGovernor] applies at once.
 */
data class PowerDecision(val requested: PowerLevel, val effective: PowerLevel, val why: String, val heatCapped: Boolean = false)

/** The policy as one pure function. */
object PowerResolver {
    const val LOW_BATTERY_PERCENT = 15

    fun resolve(inputs: PowerInputs): PowerDecision {
        val (requested, source) = requested(inputs)
        var effective = if (requested == PowerLevel.MAX && !inputs.hasMax) PowerLevel.PERFORMANCE else requested
        var why = source
        fun capAt(level: PowerLevel, reason: String): Boolean {
            if (effective <= level) return false
            effective = level
            why = reason
            return true
        }
        // The heat cap applies to every level, the person's own pick included (owner, Q4).
        val heatCapped = heatCap(inputs.heat, inputs.thresholds, requested)?.let { (level, reason) -> capAt(level, reason) } ?: false
        inputs.personalCap?.let { capAt(it, "Your heat limit") }
        if (!inputs.charging && inputs.batteryPercent != null && inputs.batteryPercent <= LOW_BATTERY_PERCENT) {
            capAt(PowerLevel.STANDARD, "Battery under $LOW_BATTERY_PERCENT percent")
        }
        if (!inputs.screenOn && inputs.desktopLoad == null) capAt(PowerLevel.SAVER, "Screen off")
        return PowerDecision(requested, effective, why, heatCapped)
    }

    /** The rules' answer and its reason: the most specific rule that names a level, else the load, else a default. */
    internal fun requested(inputs: PowerInputs): Pair<PowerLevel, String> {
        val target = inputs.target
        for (scope in PowerScope.entries) {
            val key = target.keyFor(scope) ?: continue
            val rule = inputs.rules.firstOrNull { it.scope == scope && it.key == key } ?: continue
            val choice = rule.choice
            if (choice is PowerChoice.Fixed) return choice.level to ruleName(scope)
        }
        val load = inputs.desktopLoad
        if (load != null && !target.playing) return load to "Desktop is busy"
        return if (target.playing) inputs.playingDefault to "No rule for this game" else inputs.atRest to "At rest"
    }

    private fun ruleName(scope: PowerScope) = when (scope) {
        PowerScope.GAME -> "This game's setting"
        PowerScope.CORE -> "Core rule"
        PowerScope.EMULATOR -> "Emulator rule"
        PowerScope.PLATFORM -> "System rule"
    }

    /**
     * The highest level heat allows, and why; null for no cap. Android's headroom where it answers (the table in
     * SPEC), else Android's status, else the battery temperature and its rise. With no reading at all nothing
     * above Standard is asked for: no data, no boost.
     */
    internal fun heatCap(heat: HeatReading, thresholds: HeatThresholds, requested: PowerLevel): Pair<PowerLevel, String>? {
        val status = heat.status ?: 0
        if (status >= SEVERE) return PowerLevel.SAVER to "Heat is severe"
        if (status >= MODERATE) return PowerLevel.STANDARD to "Heat is high"
        val headroom = heat.headroom
        if (headroom != null) {
            return when {
                headroom >= 1f -> PowerLevel.STANDARD to "Heat is high"
                headroom >= 0.85f || status == LIGHT -> oneDown(requested) to "Getting hot"
                headroom >= 0.70f -> PowerLevel.PERFORMANCE to "Warm"
                else -> null
            }
        }
        if (status == LIGHT) return oneDown(requested) to "Getting hot"
        val battery = heat.batteryC ?: return if (heat.known) null else PowerLevel.STANDARD to "No heat reading"
        return when {
            battery >= thresholds.standardC -> PowerLevel.STANDARD to "Heat is high"
            battery >= thresholds.oneDownC -> oneDown(requested) to "Getting hot"
            (heat.risePerMinute ?: 0f) >= thresholds.risePerMinute && requested >= PowerLevel.PERFORMANCE ->
                oneDown(requested) to "Heating up fast"
            battery >= thresholds.noMaxC -> PowerLevel.PERFORMANCE to "Warm"
            else -> null
        }
    }

    private fun oneDown(level: PowerLevel) = PowerLevel.entries[maxOf(0, level.ordinal - 1)]

    // PowerManager.THERMAL_STATUS_*: none 0, light 1, moderate 2, severe 3, critical 4 and up.
    private const val LIGHT = 1
    private const val MODERATE = 2
    private const val SEVERE = 3
}

/**
 * How fast the applied level follows the decision (SPEC "Power policy", dwell): a rise waits [UP_MS] of asking, a
 * fall [DOWN_MS], so a shader-compile spike or a page load does not flip it. Heat is the exception: a cap from heat
 * applies at once. Pure; the caller keeps the [State].
 */
object PowerGovernor {
    const val UP_MS = 8_000L
    const val DOWN_MS = 30_000L

    /** [applied] is what is in force; [wanted] what the decision has asked since [wantedSince]. */
    data class State(val applied: PowerLevel, val wanted: PowerLevel, val wantedSince: Long)

    fun step(state: State?, decision: PowerDecision, nowMs: Long): State {
        val target = decision.effective
        if (state == null) return State(target, target, nowMs)
        val since = if (target == state.wanted) state.wantedSince else nowMs
        val waited = nowMs - since
        val applied = when {
            target == state.applied -> target
            target < state.applied && decision.heatCapped -> target
            target > state.applied && waited >= UP_MS -> target
            target < state.applied && waited >= DOWN_MS -> target
            else -> state.applied
        }
        return State(applied, target, since)
    }
}
