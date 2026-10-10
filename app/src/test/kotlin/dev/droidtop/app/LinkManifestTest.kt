package dev.droidtop.app

import dev.droidtop.library.integrations.ActionLinks
import dev.droidtop.library.integrations.PluginCatalogSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hosts LinkActivity declares for droidtop's own scheme against the hosts the parser answers
 * (Droidtop/tracker#477: the manifest said `do` while ActionLinks wanted `call`, so no
 * droidtop://call link ever resolved).
 */
class LinkManifestTest {
    @Test fun theManifestDeclaresExactlyTheHostsActionLinksReads() {
        val manifest = File("src/main/AndroidManifest.xml").readText().replace(Regex("(?s)<!--.*?-->"), "")
        val declared = Regex("""<data\s+android:scheme="droidtop"\s+android:host="([^"]+)"\s*/>""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(ActionLinks.HOST, PluginCatalogSources.LINK_HOST, PluginCatalogSources.INSTALL_LINK_HOST),
            declared,
        )
        // Every declared host is one the parser accepts as an action link.
        declared.forEach { host ->
            assertTrue("droidtop://$host is declared but not an action link", ActionLinks.isActionLink("droidtop://$host?v=2"))
        }
    }
}
