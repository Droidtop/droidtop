package dev.droidtop.stores.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * GOG Game entity for Room database
 * Represents a game from the GOG platform
 *
 * Lifted from GameNative (app.gamenative.data.GOGGame, GPL-3.0). GameNative's
 * `type`, `last_played` and `play_time` columns are not carried: nothing read
 * the type, and droidtop measures play itself (docs/SPEC.md 7g, "Playtime").
 */
@Entity(tableName = "gog_games")
data class GOGGame(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,

    @ColumnInfo("title")
    val title: String = "",

    @ColumnInfo("slug")
    val slug: String = "",

    @ColumnInfo("download_size")
    val downloadSize: Long = 0,

    @ColumnInfo("install_size")
    val installSize: Long = 0,

    @ColumnInfo("is_installed")
    val isInstalled: Boolean = false,

    @ColumnInfo("install_path")
    val installPath: String = "",

    @ColumnInfo("image_url")
    val imageUrl: String = "",

    @ColumnInfo("icon_url")
    val iconUrl: String = "",

    @ColumnInfo(name = "background_url", defaultValue = "''")
    val backgroundUrl: String = "",

    @ColumnInfo(name = "vertical_cover_url", defaultValue = "''")
    val verticalCoverUrl: String = "",

    @ColumnInfo("description")
    val description: String = "",

    @ColumnInfo("release_date")
    val releaseDate: String = "",

    @ColumnInfo("developer")
    val developer: String = "",

    @ColumnInfo("publisher")
    val publisher: String = "",

    @ColumnInfo("genres")
    val genres: List<String> = emptyList(),

    @ColumnInfo("languages")
    val languages: List<String> = emptyList(),

    @ColumnInfo(name = "exclude", defaultValue = "0")
    val exclude: Boolean = false,

    /** GOG's id of the build the install was made from; the update check compares it with the newest. Empty before it was recorded. */
    @ColumnInfo(name = "installed_build_id", defaultValue = "''")
    val installedBuildId: String = "",

    /** That build's version name as GOG words it ("1.2.3"), for the game page. */
    @ColumnInfo(name = "installed_version_name", defaultValue = "''")
    val installedVersionName: String = "",
)

data class GOGCredentials(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val username: String,
)
