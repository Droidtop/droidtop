package dev.droidtop.runtime

import java.io.File

/**
 * The panel and app launcher inside the shared sway desktop (docs/SPEC.md
 * 2a "The desktop panel", Droidtop/tracker#353): a stock program from the
 * distro's packages, started by sway, never part of an image. Waybar with
 * wofi is the default, the sway-native pair; sway's own bar is the
 * alternative, and the one to pick to run your own sway config. sfwbar and
 * nwg-panel are named for later. Whichever runs, the library's games are in
 * its launcher, because they are desktop entries on `XDG_DATA_DIRS`
 * ([ContainerLauncher]).
 *
 * Only sway takes a panel here: labwc's plan has none of its own.
 */
enum class DesktopPanel(val id: String, val label: String) {
    WAYBAR("waybar", "Waybar and wofi"),
    SWAYBAR("swaybar", "Sway's own bar"),
    ;

    companion object {
        val DEFAULT = WAYBAR

        fun fromId(id: String?): DesktopPanel = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** Where droidtop's panel files appear in every container. */
        const val IN_CONTAINER_DIR = "${ContainerLauncher.IN_CONTAINER_DIR}/panel"
        const val SWAY_CONFIG = "$IN_CONTAINER_DIR/sway.config"
        const val WAYBAR_CONFIG = "$IN_CONTAINER_DIR/waybar.json"

        /** The packages [WAYBAR] adds to the plan, the same names in Alpine and Debian. */
        const val WAYBAR_PACKAGES = "waybar wofi"

        /** sway with droidtop's config, which [WAYBAR] starts instead of plain `sway`. */
        const val WAYBAR_COMPOSITOR = "sway -c $SWAY_CONFIG"

        fun hostDir(filesDir: File): File = File(ContainerLauncher.hostDir(filesDir), "panel")

        /**
         * sway's own config first, so every key binding and the person's
         * /etc/sway/config.d stay as the distro ships them; then the stock
         * bar (bar-0, the id sway gives the config's unnamed bar) runs
         * `true` instead of swaybar, Waybar starts, and the stock launcher
         * key opens wofi's application list.
         */
        fun swayConfig(): String = buildString {
            appendLine("# droidtop's desktop panel (docs/SPEC.md 2a). droidtop rewrites this file at every desktop start.")
            appendLine("# To run your own sway config, choose \"Sway's own bar\" in Desktop settings and edit ~/.config/sway/config.")
            appendLine("include /etc/sway/config")
            appendLine("bar bar-0 swaybar_command true")
            appendLine("exec waybar -c $WAYBAR_CONFIG")
            appendLine("bindsym --no-warn ${'$'}mod+d exec wofi --show drun")
        }

        /**
         * Waybar along the top: an Apps button that opens wofi (a touch
         * screen has no Super key), the workspaces, the focused window's
         * title, the tray and a clock. The clock is `date`, which reads the
         * container's POSIX `TZ` ([ContainerLayout.posixTimeZone]); Waybar's
         * own clock module wants a tz database the image may not have.
         */
        fun waybarConfig(): String =
            """
            {
              "layer": "top",
              "position": "top",
              "height": 34,
              "modules-left": ["custom/apps", "sway/workspaces", "sway/mode"],
              "modules-center": ["sway/window"],
              "modules-right": ["tray", "custom/clock"],
              "custom/apps": { "format": "Apps", "tooltip": false, "on-click": "wofi --show drun" },
              "custom/clock": { "exec": "date +%H:%M", "interval": 30, "tooltip": false }
            }
            """.trimIndent() + "\n"

        /** Writes both files under [filesDir]; small, done before each start, off the main thread. */
        fun writeConfigs(filesDir: File) {
            val dir = hostDir(filesDir)
            dir.mkdirs()
            File(dir, "sway.config").writeText(swayConfig())
            File(dir, "waybar.json").writeText(waybarConfig())
        }
    }
}
