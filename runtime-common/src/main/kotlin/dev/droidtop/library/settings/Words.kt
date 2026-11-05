package dev.droidtop.library.settings

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * The words a catalog row or screen shows, read from string resources (Droidtop/tracker#177): `title =
 * words(R.string.x)`. Catalog screens are often built where no Context is at hand (a screen's definition, a row made
 * before the screen opens), so the Application hands its Context here once, before anything else runs
 * (`DroidtopApplication.attachBaseContext`, ahead of the content providers that register the catalogs). A word read
 * before that is a bug, and says so rather than showing nothing.
 */
object Words {
    @Volatile private var context: Context? = null

    fun init(context: Context) {
        this.context = context
    }

    /** A plain-JVM unit test's words, read from the modules' strings.xml ([WordsForTests]); null in the app. */
    @Volatile private var testLookup: ((Int, Array<out Any>) -> String)? = null

    @androidx.annotation.VisibleForTesting
    fun installForTests(lookup: (Int, Array<out Any>) -> String) {
        testLookup = lookup
    }

    fun get(@StringRes id: Int, vararg args: Any): String {
        context?.let { return it.getString(id, *args) }
        testLookup?.let { return it(id, args) }
        error("Words.init has not run: a catalog was built before the Application")
    }

    /**
     * A count's words, as the language counts (Droidtop/tracker#627: "plurals done right"): a `<plurals>` resource read
     * for [count], never `if (n == 1) "1 game" else "$n games"` in Kotlin, which is English's rule only. [args] fill its
     * placeholders, the count usually among them.
     */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        checkNotNull(context) { "Words.init has not run: a catalog was built before the Application" }.resources.getQuantityString(id, count, *args)
}

/** [Words.plural]: a count's words, from a `<plurals>` resource. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = Words.plural(id, count, *args)

/** [Words.get]: one catalog string, with its arguments. */
fun words(@StringRes id: Int, vararg args: Any): String = Words.get(id, *args)
