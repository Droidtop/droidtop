package dev.droidtop.stores.ea

// Ported from DroidDeck app/src/main/java/com/droiddeck/launcher/stores/ea/EaManifest.kt
// at cc63b403872b (GPL-3.0; see NOTICE.md); adapted to droidtop's store and HTTP interfaces.

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** What `__Installer/installerdata.xml` in an EA build says about running and finishing the install. */
class EaManifest(
    val version: String,
    val launchers: List<Launcher>,
    val touchupPath: String,
    val touchupParameters: String,
    val contentIds: List<String> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
) {
    fun title(locale: String): String? = titles[locale] ?: titles["en_US"] ?: titles.values.firstOrNull()

    class Launcher(val path: String, val parameters: String, val trial: Boolean)

    fun launcher(): Launcher? = launchers.firstOrNull { !it.trial } ?: launchers.firstOrNull()

    companion object {
        const val RELATIVE = "__Installer/installerdata.xml"

        fun decode(bytes: ByteArray): String {
            val utf16 = bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
            val text = if (utf16) String(bytes, Charsets.UTF_16) else String(bytes, Charsets.UTF_8)
            return text.trimStart('﻿')
        }

        fun read(folder: File): EaManifest? {
            val file = File(folder, RELATIVE).takeIf { it.isFile } ?: EaPaths.find(folder, RELATIVE) ?: return null
            if (file.length() > 4 * 1024 * 1024 || !file.canonicalFile.toPath().startsWith(folder.canonicalFile.toPath())) return null
            return runCatching { parse(decode(file.readBytes())) }.getOrNull()
        }

        fun parse(xml: String): EaManifest {
            require(xml.length <= 4 * 1024 * 1024 && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) {
                "The EA installer manifest contains unsupported XML declarations or is too large"
            }
            // JAXP is available on Android and the JVM: the same bounded parser runs in the format tests.
            val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
            val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
            fun elements(parent: Element, name: String): List<Element> {
                val nodes = parent.getElementsByTagName("*")
                return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName.equals(name, true) }
            }
            fun text(parent: Element, name: String) = elements(parent, name).firstOrNull()?.textContent?.trim().orEmpty()
            val launchers = elements(root, "launcher").mapNotNull {
                val path = text(it, "filePath")
                if (path.isBlank()) null else Launcher(path, text(it, "parameters"), text(it, "trial").let { trial -> trial == "1" || trial.equals("true", true) })
            }
            val touchup = elements(root, "touchup").firstOrNull()
            return EaManifest(
                elements(root, "gameVersion").firstOrNull()?.getAttribute("version").orEmpty(), launchers,
                touchup?.let { text(it, "filePath") }.orEmpty(), touchup?.let { text(it, "parameters") }.orEmpty(),
                elements(root, "contentIDs").flatMap { elements(it, "contentID") }.map { it.textContent.trim() }.filter { it.isNotBlank() },
                elements(root, "localeInfo").filter { it.getAttribute("locale").isNotBlank() }.associate { it.getAttribute("locale") to text(it, "title") },
            )
        }
    }
}

object EaPaths {
    private val KEY = Regex("^\\[([^\\]]+)\\](.*)$")

    /** `[HKEY_LOCAL_MACHINE\SOFTWARE\Publisher\Game\Install Dir]bin\game.exe` splits into the registry key, value and relative path. */
    class Registered(val key: String, val value: String, val rest: String)

    fun registered(path: String): Registered? {
        val m = KEY.find(path.trim()) ?: return null
        val full = m.groupValues[1]
        val cut = full.lastIndexOf('\\').takeIf { it > 0 } ?: return null
        return Registered(full.substring(0, cut), full.substring(cut + 1), m.groupValues[2])
    }

    fun relative(path: String): String {
        val rest = registered(path)?.rest ?: path.replace("{installLocation}", "", ignoreCase = true)
        val normal = rest.replace('\\', '/').trimStart('/')
        if (normal.contains(':') || normal.split('/').any { it == ".." }) return ""
        return normal.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    /** The real file for a path the manifest may spell in a different case. */
    fun find(folder: File, relative: String): File? {
        if (relative.isBlank() || relative.contains(':') || relative.replace('\\', '/').split('/').any { it == ".." }) return null
        var cur = folder
        for (part in relative.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
            val names = cur.listFiles() ?: return null
            cur = names.firstOrNull { it.name == part } ?: names.firstOrNull { it.name.equals(part, true) } ?: return null
        }
        return cur.takeIf { it.exists() && it != folder && it.canonicalFile.toPath().startsWith(folder.canonicalFile.toPath()) }
    }

    fun relativeOnDisk(folder: File, relative: String): String? =
        find(folder, relative)?.relativeTo(folder)?.path?.replace(File.separatorChar, '/')

    fun splitArgs(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var any = false
        for (c in text) {
            when {
                c == '"' -> { quoted = !quoted; any = true }
                c.isWhitespace() && !quoted -> { if (any || cur.isNotEmpty()) out += cur.toString(); cur.clear(); any = false }
                else -> cur.append(c)
            }
        }
        if (any || cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
