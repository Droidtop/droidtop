package dev.droidtop.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.words
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * droidtop's own language (docs/SPEC.md 7k, "Language is part of the system"; Droidtop/tracker#627): Follow Android,
 * the default, or one of the languages droidtop ships (res/xml/locales_config.xml, the list Android 13's per-app
 * language screen shows too). The choice is AppCompat's per-app locale, which Android keeps from 13 on and AppCompat
 * keeps before that. Games, apps and an ES-DE theme's own labels keep theirs.
 */
object AppLanguage {
    /** The value of Follow Android. */
    const val FOLLOW = ""

    /** The language tags droidtop ships, from locales_config.xml. Reads a resource: off the main thread. */
    fun shipped(context: Context): List<String> = runCatching {
        val parser = context.resources.getXml(R.xml.locales_config)
        buildList {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_NS, "name")?.let { add(it) }
                }
            }
        }
    }.getOrDefault(emptyList())

    /** The language chosen, as a tag, or [FOLLOW]. */
    fun current(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()

    /** Sets droidtop's language ([FOLLOW] to follow Android). Android recreates droidtop's screens in it. */
    fun set(tag: String) {
        val locales = if (tag == FOLLOW) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        // AppCompat applies it on the main thread; a renderer may call from elsewhere.
        Handler(Looper.getMainLooper()).post { AppCompatDelegate.setApplicationLocales(locales) }
    }

    /** A language's own name for itself ("English", "Deutsch", "日本語"). Pure but for the JDK's locale data. */
    fun nameOf(tag: String): String = Locale.forLanguageTag(tag).let { locale ->
        locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** The Settings row: Follow Android, then each shipped language in its own name. */
    fun item(context: Context): ChoiceItem = ChoiceItem(
        id = KEY,
        title = words(R.string.language_title),
        subtitle = words(R.string.language_subtitle),
        options = listOf(ChoiceOption(FOLLOW, words(R.string.language_follow_android))) +
            shipped(context).map { ChoiceOption(it, nameOf(it)) },
        current = current(),
        onSelect = { _, value -> set(value) },
    )

    private const val KEY = "pref_global_language"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
}
