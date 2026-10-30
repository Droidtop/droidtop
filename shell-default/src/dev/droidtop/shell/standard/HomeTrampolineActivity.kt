package dev.droidtop.shell.standard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.droidtop.library.settings.Mode

/**
 * droidtop's `CATEGORY_HOME` component for Standard (docs/SPEC.md 2c). `com.android.launcher3.Launcher` does not
 * declare `CATEGORY_HOME`; this activity does, so a Home press is decided ([HomeEntry.route]) before any of
 * Launcher3's own heavy `onCreate` work runs, and Launcher3 is never constructed when the target is another mode.
 * When it is Standard, it starts `com.android.launcher3.Launcher` by its component, marked as the explicit Android
 * home so Launcher3 shows itself as asked. It always finishes at once and is never shown.
 */
class HomeTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (HomeEntry.route(this, intent) == HomeEntry.Route.STANDARD) {
            HomeEntry.enterStandard(this)
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setClassName(packageName, StandardComponents.LAUNCHER)
                    .putExtra(BackButtonMenu.EXTRA_MODE, Mode.LAUNCHER.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}
