package dev.droidtop.runtime.remotestream

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Ported from NvHTTP.getAppListByReader: parses the `applist` endpoint's real XML shape. */
internal object AppListParser {
    fun parse(xml: String, hostAddress: String): List<RemoteApp> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        data class Building(var name: String? = null, var appId: Int? = null)

        val apps = mutableListOf<Building>()
        val tagStack = ArrayDeque<String>()
        var rootTerminated = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "root") {
                        val statusCode = parser.getAttributeValue(null, "status_code")?.toLongOrNull()?.toInt()
                        if (statusCode != null && statusCode != 200) {
                            throw HostHttpResponseException(statusCode, parser.getAttributeValue(null, "status_message"))
                        }
                    }
                    tagStack.addLast(parser.name)
                    if (parser.name == "App") apps.add(Building())
                }
                XmlPullParser.END_TAG -> {
                    tagStack.removeLastOrNull()
                    if (parser.name == "root") rootTerminated = true
                }
                XmlPullParser.TEXT -> {
                    val building = apps.lastOrNull()
                    when (tagStack.lastOrNull()) {
                        "AppTitle" -> building?.name = parser.text
                        "ID" -> building?.appId = parser.text?.toIntOrNull()
                    }
                }
            }
            eventType = parser.next()
        }

        if (!rootTerminated) throw XmlNotFoundException("Malformed XML: Root tag was not terminated")

        return apps.mapNotNull { building ->
            val name = building.name ?: return@mapNotNull null
            val appId = building.appId ?: return@mapNotNull null
            RemoteApp(hostAddress = hostAddress, appId = appId, name = name)
        }
    }
}
