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

    private val router = RemoteInputRouter(
        ownField = { own.takeIf { ownFocused } },
        ime = { ime.takeIf { imeFocused } },
        imeSelected = { imeSelected },
    )

    private fun session(listen: (HandoffEndpoint) -> HandoffListener = this::fake) =
        RemoteInput(clock = { now }, router = router, listen = listen, host = "192.168.1.20", random = SecureRandom(), clipboard = clipboard)

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
        input.clip("clipput", "code=$code&text=abc")
        input.close()
        assertEquals(RemoteInputState.Closed(RemoteInputEnd.PERSON), input.state.value)
        assertEquals(404, input.clip("clipget", "code=$code").status)
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
