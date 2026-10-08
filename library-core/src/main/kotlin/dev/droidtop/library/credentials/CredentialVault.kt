package dev.droidtop.library.credentials

import dev.droidtop.net.SecretCipher
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Where the sealed values rest: a SharedPreferences file on the device, a map in tests. */
interface StringStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * Every user-supplied credential droidtop keeps (docs/SPEC.md 7h, "Credentials
 * are stored encrypted"): sealed with [cipher] before it is written, opened on
 * demand, never logged. A value that cannot be opened (the Keystore key was
 * lost, or the stored text was altered) reads as blank, which every caller
 * already treats as "not set"; nothing falls back to plain text.
 */
class CredentialVault(private val store: StringStore, private val cipher: SecretCipher) {
    private val opened = ConcurrentHashMap<String, String>()

    /** The value for [key], or blank when none is stored or it cannot be opened. */
    fun get(key: String): String {
        opened[key]?.let { return it }
        val text = store.get(key) ?: return ""
        val plain = runCatching { String(cipher.decrypt(Base64.getDecoder().decode(text)), Charsets.UTF_8) }
            .getOrDefault("")
        if (plain.isNotEmpty()) opened[key] = plain
        return plain
    }

    /** Seals and stores [value]; a blank value removes the entry. Throws if it cannot be sealed. */
    fun put(key: String, value: String) {
        if (value.isBlank()) {
            remove(key)
            return
        }
        val sealed = Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
        store.put(key, sealed)
        opened[key] = value
    }

    fun remove(key: String) {
        store.remove(key)
        opened.remove(key)
    }

    fun has(key: String): Boolean = get(key).isNotBlank()

    /** The opened values of [keys] that are set, for an opted-in settings backup. */
    fun snapshot(keys: Collection<String>): Map<String, String> =
        keys.mapNotNull { key -> get(key).takeIf { it.isNotBlank() }?.let { key to it } }.toMap()

    /**
     * Moves [keys] out of [legacy] (the plain preferences they used to live
     * in) into this vault. A value is removed from [legacy] only after it
     * reads back from the vault, so a failure leaves it where it was. A vault
     * value that is already set wins over a stale plain one. Returns how many
     * keys left [legacy].
     */
    fun migrateFrom(legacy: StringStore, keys: Collection<String>): Int {
        var moved = 0
        for (key in keys) {
            val old = legacy.get(key) ?: continue
            if (old.isNotBlank() && !has(key)) {
                val stored = runCatching { put(key, old) }.isSuccess
                if (!stored || get(key) != old) continue
            }
            legacy.remove(key)
            moved++
        }
        return moved
    }

    companion object {
        /** "....abcd": enough to recognise a value without showing it. */
        fun mask(value: String): String =
            if (value.length <= 8) "••••" else "••••" + value.takeLast(4)
    }
}
