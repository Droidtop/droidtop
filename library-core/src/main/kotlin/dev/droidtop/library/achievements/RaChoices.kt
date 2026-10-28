package dev.droidtop.library.achievements

import android.content.Context

/**
 * What the person said about a RetroAchievements game proposed by name (Droidtop/tracker#318, owner decision 7a: the
 * title fallback "becomes a Tier C review card, never automatic"). A file whose hash RetroAchievements lists needs no
 * answer; a game recognised only by its title shows achievements and progress once the person confirms it, and never
 * after they said it is not that game. Kept per library game on this device.
 */
object RaChoices {
    private const val FILE = "retroachievements_choices"

    /** What the person said for one proposal. */
    enum class Answer { CONFIRMED, NOT_THIS }

    /** The answer for [entryId] about RetroAchievements game [raGameId], or null when nobody answered it. */
    fun answer(context: Context, entryId: String, raGameId: Int): Answer? = decode(prefs(context).getString(entryId, null), raGameId)

    fun confirm(context: Context, entryId: String, raGameId: Int) = put(context, entryId, "yes:$raGameId")

    fun notThis(context: Context, entryId: String, raGameId: Int) = put(context, entryId, "no:$raGameId")

    /** Forgets the answer for [entryId], so the next proposal is asked again. */
    fun forget(context: Context, entryId: String) {
        prefs(context).edit().remove(entryId).apply()
    }

    /** The answer [stored] gives about [raGameId]: one about another game is no answer. Pure. */
    internal fun decode(stored: String?, raGameId: Int): Answer? {
        val (said, id) = stored?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
        if (id.toIntOrNull() != raGameId) return null
        return when (said) {
            "yes" -> Answer.CONFIRMED
            "no" -> Answer.NOT_THIS
            else -> null
        }
    }

    private fun put(context: Context, entryId: String, value: String) {
        prefs(context).edit().putString(entryId, value).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
