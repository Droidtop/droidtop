package dev.droidtop.library.credentials.handoff

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typing from another device (docs/SPEC.md, "Type from another device", Droidtop/tracker#645): the token and code
 * checks, the code the console confirms, the one paired device, the reset on a refusal, the prompt timeout, the idle
 * limit, and which field a delivery lands in. Fakes for the clock, the listener and the fields; one test uses a real socket.
 */
class RemoteInputTest {
    private var now = 1_000L
    private val listeners = mutableListOf<FakeListener>()
    private val own = FakeTarget()
    private val ime = FakeTarget()
    private var ownFocused = false
    private var imeFocused = false
    private var imeSelected = false
    private var nextPort = 50_000

    private inner class FakeListener(val endpoint: HandoffEndpoint, val port: Int) : HandoffListener {
        var running = false

        override fun start(): Int {
            running = true
            return port
        }

        override fun stop() {
            running = false
        }
    }

    private class FakeTarget(override val secret: Boolean = false) : RemoteTarget {
        val log = mutableListOf<String>()

        override fun commit(text: String) { log += "text:$text" }

        override fun backspace(count: Int) { log += "back:$count" }

        override fun enter() { log += "enter" }
    }

    private class FakeClipboard : ConsoleClipboard {
        var held: ClipRead = ClipRead.Empty
        var refuse = false
        val written = mutableListOf<String>()
        var reads = 0

        override fun write(text: String): Boolean {
            if (refuse) return false
            written += text
            held = ClipRead.Text(text)
            return true
        }

        override fun read(): ClipRead {
            reads++
            return held
        }
    }

    private val clipboard = FakeClipboard()

    private class FakeIncoming(override val name: String) : IncomingFile {
        val bytes = java.io.ByteArrayOutputStream()
        var failWrites = false
        @Volatile var finished = false
        @Volatile var discarded = false

        override val out: java.io.OutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) {
                if (failWrites) throw java.io.IOException("disk full")
                bytes.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWrites) throw java.io.IOException("disk full")
                bytes.write(b, off, len)
            }
        }

        override fun finish() { finished = true }

        override fun discard() { discarded = true }
    }

    private class FakeFiles : ConsoleFiles {
        val existing = mutableSetOf<String>()
        val made: MutableList<FakeIncoming> = java.util.Collections.synchronizedList(mutableListOf())
        var unwritable = false
        var failWrites = false

        override fun folderLabel() = "Download/droidtop"

        override fun exists(name: String) = name in existing

        override fun create(name: String): IncomingFile? {
            if (unwritable) return null
            existing += name
            return FakeIncoming(name).also { it.failWrites = failWrites; made += it }
        }
    }

    private val files = FakeFiles()

    private val router = RemoteInputRouter(
        ownField = { own.takeIf { ownFocused } },
        ime = { ime.takeIf { imeFocused } },
        imeSelected = { imeSelected },
    )

    private fun session(listen: (HandoffEndpoint) -> HandoffListener = this::fake) =
        RemoteInput(clock = { now }, router = router, listen = listen, host = "192.168.1.20", random = SecureRandom(), clipboard = clipboard, files = files)

    private fun fake(endpoint: HandoffEndpoint): HandoffListener = FakeListener(endpoint, nextPort++).also { listeners += it }

    private fun RemoteInput.tokenOf(): String = url()!!.substringAfterLast('/')

    private fun RemoteInput.open(peer: String = PHONE, token: String = tokenOf()) =
        listeners.last().endpoint.handle("GET", "/r/$token", "", peer)

    private fun RemoteInput.codeOf(): String = (state.value as RemoteInputState.Confirming).code.replace(" ", "")

    private fun RemoteInput.send(
        body: String,
        peer: String = PHONE,
        token: String = tokenOf(),
    ) = listeners.last().endpoint.handle("POST", "/r/$token/in", body, peer)

    /** Opens the page from the phone and has the console confirm. */
    private fun RemoteInput.pair(): String {
        open()
        val code = codeOf()
        confirm()
        return code
    }

    // The token.

    @Test
    fun `the session listens on the lan address and the qr code is its token address`() {
        val input = session()
        input.start()
        assertEquals(1, listeners.size)
        assertTrue(listeners.single().running)
        val url = (input.state.value as RemoteInputState.Waiting).url
        assertEquals("http://192.168.1.20:50000/r/${input.tokenOf()}", url)
        assertTrue(input.tokenOf().length >= 22)
    }

    @Test
    fun `a wrong token, path or method is an empty 404 and shows no prompt`() {
        val input = session()
        input.start()
        val endpoint = listeners.last().endpoint
        for (target in listOf("/", "/r/", "/r/wrong", "/r/${input.tokenOf()}x", "/h/${input.tokenOf()}", "/r/${input.tokenOf()}/other/more")) {
            val reply = endpoint.handle("GET", target, "", PHONE)
            assertEquals(target, 404, reply.status)
            assertEquals("", reply.body)
        }
        assertEquals(404, endpoint.handle("PUT", "/r/${input.tokenOf()}", "", PHONE).status)
        assertTrue(input.state.value is RemoteInputState.Waiting)
    }

    @Test
    fun `tokens differ between sessions and the comparison is exact`() {
        val a = session().also { it.start() }.tokenOf()
        val b = session().also { it.start() }.tokenOf()
        assertNotEquals(a, b)
        assertTrue(RemoteInput.sameSecret(a, a))
        assertFalse(RemoteInput.sameSecret(a, a.dropLast(1)))
        assertFalse(RemoteInput.sameSecret(a, b))
        assertFalse(RemoteInput.sameSecret("482913", "482914"))
    }

    // The code and the console's confirmation.

    @Test
    fun `the first device is shown a six digit code, the console asks about it, and nothing is accepted before Confirm`() {
        val input = session()
        input.start()
        val reply = input.open()
        assertEquals(200, reply.status)
        val code = input.codeOf()
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
        assertTrue(reply.body.contains(RemoteInput.spaced(code)))
        assertEquals(RemoteInputState.Confirming(RemoteInput.spaced(code)), input.state.value)
        // The prompt is open: an edit with the right code is still refused and reaches no field.
        ownFocused = true
        assertEquals(403, input.send("code=$code&text=hi").status)
        assertEquals(emptyList<String>(), own.log)
    }

    @Test
    fun `after Confirm the paired device types, and a wrong code is refused`() {
        val input = session()
        input.start()
        ownFocused = true
        val code = input.pair()
        assertEquals(RemoteInputState.Paired(), input.state.value)
        assertEquals(200, input.send("code=$code&text=hello").status)
        assertEquals(403, input.send("code=000000&text=bad").status)
        assertEquals(403, input.send("text=nocode").status)
        assertEquals(listOf("text:hello"), own.log)
        assertEquals(RemoteInputState.Paired("hello"), input.state.value)
    }

    @Test
    fun `an edit applies backspaces, then text, then Enter, and bad edits are refused`() {
        val input = session()
        input.start()
        ownFocused = true
        val code = input.pair()
        assertEquals(200, input.send("code=$code&back=2&text=ab&enter=1").status)
        assertEquals(listOf("back:2", "text:ab", "enter"), own.log)
        assertEquals(400, input.send("code=$code&text=a%0Ab").status)
        assertEquals(400, input.send("code=$code&back=-1").status)
        assertEquals(400, input.send("code=$code&text=" + "x".repeat(2000)).status)
        assertEquals(3, own.log.size)
    }

    // One device.

    @Test
    fun `any other device is refused with 409 before and after pairing`() {
        val input = session()
        input.start()
        input.open()
        assertEquals(409, input.open(peer = "192.168.1.99").status)
        val code = input.codeOf()
        input.confirm()
        val other = listeners.last().endpoint
        assertEquals(409, input.open(peer = "192.168.1.99").status)
        assertEquals(409, other.handle("GET", "/r/${input.tokenOf()}/state", "", "192.168.1.99").status)
        ownFocused = true
        assertEquals(409, input.send("code=$code&text=intruder", peer = "192.168.1.99").status)
        assertEquals(emptyList<String>(), own.log)
        // The paired device itself can reload its page.
        assertEquals(200, input.open().status)
        assertTrue(input.open(peer = "192.168.1.99").body.contains("already connected"))
    }

    // The reset.

    @Test
    fun `Refuse closes the listener and starts a new one with a new port, token and code`() {
        val input = session()
        input.start()
        val oldToken = input.tokenOf()
        input.open()
        val oldCode = input.codeOf()
        val first = listeners.last()
        input.refuse()
        assertFalse(first.running)
        assertFalse(first.endpoint.isLive())
        assertEquals(2, listeners.size)
        val second = listeners.last()
        assertTrue(second.running)
        assertNotEquals(first.port, second.port)
        assertNotEquals(oldToken, input.tokenOf())
        val waiting = input.state.value as RemoteInputState.Waiting
        assertTrue(waiting.refused)
        assertEquals("http://192.168.1.20:${second.port}/r/${input.tokenOf()}", waiting.url)
        // The old address answers nothing, on the old endpoint and on the new one.
        assertEquals(404, first.endpoint.handle("GET", "/r/$oldToken", "", PHONE).status)
        assertEquals(404, second.endpoint.handle("GET", "/r/$oldToken", "", PHONE).status)
        // Pairing starts over, and the next code is never the refused one.
        assertEquals(200, input.open().status)
        assertNotEquals(oldCode, input.codeOf())
    }

    @Test
    fun `a refused device cannot use the old code, and the old paired state is gone`() {
        val input = session()
        input.start()
        ownFocused = true
        input.open()
        val oldCode = input.codeOf()
        input.refuse()
        assertEquals(403, input.send("code=$oldCode&text=x").status)
        assertEquals(emptyList<String>(), own.log)
    }

    @Test
    fun `a code is never used twice in a session`() {
        // A random source that offers the same number first and then others.
        val repeating = object : SecureRandom() {
            private var calls = 0

            override fun nextInt(bound: Int): Int = if (calls++ < 3) 482_913 else 123_456 + calls

            override fun nextBytes(bytes: ByteArray) = SecureRandom().nextBytes(bytes)
        }
        val input = RemoteInput(clock = { now }, router = router, listen = this::fake, host = "h", random = repeating)
        input.start()
        input.open()
        val first = input.codeOf()
        input.refuse()
        input.open()
        assertEquals("482913", first)
        assertNotEquals(first, input.codeOf())
    }

    @Test
    fun `sixty seconds with no answer resets the same way, and answering in time does not`() {
        val input = session()
        input.start()
        input.open()
        now += 59_000
        input.tick()
        assertEquals(1, listeners.size)
        assertTrue(input.state.value is RemoteInputState.Confirming)
        now += 1_000
        input.tick()
        assertEquals(2, listeners.size)
        assertFalse(listeners.first().running)
        assertTrue((input.state.value as RemoteInputState.Waiting).refused)
    }

    @Test
    fun `the server loop's own liveness check applies the prompt timeout`() {
        val input = session()
        input.start()
        input.open()
        val first = listeners.last()
        now += 60_000
        assertFalse(first.endpoint.isLive())
        assertTrue(listeners.last().endpoint.isLive())
    }

    @Test
    fun `a restart never lands on the port just closed`() {
        val ports = ArrayDeque(listOf(50_000, 50_000, 50_001))
        val input = session { endpoint -> FakeListener(endpoint, ports.removeFirst()).also { listeners += it } }
        input.start()
        input.open()
        input.refuse()
        assertEquals(50_001, (input.state.value as RemoteInputState.Waiting).url.substringAfterLast(':').substringBefore('/').toInt())
    }

    @Test
    fun `no network ends the session`() {
        val input = session { _ -> object : HandoffListener {
            override fun start(): Int = throw java.io.IOException("no route")

            override fun stop() = Unit
        } }
        input.start()
        assertEquals(RemoteInputState.Closed(RemoteInputEnd.NO_NETWORK), input.state.value)
        assertNull(input.url())
    }

    // The end.

    @Test
    fun `closing stops the listener and every request after it is refused`() {
        val input = session()
        input.start()
        val code = input.pair()
        val endpoint = listeners.last()
        input.close()
        assertFalse(endpoint.running)
        assertFalse(endpoint.endpoint.isLive())
        assertEquals(RemoteInputState.Closed(RemoteInputEnd.PERSON), input.state.value)
        assertEquals(404, endpoint.endpoint.handle("POST", "/r/x/in", "code=$code&text=a", PHONE).status)
        input.confirm()
        input.refuse()
        assertEquals(1, listeners.size)
    }

    @Test
    fun `ten minutes with nothing typed ends the session`() {
        val input = session()
        input.start()
        ownFocused = true
        val code = input.pair()
        now += 9 * 60_000
        input.send("code=$code&text=a")
        // The page's own status polling is not typing.
        now += 9 * 60_000
        listeners.last().endpoint.handle("GET", "/r/${input.tokenOf()}/state", "", PHONE)
        input.tick()
        assertTrue(input.state.value is RemoteInputState.Paired)
        now += 60_000
        input.tick()
        assertEquals(RemoteInputState.Closed(RemoteInputEnd.IDLE), input.state.value)
        assertFalse(listeners.last().running)
    }

    @Test
    fun `an unused session also ends after ten minutes`() {
        val input = session()
        input.start()
        now += 10 * 60_000
        input.tick()
        assertEquals(RemoteInputState.Closed(RemoteInputEnd.IDLE), input.state.value)
        assertFalse(listeners.single().running)
    }

    @Test
    fun `the console says what the session is doing`() {
        val input = session()
        input.start()
        assertEquals("Scan the QR code with a device on this network", input.state.value.consoleLine())
        input.open()
        assertEquals("Does your device show ${RemoteInput.spaced(input.codeOf())}?", input.state.value.consoleLine())
        input.confirm()
        assertEquals("Phone connected: typing", input.state.value.consoleLine())
    }

    // Where a delivery lands.

    @Test
    fun `a droidtop field is typed into first, even while droidtop's input method has an editor`() {
        ownFocused = true
        imeFocused = true
        imeSelected = true
        assertTrue(router.route() is Route.OwnField)
        assertEquals(Delivery.Sent(false), router.deliver(0, "a", false))
        assertEquals(listOf("text:a"), own.log)
        assertEquals(emptyList<String>(), ime.log)
    }

    @Test
    fun `otherwise droidtop's input method connection is used`() {
        imeFocused = true
        imeSelected = true
        assertTrue(router.route() is Route.Ime)
        router.deliver(1, "b", true)
        assertEquals(listOf("back:1", "text:b", "enter"), ime.log)
        assertEquals(emptyList<String>(), own.log)
    }

    @Test
    fun `otherwise the console is told to switch keyboard`() {
        assertEquals(Route.SwitchKeyboard, router.route())
        assertEquals(Delivery.Refused("Switch to droidtop's keyboard to type from another device"), router.deliver(0, "c", false))
        assertEquals(emptyList<String>(), own.log + ime.log)
        imeSelected = true
        assertEquals(Route.NoField, router.route())
    }

    @Test
    fun `a refused delivery reaches the console and the phone and types nothing`() {
        val input = session()
        input.start()
        val code = input.pair()
        val reply = input.send("code=$code&text=a")
        assertTrue(reply.body.contains("\"ok\":false"))
        assertTrue(reply.body.contains("Switch to droidtop's keyboard"))
        assertEquals(RemoteInputState.Paired(null, RemoteInputRouter.SWITCH_KEYBOARD), input.state.value)
    }

    // Passwords.

    @Test
    fun `text sent to a password field is masked on the console and the phone is told`() {
        val secretField = FakeTarget(secret = true)
        val secretRouter = RemoteInputRouter(ownField = { secretField }, ime = { null }, imeSelected = { false })
        val input = RemoteInput(clock = { now }, router = secretRouter, listen = this::fake, host = "h")
        input.start()
        val code = input.pair()
        val reply = input.send("code=$code&text=hunter2")
        assertTrue(reply.body.contains("\"secret\":true"))
        assertEquals(listOf("text:hunter2"), secretField.log)
        assertEquals(RemoteInputState.Paired("••••"), input.state.value)
        assertTrue(listeners.last().endpoint.handle("GET", "/r/${input.tokenOf()}/state", "", PHONE).body.contains("\"secret\":true"))
    }

    @Test
    fun `password input types are recognised`() {
        assertTrue(RemoteInputRouter.isPasswordInputType(0x81))
        assertTrue(RemoteInputRouter.isPasswordInputType(0x91))
        assertTrue(RemoteInputRouter.isPasswordInputType(0xe1))
        assertTrue(RemoteInputRouter.isPasswordInputType(0x12))
        assertFalse(RemoteInputRouter.isPasswordInputType(0x01))
        assertFalse(RemoteInputRouter.isPasswordInputType(0x21))
        assertFalse(RemoteInputRouter.isPasswordInputType(0x02))
    }

    // The shared clipboard.

    private fun RemoteInput.clip(
        path: String,
        body: String,
        peer: String = PHONE,
        token: String = tokenOf(),
        method: String = "POST",
    ) = listeners.last().endpoint.handle(method, "/r/$token/$path", body, peer)

    @Test
    fun `Send to console puts the text on the console clipboard and shows it on the console for a few seconds`() {
        val input = session()
        input.start()
        val code = input.pair()
        val reply = input.clip("clipput", "code=$code&text=" + java.net.URLEncoder.encode("two\nlines  of text", "UTF-8"))
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("\"ok\":true"))
        assertEquals(listOf("two\nlines  of text"), clipboard.written)
        assertEquals(RemoteInputState.Paired(clip = "two lines of text"), input.state.value)
        now += 7_000
        input.tick()
        assertEquals(RemoteInputState.Paired(clip = "two lines of text"), input.state.value)
        now += 1_000
        input.tick()
        assertEquals(RemoteInputState.Paired(), input.state.value)
    }

    @Test
    fun `the console shows only a line of a long clip`() {
        val input = session()
        input.start()
        val code = input.pair()
        input.clip("clipput", "code=$code&text=" + "x".repeat(1_000))
        val shown = (input.state.value as RemoteInputState.Paired).clip!!
        assertEquals(ClipRules.PREVIEW_CHARS + 1, shown.length)
        assertTrue(shown.endsWith("\u2026"))
        assertEquals(1_000, clipboard.written.single().length)
    }

    @Test
    fun `typing keeps the clip line while it is shown`() {
        val input = session()
        input.start()
        ownFocused = true
        val code = input.pair()
        input.clip("clipput", "code=$code&text=abc")
        input.send("code=$code&text=z")
        assertEquals(RemoteInputState.Paired("z", null, "abc"), input.state.value)
    }

    @Test
    fun `Get console clipboard returns the text, with every character kept`() {
        val input = session()
        input.start()
        val code = input.pair()
        clipboard.held = ClipRead.Text("a \"quoted\" <b>line</b>\nnext\\ \u2028 caf\u00e9")
        val reply = input.clip("clipget", "code=$code")
        assertEquals(200, reply.status)
        assertEquals("application/json", reply.contentType)
        assertTrue(reply.body.contains("\"ok\":true"))
        assertEquals("""{"ok":true,"text":"a \"quoted\" \u003cb>line\u003c/b>\nnext\\ \u2028 caf""" + "\u00e9\"}", reply.body)
        assertFalse(reply.body.contains("<"))
    }

    @Test
    fun `a clip marked sensitive is never sent to the phone, and says nothing of what it holds`() {
        val input = session()
        input.start()
        val code = input.pair()
        clipboard.held = ClipRead.Sensitive
        val reply = input.clip("clipget", "code=$code")
        assertTrue(reply.body.contains("\"ok\":false"))
        assertTrue(reply.body.contains("sensitive"))
        assertFalse(reply.body.contains("\"text\""))
    }

    @Test
    fun `an empty, non text or unreadable clipboard is told as such and sends no text`() {
        val input = session()
        input.start()
        val code = input.pair()
        for ((held, notice) in listOf(
            ClipRead.Empty to RemoteInput.CLIP_EMPTY,
            ClipRead.NotText to RemoteInput.CLIP_NOT_TEXT,
            ClipRead.Unavailable to RemoteInput.CLIP_UNAVAILABLE,
        )) {
            clipboard.held = held
            val reply = input.clip("clipget", "code=$code")
            assertEquals(RemoteInput.jsonString(notice), reply.body.substringAfter("\"notice\":").removeSuffix("}"))
            assertTrue(reply.body.contains("\"ok\":false"))
        }
    }

    @Test
    fun `the limit is 64 KB of text each way`() {
        val input = session()
        input.start()
        val code = input.pair()
        val atLimit = "a".repeat(ClipRules.MAX_BYTES)
        assertTrue(input.clip("clipput", "code=$code&text=$atLimit").body.contains("\"ok\":true"))
        assertEquals(atLimit, clipboard.written.single())
        val over = input.clip("clipput", "code=$code&text=${atLimit}a")
        assertEquals(413, over.status)
        assertEquals(1, clipboard.written.size)
        // Bytes, not characters: 32 769 two byte characters are over.
        val wide = "\u00e9".repeat(ClipRules.MAX_BYTES / 2 + 1)
        assertEquals(413, input.clip("clipput", "code=$code&text=" + java.net.URLEncoder.encode(wide, "UTF-8")).status)
        assertEquals(1, clipboard.written.size)
        clipboard.held = ClipRead.Text(atLimit + "a")
        val get = input.clip("clipget", "code=$code")
        assertTrue(get.body.contains("\"ok\":false"))
        assertFalse(get.body.contains("aaaa"))
    }

    @Test
    fun `only a clipboard send may carry a large body, and only with the right token`() {
        val input = session()
        input.start()
        val endpoint = listeners.last().endpoint
        val token = input.tokenOf()
        assertEquals(ClipRules.MAX_BODY, endpoint.maxBody("/r/$token/clipput"))
        assertTrue(ClipRules.MAX_BODY >= java.net.URLEncoder.encode("\u00e9".repeat(ClipRules.MAX_BYTES / 2), "UTF-8").length + 64)
        assertEquals(HandoffServer.MAX_BODY, endpoint.maxBody("/r/$token/in"))
        assertEquals(HandoffServer.MAX_BODY, endpoint.maxBody("/r/$token/clipget"))
        assertEquals(HandoffServer.MAX_BODY, endpoint.maxBody("/r/wrong/clipput"))
        assertEquals(HandoffServer.MAX_BODY, endpoint.maxBody("/r/$token"))
        input.close()
        assertEquals(HandoffServer.MAX_BODY, endpoint.maxBody("/r//clipput"))
    }

    @Test
    fun `the clipboard follows the pairing, the code and the token rules`() {
        val input = session()
        input.start()
        input.open()
        val code = input.codeOf()
        clipboard.held = ClipRead.Text("secret clip")
        // Before Confirm, nothing is read or written.
        assertEquals(403, input.clip("clipput", "code=$code&text=x").status)
        assertEquals(403, input.clip("clipget", "code=$code").status)
        input.confirm()
        // A wrong or missing code.
        assertEquals(403, input.clip("clipget", "code=000000").status)
        assertEquals(403, input.clip("clipget", "").status)
        assertEquals(403, input.clip("clipput", "text=x").status)
        // Another device, a wrong token, a wrong method.
        assertEquals(409, input.clip("clipget", "code=$code", peer = "192.168.1.99").status)
        assertEquals(409, input.clip("clipput", "code=$code&text=x", peer = "192.168.1.99").status)
        assertEquals(404, input.clip("clipget", "code=$code", token = "wrong").status)
        assertEquals(404, input.clip("clipget", "code=$code", method = "GET").status)
        assertEquals(404, input.clip("clipput", "code=$code&text=x", method = "PUT").status)
        assertEquals(0, clipboard.reads)
        assertEquals(emptyList<String>(), clipboard.written)
        // The paired device with the code is served.
        assertTrue(input.clip("clipget", "code=$code").body.contains("secret clip"))
    }

    @Test
    fun `a refused clipboard write is told to the phone and shown nowhere`() {
        val input = session()
        input.start()
        val code = input.pair()
        clipboard.refuse = true
        val reply = input.clip("clipput", "code=$code&text=hello")
        assertTrue(reply.body.contains("\"ok\":false"))
        assertEquals(RemoteInputState.Paired(), input.state.value)
        assertEquals(400, input.clip("clipput", "code=$code&text=a%00b").status)
        assertTrue(input.clip("clipput", "code=$code").body.contains("\"ok\":false"))
    }

    @Test
    fun `a clipboard request counts as the session being used`() {
        val input = session()
        input.start()
        val code = input.pair()
        now += 9 * 60_000
        input.clip("clipget", "code=$code")
        now += 9 * 60_000
        input.tick()
        assertTrue(input.state.value is RemoteInputState.Paired)
    }

    @Test
    fun `closing the session closes the clipboard with it`() {
        val input = session()
        input.start()
        val code = input.pair()
        val token = input.tokenOf()
        input.clip("clipput", "code=$code&text=abc")
        input.close()
        assertEquals(RemoteInputState.Closed(RemoteInputEnd.PERSON), input.state.value)
        // The session has no address once closed, so the old token is given by hand.
        assertEquals(404, input.clip("clipget", "code=$code", token = token).status)
    }

    @Test
    fun `the clip rules classify a clip before reading it`() {
        var asked = false
        val text = { asked = true; "hello" }
        assertEquals(ClipRead.Empty, ClipRules.classify(present = false, sensitive = false, isText = true, text = text))
        assertEquals(ClipRead.Sensitive, ClipRules.classify(present = true, sensitive = true, isText = true, text = text))
        assertEquals(ClipRead.NotText, ClipRules.classify(present = true, sensitive = false, isText = false, text = text))
        assertFalse("the text of a sensitive or non text clip is never read", asked)
        assertEquals(ClipRead.Text("hello"), ClipRules.classify(present = true, sensitive = false, isText = true, text = text))
        assertEquals(ClipRead.NotText, ClipRules.classify(present = true, sensitive = false, isText = true, text = { null }))
        assertEquals(ClipRead.Empty, ClipRules.classify(present = true, sensitive = false, isText = true, text = { "" }))
    }

    @Test
    fun `the page has a clipboard panel with its three buttons and no outside address`() {
        val html = RemoteInputPage.html("482913")
        assertTrue(html.contains("id=\"cput\"") && html.contains("id=\"cget\"") && html.contains("id=\"ccopy\""))
        assertTrue(html.contains("Send to console") && html.contains("Get console clipboard") && html.contains("Copy"))
        assertTrue(html.contains("'clipput'") && html.contains("'clipget'"))
        assertFalse(html.contains("http://") || html.contains("https://"))
    }

    @Test
    fun `a clipboard send over a real socket is read in full, past the small body limit`() {
        val input = RemoteInput(
            clock = { now },
            router = router,
            listen = { endpoint -> HandoffServer(endpoint, "127.0.0.1") },
            host = "127.0.0.1",
            state = MutableStateFlow(RemoteInputState.Idle),
            clipboard = clipboard,
        )
        input.start()
        val url = input.url()!!
        val port = url.substringAfterLast(':').substringBefore('/').toInt()
        val path = url.substringAfter("$port")
        fun request(target: String, body: String): String =
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                socket.soTimeout = 5_000
                val bytes = body.toByteArray()
                socket.getOutputStream().write(("POST $target HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray() + bytes)
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        fun headOnly(target: String, length: Int): String =
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("POST $target HTTP/1.1\r\nContent-Length: $length\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        try {
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("GET $path HTTP/1.1\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes()
            }
            val code = input.codeOf()
            input.confirm()
            val big = "b".repeat(ClipRules.MAX_BYTES)
            assertTrue(request("$path/clipput", "code=$code&text=$big").startsWith("HTTP/1.1 200"))
            assertEquals(big, clipboard.written.single())
            // A body larger than the path allows is refused from its head, unread (only the head is sent here).
            assertTrue(headOnly("$path/in", HandoffServer.MAX_BODY + 1).startsWith("HTTP/1.1 413"))
            assertTrue(headOnly("$path/clipget", HandoffServer.MAX_BODY + 1).startsWith("HTTP/1.1 413"))
            assertTrue(headOnly("$path/clipput", ClipRules.MAX_BODY + 1).startsWith("HTTP/1.1 413"))
        } finally {
            input.close()
        }
    }

    // File transfer (slice 3).

    private fun enc(text: String) = java.net.URLEncoder.encode(text, "UTF-8")

    /** One upload as the server would run it: the head decides, then the body is streamed from [bytes] (or [stream]). */
    private fun RemoteInput.put(
        name: String,
        bytes: ByteArray,
        code: String?,
        peer: String = PHONE,
        token: String = tokenOf(),
        length: Long? = bytes.size.toLong(),
        stream: java.io.InputStream = bytes.inputStream(),
        method: String = "POST",
    ): HandoffReply {
        val query = (if (code == null) "" else "code=$code&") + "name=${enc(name)}"
        val target = "/r/$token/put?$query"
        val endpoint = listeners.last().endpoint
        return when (val up = endpoint.receive(method, target, length, peer)) {
            null -> endpoint.handle(method, target, "", peer)
            is HandoffUpload.Refuse -> up.reply
            is HandoffUpload.Accept -> up.accept(stream)
        }
    }

    private fun RemoteInput.get(id: Int, code: String?, peer: String = PHONE, token: String = tokenOf()) =
        listeners.last().endpoint.handle("GET", "/r/$token/get/$id" + (if (code == null) "" else "?code=$code"), "", peer)

    private fun RemoteInput.stateBody() = listeners.last().endpoint.handle("GET", "/r/${tokenOf()}/state", "", PHONE).body

    @Test
    fun `the console line says what is being received and what arrived`() {
        assertEquals("Receiving a.bin (1.0 KB)", RemoteInputState.Paired(receiving = "a.bin (1.0 KB)").consoleLine())
        assertEquals("Phone sent a.bin (1.0 KB)", RemoteInputState.Paired(received = "Phone sent a.bin (1.0 KB)").consoleLine())
        assertEquals("Phone connected: typing", RemoteInputState.Paired(offered = "b (1 B)").consoleLine())
    }

    @Test
    fun `a file from the phone is streamed into the chosen folder and the console says who sent what`() {
        val input = session()
        input.start()
        val code = input.pair()
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val reply = input.put("Holiday.jpg", data, code)
        assertEquals(200, reply.status)
        assertEquals("""{"ok":true,"name":"Holiday.jpg","size":300000}""", reply.body)
        val made = files.made.single()
        assertEquals("Holiday.jpg", made.name)
        assertTrue(data.contentEquals(made.bytes.toByteArray()))
        assertTrue(made.finished)
        assertFalse(made.discarded)
        assertEquals(RemoteInputState.Paired(received = "Phone sent Holiday.jpg (293.0 KB)"), input.state.value)
    }

    @Test
    fun `the name is sanitised and a taken name becomes Name (2)`() {
        val input = session()
        input.start()
        val code = input.pair()
        input.put("../../etc/cron.d/evil", "x".toByteArray(), code)
        assertEquals("evil", files.made.last().name)
        input.put("a.txt", "1".toByteArray(), code)
        input.put("a.txt", "2".toByteArray(), code)
        input.put("C:\\dir\\a.txt", "3".toByteArray(), code)
        assertEquals(listOf("evil", "a.txt", "a (2).txt", "a (3).txt"), files.made.map { it.name })
        assertEquals("2", files.made[2].bytes.toString())
        // The reply and the console tell the name the file was stored under.
        assertEquals("Phone sent a (3).txt (1 B)", (input.state.value as RemoteInputState.Paired).received)
    }

    @Test
    fun `a file of 4 GB is allowed and one byte over is refused before anything is read or created`() {
        val input = session()
        input.start()
        val code = input.pair()
        val endpoint = listeners.last().endpoint
        val head = "/r/${input.tokenOf()}/put?code=$code&name=big.bin"
        assertTrue(endpoint.receive("POST", head, FileNames.MAX_BYTES, PHONE) is HandoffUpload.Accept)
        val over = endpoint.receive("POST", head, FileNames.MAX_BYTES + 1, PHONE) as HandoffUpload.Refuse
        assertEquals(413, over.reply.status)
        assertTrue(over.reply.body.contains(RemoteInput.FILE_TOO_LARGE))
        assertEquals(emptyList<FakeIncoming>(), files.made)
        assertEquals(RemoteInputState.Paired(), input.state.value)
    }

    @Test
    fun `an upload follows the pairing, code, device and token rules, and is refused before the body is read`() {
        val input = session()
        input.start()
        val endpoint = listeners.last().endpoint
        val token = input.tokenOf()
        val data = "data".toByteArray()
        // Nobody has opened the page yet.
        assertEquals(403, input.put("a.bin", data, "123456").status)
        input.open()
        val code = input.codeOf()
        // The prompt is open: not before Confirm.
        assertEquals(403, input.put("a.bin", data, code).status)
        input.confirm()
        assertEquals(403, input.put("a.bin", data, null).status)
        assertEquals(403, input.put("a.bin", data, "000000").status)
        assertEquals(409, input.put("a.bin", data, code, peer = "192.168.1.99").status)
        // A missing Content-Length is a 411.
        assertEquals(411, input.put("a.bin", data, code, length = null).status)
        // A wrong token or another method is not a streamed request at all: the same empty 404.
        assertNull(endpoint.receive("POST", "/r/wrong/put?code=$code", 4L, PHONE))
        assertEquals(404, input.put("a.bin", data, code, token = "wrong").status)
        assertEquals(404, input.put("a.bin", data, code, method = "GET").status)
        assertNull(endpoint.receive("POST", "/r/$token/in", 4L, PHONE))
        assertNull(endpoint.receive("POST", "/r/$token/clipput", 4L, PHONE))
        assertEquals(emptyList<FakeIncoming>(), files.made)
        // The paired device with the code is served.
        assertEquals(200, input.put("a.bin", data, code).status)
        assertEquals(1, files.made.size)
    }

    @Test
    fun `the ordinary body cap is not raised for any request an upload does not take`() {
        val input = session()
        input.start()
        val endpoint = listeners.last().endpoint
        val token = input.tokenOf()
        for (target in listOf("/r/$token/put", "/r/$token/in", "/r/$token/clipget", "/r/wrong/put")) {
            assertEquals(target, HandoffServer.MAX_BODY, endpoint.maxBody(target))
        }
    }

    @Test
    fun `a file that stops short is discarded, not kept, and the console says so`() {
        val input = session()
        input.start()
        val code = input.pair()
        val reply = input.put("half.bin", ByteArray(10), code, length = 100)
        assertTrue(reply.body.contains("\"ok\":false"))
        assertTrue(reply.body.contains(RemoteInput.FILE_INCOMPLETE))
        val made = files.made.single()
        assertTrue(made.discarded)
        assertFalse(made.finished)
        val shown = input.state.value as RemoteInputState.Paired
        assertNull(shown.received)
        assertNull(shown.receiving)
        assertTrue(shown.notice!!.startsWith("Could not receive half.bin"))
    }

    @Test
    fun `a connection that breaks in the middle discards the file`() {
        val input = session()
        input.start()
        val code = input.pair()
        val breaking = object : java.io.InputStream() {
            private var sent = 0

            override fun read(): Int = throw java.io.IOException("reset")

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent > 0) throw java.io.IOException("reset")
                sent = 50
                return 50
            }
        }
        input.put("cut.bin", ByteArray(0), code, length = 500, stream = breaking)
        assertTrue(files.made.single().discarded)
        assertNull((input.state.value as RemoteInputState.Paired).received)
    }

    @Test
    fun `a full disk or an unwritable folder fails the upload and keeps no part of it`() {
        val input = session()
        input.start()
        val code = input.pair()
        files.failWrites = true
        val full = input.put("a.bin", ByteArray(1000), code)
        assertTrue(full.body.contains(RemoteInput.FILE_NOT_STORED.take(30)))
        assertTrue(files.made.single().discarded)
        files.failWrites = false
        files.unwritable = true
        val none = input.put("b.bin", ByteArray(10), code)
        assertTrue(none.body.contains("\"ok\":false"))
        assertEquals(1, files.made.size)
        assertTrue((input.state.value as RemoteInputState.Paired).notice!!.contains(RemoteInput.NO_FOLDER))
    }

    @Test
    fun `closing the session in the middle of a file stops it and discards it`() {
        val input = session()
        input.start()
        val code = input.pair()
        var reads = 0
        val closing = object : java.io.InputStream() {
            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reads++ == 1) input.close()
                return minOf(len, 100)
            }
        }
        input.put("long.bin", ByteArray(0), code, length = 10_000_000, stream = closing)
        assertEquals(2, reads)
        assertTrue(files.made.single().discarded)
        assertEquals(RemoteInputState.Closed(RemoteInputEnd.PERSON), input.state.value)
    }

    @Test
    fun `an upload counts as the session being used`() {
        val input = session()
        input.start()
        val code = input.pair()
        now += 9 * 60_000
        input.put("a.bin", ByteArray(5), code)
        now += 9 * 60_000
        input.tick()
        assertTrue(input.state.value is RemoteInputState.Paired)
    }

    @Test
    fun `the console offers one file and the page learns of it, for this session only`() {
        val input = session()
        input.start()
        val other = OfferedFile("a.txt", 5) { "hello".byteInputStream() }
        // Offered before the phone is paired: shown once it is.
        assertTrue(input.offer(other))
        val code = input.pair()
        assertEquals(RemoteInputState.Paired(offered = "a.txt (5 B)"), input.state.value)
        assertEquals("""{"state":"paired","secret":false,"offer":{"id":1,"name":"a.txt","size":"5 B"}}""", input.stateBody())
        val reply = input.get(1, code)
        assertEquals(200, reply.status)
        assertEquals("application/octet-stream", reply.contentType)
        val download = reply.download!!
        assertEquals("a.txt", download.name)
        assertEquals(5L, download.length)
        assertEquals("hello", download.stream.readBytes().toString(Charsets.UTF_8))
        // A second offer replaces it: the first link answers nothing.
        assertTrue(input.offer(OfferedFile("b.txt", 1) { "b".byteInputStream() }))
        assertEquals(404, input.get(1, code).status)
        assertEquals(200, input.get(2, code).status)
        input.withdrawOffer()
        assertEquals(404, input.get(2, code).status)
        assertTrue(input.stateBody().contains("\"offer\":null"))
        assertEquals(RemoteInputState.Paired(), input.state.value)
    }

    @Test
    fun `a download follows the pairing, code, device and token rules`() {
        val input = session()
        input.start()
        input.offer(OfferedFile("a.txt", 1) { "a".byteInputStream() })
        assertEquals(403, input.get(1, "123456").status)
        input.open()
        val code = input.codeOf()
        assertEquals(403, input.get(1, code).status)
        input.confirm()
        assertEquals(403, input.get(1, null).status)
        assertEquals(403, input.get(1, "000000").status)
        assertEquals(409, input.get(1, code, peer = "192.168.1.99").status)
        assertEquals(404, input.get(1, code, token = "wrong").status)
        assertEquals(404, input.get(7, code).status)
        assertNull(input.get(1, code).takeIf { it.status != 200 }?.download)
        assertEquals(200, input.get(1, code).status)
    }

    @Test
    fun `a refusal, or the end, takes the offer and its link away, and a file that cannot be opened is a page not a crash`() {
        val input = session()
        input.start()
        input.pair()
        input.offer(OfferedFile("a.txt", 1) { "a".byteInputStream() })
        input.close()
        assertFalse(input.offer(OfferedFile("b.txt", 1) { "b".byteInputStream() }))
        val again = session()
        again.start()
        val code = again.pair()
        again.offer(OfferedFile("gone.txt", 1) { throw java.io.FileNotFoundException("gone") })
        val reply = again.get(1, code)
        assertEquals(404, reply.status)
        assertNull(reply.download)
        // A refusal while an offer stands starts over without it.
        val third = session()
        third.start()
        third.offer(OfferedFile("a.txt", 1) { "a".byteInputStream() })
        third.open()
        third.refuse()
        val code3 = third.run { open(); codeOf() }
        third.confirm()
        assertEquals(404, third.get(1, code3).status)
        assertTrue(third.stateBody().contains("\"offer\":null"))
    }

    @Test
    fun `a download in the middle stops when the session closes, and the offered name is cleaned`() {
        val input = session()
        input.start()
        val code = input.pair()
        input.offer(OfferedFile("../x/\"y\".bin", 3) { "abc".byteInputStream() })
        val download = input.get(1, code).download!!
        assertEquals("_y_.bin", download.name)
        input.close()
        val failed = runCatching { download.stream.read(ByteArray(2)) }.exceptionOrNull()
        assertTrue(failed is java.io.IOException)
    }

    @Test
    fun `the page has Send a file and the offered file's link, and nothing from outside`() {
        val html = RemoteInputPage.html("482913")
        assertTrue(html.contains("Send a file") && html.contains("id=\"fsend\"") && html.contains("id=\"offer\""))
        assertTrue(html.contains("/put?code=") && html.contains("/get/"))
        assertFalse(html.contains("http://") || html.contains("https://"))
    }

    @Test
    fun `files travel over a real socket in both directions, streamed, and a broken upload does not end the listener`() {
        val input = RemoteInput(
            clock = { now },
            router = router,
            listen = { endpoint -> HandoffServer(endpoint, "127.0.0.1") },
            host = "127.0.0.1",
            state = MutableStateFlow(RemoteInputState.Idle),
            files = files,
        )
        input.start()
        val url = input.url()!!
        val port = url.substringAfterLast(':').substringBefore('/').toInt()
        val path = url.substringAfter("$port")
        fun connect() = Socket(InetAddress.getByName("127.0.0.1"), port).also { it.soTimeout = 10_000 }
        fun text(target: String, method: String = "GET", length: Long? = null): String = connect().use { socket ->
            val extra = if (length == null) "" else "Content-Length: $length\r\n"
            socket.getOutputStream().write("$method $target HTTP/1.1\r\n$extra\r\n".toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
        try {
            text(path)
            val code = input.codeOf()
            input.confirm()
            // 20 MB: a thousand times the small body cap, held nowhere but on its way to the fake disk.
            val size = 20 * 1024 * 1024
            val data = ByteArray(size) { (it * 31 + it / 7).toByte() }
            connect().use { socket ->
                val out = socket.getOutputStream()
                out.write("POST $path/put?code=$code&name=big%20file.bin HTTP/1.1\r\nContent-Length: $size\r\n\r\n".toByteArray())
                out.write(data)
                out.flush()
                val answer = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(answer, answer.startsWith("HTTP/1.1 200"))
                assertTrue(answer.contains("\"name\":\"big file.bin\""))
            }
            val stored = files.made.single()
            assertEquals("big file.bin", stored.name)
            assertTrue(data.contentEquals(stored.bytes.toByteArray()))
            // A head that promises more than 4 GB is refused with a 413 and its body is never asked for.
            val over = text("$path/put?code=$code&name=x", "POST", FileNames.MAX_BYTES + 1)
            assertTrue(over, over.startsWith("HTTP/1.1 413"))
            assertTrue(over.contains("4 GB"))
            // The wrong token gets the small cap, not the file cap.
            assertTrue(text("/r/wrong/put?code=$code", "POST", 5_000_000).startsWith("HTTP/1.1 413"))
            // A phone that goes away half way through leaves the listener serving, and no file.
            connect().use { socket ->
                socket.getOutputStream().write("POST $path/put?code=$code&name=cut.bin HTTP/1.1\r\nContent-Length: 1000000\r\n\r\n".toByteArray())
                socket.getOutputStream().write(ByteArray(1000))
                socket.getOutputStream().flush()
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (files.made.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            while (!files.made.last().discarded && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(files.made[1].discarded)
            assertTrue(text("$path/state").startsWith("HTTP/1.1 200"))
            // The console's file comes down with its length and a name the browser must save, never show.
            val offered = ByteArray(1_500_000) { (it % 97).toByte() }
            assertTrue(input.offer(OfferedFile("Tr\u00e4ck.bin", offered.size.toLong()) { offered.inputStream() }))
            val raw = connect().use { socket ->
                socket.getOutputStream().write("GET $path/get/1?code=$code HTTP/1.1\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes()
            }
            val split = String(raw, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val head = String(raw, 0, split, Charsets.ISO_8859_1)
            assertTrue(head, head.startsWith("HTTP/1.1 200"))
            assertTrue(head, head.contains("Content-Length: ${offered.size}"))
            assertTrue(head, head.contains("Content-Disposition: attachment; filename=\"Tr_ck.bin\"; filename*=UTF-8''Tr%C3%A4ck.bin"))
            assertTrue(head.contains("X-Content-Type-Options: nosniff"))
            assertTrue(offered.contentEquals(raw.copyOfRange(split + 4, raw.size)))
            // And a wrong code gets no file.
            assertTrue(text("$path/get/1?code=000000").startsWith("HTTP/1.1 403"))
        } finally {
            input.close()
        }
    }

    // The page and the socket.

    @Test
    fun `the page is static - no outside address, one box and its buttons`() {
        val html = RemoteInputPage.html("482913")
        assertTrue(html.contains("482 913"))
        assertTrue(html.contains("id=\"send\"") && html.contains("id=\"enter\"") && html.contains("id=\"back\"") && html.contains("id=\"live\""))
        assertFalse(html.contains("http://") || html.contains("https://"))
        assertFalse(html.contains("src="))
    }

    @Test
    fun `the server answers over a real socket on the address it was given, and stops listening when stopped`() {
        val input = RemoteInput(
            clock = { now },
            router = router,
            listen = { endpoint -> HandoffServer(endpoint, "127.0.0.1") },
            host = "127.0.0.1",
            state = MutableStateFlow(RemoteInputState.Idle),
        )
        input.start()
        val url = input.url()!!
        val port = url.substringAfterLast(':').substringBefore('/').toInt()
        try {
            assertTrue(port in 49152..65535)
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("GET ${url.substringAfter("$port")} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(text.startsWith("HTTP/1.1 200"))
                assertTrue(text.contains("Cache-Control: no-store"))
            }
            assertTrue(input.state.value is RemoteInputState.Confirming)
            // An oversized body is refused before it is read.
            Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("POST /r/x/in HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 413"))
            }
        } finally {
            input.close()
        }
        assertNotNull(input.state.value as? RemoteInputState.Closed)
        val refused = runCatching { Socket(InetAddress.getByName("127.0.0.1"), port).use { } }.isFailure
        assertTrue("the port must not accept connections after close", refused)
    }

    @Test
    fun `only local network peers are served`() {
        assertTrue(HandoffServer.isLocalPeer(InetAddress.getByName("192.168.1.20")))
        assertTrue(HandoffServer.isLocalPeer(InetAddress.getByName("10.0.0.5")))
        assertTrue(HandoffServer.isLocalPeer(InetAddress.getByName("127.0.0.1")))
        assertFalse(HandoffServer.isLocalPeer(InetAddress.getByName("8.8.8.8")))
        assertFalse(HandoffServer.isLocalPeer(null))
    }

    @Test
    fun `the lan address is the site local ipv4 one`() {
        val picked = HandoffServer.pickLanAddress(
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("fe80::1"), InetAddress.getByName("192.168.1.20")),
        )
        assertEquals("192.168.1.20", picked)
        assertNull(HandoffServer.pickLanAddress(listOf(InetAddress.getByName("8.8.8.8"))))
    }

    private companion object {
        const val PHONE = "192.168.1.50"
    }
}
