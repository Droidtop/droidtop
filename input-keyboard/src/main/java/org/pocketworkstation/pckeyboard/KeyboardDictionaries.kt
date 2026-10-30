package org.pocketworkstation.pckeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * The keyboard's own dictionaries, one per language (Droidtop/tracker#339; docs/SPEC.md 6a "Dictionaries"): an open
 * `.combined` wordlist the person adds is converted on the device ([CombinedWordlist], [DictionaryWriter]) into the
 * engine's format and kept in the keyboard's files, `keyboard-dictionaries/main_<language>.dict`. [loadInto] gives one
 * to [Suggest] when it has no main dictionary of its own. All file work runs on one background thread; the main thread
 * only swaps the loaded dictionary in.
 */
object KeyboardDictionaries {
    private val worker by lazy { KeyboardThreads.single("keyboard-dictionaries") }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Languages whose dictionary file failed to load in this process: corrupt, or not a dictionary. */
    val damaged: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    fun dir(context: Context): File = File(context.filesDir, "keyboard-dictionaries")

    /** The file a language's dictionary is kept in: by its language code ("en" for en_US), lower case. */
    fun fileFor(context: Context, language: String): File = File(dir(context), "main_${code(language)}.dict")

    /** The words glide typing guesses from for a language (Droidtop/tracker#341), kept beside its dictionary. */
    fun glideFileFor(context: Context, language: String): File = File(dir(context), "glide_${code(language)}.txt")

    /** How many of a wordlist's words glide typing keeps: the most frequent, so working out their paths stays quick. */
    const val GLIDE_WORDS = 40_000

    /** "en" for "en_US", "en-US" or "EN". Pure. */
    fun code(locale: String): String = locale.substringBefore('_').substringBefore('-').lowercase(Locale.ROOT)

    /** Bumped whenever a dictionary is added or removed, so the keyboard loads again on its next field. */
    @JvmStatic
    @Volatile
    var generation: Int = 0
        private set

    /** The languages with a dictionary, by code. Reads a folder: off the main thread. */
    fun installed(context: Context): List<String> =
        dir(context).listFiles { f -> f.isFile && f.name.startsWith("main_") && f.name.endsWith(".dict") }
            ?.map { it.name.removePrefix("main_").removeSuffix(".dict") }?.sorted().orEmpty()

    /** The language a wordlist's header names (`locale=en_US`, or `dictionary=main:en_us`), as a code; null when none. Pure. */
    fun languageOf(header: Map<String, String>): String? {
        val tag = header["locale"] ?: header["dictionary"]?.substringAfter(':', "")
        return tag?.takeIf { it.isNotBlank() }?.let(::code)
    }

    /** Opens [input], gunzipping it when it starts with gzip's magic bytes. */
    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val a = buffered.read()
        val b = buffered.read()
        buffered.reset()
        return if (a == 0x1f && b == 0x8b) GZIPInputStream(buffered) else buffered
    }

    /** What adding a wordlist came to: the language it was kept for and how many words, or why it was not. */
    sealed class Added {
        data class Done(val language: String, val words: Int, val bytes: Int) : Added()
        data class Refused(val reason: String) : Added()
    }

    /**
     * Converts the wordlist in [input] (plain or gzipped `.combined`) and keeps it for its language, replacing an
     * earlier one. Blocking disk and CPU work: call it off the main thread.
     */
    fun add(context: Context, input: InputStream): Added {
        val parsed = maybeGunzip(input).bufferedReader(Charsets.UTF_8).use { CombinedWordlist.parse(it.lineSequence()) }
        val language = languageOf(parsed.header) ?: return Added.Refused(context.getString(R.string.dictionary_refused_no_language))
        if (parsed.words.isEmpty()) return Added.Refused(context.getString(R.string.dictionary_refused_no_words))
        val bytes = try {
            DictionaryWriter.writeFitting(parsed.words, parsed.bigrams)
        } catch (e: DictionaryWriter.Unsupported) {
            return Added.Refused(context.getString(R.string.dictionary_refused_engine, e.message))
        }
        val target = fileFor(context, language)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            return Added.Refused(context.getString(R.string.dictionary_refused_not_saved))
        }
        writeGlideWords(fileFor(context, language).parentFile!!, glideFileFor(context, language), parsed.words)
        generation++
        return Added.Done(language, parsed.words.size, bytes.size)
    }

    /** The [GLIDE_WORDS] most frequent single words of [words] as "word<TAB>frequency" lines, most frequent first. */
    fun glideLines(words: Map<String, Int>): List<String> =
        words.entries.filter { (w, _) -> w.isNotEmpty() && w.all { it.isLetter() || it == '\'' } }
            .sortedByDescending { it.value }.take(GLIDE_WORDS).map { "${it.key}\t${it.value}" }

    private fun writeGlideWords(dir: File, target: File, words: Map<String, Int>) {
        val temp = File(dir, target.name + ".part")
        temp.writeText(glideLines(words).joinToString("\n"), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            Log.w(KEYBOARD_TAG, "glide words not saved: rename to ${target.name} failed")
            temp.delete()
        }
    }

    /** [language]'s glide words and their frequencies, or null when it has none. Disk: off the main thread. */
    fun glideWords(context: Context, language: String): Pair<List<String>, IntArray>? {
        val file = glideFileFor(context, language)
        if (!file.isFile) return null
        val words = ArrayList<String>()
        val frequencies = ArrayList<Int>()
        file.forEachLine(Charsets.UTF_8) { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) {
                words += line.substring(0, tab)
                frequencies += line.substring(tab + 1).toIntOrNull() ?: 0
            }
        }
        return if (words.isEmpty()) null else words to frequencies.toIntArray()
    }

    /** Forgets [language]'s dictionary. Disk: off the main thread. */
    fun remove(context: Context, language: String): Boolean {
        glideFileFor(context, language).delete()
        return fileFor(context, language).delete().also {
            if (it) {
                generation++
                damaged -= language
            }
        }
    }

    /**
     * Loads [language]'s dictionary on the background thread and, on the main thread, gives it to [suggest] if that is
     * still [isCurrent] and has no main dictionary of its own, then runs [onLoaded] (the keyboard decides corrections
     * from whether it has a dictionary); otherwise closes it.
     */
    @JvmStatic
    fun loadInto(context: Context, suggest: Suggest, language: String, isCurrent: () -> Boolean, onLoaded: Runnable) {
        val app = context.applicationContext
        worker.execute {
            val file = fileFor(app, language)
            if (!file.isFile) return@execute
            val dictionary = logged("dictionary $language not loaded") {
                RandomAccessFile(file, "r").use { raf ->
                    val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    BinaryDictionary(app, mapped, Suggest.DIC_MAIN)
                }
            }.getOrNull()
            if (dictionary == null) {
                // The Dictionaries row names it as damaged, so the person can remove it and add it again.
                damaged += language
                return@execute
            }
            damaged -= language
            main.post {
                if (isCurrent() && !suggest.hasMainDictionary()) {
                    suggest.setMainDictionary(dictionary)
                    onLoaded.run()
                } else {
                    dictionary.close()
                }
            }
        }
    }
}

