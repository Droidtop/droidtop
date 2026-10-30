package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plugin's words are cleaned where they enter droidtop (Droidtop/tracker#784 slice 4: "sanitize it against any injection attacks"). */
class PluginTextTest {
    @Test
    fun `markup and entities are removed, so nothing reads as a page's own`() {
        assertEquals("Click here now", PluginText.clean("<a href=\"https://evil.example\">Click</a> here &amp; now"))
        assertEquals("alert(1) hi", PluginText.clean("<script>alert(1)</script> hi"))
    }

    @Test
    fun `bidi overrides, zero-width characters and the byte-order mark are dropped`() {
        // U+202E would show "exe.txt" as "txt.exe"; U+2066 to U+2069 isolate, U+200B is zero-width, U+FEFF the BOM.
        assertEquals("Saved file.txtexe", PluginText.clean("Saved file.\u202Etxt\u202Cexe"))
        assertEquals("ab", PluginText.clean("a\u2066\u200B\uFEFF\u2069b"))
    }

    @Test
    fun `control characters become spaces and runs of space become one`() {
        assertEquals("one two three", PluginText.clean("one\n\ttwo\u0000\u0007 three"))
    }

    @Test
    fun `lone surrogates are dropped and emoji are kept`() {
        assertEquals("ok 🎮", PluginText.clean("ok \uD800 🎮"))
    }

    @Test
    fun `text is bounded, and nothing left is null`() {
        val long = PluginText.clean("w ".repeat(500), 20)!!
        assertTrue(long.length <= 20)
        assertTrue(long.endsWith("…"))
        assertNull(PluginText.clean(" <br> \u200B "))
        assertNull(PluginText.clean(null))
    }

    @Test
    fun `a reply's error words are cleaned when it is read, and a permission must look like one`() {
        val reply = PluginReply.parse(
            """{"ok":false,"error":{"code":"FAILED","message":"<b>Bad</b>\u202E thing","title":"T\u0000itle","permission":"web.session; rm -rf","detail":"<p>d</p>"}}""",
        )
        assertEquals("Bad thing", reply.message)
        assertEquals("T itle", reply.title)
        assertNull(reply.permission)
        assertEquals("d", reply.detail)
        assertEquals("Bad thing", PluginReply.error(PluginErrorCode.FAILED, "Bad\u202E thing").message)
        assertEquals("web.session", PluginReply.parse("""{"ok":false,"error":{"code":"PERMISSION_DENIED","message":"x","permission":"web.session"}}""").permission)
    }

    @Test
    fun `a job's failure and its shown values are cleaned, its data is not`() {
        assertEquals("Nope", PluginResult.failure("<i>Nope</i>").error)
        val ok = PluginResult.success(mapOf("summary" to "Added\u202E x", "filePath" to "/data/<odd>/name"))
        assertEquals("Added x", ok.values["summary"])
        assertEquals("/data/<odd>/name", ok.values["filePath"])
    }

    @Test
    fun `the shared codes are droidtop's words, and a plugin's own only for FAILED, and never with a link`() {
        assertEquals(SourceFailures.SLOW_DOWN, SourceFailures.forCode(PluginErrorCode.RATE_LIMITED, "please wait 5 min"))
        assertEquals(SourceFailures.GONE, SourceFailures.forCode(PluginErrorCode.NOT_FOUND, "Thread was deleted"))
        assertEquals(SourceFailures.SIGN_IN, SourceFailures.forCode(PluginErrorCode.SIGN_IN_REQUIRED, "log in at evil.example"))
        assertEquals("The thread is locked", SourceFailures.forCode(PluginErrorCode.FAILED, "The thread is locked"))
        assertEquals(SourceFailures.SEE_DETAILS, SourceFailures.forCode(PluginErrorCode.FAILED, "Update your account at https://evil.example"))
        assertEquals(SourceFailures.SEE_DETAILS, SourceFailures.forCode(PluginErrorCode.FAILED, "Visit www.evil.example now"))
        assertEquals(SourceFailures.SEE_DETAILS, SourceFailures.forCode(PluginErrorCode.FAILED, "Pay at evil-site.com first"))
    }

    @Test
    fun `a source's failure is said in one shape`() {
        assertEquals(
            "F95zone could not open its page: it is no longer on the site",
            SourceFailures.line("F95zone", "open its page", PluginErrorCode.NOT_FOUND, null),
        )
    }
}
