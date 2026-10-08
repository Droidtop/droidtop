package dev.droidtop.stores.steam

import `in`.dragonbra.javasteam.types.FileData
import java.io.File
import java.security.MessageDigest
import java.util.EnumSet

/** What a depot manifest's file entries say, read the one way (verify, launch, DLC removal and cloud sync all use it). */
internal object SteamManifests {
    /**
     * Whether a depot file carries one of Steam's file flags
     * (EDepotFileFlag), given by name and by bit, however this JavaSteam
     * build hands the flags over (GameNative's isExecutable reads both).
     */
    fun hasFlag(file: FileData, names: Set<String>, bits: Int): Boolean = when (val flags: Any? = file.flags) {
        is EnumSet<*> -> flags.any { (it as? Enum<*>)?.name in names }
        is Number -> flags.toLong() and bits.toLong() != 0L
        else -> false
    }

    fun isDirectory(file: FileData): Boolean = hasFlag(file, setOf("Directory"), 0x40)

    fun isExecutable(file: FileData): Boolean = hasFlag(file, setOf("Executable", "CustomExecutable"), 0x20 or 0x80)

    fun sha1Matches(file: File, expected: ByteArray?): Boolean {
        if (expected == null || expected.isEmpty()) return true
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().contentEquals(expected)
    }
}