/**
 * The keyboard settings' Dictionaries row (Droidtop/tracker#339): the languages with a dictionary, each removable,
 * and "Add from a file" for a `.combined` wordlist (plain or .gz) the person has, converted in the background. What
 * is going on and what was done show on the row itself; a failure is a dialog that says why (droidtop's UI rules: no
 * toasts).
 */
internal object DictionaryRow {
    const val KEY = "pref_dictionaries"
    const val PICK_REQUEST = 3390

    private val worker by lazy { KeyboardThreads.single("keyboard-dictionary-row") }

    @JvmStatic
    fun open(activity: android.app.Activity, row: android.preference.Preference) {
        worker.execute {
            val languages = KeyboardDictionaries.installed(activity)
            activity.runOnUiThread { show(activity, row, languages) }
        }
    }

    private fun name(code: String): String = Locale(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code }

    private fun show(activity: android.app.Activity, row: android.preference.Preference, languages: List<String>) {
        if (activity.isFinishing) return
        val labels = languages.map {
            val label = if (it in KeyboardDictionaries.damaged) R.string.dictionary_remove_damaged else R.string.dictionary_remove
            activity.getString(label, name(it))
        } + activity.getString(R.string.dictionary_add_from_file)
        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.title_dictionaries)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == languages.size) {
                    val pick = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                    activity.startActivityForResult(pick, PICK_REQUEST)
                } else {
                    val code = languages[which]
                    worker.execute {
                        val gone = KeyboardDictionaries.remove(activity, code)
                        activity.runOnUiThread {
                            if (gone) row.summary = activity.getString(R.string.dictionary_removed, name(code))
                            else fail(activity, activity.getString(R.string.dictionary_not_removed, name(code)))
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The picked wordlist, converted off the main thread; the row says it is converting, then what was added. */
    @JvmStatic
    fun picked(activity: android.app.Activity, row: android.preference.Preference?, uri: android.net.Uri?) {
        if (uri == null) return
        row?.summary = activity.getString(R.string.dictionary_converting)
        val app = activity.applicationContext
        worker.execute {
            val result = logged("wordlist not added") {
                app.contentResolver.openInputStream(uri)?.use { KeyboardDictionaries.add(app, it) }
                    ?: KeyboardDictionaries.Added.Refused(app.getString(R.string.dictionary_refused_not_opened))
            }.getOrElse { KeyboardDictionaries.Added.Refused(app.getString(R.string.dictionary_refused_not_read, it.message ?: it.toString())) }
            activity.runOnUiThread {
                when (result) {
                    is KeyboardDictionaries.Added.Done ->
                        row?.summary = activity.getString(R.string.dictionary_added, name(result.language), result.words)
                    is KeyboardDictionaries.Added.Refused -> {
                        row?.setSummary(R.string.summary_dictionaries)
                        fail(activity, result.reason)
                    }
                }
            }
        }
    }

    private fun fail(activity: android.app.Activity, reason: String) {
        if (activity.isFinishing) return
        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.title_dictionaries)
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
