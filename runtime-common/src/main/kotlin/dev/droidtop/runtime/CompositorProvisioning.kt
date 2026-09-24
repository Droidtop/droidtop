package dev.droidtop.runtime

/**
 * How a stock image becomes a desktop: the distro's own package-manager
 * command that installs a compositor and [ContainerTerminal.PACKAGE], and the
 * command that then runs that compositor. Both come from the same catalog
 * entry, so the compositor that is started is always the one that was
 * installed (the two used to be separate: labwc was installed and `sway`
 * was started).
 */
data class PrimaryProvisioning(
    val installCommand: String,
    val compositorCommand: String,
)

/**
 * Maps a PRIMARY-role catalog entry's `os`/`desktopEnvironment`
 * (known-image-repositories.json) to its [PrimaryProvisioning]. A terminal
 * is part of what makes the result a desktop rather than a screen
 * (docs/SPEC.md §3d), and it is provisioned here rather than through a
 * second mechanism of its own because there is exactly one moment a stock
 * image gets its packages: the primary container's first boot
 * ([ContainerLayout.primaryInitScript]), never baked into a pre-built image
 * (docs/SPEC.md §2a: "OCI images stay stock... injected at runtime, not
 * part of any image"). This is what makes "any OCI image works" (§3a)
 * true for the PRIMARY role too.
 *
 * No seat daemon: the compositor runs on wlroots' headless backend, which
 * never opens a session (see [ContainerLayout.compositorEnvironment]).
 *
 * A font, named explicitly: Alpine's fontconfig installs none, and with no
 * font swaybar dies computing a garbage buffer height and foot refuses to
 * start ("failed to match font"), both seen running the alpine plan
 * off-device (2026-09-24). Debian's fontconfig-config already depends on
 * fonts-dejavu-core; it is named anyway so neither plan relies on it.
 *
 * Debian's command first installs a `policy-rc.d` that refuses every
 * service start. That is Debian's own documented mechanism for package
 * installation inside a chroot or container with no init (invoke-rc.d
 * consults it before starting anything): without it, a maintainer script
 * such as dbus's or polkitd's tries to start its daemon in a container
 * that has no service manager and fails the whole install.
 *
 * Only combinations droidtop actually recommends today (see each PRIMARY
 * entry's own `notes`) are covered; returning null for anything else is
 * deliberate, so an unsupported combination fails fast with a clear error
 * instead of silently doing nothing.
 */
object CompositorProvisioning {
    private const val DEBIAN_NO_SERVICE_STARTS =
        "printf '#!/bin/sh\\nexit 101\\n' > /usr/sbin/policy-rc.d && chmod 755 /usr/sbin/policy-rc.d"

    fun plan(os: String, desktopEnvironment: String): PrimaryProvisioning? {
        val terminal = ContainerTerminal.PACKAGE
        return when (os to desktopEnvironment) {
            "debian" to "sway" -> PrimaryProvisioning(
                installCommand = "$DEBIAN_NO_SERVICE_STARTS && export DEBIAN_FRONTEND=noninteractive && " +
                    "apt-get update && apt-get install -y --no-install-recommends sway xwayland fonts-dejavu-core $terminal",
                compositorCommand = "sway",
            )
            "alpine" to "sway" -> PrimaryProvisioning(
                installCommand = "apk add --no-cache sway xwayland font-dejavu $terminal",
                compositorCommand = "sway",
            )
            "alpine" to "labwc" -> PrimaryProvisioning(
                installCommand = "apk add --no-cache labwc font-dejavu $terminal",
                compositorCommand = "labwc",
            )
            else -> null
        }
    }
}
