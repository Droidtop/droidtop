package dev.droidtop.runtime.remotestream

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** A GameStream host responded with a non-200 `status_code` in its XML envelope. */
class HostHttpResponseException(val statusCode: Int, statusMessage: String?) :
    Exception("Host returned error $statusCode: $statusMessage")

/**
 * Minimal port of NvHTTP's `getXmlString`/`verifyResponseStatus`: GameStream
 * responses are a flat `<root status_code="200">...<tag>value</tag>...</root>`
 * envelope: pull out one tag's text, or throw if the envelope itself reports
 * an error.
 */
internal object GameStreamXml {
    fun getString(xml: String, tagName: String, throwIfMissing: Boolean): String? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        val tagStack = ArrayDeque<String>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "root") verifyResponseStatus(parser)
                    tagStack.addLast(parser.name)
                }
                XmlPullParser.END_TAG -> tagStack.removeLastOrNull()
                XmlPullParser.TEXT -> {
                    if (tagStack.lastOrNull() == tagName) return parser.text
                }
            }
            eventType = parser.next()
        }

        if (throwIfMissing) {
            throw XmlNotFoundException("Missing mandatory field in host response: $tagName")
        }
        return null
    }

    private fun verifyResponseStatus(parser: XmlPullParser) {
        val statusCode = parser.getAttributeValue(null, "status_code")?.toLongOrNull()?.toInt() ?: return
        if (statusCode != 200) {
            val statusMessage = parser.getAttributeValue(null, "status_message")
            throw HostHttpResponseException(statusCode, statusMessage)
        }
    }
}

class XmlNotFoundException(message: String) : Exception(message)
