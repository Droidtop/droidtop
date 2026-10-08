package dev.droidtop.library.credentials.handoff

import java.net.URLDecoder

class HandoffReply(val status: Int, val body: String, val contentType: String = "text/html; charset=utf-8")

/**
 * The page a phone or PC browser opens and what it may do (docs/SPEC.md 7h).
 * Two routes only, `GET /h/<token>` (the form) and `POST /h/<token>` (the
 * values); everything else, and every wrong token, gets the same empty 404 so
 * the answer says nothing about what exists. No external resources: one inline
 * form, no script. Pure: [HandoffServer] feeds it parsed requests.
 */
class HandoffHttp(private val session: HandoffSession, private val title: String) {

    fun handle(method: String, target: String, body: String): HandoffReply {
        val path = target.substringBefore('?')
        val token = path.removePrefix("/h/").takeIf { path.startsWith("/h/") && !it.contains('/') } ?: return NOT_FOUND
        return when (method) {
            "GET" -> session.admit(token).let { admission ->
                if (admission == HandoffSession.Admission.OK) HandoffReply(200, form(null)) else denied(admission)
            }
            "POST" -> when (val result = session.submit(token, parseForm(body))) {
                HandoffSession.Submit.Accepted -> HandoffReply(200, page("<p>Sent. Check the console and confirm there.</p>"))
                is HandoffSession.Submit.Invalid -> HandoffReply(400, form(result.fieldIds))
                is HandoffSession.Submit.Denied -> denied(result.reason)
            }
            else -> NOT_FOUND
        }
    }

    private fun denied(reason: HandoffSession.Admission): HandoffReply = when (reason) {
        HandoffSession.Admission.RATE_LIMITED -> HandoffReply(429, page("<p>Too many tries. Wait a minute.</p>"))
        HandoffSession.Admission.EXPIRED -> HandoffReply(410, page("<p>This page has expired. Start again on the console.</p>"))
        HandoffSession.Admission.USED -> HandoffReply(410, page("<p>Already used.</p>"))
        else -> NOT_FOUND
    }

    private fun form(invalid: Set<String>?): String {
        val rows = session.fields.joinToString("") { field ->
            val mark = if (invalid != null && field.id in invalid) " class=\"bad\"" else ""
            "<label$mark>${esc(field.label)}<input name=\"${esc(field.id)}\" " +
                "type=\"${if (field.secret) "password" else "text"}\" maxlength=\"${field.maxLength}\" " +
                "autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\" required></label>"
        }
        return page("<form method=\"post\">$rows<button type=\"submit\">Send</button></form>")
    }

    private fun page(inner: String): String =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta name=\"referrer\" content=\"no-referrer\"><title>${esc(title)}</title>" +
            "<style>body{font:18px system-ui,sans-serif;margin:24px;max-width:28em}" +
            "label{display:block;margin:0 0 16px}input{display:block;width:100%;box-sizing:border-box;" +
            "font-size:18px;padding:10px;margin-top:6px}.bad{color:#b00020}button{font-size:18px;padding:10px 24px}" +
            "</style></head><body><h1>${esc(title)}</h1>$inner</body></html>"

    companion object {
        val NOT_FOUND = HandoffReply(404, "")

        /** Headers every reply carries: nothing cached, nothing embedded, nothing leaked in a Referer. */
        val SECURITY_HEADERS = listOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'",
            "Referrer-Policy" to "no-referrer",
            "X-Content-Type-Options" to "nosniff",
        )

        fun parseForm(body: String): Map<String, String> =
            body.split('&').filter { it.contains('=') }.associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }

        private fun esc(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
