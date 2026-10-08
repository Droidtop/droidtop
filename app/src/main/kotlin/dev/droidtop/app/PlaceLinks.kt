package dev.droidtop.app

import android.content.Context
import android.content.Intent
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.library.settings.Place
import dev.droidtop.shell.standard.BackButtonMenu

/**
 * What opens a place from outside every screen (a notification), in the mode the person is using
 * (docs/SPEC.md 7j "Places in every mode", Droidtop/tracker#346): Gaming on that place when Gaming is
 * the mode in use, else droidtop's screen host. Message and plugin-update notifications used to open
 * Gaming whatever the mode, which with Gaming off landed in Desktop or nowhere.
 */
object PlaceLinks {
    fun intent(context: Context, place: Place): Intent =
        if (Place.opensInGaming(Modes.lastMode(context), Modes.enabled)) {
            Intent(context, MainActivity::class.java)
                .putExtra(BackButtonMenu.EXTRA_MODE, Mode.GAMING.id)
                // GamingSection's names are the places' names.
                .putExtra(BackButtonMenu.EXTRA_GAMING_START_SECTION, place.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Place.openIntent(context, place)
        }
}
