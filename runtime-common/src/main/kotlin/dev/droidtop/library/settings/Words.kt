package dev.droidtop.library.settings

import android.content.Context
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

    fun get(@StringRes id: Int, vararg args: Any): String =
        checkNotNull(context) { "Words.init has not run: a catalog was built before the Application" }.getString(id, *args)
}

/** [Words.get]: one catalog string, with its arguments. */
fun words(@StringRes id: Int, vararg args: Any): String = Words.get(id, *args)
