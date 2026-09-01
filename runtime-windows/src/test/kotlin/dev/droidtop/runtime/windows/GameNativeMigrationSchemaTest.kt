package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration refuses an upstream database whose schema is newer than
 * this build understands, which only works if the constant it compares
 * against tracks the vendored database's real version. A vendor sync
 * that bumps the Room version silently would otherwise make droidtop
 * refuse perfectly good imports (safe, but wrong), so the two are
 * pinned together here rather than by memory.
 */
class GameNativeMigrationSchemaTest {

    @Test
    fun `supported schema version matches the vendored database`() {
        val source = File("../vendor/gamenative/app/src/main/java/app/gamenative/db/PluviaDatabase.kt")
        assertTrue("Vendored PluviaDatabase.kt not found at ${source.absolutePath}", source.isFile)
        val declared = Regex("""version\s*=\s*(\d+)""").find(source.readText())?.groupValues?.get(1)?.toInt()
        assertEquals(
            "PluviaDatabase's Room version changed; update GameNativeMigration.SUPPORTED_SCHEMA_VERSION",
            declared,
            GameNativeMigration.SUPPORTED_SCHEMA_VERSION,
        )
    }
}
