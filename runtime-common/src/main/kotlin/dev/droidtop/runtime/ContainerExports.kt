package dev.droidtop.runtime

import java.io.File

/**
 * One application of a sibling container shown in the desktop's menus, distrobox-export style
 * (docs/SPEC.md 3d "Exported apps", Droidtop/tracker#555 D5).
 */
data class ExportedApp(
    val containerId: String,
    val containerName: String,
    val app: ContainerApp,
) {
    /** The token the container's launcher names it by ([ContainerLauncher.token] of the container and the app). */
    val token: String get() = ContainerLauncher.token("export:$containerId/${app.id}")

    /** What the menus call it: the app's own name and where it runs, as distrobox-export writes it. */
    val title: String get() = "${app.name} (on $containerName)"
}

/**
 * The apps of sibling containers exported into the desktop. Each is a desktop entry in droidtop's own data
 * directory ([ContainerLauncher.DATA_DIR], on every container's `XDG_DATA_DIRS`) whose `Exec` is the launch
 * helper with the app's token, so the primary's launcher and droidtop's Start menu both show it and a launch
 * comes back over the one request channel ([ContainerLauncher]); droidtop then runs the app in its own
 * container, whose window reaches the shared desktop. No program path, argument or container name the
 * container wrote is ever run: the token only names an export droidtop itself published.
 *
 * Command-line programs are not exported this way: a request carries no arguments and no terminal, so they
 * wait for the container-enter path (Droidtop/tracker#555 D7).
 */
object ContainerExports {
    /** Exported entries start with this, so neither the library's entries (`droidtop-`) nor these remove the other's. */
    const val ENTRY_PREFIX = "dtexport-"

    private const val LIST_FILE = "exports.list"

    fun hostListFile(filesDir: File): File = File(ContainerLauncher.hostDir(filesDir), LIST_FILE)

    fun entryFileName(export: ExportedApp): String = "$ENTRY_PREFIX${export.token}.desktop"

    /** The exported entry: the app's name and icon, launched through the helper. */
    fun desktopEntry(export: ExportedApp): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Version=1.5")
        appendLine("Name=${ContainerLauncher.escapeValue(export.title)}")
        export.app.genericName?.let { appendLine("GenericName=${ContainerLauncher.escapeValue(it)}") }
        appendLine("Comment=${ContainerLauncher.escapeValue("Runs in ${export.containerName}")}")
        appendLine("Exec=sh ${ContainerLauncher.HELPER} ${export.token}")
        export.app.icon?.let { appendLine("Icon=${ContainerLauncher.escapeValue(it)}") }
        appendLine("Terminal=false")
    }

    /**
     * The list, one export per line: container id, container name, app id, name, terminal (1/0), generic
     * name, icon, then the command's arguments, all tab-separated. A field holding a tab or a line break
     * cannot be written and the export is left out rather than corrupting the lines after it.
     */
    fun encode(exports: List<ExportedApp>): String = buildString {
        exports.forEach { e ->
            val fields = listOf(
                e.containerId, e.containerName, e.app.id, e.app.name, if (e.app.terminal) "1" else "0",
                e.app.genericName.orEmpty(), e.app.icon.orEmpty(),
            ) + e.app.command
            if (fields.any { it.contains('\t') || it.contains('\n') || it.contains('\r') }) return@forEach
            append(fields.joinToString("\t")).append('\n')
        }
    }

    fun decode(text: String): List<ExportedApp> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 8 || f[0].isBlank() || f[2].isBlank()) return@mapNotNull null
        ExportedApp(
            containerId = f[0],
            containerName = f[1],
            app = ContainerApp(
                id = f[2],
                name = f[3],
                terminal = f[4] == "1",
                genericName = f[5].ifEmpty { null },
                icon = f[6].ifEmpty { null },
                command = f.drop(7),
            ),
        )
    }.toList()

    fun read(filesDir: File): List<ExportedApp> =
        runCatching { hostListFile(filesDir).takeIf { it.isFile }?.readText() }.getOrNull()?.let(::decode).orEmpty()

    /** [exports] with [export] in it (replacing the same container's same app) or out of it. */
    fun with(exports: List<ExportedApp>, export: ExportedApp, on: Boolean): List<ExportedApp> {
        val rest = exports.filterNot { it.containerId == export.containerId && it.app.id == export.app.id }
        return if (on) rest + export else rest
    }

    /** [exports] without anything of [containerId]: the container was deleted. */
    fun without(exports: List<ExportedApp>, containerId: String): List<ExportedApp> =
        exports.filterNot { it.containerId == containerId }

    /** The export a launch request named, or null for a token droidtop did not publish. */
    fun forToken(exports: List<ExportedApp>, token: String): ExportedApp? = exports.firstOrNull { it.token == token }

    /**
     * Saves [exports] and makes the entries match: every exported app has its entry, and every other
     * [ENTRY_PREFIX] entry is removed. Small files, written only when changed; off the main thread.
     */
    fun save(filesDir: File, exports: List<ExportedApp>) {
        val list = hostListFile(filesDir)
        list.parentFile?.mkdirs()
        writeIfChanged(list, encode(exports))
        writeEntries(filesDir, exports)
    }

    /** The entries for [exports], stale ones removed; run at every desktop start and after each change. */
    fun writeEntries(filesDir: File, exports: List<ExportedApp>) {
        val dir = ContainerLauncher.hostApplicationsDir(filesDir)
        dir.mkdirs()
        val wanted = exports.associate { entryFileName(it) to desktopEntry(it) }
        dir.list()?.forEach { name ->
            if (name.startsWith(ENTRY_PREFIX) && name !in wanted) File(dir, name).delete()
        }
        wanted.forEach { (name, text) -> writeIfChanged(File(dir, name), text) }
    }

    private fun writeIfChanged(file: File, text: String) {
        if (file.isFile && file.readText() == text) return
        val tmp = File(file.parentFile, ".${file.name}")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) tmp.delete()
    }
}
