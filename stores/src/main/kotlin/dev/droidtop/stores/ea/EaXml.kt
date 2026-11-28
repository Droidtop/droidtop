package dev.droidtop.stores.ea

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** The same bounded XML boundary for installed manifests and official activation payloads. */
internal object EaXml {
    fun root(xml: String, maxChars: Int): Element {
        require(xml.length <= maxChars && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) {
            "EA XML contains unsupported declarations or is too large"
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External EA XML entities are not supported") }
            setErrorHandler(object : DefaultHandler() {
                override fun error(error: SAXParseException) { throw SAXException("EA XML could not be parsed") }
                override fun fatalError(error: SAXParseException) { throw SAXException("EA XML could not be parsed") }
            })
        }
        return builder.parse(InputSource(StringReader(xml))).documentElement
    }
}
