package dev.droidtop.stores.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * itch.io game entity for Room database.
 *
 * Lifted from droidtop's fork of GameNative (app.gamenative.data.ItchGame,
 * GPL-3.0) into droidtop's own store module.
 *
 * itch has no depot/manifest system the way GOG, Epic and Amazon do: an
 * owned game is a "download key" (proof of ownership) plus a list of
 * uploads (one file per platform/build), and there is no separate
 * install-size vs download-size distinction from the API -- [sizeBytes]
 * is the chosen upload's own size.
 */
@Entity(tableName = "itch_games")
data class ItchGame(
    /** itch's own numeric game id, as a string (matches the other stores' [id] shape). */
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,

    @ColumnInfo("title")
    val title: String = "",

    /** The download key id proving ownership; required on every uploads/download call. */
    @ColumnInfo("download_key_id")
    val downloadKeyId: String = "",

    @ColumnInfo("cover_url")
    val coverUrl: String = "",

    /** The game's own page, e.g. "https://someuser.itch.io/some-game". */
    @ColumnInfo("url")
    val url: String = "",

    @ColumnInfo("is_installed")
    val isInstalled: Boolean = false,

    @ColumnInfo("install_path")
    val installPath: String = "",

    /** The upload id last installed, so a re-install or update reuses the same choice. */
    @ColumnInfo("installed_upload_id")
    val installedUploadId: Long = 0,

    @ColumnInfo("size_bytes")
    val sizeBytes: Long = 0,

    /**
     * The chosen upload's `updated_at` as itch gave it when the game was
     * installed: itch re-uploads a build under the same upload id, so this
     * stamp is what says a newer build exists. Empty for an install made
     * before it was recorded, which the update check then cannot judge.
     */
    @ColumnInfo(name = "installed_stamp", defaultValue = "''")
    val installedStamp: String = "",

    @ColumnInfo(name = "exclude", defaultValue = "0")
    val exclude: Boolean = false,
)

/** One buildable file for an itch game, as `GET /games/{id}/uploads` returns it. */
data class ItchUpload(
    val id: Long,
    val filename: String,
    val displayName: String?,
    val sizeBytes: Long,
    val platformWindows: Boolean,
    val platformLinux: Boolean,
    val platformAndroid: Boolean,
    val isDemo: Boolean,
    /** itch's `updated_at` for the upload, empty when it names none. */
    val updatedAt: String = "",
)
