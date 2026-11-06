package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * Open with, for plugins (docs/plugin-api.md 1.12 and catalogue E4, Droidtop/tracker#221): a `files.handler` entry
 * lists the files it opens, `"associations": [{"ext": "AppImage", "mime": "application/vnd.appimage", "label":
 * "Inspect with Acme"}]`, and droidtop's own Open with offers the plugin for a file one of them matches. The plugin is
 * handed the file only when the person picks it there: as a read-only token (`files.open {token}`, D4), never a path.
 * Pure, so the matching is tested on the JVM.
 */
object FileHandlers {
    const val POINT = "files.handler"
    const val OP_OPEN = "open"

    /** At most this many associations are read from one entry, and a label is cut to [MAX_LABEL] characters. */
    const val MAX_ASSOCIATIONS = 32
    const val MAX_LABEL = 60

    /** One file a plugin opens: by extension (no dot, any case) or by MIME type, and the words of its Open with row. */
    data class Association(val ext: String?, val mime: String?, val label: String)

    /** [entry]'s associations; one with neither `ext` nor `mime` matches nothing and is skipped. */
    fun associations(entry: ProvidedPoint): List<Association> {
        if (entry.point != POINT) return emptyList()
        val list = runCatching { JSONObject(entry.extra).optJSONArray("associations") }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until minOf(list.length(), MAX_ASSOCIATIONS)) {
                val o = list.optJSONObject(i) ?: continue
                val ext = o.optString("ext").trim().removePrefix(".").takeIf { it.isNotEmpty() }
                val mime = o.optString("mime").trim().lowercase().takeIf { it.isNotEmpty() && '/' in it }
                if (ext == null && mime == null) continue
                val label = o.optString("label").trim().take(MAX_LABEL)
                add(Association(ext, mime, label))
            }
        }
    }

    /**
     * The association of [entry] that takes a file named [fileName] of type [mime] (null when unknown), or null. An
     * extension matches ignoring case; a MIME type matches exactly, or by its top-level type for an association whose
     * subtype is a star ("image" and a star). A star alone, or a star for both parts, takes nothing: a plugin names
     * what it opens.
     */
    fun match(entry: ProvidedPoint, fileName: String, mime: String?): Association? {
        val ext = fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != fileName }
        val type = mime?.trim()?.lowercase()
        return associations(entry).firstOrNull { a ->
            (a.ext != null && ext != null && a.ext.equals(ext, ignoreCase = true)) ||
                (a.mime != null && type != null && mimeMatches(a.mime, type))
        }
    }

    private fun mimeMatches(pattern: String, type: String): Boolean = when {
        pattern == "*/*" || pattern == "*" -> false
        pattern.endsWith("/*") -> type.substringBefore('/') == pattern.substringBefore('/')
        else -> pattern == type
    }

    /** The args of the plugin's `open`: the token droidtop made for the file, its name and its type. */
    fun openArgs(token: String, fileName: String, mime: String?): JSONObject =
        JSONObject().put("token", token).put("name", fileName).put("mime", mime ?: "")
}
