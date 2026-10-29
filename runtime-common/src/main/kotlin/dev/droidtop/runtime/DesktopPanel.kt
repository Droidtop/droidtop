package dev.droidtop.runtime

import java.io.File

/**
 * The status strip inside the shared sway desktop (docs/SPEC.md 2a "The
 * desktop panel", Droidtop/tracker#353, #683): a stock program from the
 * distro's packages, started by sway, never part of an image. Waybar is the
 * default, showing only what droidtop's bar does not: the workspaces, the
 * Linux tray and the clock. The launcher and the window list are droidtop's
 * bar, so the stock launcher key opens droidtop's Start menu. Sway's own
 * bar is the alternative, and the one to pick to run your own sway config
 * (and your own launcher). sfwbar and nwg-panel are named for later.
 *
 * Only sway takes a panel here: labwc's plan has none of its own.
 */
enum class DesktopPanel(val id: String, val label: String) {
    WAYBAR("waybar", "Waybar"),
    SWAYBAR("swaybar", "Sway's own bar"),
    ;

    companion object {
        val DEFAULT = WAYBAR

        fun fromId(id: String?): DesktopPanel = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** Where droidtop's panel files appear in every container. */
        const val IN_CONTAINER_DIR = "${ContainerLauncher.IN_CONTAINER_DIR}/panel"
        const val SWAY_CONFIG = "$IN_CONTAINER_DIR/sway.config"
        const val WAYBAR_CONFIG = "$IN_CONTAINER_DIR/waybar.json"

        /** The package [WAYBAR] adds to the plan, the same name in Alpine and Debian. */
        const val WAYBAR_PACKAGES = "waybar"

        /** sway with droidtop's config, which [WAYBAR] starts instead of plain `sway`. */
        const val WAYBAR_COMPOSITOR = "sway -c $SWAY_CONFIG"

        fun hostDir(filesDir: File): File = File(ContainerLauncher.hostDir(filesDir), "panel")

        /**
         * sway's own config first, so every key binding and the person's
         * /etc/sway/config.d stay as the distro ships them; then the stock
         * bar (bar-0, the id sway gives the config's unnamed bar) runs
         * `true` instead of swaybar, Waybar starts, and the stock launcher
         * key asks droidtop for its Start menu ([ContainerLauncher.START]),
         * so a keyboard's Super+D opens the same menu as the bar's Start.
         *
         * Windows float unless [tileWindows] (docs/SPEC.md 2b "Window controls", Droidtop/tracker#686): a new
         * window opens at its own size instead of halving the others, a finger moves it by its title bar and
         * resizes it by its border, and droidtop's bar maximizes and snaps it.
         */
        fun swayConfig(tileWindows: Boolean = false): String = buildString {
            appendLine("# droidtop's desktop panel (docs/SPEC.md 2a). droidtop rewrites this file at every desktop start.")
            appendLine("# To run your own sway config, choose \"Sway's own bar\" in Desktop settings and edit ~/.config/sway/config.")
            appendLine("include /etc/sway/config")
            appendLine("bar bar-0 swaybar_command true")
            appendLine("exec waybar -c $WAYBAR_CONFIG")
            appendLine("bindsym --no-warn ${'$'}mod+d exec sh ${ContainerLauncher.HELPER} ${ContainerLauncher.START}")
            if (!tileWindows) {
                appendLine("for_window [all] floating enable")
                // A border wide enough to take a fingertip's drag; the title bar stays, to move the window by.
                appendLine("default_floating_border normal $FLOATING_BORDER_PX")
            }
        }

        /** The floating windows' border, in pixels: what a finger drags to resize one. */
        const val FLOATING_BORDER_PX = 8

        /**
         * Waybar along the top, a status strip: the workspaces, the tray of
         * the Linux programs and a clock. No launcher and no window title:
         * droidtop's bar has Start and the window list, and two of each was
         * two ways to do one thing (Droidtop/tracker#683). The clock is
         * `date`, which reads the container's POSIX `TZ`
         * ([ContainerLayout.posixTimeZone]); Waybar's own clock module wants
         * a tz database the image may not have.
         */
        fun waybarConfig(): String =
            """
            {
              "layer": "top",
              "position": "top",
              "height": 26,
              "modules-left": ["sway/workspaces", "sway/mode"],
              "modules-right": ["tray", "custom/clock"],
              "custom/clock": { "exec": "date +%H:%M", "interval": 30, "tooltip": false }
            }
            """.trimIndent() + "\n"

        /** Writes both files under [filesDir]; small, done before each start, off the main thread. */
        fun writeConfigs(filesDir: File, tileWindows: Boolean = false) {
            val dir = hostDir(filesDir)
            dir.mkdirs()
            File(dir, "sway.config").writeText(swayConfig(tileWindows))
            File(dir, "waybar.json").writeText(waybarConfig())
        }
    }
}
