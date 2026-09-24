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

        return tokens.map { token -> expandToken(token, filePath, fileUri, placeholders) }
    }

    /**
     * Expands one template token in a single left-to-right pass.
     *
     * Only the TEMPLATE's own text is ever read for placeholders and
     * `{file.inject:...}` directives. A substituted value (a ROM path, a
     * folder, a search query) and injected file content are copied through
     * verbatim and never scanned again. Before this, a file under shared
     * storage whose path contained `{file.inject:/data/...}` -- any app with
     * storage access can create such a folder -- made droidtop read that
     * file out of its own private storage into the launch Intent
     * (docs/security/2026-09-24-droidtop-intents-updater.md, finding 2).
     */
    private fun expandToken(
        token: String,
        filePath: String?,
        fileUri: String?,
        placeholders: Map<String, String>,
    ): String {
        val values = buildMap {
            if (filePath != null) put("{file.path}", filePath)
            if (fileUri != null) put("{file.uri}", fileUri)
            // Integrations' own placeholders ({system.folder}, {query},
            // ...) expand here for the same reason the file ones do: a
            // value containing a space must not be split into separate
            // tokens. One place knows how a template becomes tokens.
            putAll(placeholders)
        }
        val out = StringBuilder()
        var i = 0
        while (i < token.length) {
            if (token.startsWith(INJECT_PREFIX, i)) {
                val end = closingBrace(token, i + INJECT_PREFIX.length)
                val rel = substitute(token.substring(i + INJECT_PREFIX.length, end), values)
                out.append(readInject(rel, filePath))
                i = end + 1
                continue
            }
            val end = token.indexOf(INJECT_PREFIX, i).let { if (it < 0) token.length else it }
            out.append(substitute(token.substring(i, end), values))
            i = end
        }
        return out.toString()
    }

    /** Replaces each placeholder in [text] once; a substituted value is never scanned again. */
    private fun substitute(text: String, values: Map<String, String>): String {
        if (values.isEmpty() || '{' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val hit = if (text[i] == '{') values.entries.firstOrNull { text.startsWith(it.key, i) } else null
            if (hit != null) {
                out.append(hit.value)
                i += hit.key.length
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** The `}` closing a directive whose body starts at [from], allowing placeholders nested inside it. */
    private fun closingBrace(token: String, from: Int): Int {
        var depth = 1
        for (i in from until token.length) {
            when (token[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        throw IllegalArgumentException("Unterminated {file.inject:...} in am start command")
    }

    /**
     * `{file.inject:REL}` becomes the CONTENT of the file at REL,
     * resolved against the game's own directory (an absolute REL is
     * allowed -- the GameNative presets read the launched file itself via
     * `{file.inject:{file.path}}`). This is ES-DE's own %INJECT%
     * mechanism: Vita3K launches by a title id stored in
     * `<basename>.psvita`, GameNative by an app id stored in the .steam
     * stub -- the argument the emulator needs simply is not derivable
     * from the path, only from the bytes.
     *
     * Placeholders inside REL are expanded first, so
     * `{file.inject:{file.basename}.psvita}` works. Content is trimmed
     * (these are one-line id files, and a trailing newline would ride
     * into the extra). A missing or unreadable file is an error by name
     * -- launching with a half-substituted argument would fail somewhere
     * far less explicable inside the emulator.
     *
     * The file must lie in the game's own directory, after resolving
     * `..` and links. Every real use reads a sibling of the game or the
     * game itself; anything else would let a launch template (which can
     * come from the downloaded players database) copy an arbitrary file
     * droidtop can read, its own private storage included, into an
     * Intent bound for another app.
     */
    private fun readInject(rel: String, filePath: String?): String {
        val base = File(filePath ?: throw IllegalArgumentException("{file.inject} needs a game file")).parentFile
            ?: throw IllegalArgumentException("{file.inject} needs a game file")
        val resolved = File(rel).let { raw -> if (raw.isAbsolute) raw else File(base, rel) }
        val inside = runCatching {
            resolved.canonicalPath.startsWith(base.canonicalPath.trimEnd(File.separatorChar) + File.separator)
        }.getOrDefault(false)
        if (!inside) {
            throw IllegalArgumentException("{file.inject} reads only files beside the game; refused ${resolved.absolutePath}")
        }
        return runCatching { resolved.readText().trim() }.getOrElse {
            throw IllegalArgumentException("Couldn't read ${resolved.absolutePath} for {file.inject}")
        }
    }

    private const val INJECT_PREFIX = "{file.inject:"

    private const val URI_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private fun fileProviderAuthority(context: Context) = "${context.packageName}.fileprovider"

    /**
     * A launch template targets another app. Pointed at droidtop itself it
     * would start droidtop's own unexported screens with extras of the
     * template's choosing, which nothing legitimate needs.
     */
    private fun requireOtherApp(context: Context, packageName: String) {
        require(packageName != context.packageName) { "am start command targets droidtop itself" }
    }

    /** Whether [file] resolves into droidtop's credential- or device-protected app data. */
    private fun isPrivateStorage(context: Context, file: File): Boolean {
        val target = runCatching { file.canonicalPath }.getOrElse { return true }
        val roots = listOfNotNull(
            context.dataDir,
            context.createDeviceProtectedStorageContext().dataDir,
        )
        return roots.any { root ->
            val base = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            target == base || target.startsWith(base.trimEnd(File.separatorChar) + File.separator)
        }
    }

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
            val file = File(filePath!!)
            // The provider's root-path reaches droidtop's own private
            // storage too; a game never lives there, so a URI for it is
            // never issued, whatever path a caller or template supplies.
            require(!isPrivateStorage(context, file)) { "Refusing to share droidtop's private file ${file.path}" }
            FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
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
                    requireOtherApp(context, component.packageName)
                    intent.component = component
                }
                "-p" -> intent.setPackage(tokens.removeFirst().also { requireOtherApp(context, it) })
                "-d" -> dataUri = Uri.parse(tokens.removeFirst()).also { uri ->
                    // droidtop's own provider serves exactly the file being
                    // launched, never a URI a template wrote by hand.
                    if (uri.scheme == "content" && uri.authority == fileProviderAuthority(context)) {
                        require(uri.toString() == fileUri?.toString()) {
                            "am start command names a droidtop content URI other than {file.uri}"
                        }
                    }
                }
                "-t" -> mimeType = tokens.removeFirst()
                // URI grants are droidtop's to decide (below), never the
                // template's: a template setting write or persistable
                // grant bits would hand the target lasting write access.
                "-f" -> intent.flags = Integer.decode(tokens.removeFirst()) and URI_GRANT_FLAGS.inv()
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
