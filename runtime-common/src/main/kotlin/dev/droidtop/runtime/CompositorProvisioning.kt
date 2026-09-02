package dev.droidtop.runtime

/**
 * Maps a PRIMARY-role catalog entry's `os`/`desktopEnvironment`
 * (known-image-repositories.json) to that distro's own real package-
 * manager command for installing a desktop into an otherwise-stock
 * rootfs: a compositor, and [ContainerTerminal.PACKAGE] — a terminal is
 * part of what makes the result a desktop rather than a screen (docs/SPEC.md
 * §3d), and it is provisioned here rather than through a second mechanism of
 * its own because there is exactly one moment a stock image gets its
 * packages: the primary container's first boot. A [ContainerRuntime] backend runs this once, on the primary
 * container's first boot -- never baked into a pre-built image
 * (docs/SPEC.md §2a: "OCI images stay stock... injected at runtime, not
 * part of any image"). This is what makes "any OCI image works" (§3a)
 * actually true for the PRIMARY role too, not just siblings: the catalog
 * entry just needs to name a real stock image plus which compositor to
 * provision into it, not a droidtop-maintained custom build.
 *
 * Only combinations droidtop actually recommends today (see each
 * PRIMARY entry's own `notes`) are covered -- returning null for anything
 * else is deliberate, so an unsupported combination fails fast with a
 * clear error instead of silently doing nothing.
 */
object CompositorProvisioning {
    fun installCommand(os: String, desktopEnvironment: String): String? {
        val terminal = ContainerTerminal.PACKAGE
        return when (os to desktopEnvironment) {
            "debian" to "sway" ->
                "apt-get update && apt-get install -y --no-install-recommends sway seatd xwayland " + terminal
            "alpine" to "sway" -> "apk add --no-cache sway seatd xwayland " + terminal
            "alpine" to "labwc" -> "apk add --no-cache labwc seatd " + terminal
            else -> null
        }
    }
}
