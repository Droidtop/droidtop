package dev.droidtop.hostbridge

import org.json.JSONObject

/**
 * One window sway shows, as its layout tree (GET_TREE) places it, in the layout's logical pixels
 * (docs/SPEC.md 2b "Window controls", Droidtop/tracker#910): what droidtop draws a window's title-bar buttons and
 * resize band from.
 *
 * [rect] is the tree's `rect`, the container below its title bar with its border (sway's ipc-json.c moves a
 * container's rect down by its title bar's height); [titleBar] is the bar sway draws right above it, or null when
 * the window has none (border `pixel`, `none` or `csd`, a tab of a tabbed or stacked container, fullscreen).
 * [border] is the border's width. [output] is the output whose workspace holds the window.
 */
data class WindowFrame(
    val conId: Long,
    val appId: String,
    val title: String,
    val output: String,
    val floating: Boolean,
    val focused: Boolean,
    val rect: SwayRect,
    val titleBar: SwayRect?,
    val border: Int,
) {
    /** The whole window, its title bar included. */
    val frame: SwayRect
        get() = titleBar?.let { SwayRect(rect.x, it.y, rect.width, rect.height + it.height) } ?: rect

    /** This window as the bar's actions take it ([DesktopWindows]): the rect is the same `rect` they keep. */
    val window: SwayWindow get() = SwayWindow(conId, floating, focused, rect)
}

/**
 * The windows sway shows on each output, and each output's box in the layout, from one GET_TREE (Droidtop/tracker#910).
 * [windows] holds every output's, each output's from the bottom up: the tiled windows, then the floating ones in
 * sway's stacking order (a raised floating window is moved to the end of its workspace's list, so the last is on
 * top). Pure, so it is tested on the JVM (SwayFramesTest).
 */
data class SwayFrames(val outputs: Map<String, SwayRect>, val windows: List<WindowFrame>) {

    /**
     * The output a surface shows, by [name] (the session's name for it); a surface whose output has no name yet (""
     * or null) takes the one output when there is only one. Null when that leaves none.
     */
    fun output(name: String?): String? = when {
        !name.isNullOrEmpty() -> name.takeIf { it in outputs }
        else -> outputs.keys.singleOrNull()
    }

    /** The windows on [output], bottom first. */
    fun on(output: String): List<WindowFrame> = windows.filter { it.output == output }

    companion object {
        val NONE = SwayFrames(emptyMap(), emptyList())

        /** sway's own output for the scratchpad, which is never shown. */
        private const val SCRATCH_OUTPUT = "__i3"

        /**
         * The frames in [tree]. Only each output's current workspace is read (the output's `current_workspace`), and
         * only views sway reports `visible`. An output with a fullscreen container on it has no frames at all: the
         * fullscreen window covers the others and has no title bar, so there is nothing to put a button or a band on.
         * A global fullscreen (mode 2) empties every output.
         */
        fun parse(tree: JSONObject): SwayFrames {
            val outputs = linkedMapOf<String, SwayRect>()
            val windows = mutableListOf<WindowFrame>()
            var globalFullscreen = false
            for (output in children(tree, "nodes")) {
                if (output.optString("type") != "output") continue
                val name = output.optString("name")
                if (name.isEmpty() || name == SCRATCH_OUTPUT) continue
                val box = rect(output, "rect") ?: continue
                outputs[name] = box
                val current = if (output.isNull("current_workspace")) null else output.optString("current_workspace")
                val workspace = children(output, "nodes").firstOrNull {
                    it.optString("type") == "workspace" && (current == null || it.optString("name") == current)
                } ?: continue
                val fullscreen = fullscreenModes(workspace)
                if (2 in fullscreen) globalFullscreen = true
                if (fullscreen.isNotEmpty()) continue
                val here = mutableListOf<WindowFrame>()
                for (child in children(workspace, "nodes")) collect(child, workspace, name, floating = false, into = here)
                for (child in children(workspace, "floating_nodes")) collect(child, workspace, name, floating = true, into = here)
                windows += here
            }
            return if (globalFullscreen) SwayFrames(outputs, emptyList()) else SwayFrames(outputs, windows)
        }

        /** The non-zero `fullscreen_mode`s of the containers under [workspace] (a workspace reports 1 of itself). */
        private fun fullscreenModes(workspace: JSONObject): Set<Int> = buildSet {
            fun walk(node: JSONObject) {
                val mode = node.optInt("fullscreen_mode")
                if (mode != 0) add(mode)
                for (key in listOf("nodes", "floating_nodes")) children(node, key).forEach(::walk)
            }
            for (key in listOf("nodes", "floating_nodes")) children(workspace, key).forEach(::walk)
        }

        private fun collect(node: JSONObject, parent: JSONObject, output: String, floating: Boolean, into: MutableList<WindowFrame>) {
            val isView = node.has("pid") || node.has("app_id")
            if (isView) {
                frame(node, parent, output, floating)?.let { into += it }
                return
            }
            for (child in children(node, "nodes")) collect(child, node, output, floating, into)
            for (child in children(node, "floating_nodes")) collect(child, node, output, true, into)
        }

        private fun frame(node: JSONObject, parent: JSONObject, output: String, floating: Boolean): WindowFrame? {
            if (!node.optBoolean("visible", true)) return null
            val rect = rect(node, "rect")?.takeIf { it.width > 0 && it.height > 0 } ?: return null
            val deco = rect(node, "deco_rect")
            // A tab's title is one of several in its parent's row, not a bar of its own over the window.
            val tabbed = parent.optString("layout") in TABBED_LAYOUTS
            val titleBar = if (node.optString("border") == "normal" && !tabbed && deco != null && deco.height > 0) {
                SwayRect(rect.x, rect.y - deco.height, rect.width, deco.height)
            } else {
                null
            }
            return WindowFrame(
                conId = node.optLong("id"),
                appId = if (node.isNull("app_id")) "" else node.optString("app_id"),
                title = if (node.isNull("name")) "" else node.optString("name"),
                output = output,
                floating = floating || node.optString("type") == "floating_con",
                focused = node.optBoolean("focused"),
                rect = rect,
                titleBar = titleBar,
                border = node.optInt("current_border_width").coerceAtLeast(0),
            )
        }

        private val TABBED_LAYOUTS = setOf("tabbed", "stacked")

        private fun children(node: JSONObject, key: String): List<JSONObject> {
            val array = node.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }

        private fun rect(node: JSONObject, key: String): SwayRect? =
            node.optJSONObject(key)?.let { SwayRect(it.optInt("x"), it.optInt("y"), it.optInt("width"), it.optInt("height")) }
    }
}
