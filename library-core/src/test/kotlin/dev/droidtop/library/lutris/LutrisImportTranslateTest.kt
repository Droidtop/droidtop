package dev.droidtop.library.lutris

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The closed settings vocabulary SPEC 7e3 (docs/SPEC.md 5497-5510)
 * lets through, and the boundaries around it: $GAMEDIR-relative paths
 * only, the winetricks-verb table, the DLL override modes and the env
 * allowlist. What the tables do not name is covered by
 * LutrisImportRefusalTest.
 */
class LutrisImportTranslateTest {

    private fun obj(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    private fun arr(vararg items: Any): JSONArray = JSONArray().apply { items.forEach { put(it) } }

    private fun line(what: String, detail: String) = ImportLine(what, detail)

    // ---- the game half ------------------------------------------------------

    @Test
    fun `game exe args and working dir import from GAMEDIR-relative paths`() {
        val plan = LutrisImport.translate(
            obj(
                "game" to obj(
                    "exe" to "\$GAMEDIR/drive_c/GOG Games/Foo/bin/Foo.exe",
                    "args" to "-w -nolauncher",
                    "working_dir" to "\$GAMEDIR/drive_c/GOG Games/Foo/bin",
                ),
                // YAML's own spellings of yes and no, not JSON booleans.
                "wine" to obj("dxvk" to "yes", "esync" to "off"),
                "system" to obj("env" to obj("DXVK_HUD" to "fps")),
            ),
        )
        assertEquals(listOf("drive_c", "GOG Games", "Foo", "bin", "Foo.exe"), plan.exePath)
        assertEquals(listOf("-w", "-nolauncher"), plan.args)
        assertEquals(listOf("drive_c", "GOG Games", "Foo", "bin"), plan.workingDir)
        assertEquals(WinePrefixChanges(dxvk = true, esync = false, env = mapOf("DXVK_HUD" to "fps")), plan.prefix)
        assertTrue(plan.covered.isEmpty())
        assertTrue(plan.notImported.isEmpty())
    }

    @Test
    fun `backslash Windows-style paths import the same`() {
        val plan = LutrisImport.translate(obj("game" to obj("exe" to "\$GAMEDIR\\drive_c\\GOG Games\\Foo.exe")))
        assertEquals(listOf("drive_c", "GOG Games", "Foo.exe"), plan.exePath)
        assertTrue(plan.notImported.isEmpty())
    }

    @Test
    fun `the exe suffix is checked without case`() {
        val plan = LutrisImport.translate(obj("game" to obj("exe" to "\$GAMEDIR/GAME.EXE")))
        assertEquals(listOf("GAME.EXE"), plan.exePath)
    }

    @Test
    fun `a yes or no that is neither is refused rather than guessed`() {
        val plan = LutrisImport.translate(obj("wine" to obj("dxvk" to "maybe")))
        assertNull(plan.prefix.dxvk)
        assertEquals(listOf(line("DXVK \"maybe\"", "Not a yes or no")), plan.notImported)
    }

    // ---- the path boundary --------------------------------------------------

    @Test
    fun `an absolute exe path is refused`() {
        val plan = LutrisImport.translate(obj("game" to obj("exe" to "/opt/game/Foo.exe")))
        assertNull(plan.exePath)
        assertEquals(listOf(line("Executable /opt/game/Foo.exe", "A path outside the game's folder is never imported")), plan.notImported)
    }

    @Test
    fun `a Windows drive exe path is refused`() {
        val plan = LutrisImport.translate(obj("game" to obj("exe" to "C:\\Games\\Foo.exe")))
        assertNull(plan.exePath)
        assertEquals(listOf(line("Executable C:\\Games\\Foo.exe", "A path outside the game's folder is never imported")), plan.notImported)
    }

    @Test
    fun `a home-relative exe path is refused`() {
        val plan = LutrisImport.translate(obj("game" to obj("exe" to "~/Foo.exe")))
        assertNull(plan.exePath)
        assertEquals(listOf(line("Executable ~/Foo.exe", "A path outside the game's folder is never imported")), plan.notImported)
    }

    @Test
    fun `a path that climbs out of the game folder with dot dot is refused`() {
        val plan = LutrisImport.translate(obj("game" to obj("exe" to "\$GAMEDIR/../Foo.exe")))
        assertNull(plan.exePath)
        assertEquals(
            listOf(line("Executable \$GAMEDIR/../Foo.exe", "A path that climbs out of the game's folder is never imported")),
            plan.notImported,
        )
    }

    @Test
    fun `a script variable inside a path is refused`() {
        val plan = LutrisImport.translate(obj("game" to obj("exe" to "\$GAMEDIR/\$FILE/Foo.exe")))
        assertNull(plan.exePath)
        assertEquals(
            listOf(line("Executable \$GAMEDIR/\$FILE/Foo.exe", "It uses a Lutris variable droidtop does not fill in")),
            plan.notImported,
        )
    }

    @Test
    fun `a game exe that is not a Windows program is refused`() {
        val plan = LutrisImport.translate(obj("game" to obj("exe" to "\$GAMEDIR/setup.msi")))
        assertNull(plan.exePath)
        assertEquals(listOf(line("Executable \$GAMEDIR/setup.msi", "Not a Windows program")), plan.notImported)
    }

    @Test
    fun `a missing exe path is refused rather than guessed`() {
        val plan = LutrisImport.translate(obj("game" to obj("exe" to "")))
        assertNull(plan.exePath)
        assertEquals(listOf(line("Executable", "The script gives no path")), plan.notImported)
    }

    @Test
    fun `a GAMEDIR working folder is the game folder itself`() {
        val plan = LutrisImport.translate(obj("game" to obj("working_dir" to "\$GAMEDIR")))
        assertEquals(emptyList<String>(), plan.workingDir)
        assertTrue(plan.notImported.isEmpty())
    }

    @Test
    fun `an absolute working folder is refused`() {
        val plan = LutrisImport.translate(obj("game" to obj("working_dir" to "/opt/game")))
        assertNull(plan.workingDir)
        assertEquals(listOf(line("Working folder /opt/game", "A path outside the game's folder is never imported")), plan.notImported)
    }

    // ---- the winetricks table -----------------------------------------------

    @Test
    fun `winetricks verbs inside the component table map onto it`() {
        val plan = LutrisImport.translate(
            obj(
                "installer" to arr(
                    obj("task" to obj("name" to "winetricks", "app" to "d3dx9_43 vcrun2010 quartz")),
                    // Scripts also spell the task with its wine. prefix.
                    obj("task" to obj("name" to "wine.winetricks", "app" to "dsound")),
                ),
            ),
        )
        assertEquals(setOf("direct3d", "vcrun2010", "directshow", "directsound"), plan.prefix.components)
        assertTrue(plan.notImported.isEmpty())
    }

    @Test
    fun `the whole d3dx9 family maps into the direct3d component`() {
        val plan = LutrisImport.translate(obj("installer" to arr(obj("task" to obj("name" to "winetricks", "app" to "d3dx9 d3dx9_24 d3dx9_43 D3DX9_36")))))
        assertEquals(setOf("direct3d"), plan.prefix.components)
        assertTrue(plan.notImported.isEmpty())
    }

    @Test
    fun `a winetricks verb outside the component table is refused by name`() {
        val plan = LutrisImport.translate(obj("installer" to arr(obj("task" to obj("name" to "winetricks", "app" to "d3dx9_44 dotnet48")))))
        assertTrue(plan.prefix.components.isEmpty())
        assertEquals(
            listOf(
                line("Winetricks: d3dx9_44", "Not one of the Windows components droidtop can switch on; install it by hand if the game needs it"),
                line("Winetricks: dotnet48", "Not one of the Windows components droidtop can switch on; install it by hand if the game needs it"),
            ),
            plan.notImported,
        )
    }

    // ---- the DLL override modes ----------------------------------------------

    @Test
    fun `every override mode Lutris documents maps into Wine's own spelling`() {
        val plan = LutrisImport.translate(
            obj(
                "wine" to obj(
                    "overrides" to obj(
                        "d3d11" to "n",
                        "mscms.dll" to "native,builtin",
                        "ddraw" to "disabled",
                        "corefonts" to "b,n",
                        "quartz" to "builtin",
                        "xaudio" to "native",
                    ),
                ),
            ),
        )
        assertEquals(
            mapOf("d3d11" to "n", "mscms" to "n,b", "ddraw" to "", "corefonts" to "b,n", "quartz" to "b", "xaudio" to "n"),
            plan.prefix.dllOverrides,
        )
        assertTrue(plan.notImported.isEmpty())
    }

    @Test
    fun `an override mode Lutris does not document is refused`() {
        val plan = LutrisImport.translate(obj("wine" to obj("overrides" to obj("xaudio2_7" to "sometimes"))))
        assertTrue(plan.prefix.dllOverrides.isEmpty())
        assertEquals(listOf(line("DLL override xaudio2_7 = sometimes", "Not an override Lutris documents")), plan.notImported)
    }

    @Test
    fun `an override key that is not a DLL name is refused`() {
        val plan = LutrisImport.translate(obj("wine" to obj("overrides" to obj("my dll" to "n"))))
        assertTrue(plan.prefix.dllOverrides.isEmpty())
        assertEquals(listOf(line("DLL override my dll", "Not a DLL name")), plan.notImported)
    }

    @Test
    fun `an overrides value that is not a mapping is refused`() {
        val plan = LutrisImport.translate(obj("wine" to obj("overrides" to "d3d11=n")))
        assertEquals(listOf(line("DLL overrides", "Not a list of DLLs")), plan.notImported)
    }

    // ---- the env allowlist ----------------------------------------------------

    @Test
    fun `the env allowlist takes tuning variables only, by name`() {
        val env = obj(
            "DXVK_HUD" to "1",
            "VKD3D_DEBUG" to "none",
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "__GL_THREADED_OPTIMIZATIONS" to "1",
            "WINE_LARGE_ADDRESS_AWARE" to "1",
            "STAGING_SHARED_MEMORY" to "1",
            "PULSE_LATENCY_MSEC" to "60",
        )
        val plan = LutrisImport.translate(obj("system" to obj("env" to env)))
        assertEquals(
            mapOf(
                "DXVK_HUD" to "1",
                "VKD3D_DEBUG" to "none",
                "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
                "__GL_THREADED_OPTIMIZATIONS" to "1",
                "WINE_LARGE_ADDRESS_AWARE" to "1",
                "STAGING_SHARED_MEMORY" to "1",
                "PULSE_LATENCY_MSEC" to "60",
            ),
            plan.prefix.env,
        )
        assertTrue(plan.notImported.isEmpty())
    }

    @Test
    fun `a variable outside the env allowlist is refused by name`() {
        val plan = LutrisImport.translate(obj("system" to obj("env" to obj("LD_PRELOAD" to "/bad.so", "WINEDEBUG" to "-all"))))
        assertTrue(plan.prefix.env.isEmpty())
        // One env object, so the two refusals arrive in hash order, not
        // script order: the set is the honest comparison.
        assertEquals(
            setOf(
                line("Environment LD_PRELOAD", "Not one of the variables droidtop imports"),
                line("Environment WINEDEBUG", "Not one of the variables droidtop imports"),
            ),
            plan.notImported.toSet(),
        )
    }

    @Test
    fun `an env value with a path or a space in it is never imported`() {
        val plan = LutrisImport.translate(obj("system" to obj("env" to obj("DXVK_HUD" to "/home/user/hud"))))
        assertTrue(plan.prefix.env.isEmpty())
        assertEquals(
            listOf(line("Environment DXVK_HUD=/home/user/hud", "Paths, variables and spaces in a value are never imported")),
            plan.notImported,
        )
    }

    @Test
    fun `WINEDLLOVERRIDES in the environment is refused, overrides come only from the script's list`() {
        val plan = LutrisImport.translate(obj("system" to obj("env" to obj("WINEDLLOVERRIDES" to "d3d11=n,mscms=b"))))
        assertTrue(plan.prefix.env.isEmpty())
        assertTrue(plan.prefix.dllOverrides.isEmpty())
        assertEquals(
            listOf(line("Environment WINEDLLOVERRIDES", "DLL overrides are imported only from the script's overrides list")),
            plan.notImported,
        )
    }

    @Test
    fun `WINEESYNC in the environment maps onto esync`() {
        val plan = LutrisImport.translate(obj("system" to obj("env" to obj("WINEESYNC" to "1"))))
        assertEquals(WinePrefixChanges(esync = true), plan.prefix)
        val off = LutrisImport.translate(obj("system" to obj("env" to obj("WINEESYNC" to "0"))))
        assertEquals(WinePrefixChanges(esync = false), off.prefix)
    }

    @Test
    fun `pulse_latency maps onto the PULSE_LATENCY_MSEC variable`() {
        val plan = LutrisImport.translate(obj("system" to obj("pulse_latency" to true)))
        assertEquals(mapOf("PULSE_LATENCY_MSEC" to "60"), plan.prefix.env)
        assertTrue(plan.notImported.isEmpty())
    }

    @Test
    fun `an env that is not a mapping is refused`() {
        val plan = LutrisImport.translate(obj("system" to obj("env" to "FOO=1")))
        assertTrue(plan.prefix.env.isEmpty())
        assertEquals(listOf(line("Environment", "Not a list of variables")), plan.notImported)
    }
}
