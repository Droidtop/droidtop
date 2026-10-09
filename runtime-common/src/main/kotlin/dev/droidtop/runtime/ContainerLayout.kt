package dev.droidtop.runtime

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule

/**
 * What every droidtop container looks like from the inside, whichever
 * backend runs it. Both [ContainerRuntime] implementations map the same
 * host directories to the same in-container paths and hand processes the
 * same environment, so a program started in the primary container, in a
 * sibling, or through either backend finds the primary compositor the same
 * way (docs/SPEC.md 2, the distrobox-style shared socket).
 *
 * The primary container's first-boot script lives here too, for the same
 * reason: droidspaces writes it to `/sbin/init`, proot runs it as the
 * primary's long-lived process, and it is one script.
 */
object ContainerLayout {
    /**
     * In-container `XDG_RUNTIME_DIR`. Each backend maps ONE host directory
     * here in every container it creates, so the socket the primary's
     * compositor creates is the same file for every sibling and for
     * `:host-bridge`, which connects to the host side directly.
     */
    const val SOCKET_DIR = "/run/droidtop-sockets"

    /**
     * The device VPN's endpoint, under [SOCKET_DIR]: a SOCKS5 proxy the
     * container's own VPN client serves on this Unix socket (wireproxy
     * for WireGuard, microsocks beside OpenVPN, a client's proxy mode).
     * droidtop relays every device connection to it (docs/SPEC.md 4a).
     */
    const val VPN_SOCKET = "vpn.sock"

    /**
     * The primary's CUPS, under [SOCKET_DIR] so every container prints
     * through it ([CompositorProvisioning.plan] with printing on; clients
     * find it through `CUPS_SERVER`, [clientEnvironment]).
     */
    const val CUPS_SOCKET = "cups.sock"

    /**
     * The host-side audio bridge's socket, under [SOCKET_DIR] so every
     * container reaches it the same way it reaches [CUPS_SOCKET]: clients
     * find it through `PULSE_SERVER` ([clientEnvironment]). Unlike CUPS
     * this has nothing to do with a container's own provisioning -- the
     * server lives entirely on the Android side (see runtime-linux-noroot's
     * HostAudioServer), since a container has no audio hardware of its own
     * to provision a daemon against.
     */
    const val AUDIO_SOCKET = "audio.sock"

    /** Where the app's private storage root (`Context.getFilesDir()`) appears inside every container. */
    const val APP_STORAGE_DIR = "/run/droidtop-app-storage"

    /**
     * Where the device's shared storage appears inside every container,
     * one directory per mounted volume ([SharedVolume.name]), read-write,
     * the way distrobox shares the home directory (docs/SPEC.md 4b):
     * Downloads, documents and game folders are the same files inside and
     * outside the desktop.
     */
    const val SHARED_STORAGE_DIR = "/run/droidtop-shared-storage"

    /**
     * Where a container's own extra Mounts (docs/SPEC.md 3d) appear
     * inside it, one directory per bind (ExtraMount.name) -- the same
     * "one directory per thing shared" shape as SHARED_STORAGE_DIR,
     * kept separate from it because these are per-CONTAINER binds a
     * person adds one at a time, not the one device-wide set every
     * container gets automatically.
     */
    const val EXTRA_MOUNTS_DIR = "/run/droidtop-mounts"

    /**
     * Every plan ([planId]) whose install has completed in this container,
     * one per line. Provisioning runs when the current plan is not among
     * them, so a package added to a plan reaches containers made before
     * it, and switching back to a plan already installed (Printing off,
     * then on again) costs nothing. It used to hold only the last plan,
     * so every such switch re-ran the install and announced "first boot"
     * each time (rig, dq-desk2-02). Package managers skip what is already
     * installed, so a run costs only what changed.
     */
    const val PROVISIONED_MARKER = "/var/lib/droidtop-provisioned"

