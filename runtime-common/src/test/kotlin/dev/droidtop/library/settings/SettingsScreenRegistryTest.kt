package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deep-link resolution in [SettingsScreenRegistry]: one screen builder,
 * re-opened parameterized by its argument, never a second implementation
 * -- and the no-argument lookups every existing surface already makes
 * resolving exactly as before.
 */
class SettingsScreenRegistryTest {

    private fun screen(id: String, title: String, forDeepLink: ((String) -> CatalogScreen)? = null) =
        CatalogScreen(id = id, title = title, groups = { _ -> emptyList() }, forDeepLink = forDeepLink)

    @Test
    fun `a deep-link argument re-opens the same screen parameterized`() {
        SettingsScreenRegistry.register(screen("registry_test_deep_link", "Console systems") { arg ->
            screen("registry_test_deep_link", "Console systems for $arg")
        })
        assertEquals("Console systems", SettingsScreenRegistry.get("registry_test_deep_link")?.title)
        assertEquals("Console systems for n64", SettingsScreenRegistry.get("registry_test_deep_link", "n64")?.title)
    }

    @Test
    fun `a screen with no deep link resolves unchanged for an argument`() {
        SettingsScreenRegistry.register(screen("registry_test_plain", "Console systems"))
        assertEquals("Console systems", SettingsScreenRegistry.get("registry_test_plain")?.title)
        assertEquals("Console systems", SettingsScreenRegistry.get("registry_test_plain", "n64")?.title)
    }

    @Test
    fun `an unknown id resolves to nothing with or without an argument`() {
        assertNull(SettingsScreenRegistry.get("registry_test_missing"))
        assertNull(SettingsScreenRegistry.get("registry_test_missing", "n64"))
    }

    @Test
    fun `a saved stack resolves back to its screens`() {
        SettingsScreenRegistry.register(screen("registry_test_stack_a", "Accounts"))
        SettingsScreenRegistry.register(screen("registry_test_stack_b", "Updates"))

        val resolved = SettingsScreenRegistry.resolveStack(
            listOf("registry_test_stack_a", "registry_test_stack_b"),
        )

        assertEquals(listOf("registry_test_stack_a", "registry_test_stack_b"), resolved.map { it.id })
    }

    @Test
    fun `a saved stack resolves until an id nothing registers`() {
        SettingsScreenRegistry.register(screen("registry_test_stack_a", "Accounts"))

        val resolved = SettingsScreenRegistry.resolveStack(
            listOf("registry_test_stack_a", "registry_test_missing", "registry_test_stack_c"),
        )

        // The restore ends at the last screen that still resolves rather
        // than dropping the whole stack.
        assertEquals(listOf("registry_test_stack_a"), resolved.map { it.id })
    }

    @Test
    fun `a stack whose first id is gone resolves to nothing`() {
        assertEquals(emptyList<CatalogScreen>(), SettingsScreenRegistry.resolveStack(listOf("registry_test_missing")))
    }
}
