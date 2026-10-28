package dev.droidtop.pluginhost

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Add site and the list of added sites (docs/SPEC.md 12a "Download sites", Droidtop/tracker#648). */
class DownloadSitesTest {
    private val taken = DownloadSites.SHIPPED

    private fun refused(answer: DownloadSites.Added) = (answer as DownloadSites.Added.Refused).reason

    @Test fun `an https address is a site named for its domain, signing in on the address typed`() {
        val added = DownloadSites.parse(" https://www.example-host.com/login ", taken, emptyMap()) as DownloadSites.Added.Site
        assertEquals(DownloadSites.Site("example-host.com", "example-host.com", "https://www.example-host.com/login", shipped = false), added.site)
    }

    @Test fun `only https, and only a site`() {
        assertTrue(refused(DownloadSites.parse("http://example.com/login", taken, emptyMap())).contains("https"))
        assertTrue(refused(DownloadSites.parse("example.com", taken, emptyMap())).contains("https"))
        assertTrue(refused(DownloadSites.parse("https://localhost/", taken, emptyMap())).contains("not a site"))
    }

    @Test fun `a site on the list, or under one, is refused by name`() {
        assertEquals("Mega is already on the list", refused(DownloadSites.parse("https://mega.nz/login", taken, emptyMap())))
        assertEquals("Gofile is already on the list", refused(DownloadSites.parse("https://store.gofile.io/", taken, emptyMap())))
    }

    @Test fun `a site a plugin signs in to is that plugin's`() {
        assertEquals(
            "f95zone.to is the F95zone plugin's sign-in",
            refused(DownloadSites.parse("https://f95zone.to/login/", taken, mapOf("f95zone.to" to "F95zone"))),
        )
    }

    @Test fun `the added list keeps its sites in order and skips what it cannot read`() {
        val file = Files.createTempDirectory("sites").resolve("download-sites.json").toFile()
        val sites = listOf(
            DownloadSites.Site("One", "one.example", "https://one.example/login", shipped = false),
            DownloadSites.Site("two.example", "two.example", "https://two.example/", shipped = false),
        )
        DownloadSites.writeAdded(file, sites)
        assertEquals(sites, DownloadSites.readAdded(file))
        file.writeText("""[{"name":"x","domain":"x.example","signIn":"http://x.example/"},{"domain":"y.example","signIn":"https://y.example/"}]""")
        assertEquals(listOf(DownloadSites.Site("y.example", "y.example", "https://y.example/", shipped = false)), DownloadSites.readAdded(file))
    }
}
