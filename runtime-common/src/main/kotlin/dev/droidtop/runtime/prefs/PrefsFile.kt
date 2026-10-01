package dev.droidtop.runtime.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Typed, apply()-backed access to one existing SharedPreferences file. */
class PrefsFile private constructor(private val preferences: SharedPreferences) {
    constructor(context: Context, name: String) : this(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    fun string(key: String, default: String? = null): ReadWriteProperty<Any?, String?> =
        preference(key, default, SharedPreferences::getString) { editor, value ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }

    fun int(key: String, default: Int = 0): ReadWriteProperty<Any?, Int> =
        preference(key, default, SharedPreferences::getInt) { editor, value -> editor.putInt(key, value) }

    fun boolean(key: String, default: Boolean = false): ReadWriteProperty<Any?, Boolean> =
        preference(key, default, SharedPreferences::getBoolean) { editor, value -> editor.putBoolean(key, value) }

    fun long(key: String, default: Long = 0L): ReadWriteProperty<Any?, Long> =
        preference(key, default, SharedPreferences::getLong) { editor, value -> editor.putLong(key, value) }

    fun keyedStrings(prefix: String) = KeyedStringStore(preferences, prefix)

    fun getString(key: String, default: String? = null): String? = preferences.getString(key, default)
    fun putString(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }
    fun putStrings(values: Map<String, String?>) {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        editor.apply()
    }
    fun getBoolean(key: String, default: Boolean = false): Boolean = preferences.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) { preferences.edit().putBoolean(key, value).apply() }

    private fun <T> preference(
        key: String,
        default: T,
        read: (SharedPreferences, String, T) -> T,
        write: (SharedPreferences.Editor, T) -> SharedPreferences.Editor,
    ) = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = read(preferences, key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            write(preferences.edit(), value).apply()
        }
    }
}

/** String values indexed by a stable prefix and caller-supplied id. */
class KeyedStringStore internal constructor(
    private val preferences: SharedPreferences,
    private val prefix: String,
) {
    fun get(id: String): String? = preferences.getString(prefix + id, null)

    fun set(id: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(prefix + id) else editor.putString(prefix + id, value)
        editor.apply()
    }

    fun entries(): Map<String, String> = preferences.all
        .filterKeys { it.startsWith(prefix) }
        .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix(prefix) to it } }
        .toMap()
}
