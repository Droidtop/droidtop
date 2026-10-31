package dev.droidtop.app

import android.app.Instrumentation
import android.os.Bundle
import android.util.Log

/**
 * One measured number from a rig test, as "rig start: <text>": in the log under droidtop.plugin, and in `am instrument`'s
 * own output, which scripts/emulator-tests.sh copies into the job summary (Droidtop/tracker#848). The emulator's
 * uploaded results are not the only place the numbers are then.
 */
fun Instrumentation.rigLine(text: String) {
    Log.i("droidtop.plugin", "rig start: $text")
    sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "rig start: $text\n") })
}
