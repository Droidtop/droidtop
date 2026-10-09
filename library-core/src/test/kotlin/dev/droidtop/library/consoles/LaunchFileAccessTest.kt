package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a launch hands the game file over (Droidtop/tracker#270): the template choice and the provider's answer. */
class LaunchFileAccessTest {
    private fun player(path: String?) = Player.AmStart(
        id = "p",
        name = "Example Emu",
        argumentsTemplate = "-n a.b/.Main -e bootPath {file.uri}",
        packageName = "a.b",
        storagePathTemplate = path,
    )

    @Test
    fun aLaunchAlertForALinkLaunchNamesTheEmulatorSetupRow() {
        val canGrant = fileAccessAdvice("NetherSX2 Turnip", "Sony PlayStation 2", canGrant = true)
        assertTrue(canGrant.startsWith("NetherSX2 Turnip has no All files access"))
        assertTrue(canGrant.endsWith("Settings > Library > Emulators > Sony PlayStation 2: \"Let NetherSX2 Turnip read your game folders\"."))
        val opensSettings = fileAccessAdvice("NetherSX2 Turnip", "Sony PlayStation 2", canGrant = false)
        assertTrue(opensSettings.endsWith("\"NetherSX2 Turnip needs All files access\"."))
    }

    @Test
    fun aPresetWithoutAPathVariantAlwaysUsesItsOwnTemplate() {
        assertEquals("-n a.b/.Main -e bootPath {file.uri}", launchTemplateFor(player(null), emulatorReadsPaths = true))
    }

    @Test
    fun theUriTemplateIsKeptWhenTheEmulatorCannotReadPaths() {
        val p = player("-n a.b/.Main -e bootPath {file.path}")
        assertEquals("-n a.b/.Main -e bootPath {file.uri}", launchTemplateFor(p, emulatorReadsPaths = false))
    }

    @Test
    fun theUriTemplateIsKeptWhenThePresetHasNoPathVariantEvenWithAccess() {
        assertEquals(player(null).argumentsTemplate, launchTemplateFor(player(null), emulatorReadsPaths = false))
    }

    @Test
    fun thePathVariantIsUsedWhenTheEmulatorCanReadPaths() {
        val p = player("-n a.b/.Main -e bootPath {file.path}")
        assertEquals("-n a.b/.Main -e bootPath {file.path}", launchTemplateFor(p, emulatorReadsPaths = true))
    }

    @Test
    fun theDatabaseRowCarriesTheOptionalPathVariant() {
        val text = """{"players":[
            {"id":"a","systemId":"s","label":"A","pkg":"a.b","argumentsTemplate":"-n a.b/.M -d {file.uri}","storagePathTemplate":"-n a.b/.M -d {file.path}"},
            {"id":"b","systemId":"s","label":"B","pkg":"c.d","argumentsTemplate":"-n c.d/.M -d {file.uri}"}]}"""
        val players = KnownPlayers.parse(text).map { it.player }
        assertEquals("-n a.b/.M -d {file.path}", players[0].storagePathTemplate)
        assertNull(players[1].storagePathTemplate)
    }

    @Test
    fun aStatReadsEveryColumnItAsksForInTheOrderAsked() {
        val asked = arrayOf("_size", "last_modified", "_display_name", "mime_type", "flags", "document_id", "unknown_column")
        val cells = asked.map {
            FileDocumentColumns.columnValue(it, name = "g.iso", size = 42L, lastModified = 7L, mimeType = "application/x-iso", documentId = "/storage/x/g.iso")
        }
        assertEquals(listOf<Any?>(42L, 7L, "g.iso", "application/x-iso", 0, "/storage/x/g.iso", null), cells)
    }

    @Test
    fun aNullProjectionIsTheWholeDocumentColumnSet() {
        assertEquals(
            listOf("_display_name", "_size", "last_modified", "mime_type", "flags", "document_id"),
            FileDocumentColumns.ALL_COLUMNS.toList(),
        )
    }

    @Test
    fun theFileBehindAUriIsTheRootPathWithItsSlashBack() {
        assertEquals("/storage/ABCD-1234/Roms/ps2/g.iso", FileDocumentColumns.filePathOf("/root/storage/ABCD-1234/Roms/ps2/g.iso"))
        assertNull(FileDocumentColumns.filePathOf("/other/storage/g.iso"))
        assertNull(FileDocumentColumns.filePathOf(null))
    }

    @Test
    fun siblingsAreTheGamesOwnNameWithACompanionExtension() {
        val game = java.io.File("/storage/x/Roms/ps2/Example Game.iso")
        val found = CompanionFiles.candidates(game)
        val dir = java.io.File("/storage/x/Roms/ps2")
        assertTrue(java.io.File(dir, "Example Game.sym") in found)
        assertTrue(java.io.File(dir, "Example Game.iso.sym") in found)
        assertTrue(java.io.File(dir, "Example Game.cue") in found)
        assertTrue(java.io.File(dir, "Example Game.pnach") in found)
    }

    @Test
    fun siblingsNeverIncludeTheGameOtherFilesOrAnotherFolder() {
        val game = java.io.File("/storage/x/Roms/ps2/Example Game.bin")
        val found = CompanionFiles.candidates(game)
        assertTrue(game !in found)
        assertTrue(found.all { it.parentFile == game.parentFile })
        assertTrue(found.all { it.name.startsWith("Example Game.") })
        assertEquals(found.size, found.toSet().size)
    }

    @Test
    fun aGameWithoutAFolderHasNoSiblings() {
        assertEquals(emptyList<java.io.File>(), CompanionFiles.candidates(java.io.File("lone.iso")))
    }

    @Test
    fun theSetupScreenPointsAtFileAccessOnlyWhenThePathVariantExistsAndCannotBeUsed() {
        assertTrue(emulatorNeedsAllFilesAccess(hasPathTemplate = true, emulatorReadsPaths = false))
        assertFalse(emulatorNeedsAllFilesAccess(hasPathTemplate = true, emulatorReadsPaths = true))
        assertFalse(emulatorNeedsAllFilesAccess(hasPathTemplate = false, emulatorReadsPaths = false))
    }
}
