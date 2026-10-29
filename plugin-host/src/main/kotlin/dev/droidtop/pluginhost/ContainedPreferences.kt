package dev.droidtop.pluginhost

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A plugin's `SharedPreferences`, droidtop's own in every plugin process (docs/plugin-api.md 5.3, "Preferences and
 * SQLite are droidtop's", Droidtop/tracker#741). Android's own `getSharedPreferences` first asks the user service whether
 * storage is unlocked, which an isolated process cannot reach, and what a plugin gets must never depend on the Android
 * it runs on (the owner, 2026-10-29: "Android updates can't be allowed to affect this"). So this is the whole store: a
 * map in memory and one file per name, written in Android's own XML format, so a plugin's settings read the same in
 * either tier and after a move between them.
 *
 * The file is `<dataDir>/shared_prefs/<name>.xml`, the path Android uses, which a plugin process's file broker sends to
 * the plugin's own folder. A write goes to a temporary file that is renamed over the old one; an Android `.bak` left by a
 * write that never finished is taken first, as Android does.
 */
class ContainedPreferences internal constructor(
    private val file: File,
    private val writes: Executor,
    private val listeners: Executor,
) : SharedPreferences {
    private val lock = Any()
    private var values: Map<String, Any?> = read(file)
    private val watchers = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>()

    override fun getAll(): Map<String, *> = synchronized(lock) { HashMap(values) }

    override fun getString(key: String?, defValue: String?): String? = value(key) as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = value(key) as Set<String>? ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = value(key) as Int? ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = value(key) as Long? ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = value(key) as Float? ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = value(key) as Boolean? ?: defValue

    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) { watchers[listener] = true }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) { watchers.remove(listener) }
    }

    private fun value(key: String?): Any? = synchronized(lock) { values[key] }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: Set<String>?) = put(key, values?.let { HashSet(it) })

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        // Android's own rule: a null value is a removal.
        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor = apply { synchronized(this) { clear = true } }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply { synchronized(this) { changes[key] = value } }

        override fun commit(): Boolean {
            val (snapshot, changed) = merge() ?: return true
            // Through the one writer, after any apply still waiting, so an older snapshot never lands over this one.
            val task = FutureTask { write(file, snapshot) }
            writes.execute(task)
            notify(changed)
            return task.get()
        }

        override fun apply() {
            val (snapshot, changed) = merge() ?: return
            writes.execute { write(file, snapshot) }
            notify(changed)
        }

        /** Applies this editor to the map, as Android does: the clear first, then the puts, whatever order they came in. */
        private fun merge(): Pair<Map<String, Any?>, List<String?>>? {
            val (cleared, edits) = synchronized(this) { clear to HashMap(changes) }
            synchronized(lock) {
                val next = if (cleared) HashMap() else HashMap(values)
                val changed = ArrayList<String?>()
                // Since Android 11 a clear is told to listeners as one null key.
                if (cleared && values.isNotEmpty()) changed += null
                for ((key, value) in edits) {
                    val before = next[key]
                    if (value == null) next.remove(key) else next[key] = value
                    if (before != value || (cleared && value != null)) changed += key
                }
                if (!cleared && changed.isEmpty()) return null
                values = next
                return next to changed
            }
        }

        private fun notify(changed: List<String?>) {
            val targets = synchronized(lock) { watchers.keys.toList() }
            if (targets.isEmpty() || changed.isEmpty()) return
            listeners.execute { for (key in changed) for (listener in targets) listener.onSharedPreferenceChanged(this@ContainedPreferences, key) }
        }
    }

    companion object {
        private val open = HashMap<String, ContainedPreferences>()

        // One writer thread for every store of the process, so writes of one file land in the order they were applied.
        private val writer: Executor by lazy { Executors.newSingleThreadExecutor { Thread(it, "droidtop-prefs").apply { isDaemon = true } } }

        private val mainThread: Executor by lazy {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            Executor { handler.post(it) }
        }

        /** The store named [name] under [dataDir], one instance per file for the life of the process. */
        fun of(dataDir: File, name: String?): ContainedPreferences = of(dataDir, name, writer, mainThread)

        internal fun of(dataDir: File, name: String?, writes: Executor, listeners: Executor): ContainedPreferences {
            val file = fileOf(dataDir, name)
            return synchronized(open) { open.getOrPut(file.path) { ContainedPreferences(file, writes, listeners) } }
        }

        /** Removes the store named [name] and its file, as `Context.deleteSharedPreferences` does. */
        fun delete(dataDir: File, name: String?): Boolean {
            val file = fileOf(dataDir, name)
            synchronized(open) { open.remove(file.path) }
            File(file.path + ".bak").delete()
            return !file.exists() || file.delete()
        }

        // Android names a store "null" when it is given none (a plugin written for API 18 and older did that).
        private fun fileOf(dataDir: File, name: String?): File {
            val safe = name ?: "null"
            require('/' !in safe) { "File $safe contains a path separator" }
            return File(File(dataDir, "shared_prefs"), "$safe.xml")
        }

        internal fun read(file: File): Map<String, Any?> {
            val backup = File(file.path + ".bak")
            val source = if (backup.exists()) backup else file
            if (!source.exists()) return emptyMap()
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance()
                val root = source.inputStream().use { factory.newDocumentBuilder().parse(it) }.documentElement
                val out = HashMap<String, Any?>()
                for (node in root.childNodes.elements()) {
                    val name = node.getAttribute("name").takeIf { node.hasAttribute("name") } ?: continue
                    val value = node.getAttribute("value")
                    out[name] = when (node.tagName) {
                        "string" -> node.textContent
                        "int" -> value.toInt()
                        "long" -> value.toLong()
                        "float" -> value.toFloat()
                        "boolean" -> value.toBoolean()
                        "set" -> node.childNodes.elements().filter { it.tagName == "string" }.mapTo(HashSet()) { it.textContent }
                        else -> continue
                    }
                }
                out
            }.getOrDefault(emptyMap())
        }

        private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

        /** Writes [values] in Android's own format, by a temporary file renamed over the old one. */
        internal fun write(file: File, values: Map<String, Any?>): Boolean = runCatching {
            file.parentFile?.mkdirs()
            val text = StringBuilder("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
            for ((key, value) in values.entries.sortedBy { it.key }) {
                val name = escape(key)
                when (value) {
                    is String -> text.append("    <string name=\"$name\">${escape(value)}</string>\n")
                    is Int -> text.append("    <int name=\"$name\" value=\"$value\" />\n")
                    is Long -> text.append("    <long name=\"$name\" value=\"$value\" />\n")
                    is Float -> text.append("    <float name=\"$name\" value=\"$value\" />\n")
                    is Boolean -> text.append("    <boolean name=\"$name\" value=\"$value\" />\n")
                    is Set<*> -> {
                        text.append("    <set name=\"$name\">\n")
                        value.filterIsInstance<String>().sorted().forEach { text.append("        <string>${escape(it)}</string>\n") }
                        text.append("    </set>\n")
                    }
                }
            }
            text.append("</map>\n")
            val temporary = File(file.path + ".tmp")
            temporary.outputStream().use { out ->
                out.write(text.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            check(temporary.renameTo(file)) { "rename failed" }
            File(file.path + ".bak").delete()
            true
        }.getOrDefault(false)

        private fun escape(text: String): String = buildString(text.length) {
            for (c in text) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> if (c < ' ' && c != '\n' && c != '\t') append("&#").append(c.code).append(';') else append(c)
            }
        }
    }
}

/**
 * The Context a plugin's code is given in a plugin process: Android's own, except that its preferences are droidtop's
 * ([ContainedPreferences]) and it is its own application context, so a Flutter package's Android half (which asks the
 * engine's context for `getApplicationContext()`) gets this one too.
 */
class PluginProcessContext(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = ContainedPreferences.of(dataDir, name)

    override fun deleteSharedPreferences(name: String?): Boolean = ContainedPreferences.delete(dataDir, name)
}
