package dev.droidtop.library.lutris

import dev.droidtop.library.PcPrefixState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What applying an import would really change in one prefix, against
 * what the prefix is already set to: Apply writes exactly the [changes]
 * half and the preview shows exactly the same lines, so a request the
 * prefix already meets must land in alreadySet and never in the write.
 */
class PrefixPlanTest {

    private fun state(
        dxvk: Boolean = false,
        esync: Boolean = false,
        components: Set<String> = emptySet(),
        env: Map<String, String> = emptyMap(),
        dllOverrides: Map<String, String> = emptyMap(),
    ) = PcPrefixState(
        name = "Provisioned prefix",
        shared = false,
        dxvk = dxvk,
        esync = esync,
        components = components,
        env = env,
        dllOverrides = dllOverrides,
    )

    @Test
    fun `against plans only what differs from what the prefix is already set to`() {
        val wanted = WinePrefixChanges(
            dxvk = true,
            esync = true,
            components = setOf("direct3d", "directsound"),
            dllOverrides = mapOf("d3d11" to "n", "mscms" to "b"),
            env = mapOf("DXVK_HUD" to "1"),
        )
        val plan = wanted.against(
            state(
                dxvk = true,
                components = setOf("directsound"),
                env = mapOf("DXVK_HUD" to "0"),
                dllOverrides = mapOf("mscms" to "b"),
            ),
        )
        assertEquals(
            WinePrefixChanges(
                esync = true,
                components = setOf("direct3d"),
                dllOverrides = mapOf("d3d11" to "n"),
                env = mapOf("DXVK_HUD" to "1"),
            ),
            plan.changes,
        )
        assertEquals(
            listOf(
                ImportLine("Esync", "Turn on"),
                ImportLine("Windows component: Direct3D extras (d3dx9, d3dx10, d3dx11, d3dcompiler)", "Switch on"),
                ImportLine("DLL override: d3d11", "the game's own DLL"),
                ImportLine("Environment: DXVK_HUD", "Set to 1 (now 0)"),
            ),
            plan.lines,
        )
        assertEquals(
            listOf(
                ImportLine("DXVK", "Already on"),
                ImportLine("Windows component: DirectSound", "Already on"),
                ImportLine("DLL override: mscms", "Already Wine's own DLL"),
            ),
            plan.alreadySet,
        )
    }

    @Test
    fun `turning DXVK off names Wine's own WineD3D`() {
        val plan = WinePrefixChanges(dxvk = false).against(state(dxvk = true))
        assertEquals(WinePrefixChanges(dxvk = false), plan.changes)
        assertEquals(listOf(ImportLine("DXVK", "Turn off: Direct3D goes through Wine's own WineD3D")), plan.lines)
    }

    @Test
    fun `a changed override says what it is now, a disabled one says disabled`() {
        val plan = WinePrefixChanges(dllOverrides = mapOf("d3d11" to "n", "mscms" to ""))
            .against(state(dllOverrides = mapOf("d3d11" to "b")))
        assertEquals(
            listOf(
                ImportLine("DLL override: d3d11", "the game's own DLL (now Wine's own DLL)"),
                ImportLine("DLL override: mscms", "disabled"),
            ),
            plan.lines,
        )
    }

    @Test
    fun `a request the prefix already meets is already set, never a change`() {
        val wanted = WinePrefixChanges(
            dxvk = true,
            esync = true,
            components = setOf("directsound"),
            dllOverrides = mapOf("mscms" to "b"),
            env = mapOf("DXVK_HUD" to "1"),
        )
        val plan = wanted.against(
            state(
                dxvk = true,
                esync = true,
                components = setOf("directsound"),
                dllOverrides = mapOf("mscms" to "b"),
                env = mapOf("DXVK_HUD" to "1"),
            ),
        )
        assertTrue(plan.changes.isEmpty)
        assertEquals(emptyList<ImportLine>(), plan.lines)
        assertEquals(
            listOf(
                ImportLine("DXVK", "Already on"),
                ImportLine("Esync", "Already on"),
                ImportLine("Windows component: DirectSound", "Already on"),
                ImportLine("DLL override: mscms", "Already Wine's own DLL"),
                ImportLine("Environment: DXVK_HUD", "Already 1"),
            ),
            plan.alreadySet,
        )
    }

    @Test
    fun `an empty request changes nothing`() {
        val plan = WinePrefixChanges().against(state())
        assertTrue(plan.changes.isEmpty)
        assertEquals(emptyList<ImportLine>(), plan.lines)
        assertEquals(emptyList<ImportLine>(), plan.alreadySet)
    }
}
