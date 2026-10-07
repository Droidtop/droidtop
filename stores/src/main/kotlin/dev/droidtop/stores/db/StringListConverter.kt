package dev.droidtop.stores.db

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The store rows' list columns (GOG's and Epic's genres, languages, tags) as
 * JSON text: the same encoding GameNative's GOGConverter wrote, so rows
 * imported from its database read back unchanged.
 */
class StringListConverter {

    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
}
