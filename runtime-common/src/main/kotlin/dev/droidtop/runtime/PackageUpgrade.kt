package dev.droidtop.runtime

/**
 * "Update its software" for a container (docs/SPEC.md 3d, "Update its software"; Droidtop/tracker#757 slice 4): the
 * image's own package manager, found inside the container, upgrades what is installed. Each command is the manager's
 * upgrade of installed packages, not a distribution upgrade, so nothing the person did not have is installed where
 * the manager lets droidtop say so (apt's `upgrade` holds back what would need a new package; zypper's `update` and
 * dnf without weak dependencies the same). Pure: the runtime runs the steps.
 */
object PackageUpgrade {
    /** One package manager: its name as a person reads it, and the commands that refresh and upgrade, in order. */
    data class Manager(val id: String, val label: String, val steps: List<Step>)

    /** One command, with a line for the row while it runs and the environment it needs. */
    data class Step(val line: String, val command: List<String>, val env: Map<String, String> = emptyMap())

    private val APT_ENV = mapOf("DEBIAN_FRONTEND" to "noninteractive")

    val MANAGERS: List<Manager> = listOf(
        Manager(
            "apk", "apk",
            listOf(
                Step("Refreshing the package list", listOf("apk", "update")),
                Step("Upgrading", listOf("apk", "upgrade")),
            ),
        ),
        Manager(
            "apt-get", "apt",
            listOf(
                Step("Refreshing the package list", listOf("apt-get", "update"), APT_ENV),
                Step("Upgrading", listOf("apt-get", "-y", "-o", "Dpkg::Options::=--force-confold", "upgrade"), APT_ENV),
            ),
        ),
        Manager(
            "dnf", "dnf",
            listOf(Step("Upgrading", listOf("dnf", "-y", "--setopt=install_weak_deps=False", "upgrade"))),
        ),
        Manager(
            "pacman", "pacman",
            listOf(Step("Upgrading", listOf("pacman", "-Syu", "--noconfirm"))),
        ),
        Manager(
            "zypper", "zypper",
            listOf(
                Step("Refreshing the package list", listOf("zypper", "--non-interactive", "refresh")),
                Step("Upgrading", listOf("zypper", "--non-interactive", "update", "--no-recommends")),
            ),
        ),
    )

    /**
     * The shell text that names the first package manager the container has (its id, on stdout), in [MANAGERS]'
     * order; it exits 1 when there is none.
     */
    val FIND_SCRIPT: String = "for m in ${MANAGERS.joinToString(" ") { it.id }}; do " +
        "command -v \"\$m\" >/dev/null 2>&1 && { echo \"\$m\"; exit 0; }; done; exit 1"

    /** The manager [FIND_SCRIPT] named, or null. Pure. */
    fun byId(found: String): Manager? = MANAGERS.firstOrNull { it.id == found.trim() }

    /**
     * What an upgrade that succeeded did, in a line ("12 packages upgraded", "Already up to date"), from the
     * manager's own output; null when its words are not recognised. Pure.
     */
    fun summary(manager: Manager, output: String): String? {
        fun count(n: Int) = if (n == 0) "Already up to date" else if (n == 1) "1 package upgraded" else "$n packages upgraded"
        return when (manager.id) {
            "apt-get" -> Regex("""(\d+) upgraded, \d+ newly installed""").find(output)?.groupValues?.get(1)?.toIntOrNull()?.let(::count)
            "apk" -> Regex("""\(\d+/\d+\) Upgrading""").findAll(output).count().let { n ->
                if (n > 0 || output.contains("OK:")) count(n) else null
            }
            "dnf" -> when {
                output.contains("Nothing to do") -> count(0)
                else -> Regex("""^\s*Upgrade\s+(\d+) Packages?""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)?.toIntOrNull()?.let(::count)
            }
            "pacman" -> when {
                output.contains("there is nothing to do") -> count(0)
                else -> Regex("""Packages \((\d+)\)""").find(output)?.groupValues?.get(1)?.toIntOrNull()?.let(::count)
            }
            "zypper" -> when {
                output.contains("Nothing to do") -> count(0)
                else -> Regex("""The following (\d+) packages? (?:is|are) going to be upgraded""").find(output)?.groupValues?.get(1)?.toIntOrNull()?.let(::count)
            }
            else -> null
        }
    }

    /** The last few lines of a failed step's output, for the row: what the manager said, not a whole log. Pure. */
    fun failureLine(output: String): String =
        output.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(2).joinToString(" ").take(300).ifEmpty { "no output" }
}
