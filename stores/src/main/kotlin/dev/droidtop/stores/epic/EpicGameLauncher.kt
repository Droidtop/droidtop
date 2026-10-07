package dev.droidtop.stores.epic

import android.content.Context
import dev.droidtop.stores.data.EpicGame
import dev.droidtop.stores.util.StoreFiles
import java.io.File
import java.io.IOException
import timber.log.Timber

/**
 * The command line Epic's own launcher gives a game, so a game that checks
 * its owner with Epic Online Services starts (GameNative's EpicGameLauncher,
 * GPL-3.0, after Legendary): a one-time exchange code, the account, the
 * locale, the sandbox, the EOS deployment id when the game ships one, an
 * ownership token file for games that need one, and the extra arguments
 * Epic's metadata names for the game.
 */
internal object EpicGameLauncher {

    /**
     * Builds the arguments for [game]. The ownership token, when the game
     * needs one, is written under the app's own files and passed as a Windows
     * path on the Z: drive, which a Wine prefix maps to the filesystem root
     * (GameNative wrote it into its own container's prefix instead).
     */
    suspend fun buildLaunchParameters(
        context: Context,
        manager: EpicManager,
        game: EpicGame,
        languageCode: String = "en-US",
    ): Result<List<String>> {
        return try {
            val params = mutableListOf<String>()
            val tokenResult = EpicAuthManager.getGameLaunchToken(
                context = context,
                namespace = game.namespace,
                catalogItemId = game.catalogId,
                requiresOwnershipToken = game.requiresOT,
            )
            val gameToken = tokenResult.getOrElse { return Result.failure(it) }

            val ownershipTokenPath = gameToken.ownershipToken?.let {
                saveOwnershipTokenToFile(context, game.namespace, game.catalogId, it)
            }

            // Authentication parameters
            params.add("-AUTH_LOGIN=unused")
            params.add("-AUTH_PASSWORD=${gameToken.authCode}")
            params.add("-AUTH_TYPE=exchangecode")
            params.add("-epicapp=${game.appName}")
            params.add("-epicenv=Prod")
            params.add("-EpicPortal")
            params.add("-epicusername=${gameToken.displayName.takeIf { it.isNotBlank() } ?: "EpicUser"}")
            params.add("-epicuserid=${gameToken.accountId}")
            params.add("-epiclocale=$languageCode")
            params.add("-epicsandboxid=${game.namespace}")

            // Required by modern EOS-integrated titles; absent for games without a sidecar.
            manager.fetchDeploymentId(context = context, namespace = game.namespace, catalogItemId = game.catalogId, appName = game.appName)
                ?.takeIf { it.isNotEmpty() }
                ?.let { params.add("-epicdeploymentid=$it") }

            ownershipTokenPath?.let { params.add("-epicovt=$it") }

            manager.fetchAdditionalCommandLine(context = context, namespace = game.namespace, catalogItemId = game.catalogId, appName = game.appName)
                ?.takeIf { it.isNotBlank() }
                ?.let { params.addAll(tokenizeArgs(it)) }

            Timber.tag("EPIC").d("Built ${params.size} launch parameters for ${game.appName}")
            Result.success(params)
        } catch (e: Exception) {
            Timber.e(e, "Failed to build launch parameters")
            Result.failure(e)
        }
    }

    /**
     * Tokenize a Windows-style command-line string, preserving double-quoted
     * segments. Adjacent quoted/unquoted runs collapse into one token (so
     * `-arg="value with spaces"` yields `-arg=value with spaces`). Single quotes
     * are treated as literal characters to match `CommandLineToArgvW` semantics.
     * Unbalanced double quotes consume to end-of-string.
     */
    fun tokenizeArgs(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inDouble = false
        var hasToken = false
        for (c in input) {
            when {
                inDouble -> if (c == '"') inDouble = false else current.append(c)
                c == '"' -> { inDouble = true; hasToken = true }
                c.isWhitespace() -> {
                    if (hasToken) {
                        tokens.add(current.toString())
                        current.setLength(0)
                        hasToken = false
                    }
                }
                else -> { current.append(c); hasToken = true }
            }
        }
        if (hasToken) tokens.add(current.toString())
        return tokens
    }

    /** A host path as Wine's Z: drive (the filesystem root) names it. */
    fun zDrivePath(file: File): String = "Z:" + file.absolutePath.replace('/', '\\')

    /** Writes the ownership token's bytes under the app's files and returns its Z: path. */
    private fun saveOwnershipTokenToFile(context: Context, namespace: String, catalogItemId: String, ownershipTokenHex: String): String {
        require(ownershipTokenHex.isNotEmpty()) { "Ownership token hex string is empty" }
        require(ownershipTokenHex.length % 2 == 0) { "Ownership token hex string has odd length: ${ownershipTokenHex.length}" }
        require(ownershipTokenHex.matches(Regex("^[0-9A-Fa-f]+$"))) { "Ownership token hex string contains invalid characters" }
        val dir = File(context.filesDir, "epic/ovt")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Failed to create ${dir.absolutePath}")
        val tokenFile = File(dir, "${StoreFiles.idPart(namespace)}${StoreFiles.idPart(catalogItemId)}.ovt")
        tokenFile.writeBytes(ownershipTokenHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        return zDrivePath(tokenFile)
    }
}
