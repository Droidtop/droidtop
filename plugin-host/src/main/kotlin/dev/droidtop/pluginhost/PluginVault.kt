package dev.droidtop.pluginhost

import android.content.Context
import dev.droidtop.net.KeystoreSecretCipher
import dev.droidtop.net.SecretCipher
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64
import org.json.JSONObject

/**
 * Each plugin's own secrets (docs/plugin-api.md 3 G1, `vault@1`): API keys and tokens a plugin must keep, sealed
 * with droidtop's one at-rest mechanism ([SecretCipher], [KeystoreSecretCipher]) under a Keystore key of that
 * plugin's own, one file per plugin under [dir]. A plugin reaches only its own namespace, because the broker names
 * the caller; uninstalling the plugin deletes the file and the key ([clear]). The production [dir] is in
 * `noBackupFilesDir`, so secrets never leave the device in a backup.
 *
 * A value that can no longer be opened (the key is gone after a device restore) reads as absent, never as an error
 * the plugin cannot act on: it asks the person again.
 */
class PluginVault(
    private val dir: File,
    private val cipherFor: (pluginId: String) -> SecretCipher,
    private val dropKey: (pluginId: String) -> Unit = {},
) {
    class Refused(message: String) : Exception(message)

    private fun file(pluginId: String) = File(dir, "$pluginId.json")

    private fun read(pluginId: String): JSONObject {
        val file = file(pluginId)
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun write(pluginId: String, json: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "$pluginId.json.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file(pluginId).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    fun put(pluginId: String, key: String, value: String) {
        if (!validKey(key)) throw Refused("a key is 1 to 128 letters, digits, dots, dashes or underscores")
        if (value.length > MAX_VALUE) throw Refused("a value is at most $MAX_VALUE characters")
        val json = read(pluginId)
        if (!json.has(key) && json.length() >= MAX_KEYS) throw Refused("a plugin keeps at most $MAX_KEYS values")
        json.put(key, Base64.getEncoder().encodeToString(cipherFor(pluginId).encrypt(value.toByteArray(Charsets.UTF_8))))
        write(pluginId, json)
    }

    @Synchronized
    fun get(pluginId: String, key: String): String? {
        val sealed = read(pluginId).optString(key).takeIf { it.isNotEmpty() } ?: return null
        return runCatching { String(cipherFor(pluginId).decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8) }.getOrNull()
    }

    @Synchronized
    fun delete(pluginId: String, key: String): Boolean {
        val json = read(pluginId)
        if (!json.has(key)) return false
        json.remove(key)
        write(pluginId, json)
        return true
    }

    @Synchronized
    fun keys(pluginId: String): List<String> = read(pluginId).keys().asSequence().toList().sorted()

    /** Uninstall: the values and the key go with the plugin. */
    @Synchronized
    fun clear(pluginId: String) {
        file(pluginId).delete()
        File(dir, "$pluginId.json.tmp").delete()
        runCatching { dropKey(pluginId) }
    }

    companion object {
        const val MAX_KEYS = 256
        const val MAX_VALUE = 16 * 1024
        private val KEY = Regex("^[A-Za-z0-9._-]{1,128}$")

        fun validKey(key: String): Boolean = KEY.matches(key)

        private fun alias(pluginId: String) = "droidtop_plugin_vault_$pluginId"

        fun forContext(context: Context): PluginVault = PluginVault(
            dir = File(context.applicationContext.noBackupFilesDir, "plugin-vault"),
            cipherFor = { KeystoreSecretCipher(alias(it)) },
            dropKey = { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(it)) },
        )
    }
}
