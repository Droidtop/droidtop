package dev.droidtop.library.consoles

import android.content.ComponentName
import android.content.Intent
import android.net.Uri

/**
 * Turns an `am start`-style argument string into a real [Intent] -- the
 * same real mechanism Daijishō uses (its own `AmStartCommandToIntentConverter`,
 * confirmed via its decompiled sources) to launch arbitrary emulators
 * without droidtop needing to hardcode per-emulator launch logic. A user
 * (or a droidtop-shipped default, see [DefaultPlayers]) writes a real
 * `am start` command for whatever emulator they use, droidtop parses it.
 *
 * Deliberately a smaller, real subset of `am start`'s flags than Daijishō's
 * own (which mirrors essentially every flag `adb shell am start` accepts,
 * including many that only matter for instrumented testing) -- covers what
 * an actual emulator launch command realistically needs: component/package/
 * action targeting, a data URI, and typed extras. An unrecognized flag is a
 * clear, real error rather than a silently-dropped token, since a launch
 * command that's silently missing part of what the user wrote is a worse
 * failure mode than refusing to guess.
 */
object AmStartCommandToIntentConverter {
    class UnsupportedArgumentException(argument: String) : IllegalArgumentException("Unsupported am start argument: $argument")

    fun toIntent(argumentsTemplate: String, filePath: String): Intent {
        val tokens = ArrayDeque(
            argumentsTemplate
                .replace("{file.path}", filePath)
                .split(Regex("[\\n\\s]+"))
                .filter { it.isNotEmpty() },
        )

        val intent = Intent()
        var dataUri: Uri? = null
        var mimeType: String? = null

        while (tokens.isNotEmpty()) {
            when (val token = tokens.removeFirst()) {
                "-a" -> intent.action = tokens.removeFirst()
                "-c" -> intent.addCategory(tokens.removeFirst())
                "-n" -> {
                    val component = ComponentName.unflattenFromString(tokens.removeFirst())
                        ?: throw IllegalArgumentException("Bad component name in am start command")
                    intent.component = component
                }
                "-p" -> intent.setPackage(tokens.removeFirst())
                "-d" -> dataUri = Uri.parse(tokens.removeFirst())
                "-t" -> mimeType = tokens.removeFirst()
                "-f" -> intent.flags = Integer.decode(tokens.removeFirst())
                "--es" -> intent.putExtra(tokens.removeFirst(), tokens.removeFirst())
                "--ei" -> intent.putExtra(tokens.removeFirst(), Integer.decode(tokens.removeFirst()))
                "--el" -> intent.putExtra(tokens.removeFirst(), tokens.removeFirst().toLong())
                "--ef" -> intent.putExtra(tokens.removeFirst(), tokens.removeFirst().toFloat())
                "--ez" -> intent.putExtra(tokens.removeFirst(), tokens.removeFirst().toBooleanStrict())
                "--esn" -> intent.putExtra(tokens.removeFirst(), null as String?)
                else -> throw UnsupportedArgumentException(token)
            }
        }

        if (dataUri != null || mimeType != null) intent.setDataAndType(dataUri, mimeType)
        if (intent.action == null && intent.component == null && intent.getPackage() == null) {
            throw IllegalArgumentException("am start command specified no action, component, or package")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}
