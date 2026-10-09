package dev.droidtop.shell.gamepad.pc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC surface names no store in its code (docs/SPEC.md 7j "Filters",
 * Droidtop/tracker#397 slice A): a string literal that says "Steam" (or any
 * casing of it) in the shell or in the PC library's shared vocabulary is a
 * store special-cased by name. Store names come from the registry
 * ([dev.droidtop.library.stores.StoreLibraries]); Steam's own code lives in
 * the Steam store module and Steam's settings group (`SteamStore.settingsItems`),
 * which this does not read. Comments may name stores; code may not.
 */
class SteamLiteralGuardTest {

    /** The files and folders the guard reads, from the shell-gamepad module's own folder (the unit tests' working directory). */
    private val scanned = listOf(
        "src/main/kotlin",
        "../library-core/src/main/kotlin/dev/droidtop/library/PcSource.kt",
        "../library-core/src/main/kotlin/dev/droidtop/library/LibraryEntry.kt",
        "../library-core/src/main/kotlin/dev/droidtop/library/StoreIdentity.kt",
        "../library-core/src/main/kotlin/dev/droidtop/library/stores/StoreLibrary.kt",
        "../runtime-windows/src/main/kotlin/dev/droidtop/runtime/windows/PcLibrary.kt",
        "../runtime-windows/src/main/kotlin/dev/droidtop/runtime/windows/PcGameProvider.kt",
    )

    /**
     * Files that may say it, each with its reason. ProtonDB is a database of
     * Steam games, keyed by Steam's ids: saying so is a fact about ProtonDB.
     */
    private val allowed = mapOf(
        "pc/PcGameMenu.kt" to "ProtonDB lists Steam games only",
    )

    private val literal = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")

    @Test
    fun `no code on the PC surface names Steam`() {
        val hits = scanned.map(::File).flatMap { root ->
            assertTrue("missing ${root.path}", root.exists())
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.filterNot { file -> allowed.keys.any { file.invariantSeparatorsPath.endsWith(it) } }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, raw ->
                    val line = raw.trim()
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@mapIndexedNotNull null
                    val code = line.replace(Regex("\\s//\\s.*$"), "")
                    literal.findAll(code).firstOrNull { it.value.contains("steam", ignoreCase = true) }
                        ?.let { "${file.path}:${index + 1}: ${it.value}" }
                }
            }
        assertTrue("A store named in code on the PC surface:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
