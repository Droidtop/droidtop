package dev.droidtop.input

/**
 * The companion pointer's second route (docs/SPEC.md "The companion's tabs", Input; Droidtop/tracker#811): the same
 * [PointerOp]s played as Android's `input` shell command through the helper app (`priv.shell`), for a device where
 * droidtop's accessibility service is off or cannot reach the screen. A tap is `input tap`; a long press is a swipe that
 * stays in place for [LONG_PRESS_MS]; a scroll is a swipe; a drag is one stroke of `input motionevent` DOWN, MOVE and UP,
 * the shell's way to keep a finger down across commands. Points are clamped to the screen ([maxX], [maxY]) and given as
 * whole pixels. Pure, so the commands are tested here.
 */
object ShellPointerCommands {
    const val LONG_PRESS_MS = 650L
    const val SWIPE_MS = 150L

    fun argv(op: PointerOp, displayId: Int, maxX: Float, maxY: Float): List<String> {
        fun px(v: Float, max: Float) = v.coerceIn(0f, max).toInt().toString()
        fun x(v: Float) = px(v, maxX)
        fun y(v: Float) = px(v, maxY)
        val input = listOf("input", "-d", displayId.toString())
        return when (op) {
            is PointerOp.Tap -> input + listOf("tap", x(op.x), y(op.y))
            is PointerOp.ContextClick -> input + listOf("swipe", x(op.x), y(op.y), x(op.x), y(op.y), LONG_PRESS_MS.toString())
            is PointerOp.Scroll -> input + listOf("swipe", x(op.x), y(op.y), x(op.x + op.dx), y(op.y + op.dy), SWIPE_MS.toString())
            is PointerOp.DragStart -> input + listOf("motionevent", "DOWN", x(op.x), y(op.y))
            is PointerOp.DragMove -> input + listOf("motionevent", "MOVE", x(op.x), y(op.y))
            is PointerOp.DragEnd -> input + listOf("motionevent", "UP", x(op.x), y(op.y))
        }
    }
}