    /** A short stable identity for [provisioning]'s install command (hex SHA-256). */
    fun planId(provisioning: PrimaryProvisioning): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(provisioning.installCommand.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val WAYLAND_SOCKET = Regex("wayland-(\\d+)")

    /**
     * The compositor's socket in the host side of [SOCKET_DIR], found
     * rather than assumed: every compositor names its own socket, and sway
     * deliberately never uses `wayland-0` (vendor/sway/sway/server.c,
     * "Avoid using wayland-0 as display socket", starting at `wayland-1`).
     * A hardcoded `wayland-0` was a socket that never appeared (found
     * running the primary boot off-device, 2026-09-24). The directory is
     * emptied before the primary starts, so the lowest-numbered socket is
     * the one compositor's. Null until it exists.
     */
    fun findWaylandSocket(hostSocketDir: File): File? =
        hostSocketDir.listFiles()
            ?.mapNotNull { file -> WAYLAND_SOCKET.matchEntire(file.name)?.let { it.groupValues[1].toInt() to file } }
            ?.minByOrNull { it.first }
            ?.second

    /**
     * Environment every process droidtop starts in a container gets, so a
     * Wayland client reaches the primary compositor: [SOCKET_DIR] as
     * `XDG_RUNTIME_DIR`, and the socket's name ([findWaylandSocket]) as
     * `WAYLAND_DISPLAY` once the compositor has created it, CUPS's shared
     * socket as `CUPS_SERVER`, and, when [audioShared] is true, the audio
     * bridge's socket as `PULSE_SERVER` -- the same variable PulseAudio
     * clients everywhere read, so a program in a container reaches host
     * audio exactly the way it would reach a real Linux desktop's server.
     * [audioShared] defaults true for a caller with nothing per-container
     * to say (droidspaces' own PulseAudio bridge owns this variable for
     * its own containers instead, see DroidSpacesRuntime, so its callers
     * pass false to leave it unset here).
     *
     * `XDG_DATA_DIRS` is the specification's default with droidtop's
     * library entries after it ([ContainerLauncher.DATA_DIR], docs/SPEC.md
     * 2a), so every stock menu lists the library's games; the distro's own
     * entries come first and win a name clash.
     *
     * `TZ` is the device's time zone as a POSIX rule ([posixTimeZone]), so
     * clocks and file times inside the desktop are local time rather than
     * a stock image's UTC.
     */
    fun clientEnvironment(waylandSocketName: String?, audioShared: Boolean = true): Map<String, String> = buildMap {
        put("XDG_RUNTIME_DIR", SOCKET_DIR)
        put("TZ", posixTimeZone(ZoneId.systemDefault()))
        put("XDG_DATA_DIRS", "/usr/local/share:/usr/share:${ContainerLauncher.DATA_DIR}")
        // CUPS clients take a socket path here. With printing off nothing
        // listens there, which to a program is the same as no CUPS.
        put("CUPS_SERVER", "$SOCKET_DIR/$CUPS_SOCKET")
        waylandSocketName?.let { put("WAYLAND_DISPLAY", it) }
        // With audio sharing off, or no bridge running, nothing listens
        // there either -- to a program that is the same as no PulseAudio.
        if (audioShared) put("PULSE_SERVER", "$SOCKET_DIR/$AUDIO_SOCKET")
    }

    /**
     * The compositor's environment. Everything here is wlroots' own, read
     * by any wlroots compositor (sway and labwc alike):
     *
     *  - `WLR_BACKENDS=headless`: outputs are virtual, which is what makes
     *    them capturable by wlr-screencopy (docs/SPEC.md 2). wlroots'
     *    backend/backend.c creates a session (seatd/logind) only for the
     *    drm and libinput backends, so a headless compositor needs no seat
     *    daemon at all.
     *  - `WLR_HEADLESS_OUTPUTS=1`: the headless backend starts with no
     *    output unless asked for one (same file, attempt_headless_backend);
     *    without it the only output would be sway's unadvertised FALLBACK.
     *  - `WLR_RENDERER=pixman`: software rendering into shared memory. A
     *    container on Android has no DRM render node to hand an EGL or
     *    Vulkan renderer, and wlr-screencopy's shm path is what
     *    `:host-bridge` reads.
     */
    val compositorEnvironment: Map<String, String> = mapOf(
        "XDG_RUNTIME_DIR" to SOCKET_DIR,
        "WLR_BACKENDS" to "headless",
        "WLR_HEADLESS_OUTPUTS" to "1",
        "WLR_RENDERER" to "pixman",
    )

    /**
     * The primary container's boot script: provision when the marker does
     * not name this plan ([PROVISIONED_MARKER]; a failed or interrupted
     * install runs again next boot instead of being skipped), then replace
     * itself with the compositor. A failed install ends the script, which each backend
     * reports as the compositor never appearing.
     *
     * The install command is tested explicitly rather than left to
     * `set -e`: it is an `&&` chain, and `set -e` ignores a failure
     * anywhere in such a list but its last command, so a failed
     * `apt-get update` would otherwise fall through to writing the marker.
     */
    fun primaryInitScript(provisioning: PrimaryProvisioning): String = buildString {
        val plan = planId(provisioning)
        appendLine("#!/bin/sh")
        appendLine("set -e")
        appendLine("if ! grep -qx $plan $PROVISIONED_MARKER 2>/dev/null; then")
        appendLine("  if [ -f $PROVISIONED_MARKER ]; then")
        appendLine("    echo 'droidtop: the desktop setup changed; installing what it needs now'")
        appendLine("  else")
        appendLine("    echo 'droidtop: provisioning the desktop (first boot)'")
        appendLine("  fi")
        appendLine("  if ! { ${provisioning.installCommand}; }; then")
        appendLine("    echo 'droidtop: provisioning failed' >&2")
        appendLine("    exit 1")
        appendLine("  fi")
        appendLine("  mkdir -p ${PROVISIONED_MARKER.substringBeforeLast('/')}")
        appendLine("  echo $plan >> $PROVISIONED_MARKER")
        appendLine("  echo 'droidtop: provisioning finished'")
        appendLine("fi")
        appendLine("mkdir -p $SOCKET_DIR")
        appendLine("chmod 700 $SOCKET_DIR")
        compositorEnvironment.forEach { (key, value) -> appendLine("export $key=$value") }
        // A daemon never holds up the desktop. Each runs in the foreground
        // of a background job, its output in its own log, and a watcher
        // says after DAEMON_CHECK_SECONDS whether it is still running. The
        // script used to run each one in line and trust it to fork itself
        // away: under proot cupsd's parent never returned, sway was never
        // started, and the desktop hung on "provisioning finished" (rig,
        // dq-desk2-01).
        provisioning.daemons.forEach { daemon ->
            val name = daemonName(daemon)
            val log = daemonLog(daemon)
            appendLine("mkdir -p ${log.substringBeforeLast('/')}")
            appendLine("$daemon </dev/null >$log 2>&1 &")
            appendLine("pid=$!")
            appendLine("echo \"droidtop: started $name (pid ${'$'}pid)\"")
            appendLine(
                "( sleep $DAEMON_CHECK_SECONDS; if kill -0 ${'$'}pid 2>/dev/null; then echo 'droidtop: $name is running'; " +
                    "else echo \"droidtop: $name stopped: ${'$'}(tail -n 3 $log | tr '\\n' ' ')\" >&2; fi ) </dev/null &",
            )
        }
        // The compositor creates the socket; a WAYLAND_DISPLAY inherited
        // from the client environment would only name the one it is about
        // to create.
        appendLine("unset WAYLAND_DISPLAY")
        appendLine("echo 'droidtop: starting ${provisioning.compositorCommand}'")
        appendLine("exec ${provisioning.compositorCommand}")
    }

    /**
     * [zone] as a POSIX `TZ` rule (POSIX.1, 8.3 "TZ"): standard offset, and
     * where the zone keeps daylight saving, the daylight offset and the
     * yearly rule for each change, e.g. `<-05>5<-04>,M3.2.0/2,M11.1.0/2`.
     * A rule rather than a zone name (`America/New_York`) because a name
     * needs the image's tzdata, which Alpine does not ship, while a rule is
     * read by glibc, musl and GLib alike with nothing installed. The names
     * are the numeric `<+hhmm>` form: Android's and the JVM's short zone
     * names are often "GMT+01:00", which is not a valid POSIX name.
     *
     * The yearly rules are java.time's own ([java.time.zone.ZoneRules.getTransitionRules]).
     * A zone with none (no daylight saving now) is its current offset. A
     * rule POSIX cannot say exactly ("the Sunday on or after the 2nd") is
     * the nearest week; the tz database uses only "last" and multiples of
     * seven plus one for the zones that keep daylight saving today.
     */
    fun posixTimeZone(zone: ZoneId, now: Instant = Instant.now()): String {
        val rules = zone.rules
        val yearly = rules.transitionRules
        val toDaylight = yearly.singleOrNull { it.offsetAfter != it.standardOffset }
        val toStandard = yearly.singleOrNull { it.offsetAfter == it.standardOffset }
        if (yearly.size != 2 || toDaylight == null || toStandard == null) {
            val offset = rules.getOffset(now)
            return posixName(offset) + posixOffset(offset)
        }
        val standard = toStandard.offsetAfter
        val daylight = toDaylight.offsetAfter
        return posixName(standard) + posixOffset(standard) + posixName(daylight) +
            (if (daylight.totalSeconds - standard.totalSeconds == 3600) "" else posixOffset(daylight)) +
            "," + posixDate(toDaylight) + "," + posixDate(toStandard)
    }

    /** `<+0530>` or `<-05>`: the angle-bracket name form, any offset. */
    private fun posixName(offset: ZoneOffset): String {
        val total = offset.totalSeconds
        val sign = if (total < 0) "-" else "+"
        val minutes = Math.abs(total) / 60
        val hh = "%02d".format(minutes / 60)
        val mm = minutes % 60
        return "<$sign$hh${if (mm == 0) "" else "%02d".format(mm)}>"
    }

    /** POSIX counts west of Greenwich positive: UTC+05:30 is `-5:30`. */
    private fun posixOffset(offset: ZoneOffset): String {
        val total = -offset.totalSeconds
        return (if (total < 0) "-" else "") + posixTime(Math.abs(total))
    }

    /** `h[:mm[:ss]]`, the hours unbounded (a change at 25:00 is legal). */
    private fun posixTime(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return when {
            s != 0 -> "%d:%02d:%02d".format(h, m, s)
            m != 0 -> "%d:%02d".format(h, m)
            else -> "$h"
        }
    }

    /**
     * One change as `Mm.w.d/time` (or `Jn/time` for a fixed date), the time
     * in the wall clock in force before it, left out when it is POSIX's
     * default 02:00.
     */
    private fun posixDate(rule: ZoneOffsetTransitionRule): String {
        val month = rule.month.value
        val dayOfWeek = rule.dayOfWeek
        val date = if (dayOfWeek == null) {
            // A fixed day of the month: POSIX's Jn counts days 1-365, never February 29.
            "J" + LocalDate.of(2001, month, rule.dayOfMonthIndicator.coerceAtLeast(1)).dayOfYear
        } else {
            val indicator = rule.dayOfMonthIndicator
            // java.time stores "last Sunday" as "Sunday on or after maxLength - 6" for every month but
            // February (ZoneRulesBuilder), so that, like a negative indicator, is POSIX's week 5.
            val week = if (indicator < 0 || indicator + 6 >= rule.month.maxLength()) 5 else ((indicator + 6) / 7).coerceIn(1, 5)
            "M$month.$week.${dayOfWeek.value % 7}"
        }
        var seconds = if (rule.isMidnightEndOfDay) 86_400 else rule.localTime.toSecondOfDay()
        seconds += when (rule.timeDefinition) {
            ZoneOffsetTransitionRule.TimeDefinition.UTC -> rule.offsetBefore.totalSeconds
            ZoneOffsetTransitionRule.TimeDefinition.STANDARD -> rule.offsetBefore.totalSeconds - rule.standardOffset.totalSeconds
            else -> 0
        }
        return if (seconds == 7200) date else "$date/" + (if (seconds < 0) "-" + posixTime(-seconds) else posixTime(seconds))
    }

    /** How long after starting a daemon the boot script reports whether it is still running. */
    const val DAEMON_CHECK_SECONDS = 15

    /** A daemon's name, the program its command runs (`cupsd -f` is `cupsd`). */
    fun daemonName(daemon: String): String = daemon.trim().substringBefore(' ').substringAfterLast('/')

    /** Where the boot script sends [daemon]'s output inside the primary. */
    fun daemonLog(daemon: String): String = "/var/log/droidtop/${daemonName(daemon)}.log"

    /** Host directory to in-container path, one per volume, for a backend's bind list. */
    fun sharedStorageBinds(volumes: List<SharedVolume>): List<Pair<String, String>> =
        volumes.map { it.root.absolutePath to "$SHARED_STORAGE_DIR/${it.name}" }

    /**
     * [hostPath] as seen inside a container through [sharedStorageBinds],
     * or null when it is on none of [volumes] (a file only a content
     * provider serves, or one in another app's private storage).
     */
    fun sharedStorageToContainerPath(volumes: List<SharedVolume>, hostPath: File): String? {
        val path = hostPath.absoluteFile
        for (volume in volumes) {
            val relative = path.toRelativeString(volume.root.absoluteFile)
            if (relative.startsWith("..") || File(relative).isAbsolute) continue
            return if (relative.isEmpty()) "$SHARED_STORAGE_DIR/${volume.name}" else "$SHARED_STORAGE_DIR/${volume.name}/$relative"
        }
        return null
    }

    /**
     * [hostPath] (under [appStorageDir], the app's `getFilesDir()`) as seen
     * inside a container, where [appStorageDir] is mapped to
     * [APP_STORAGE_DIR]. Anything outside [appStorageDir] is a caller bug.
     */
    fun hostStorageToContainerPath(appStorageDir: File, hostPath: File): String {
        val relative = hostPath.absoluteFile.toRelativeString(appStorageDir.absoluteFile)
        require(!relative.startsWith("..")) { "$hostPath isn't under the app storage root $appStorageDir" }
        return if (relative.isEmpty()) APP_STORAGE_DIR else "$APP_STORAGE_DIR/$relative"
    }
}
