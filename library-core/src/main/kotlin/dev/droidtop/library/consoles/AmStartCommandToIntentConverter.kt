package dev.droidtop.library.consoles

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

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

    /**
     * Splits a template into `am start` tokens, expanding the file
     * placeholders.
     *
     * Split FIRST, substitute SECOND. The order is the whole point: a
     * real ROM path routinely contains spaces, and substituting into the
     * template before splitting on whitespace tore one path into several
     * tokens.
     *
     * Confirmed on-device by the all-systems launch sweep, which is how
     * this was found: N64, NDS, GBA and GBC all failed with "Unsupported
     * am start argument: (USA).z64" launching "Glover (USA).z64" through
     * RetroArch's `--es ROM {file.path}`. `ROM` received a truncated path
     * and the remainder of the filename hit the unrecognized-flag branch.
     * Every system that worked in that sweep happened to use
     * `-d {file.uri}`, whose percent-encoded URI has no spaces to split
     * on -- which is why this survived despite breaking any collection
     * whose filenames contain spaces, which is most of them.
     *
     * Splitting the raw template is safe because the placeholders contain
     * no whitespace themselves, so each always lands wholly inside one
     * token.
     */
    internal fun tokenize(
        argumentsTemplate: String,
        filePath: String?,
        fileUri: String?,
        placeholders: Map<String, String> = emptyMap(),
    ): List<String> {
        // Split honoring double quotes: a "..."-delimited span keeps its
        // spaces and lands in one token, with the quotes themselves
        // stripped and \" inside a span meaning a literal quote. This is
        // not speculative shell emulation -- it is exactly the shape of
        // the 78 real MAME4droid commands in ES-DE's own es_systems.xml
        // (a multi-word `cli_params` string extra with escaped inner
        // quotes), which the players-database generator used to SKIP
        // because this function could not represent them.
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        // Distinguishes `""` (a real, empty extra value) from "no token".
        var sawQuote = false
        var i = 0
        while (i < argumentsTemplate.length) {
            val c = argumentsTemplate[i]
            when {
                inQuotes && c == '\\' && i + 1 < argumentsTemplate.length && argumentsTemplate[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                    sawQuote = true
                }
                !inQuotes && c.isWhitespace() -> {
                    if (current.isNotEmpty() || sawQuote) tokens.add(current.toString())
                    current.setLength(0)
                    sawQuote = false
                }
                else -> current.append(c)
            }
            i++
        }
        if (inQuotes) throw IllegalArgumentException("Unterminated quote in am start command")
        if (current.isNotEmpty() || sawQuote) tokens.add(current.toString())

        return tokens.map { token ->
            var t = token
            if (filePath != null) t = t.replace("{file.path}", filePath)
            if (fileUri != null) t = t.replace("{file.uri}", fileUri)
            // Integrations' own placeholders ({system.folder}, {query},
            // ...) expand here for the same reason the file ones do: a
            // value containing a space must not be split into separate
            // tokens. One place knows how a template becomes tokens.
            for ((key, value) in placeholders) t = t.replace(key, value)
            expandInject(t, filePath)
        }
    }

    /**
     * `{file.inject:REL}` becomes the CONTENT of the file at REL,
     * resolved against the game's own directory (absolute REL allowed --
     * the GameNative presets read the launched file itself). This is
     * ES-DE's own %INJECT% mechanism: Vita3K launches by a title id
     * stored in `<basename>.psvita`, GameNative by an app id stored in
     * the .steam stub -- the argument the emulator needs simply is not
     * derivable from the path, only from the bytes.
     *
     * REL is expanded AFTER the ordinary placeholders, so
     * `{file.inject:{file.basename}.psvita}` works. Content is trimmed
     * (these are one-line id files, and a trailing newline would ride
     * into the extra). A missing or unreadable file is an error by name
     * -- launching with a half-substituted argument would fail somewhere
     * far less explicable inside the emulator.
     */
    private fun expandInject(token: String, filePath: String?): String {
        val start = token.indexOf(INJECT_PREFIX)
        if (start < 0) return token
        val end = token.indexOf('}', start + INJECT_PREFIX.length)
        if (end < 0) throw IllegalArgumentException("Unterminated {file.inject:...} in am start command")
        val rel = token.substring(start + INJECT_PREFIX.length, end)
        val resolved = File(rel).let { raw ->
            if (raw.isAbsolute) raw
            else File(File(filePath ?: throw IllegalArgumentException("{file.inject} needs a game file")).parentFile, rel)
        }
        val content = runCatching { resolved.readText().trim() }.getOrElse {
            throw IllegalArgumentException("Couldn't read ${resolved.absolutePath} for {file.inject}")
        }
        // Recurse: a token may hold several directives.
        return expandInject(token.substring(0, start) + content + token.substring(end + 1), filePath)
    }

    private const val INJECT_PREFIX = "{file.inject:"

    fun toIntent(
        context: Context,
        argumentsTemplate: String,
        filePath: String?,
        placeholders: Map<String, String> = emptyMap(),
    ): Intent {
        // Real, second placeholder alongside {file.path} -- roughly half of
        // the real presets pulled from Daijishō's own wiki (KnownPlayers.kt)
        // specifically need a file:// URI, not a bare path string (several
        // emulators' own file pickers only accept a URI, not a raw path).
        //
        // A real, live crash (android.os.FileUriExposedException, confirmed
        // via adb logcat launching a real PSP game through PPSSPP) showed
        // Uri.fromFile is NOT safe here: modern Android (API 24+) StrictMode
        // forbids handing a plain file:// URI to another app via an Intent,
        // regardless of what storage permissions droidtop itself holds --
        // that's the SENDING app's own policy, not the receiver's. The real
        // fix is a FileProvider-issued content:// URI (see the <provider>
        // in AndroidManifest.xml), which every actively-maintained emulator
        // targeting a real Android version already understands via
        // ACTION_VIEW, plus FLAG_GRANT_READ_URI_PERMISSION on the resulting
        // Intent so the receiving app can actually read it.
        val usesFileUri = argumentsTemplate.contains("{file.uri}")
        val usesFilePath = argumentsTemplate.contains("{file.path}")
        if ((usesFileUri || usesFilePath) && filePath == null) {
            throw IllegalArgumentException(
                "This command references {file.uri}/{file.path} but no file was supplied.",
            )
        }
        // Computed only when the template actually asks for it. Not just
        // an efficiency point: callers that have no file at all (an app
        // integration handed a destination folder, say) must not be made
        // to invent one purely to satisfy a URI nobody referenced.
        val fileUri = if (usesFileUri) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath!!))
        } else {
            null
        }
        val tokens = ArrayDeque(tokenize(argumentsTemplate, filePath, fileUri?.toString(), placeholders))

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
                // "-e" is real `am start`'s own documented shorthand for
                // "--es" (a string extra) -- both forms show up verbatim
                // across real emulator presets pulled from Daijishō's own
                // wiki (see KnownPlayers.kt), so both need to work, not
                // just the long form droidtop's own DefaultPlayers uses.
                "-e", "--es" -> intent.putExtra(tokens.removeFirst(), tokens.removeFirst())
                "--ei" -> intent.putExtra(tokens.removeFirst(), Integer.decode(tokens.removeFirst()))
                "--el" -> intent.putExtra(tokens.removeFirst(), tokens.removeFirst().toLong())
                "--ef" -> intent.putExtra(tokens.removeFirst(), tokens.removeFirst().toFloat())
                // Lenient boolean, NOT toBooleanStrict(): real presets from
                // Daijishō's wiki write boolean extras as 0/1 as well as
                // true/false, and toBooleanStrict("0") threw -- confirmed
                // live: the very first real game launch on-device crashed
                // the whole shell on a preset carrying `--ez key 0`.
                "--ez" -> intent.putExtra(
                    tokens.removeFirst(),
                    when (val raw = tokens.removeFirst().lowercase()) {
                        "true", "1" -> true
                        "false", "0" -> false
                        else -> throw IllegalArgumentException("Bad boolean extra value: $raw")
                    },
                )
                "--esn" -> intent.putExtra(tokens.removeFirst(), null as String?)
                // Real, documented am flag: a comma-separated string
                // array. Vita3K's own ES-DE launch command passes its
                // AppStartParameters this way.
                "--esa" -> intent.putExtra(tokens.removeFirst(), tokens.removeFirst().split(',').toTypedArray())
                // Real, documented `am start` boolean flags (no following
                // value) -- several real emulator presets (DuckStation,
                // AetherSX2, ePSXe, ...) depend on these actually being
                // set, not just tolerated/ignored.
                "--activity-clear-top" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                "--activity-clear-task" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                "--activity-no-history" -> intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                else -> throw UnsupportedArgumentException(token)
            }
        }

        if (dataUri != null || mimeType != null) intent.setDataAndType(dataUri, mimeType)
        if (intent.action == null && intent.component == null && intent.getPackage() == null) {
            throw IllegalArgumentException("am start command specified no action, component, or package")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Required for the receiving app to actually read a FileProvider
        // content:// data URI (see {file.uri} above) -- without this the
        // grant made via android:grantUriPermissions doesn't extend to
        // this specific Intent, and the receiver gets a SecurityException
        // instead of the earlier FileUriExposedException.
        if (dataUri?.scheme == "content") intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Real, confirmed on-device launch failure this fixes (PS2 via the
        // all-systems launch sweep: "Failed to open CD image ... Failed to
        // open 'content://dev.droidtop.app.fileprovider/...'"): Android's
        // URI grant flags only apply to the Intent's DATA and ClipData --
        // never to a URI riding inside a string extra, which is exactly
        // where several real presets put {file.uri} (--es bootPath ...).
        // The receiving emulator got the URI text but zero permission to
        // open it. Attaching the same URI as ClipData extends the grant to
        // it (the documented mechanism for exactly this), and the explicit
        // per-package grant below covers receivers that stash the string
        // and open it later from a context the Intent grant no longer
        // reaches. The grant is read-only and Android revokes it when the
        // receiving task dies.
        if (fileUri != null) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri(null, fileUri)
            val targetPackage = intent.component?.packageName ?: intent.getPackage()
            if (targetPackage != null) {
                try {
                    context.grantUriPermission(targetPackage, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    android.util.Log.w("droidtop.AmStart", "Could not pre-grant $fileUri to $targetPackage", e)
                }
            }
        }
        return intent
    }
}
