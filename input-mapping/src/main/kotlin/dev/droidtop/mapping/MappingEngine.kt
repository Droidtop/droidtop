package dev.droidtop.mapping

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Plays a [ControllerLayout] (Droidtop/tracker#618 C3; docs/SPEC.md 5b "Controller layouts"): a pad's presses and
 * sticks in, what the game receives out. Pure and single-threaded; time is passed in, so every rule is tested
 * without a clock.
 *
 * A physical input is named as Steam names it, a source and an input of it: (`button_diamond`, `button_a`),
 * (`dpad`, `dpad_north`), (`switch`, `left_bumper`), (`joystick`, `click`), (`left_trigger`, `click`). The host
 * translates its pad's events into those names; a stick's deflection goes to [stick].
 *
 * What it does:
 * - the active preset ([presetId]) says which group is on which source; a `controller_action CHANGE_PRESET <id>`
 *   binding switches preset (Steam's ids are 1-based: preset id + 1), releasing what the old one held;
 * - a `mode_shift <source> <group>` binding, held, puts that group on the source instead, as Steam's mode shift;
 * - activators: `Full_Press` is held while the input is; `Start_Press` taps on press and `Release_Press` on release;
 *   `Long_Press` is held from [Activator.long_press_time][DEFAULT_LONG_PRESS_MS] after the press until release. An
 *   input with a Long_Press makes its Full_Press wait: released sooner, the Full_Press is a tap;
 * - `Double_Press` is held from a second press that comes within its `double_tap_time` ([DEFAULT_DOUBLE_TAP_MS]) of
 *   the first one's release, until that second release. An input with a Double_Press makes its Full_Press wait too:
 *   when no second press comes in time, the Full_Press taps then (at the [tick] or event that passes the window);
 * - a stick in `dpad` mode presses `dpad_north` and the others past [STICK_DPAD_THRESHOLD], 8-way;
 * - a stick in `joystick_mouse` mode moves the pointer at each [tick], by today's controller mouse curve (a push under
 *   [MOUSE_DEADZONE] is rest, then the square of the push up to [MOUSE_FULL_WIDTHS_PER_S] screen widths a second),
 *   scaled by the group's `sensitivity` (percent, 100 by default);
 * - a stick in `scrollwheel` mode turns the mouse wheel, faster the further it is pushed ([scrollRate]), scaled by
 *   the group's `sensitivity`; the stick's own click held as a mode shift onto such a group is mouse mode's wheel;
 * - a stick in `joystick_move` mode is passed through as the pad's stick, its group's `output_joystick` choosing which
 *   (0 left, 1 right; by default the stick it came from).
 * Not yet: chords, turbo, joystick camera, radial and touch menus, and action layers.
 */
class MappingEngine(private val layout: ControllerLayout, presetId: Int? = null) {

    /** What the game receives. Keys and buttons are Steam's names (`W`, `SPACE`, `LEFT`, `A`); the sink maps them. */
    sealed class Output {
        data class KeyDown(val key: String) : Output()
        data class KeyUp(val key: String) : Output()
        data class MouseDown(val button: String) : Output()
        data class MouseUp(val button: String) : Output()
        data class Wheel(val direction: String) : Output()
        data class PadDown(val button: String) : Output()
        data class PadUp(val button: String) : Output()
        data class ActionDown(val set: String, val action: String) : Output()
        data class ActionUp(val set: String, val action: String) : Output()
        data class PresetChanged(val presetId: Int) : Output()

        /** The pointer moved by this much, in screen widths (the sink knows the screen). */
        data class MouseMove(val dx: Float, val dy: Float) : Output()

        /** The pad's [stick] ("LEFT" or "RIGHT") is now at ([x], [y]). */
        data class StickMove(val stick: String, val x: Float, val y: Float) : Output()
    }

    data class Physical(val source: String, val input: String)

    var presetId: Int = presetId ?: layout.presets.firstOrNull()?.id ?: 0
        private set

    /** Held mode shifts: for each holder, every source it shifts and the group it put there (one button may shift several). */
    private val shifts = LinkedHashMap<Physical, MutableList<Pair<String, Int>>>()

    /** Each held physical input: what it pressed, and how it began. */
    private class Held(val since: Long, val activators: List<ControllerLayout.Activator>) {
        val down = ArrayList<Binding>()
        var longFired = false
        var double = false
    }

    /** Inputs released once with a Double_Press waiting: when, and the Full_Press that taps if no second press comes. */
    private class Waiting(val releasedAt: Long, val window: Long, val full: List<ControllerLayout.Activator>)

    private val waiting = LinkedHashMap<Physical, Waiting>()

    private val held = LinkedHashMap<Physical, Held>()

    /** The 8-way directions a stick in dpad mode holds now, by source. */
    private val stickDirs = HashMap<String, Set<String>>()

    /** Each stick's last deflection, by source, for the pointer it moves between ticks. */
    private val deflection = HashMap<String, Pair<Float, Float>>()

    /** A scrolling stick's wheel travel below one step, carried to the next tick, by source. */
    private val scrollCarry = HashMap<String, Float>()
    private var lastTick: Long? = null

    fun press(source: String, input: String, now: Long): List<Output> {
        val physical = Physical(source, input)
        if (physical in held) return emptyList()
        val out = ArrayList<Output>()
        // Settles any double-press window that has passed, this input's included.
        tick(now, out)
        val activators = groupOn(source)?.input(input)?.activators.orEmpty()
        val h = Held(now, activators)
        held[physical] = h
        val second = waiting.remove(physical)
        if (second != null) {
            // The second press of a double press: it is held as the Double_Press, and the first press's tap is dropped.
            h.double = true
            activators.filter { it.type.equals(DOUBLE, true) }.forEach { a -> a.bindings.forEach { begin(physical, h, it, out) } }
            return out
        }
        val waits = activators.any { it.type.equals(LONG, true) || it.type.equals(DOUBLE, true) }
        for (a in activators) {
            when {
                a.type.equals(FULL, true) && !waits -> a.bindings.forEach { begin(physical, h, it, out) }
                a.type.equals(START, true) -> a.bindings.forEach { tap(it, out) }
            }
        }
        return out
    }

    fun release(source: String, input: String, now: Long): List<Output> {
        val physical = Physical(source, input)
        if (physical !in held) return emptyList()
        val out = ArrayList<Output>()
        // A long press held past its time counts even when no tick came in between.
        tick(now, out)
        val h = held.remove(physical) ?: return out
        val full = h.activators.filter { it.type.equals(FULL, true) }
        val double = h.activators.firstOrNull { it.type.equals(DOUBLE, true) }
        when {
            h.double || h.longFired -> Unit
            double != null -> {
                val window = double.settings["double_tap_time"]?.toLongOrNull() ?: DEFAULT_DOUBLE_TAP_MS
                waiting[physical] = Waiting(now, window, full)
            }
            h.activators.any { it.type.equals(LONG, true) } -> full.forEach { a -> a.bindings.forEach { tap(it, out) } }
        }
        for (b in h.down.asReversed()) end(physical, b, out)
        h.activators.filter { it.type.equals(RELEASE, true) }.forEach { a -> a.bindings.forEach { tap(it, out) } }
        return out
    }

    /** Fires what time alone fires (a long press reaching its time). Call it each frame, or before the next event. */
    fun tick(now: Long): List<Output> = ArrayList<Output>().also { tick(now, it) }

    private fun tick(now: Long, out: MutableList<Output>) {
        val since = lastTick
        lastTick = now
        if (since != null && now > since) {
            val seconds = (now - since) / 1000f
            for ((source, xy) in deflection) {
                val group = groupOn(source) ?: continue
                if (group.mode.equals("scrollwheel", true)) {
                    val gain = (group.settings["sensitivity"]?.toFloatOrNull() ?: 100f) / 100f
                    var carry = (scrollCarry[source] ?: 0f) + scrollRate(xy.second) * gain * seconds
                    while (carry >= 1f) { out += Output.Wheel(SCROLL_DOWN); carry -= 1f }
                    while (carry <= -1f) { out += Output.Wheel(SCROLL_UP); carry += 1f }
                    scrollCarry[source] = carry
                    continue
                }
                if (!group.mode.equals("joystick_mouse", true)) continue
                val gain = (group.settings["sensitivity"]?.toFloatOrNull() ?: 100f) / 100f
                val (vx, vy) = mouseVelocity(xy.first, xy.second)
                if (vx != 0f || vy != 0f) out += Output.MouseMove(vx * gain * seconds, vy * gain * seconds)
            }
        }
        for ((physical, w) in waiting.entries.toList()) {
            if (now - w.releasedAt <= w.window) continue
            waiting.remove(physical)
            w.full.forEach { a -> a.bindings.forEach { tap(it, out) } }
        }
        for ((physical, h) in held.entries.toList()) {
            if (h.longFired || h.double) continue
            val long = h.activators.firstOrNull { it.type.equals(LONG, true) } ?: continue
            val time = long.settings["long_press_time"]?.toLongOrNull() ?: DEFAULT_LONG_PRESS_MS
            if (now - h.since >= time) {
                h.longFired = true
                long.bindings.forEach { begin(physical, h, it, out) }
            }
        }
    }

    /**
     * A stick at ([x], [y]), -1 to 1 with up negative as Android reports it. In `dpad` mode it presses and releases
     * the group's dpad inputs; in other modes it does nothing yet.
     */
    fun stick(source: String, x: Float, y: Float, now: Long): List<Output> {
        val out = ArrayList<Output>()
        // The pointer has moved by the old deflection until now.
        tick(now, out)
        deflection[source] = x to y
        val group = groupOn(source)
        if (group?.mode.equals("joystick_move", true)) {
            val output = group?.settings?.get("output_joystick")?.trim()?.toIntOrNull()
            val right = if (output != null) output == 1 else source.equals("right_joystick", true)
            out += Output.StickMove(if (right) "RIGHT" else "LEFT", x, y)
        }
        val wanted = if (group?.mode.equals("dpad", true)) directions(x, y) else emptySet()
        val before = stickDirs[source].orEmpty()
        stickDirs[source] = wanted
        for (d in before - wanted) out += release(source, d, now)
        for (d in wanted - before) out += press(source, d, now)
        return out
    }

    /** The group on [source] now: a held mode shift's, else the active preset's. */
    fun groupOn(source: String): ControllerLayout.Group? {
        shifts.values.flatten().lastOrNull { it.first.equals(source, true) }?.let { return layout.group(it.second) }
        val preset = layout.presets.firstOrNull { it.id == presetId } ?: return null
        val binding = preset.sources.firstOrNull { it.source.equals(source, true) && it.active && !it.modeShift } ?: return null
        return layout.group(binding.groupId)
    }

    private fun begin(physical: Physical, h: Held, binding: Binding, out: MutableList<Output>) {
        when (binding) {
            is Binding.KeyPress -> out += Output.KeyDown(binding.key)
            is Binding.MouseButton -> out += Output.MouseDown(binding.button)
            is Binding.XinputButton -> out += Output.PadDown(binding.button)
            is Binding.GameAction -> out += Output.ActionDown(binding.set, binding.action)
            is Binding.MouseWheel -> out += Output.Wheel(binding.direction)
            is Binding.ModeShift -> shifts.getOrPut(physical) { ArrayList() } += binding.source to binding.groupId
            is Binding.ControllerAction -> changePreset(binding, out)
            is Binding.Other -> Unit
        }
        h.down += binding
    }

    private fun end(physical: Physical, binding: Binding, out: MutableList<Output>) {
        when (binding) {
            is Binding.KeyPress -> out += Output.KeyUp(binding.key)
            is Binding.MouseButton -> out += Output.MouseUp(binding.button)
            is Binding.XinputButton -> out += Output.PadUp(binding.button)
            is Binding.GameAction -> out += Output.ActionUp(binding.set, binding.action)
            is Binding.ModeShift -> shifts.remove(physical)
            else -> Unit
        }
    }

    private fun tap(binding: Binding, out: MutableList<Output>) {
        val scratch = Held(0, emptyList())
        val tapper = Physical("", "")
        begin(tapper, scratch, binding, out)
        for (b in scratch.down.asReversed()) end(tapper, b, out)
    }

    /** `controller_action CHANGE_PRESET <n>`: everything held is let go under the old preset first. */
    private fun changePreset(binding: Binding.ControllerAction, out: MutableList<Output>) {
        if (!binding.words[0].equals("CHANGE_PRESET", true)) return
        val target = binding.words.getOrNull(1)?.toIntOrNull()?.minus(1) ?: return
        if (target == presetId || layout.presets.none { it.id == target }) return
        for ((physical, h) in held.entries.toList()) {
            for (b in h.down.asReversed()) end(physical, b, out)
            h.down.clear()
        }
        presetId = target
        out += Output.PresetChanged(target)
    }

    companion object {
        const val DEFAULT_LONG_PRESS_MS = 400L
        const val DEFAULT_DOUBLE_TAP_MS = 250L
        const val STICK_DPAD_THRESHOLD = 0.5f
        const val MOUSE_DEADZONE = 0.15f
        const val MOUSE_FULL_WIDTHS_PER_S = 1.5f

        /** Wheel steps a second at full deflection of a stick in `scrollwheel` mode. */
        const val SCROLL_STEPS_PER_S = 12f
        const val SCROLL_DOWN = "SCROLL_DOWN"
        const val SCROLL_UP = "SCROLL_UP"
        private const val FULL = "Full_Press"
        private const val LONG = "Long_Press"
        private const val DOUBLE = "Double_Press"
        private const val START = "Start_Press"
        private const val RELEASE = "Release_Press"

        /**
         * Pointer velocity in screen widths a second for a stick at ([x], [y]): the controller mouse curve
         * (docs/SPEC.md 5b, "Controller mouse mode"), so a layout's mouse feels as mouse mode does. Pure.
         */
        fun mouseVelocity(x: Float, y: Float): Pair<Float, Float> {
            val magnitude = sqrt(x * x + y * y).coerceAtMost(1f)
            if (magnitude <= MOUSE_DEADZONE) return 0f to 0f
            val scaled = (magnitude - MOUSE_DEADZONE) / (1f - MOUSE_DEADZONE)
            val speed = scaled * scaled * MOUSE_FULL_WIDTHS_PER_S
            return speed * x / magnitude to speed * y / magnitude
        }

        /**
         * Wheel steps a second for a stick pushed to [y] (down positive, scrolling down): nothing under [MOUSE_DEADZONE],
         * then in proportion up to [SCROLL_STEPS_PER_S]. Controller mouse mode's wheel (docs/SPEC.md 5b). Pure.
         */
        fun scrollRate(y: Float): Float {
            val a = abs(y)
            if (a <= MOUSE_DEADZONE) return 0f
            val scaled = ((a - MOUSE_DEADZONE) / (1f - MOUSE_DEADZONE)).coerceAtMost(1f)
            return if (y > 0) scaled * SCROLL_STEPS_PER_S else -scaled * SCROLL_STEPS_PER_S
        }

        /** The dpad inputs a stick at ([x], [y]) holds: one or two, 8-way, past the threshold on each axis. Pure. */
        fun directions(x: Float, y: Float): Set<String> {
            val out = HashSet<String>()
            if (abs(x) >= STICK_DPAD_THRESHOLD) out += if (x > 0) "dpad_east" else "dpad_west"
            if (abs(y) >= STICK_DPAD_THRESHOLD) out += if (y > 0) "dpad_south" else "dpad_north"
            return out
        }
    }
}
