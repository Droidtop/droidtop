package dev.droidtop.stores.steam

import java.util.Date
import java.util.EnumSet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * What Steam's product info (PICS) says about an app, as droidtop keeps it.
 * Lifted from GameNative (app.gamenative.data and app.gamenative.enums,
 * GPL-3.0, docs/SPEC.md 7g "Stores"): the same shapes and the same JSON
 * field names, so a row GameNative stored reads back unchanged
 * (SteamDatabase's import). Only what droidtop reads is kept; a field only
 * GameNative used is left out and ignored when an old row names it.
 */

/** The ids Steam uses for "no app" and "no package" (GameNative's SteamService.INVALID_APP_ID / INVALID_PKG_ID). */
object SteamIds {
    const val INVALID_APP_ID: Int = Int.MAX_VALUE
    const val INVALID_PKG_ID: Int = Int.MAX_VALUE

    /** Spacewar, the app every account owns for testing; never a game anybody has. */
    const val SPACEWAR: Int = 480
}

enum class OS(val code: Int) {
    none(0),
    windows(0x01),
    macos(0x02),
    linux(0x04),
    ;

    companion object {
        fun from(keyValue: String?): EnumSet<OS> {
            val found = keyValue?.takeUnless { it.isEmpty() }
                ?.split(',')
                ?.map { name -> entries.firstOrNull { it.name == name.trim() } ?: none }
                ?.toCollection(EnumSet.noneOf(OS::class.java))
            return found ?: EnumSet.of(none)
        }

        fun from(code: Int): EnumSet<OS> {
            val result = EnumSet.noneOf(OS::class.java)
            entries.forEach { os -> if (code and os.code == os.code) result.add(os) }
            return result
        }

        fun code(value: EnumSet<OS>): Int = value.map { it.code }.reduceOrNull { a, b -> a or b } ?: none.code
    }
}

enum class OSArch(val keyValName: String) {
    Arch32("32"),
    Arch64("64"),
    Unknown("unknown"),
    ;

    companion object {
        fun from(keyValue: String?): OSArch = when (keyValue) {
            Arch32.keyValName -> Arch32
            Arch64.keyValName -> Arch64
            else -> Unknown
        }
    }
}

enum class AppType(val code: Int) {
    invalid(0),
    game(0x01),
    application(0x02),
    tool(0x04),
    demo(0x08),
    deprected(0x10),
    dlc(0x20),
    guide(0x40),
    driver(0x80),
    config(0x100),
    hardware(0x200),
    franchise(0x400),
    video(0x800),
    plugin(0x1000),
    music(0x2000),
    series(0x4000),
    comic(0x8000),
    beta(0x10000),
    shortcut(0x20000),
    ;

    companion object {
        fun from(keyValue: String?): AppType = entries.firstOrNull { it.name == keyValue?.lowercase() } ?: invalid

        fun fromCode(code: Int): AppType = entries.firstOrNull { it.code == code } ?: invalid
    }
}

enum class SteamRealm(val keyValue: String?) {
    SteamGlobal("steamglobal"),
    SteamChina("steamchina"),
    Unknown("unknown"),
    ;

    companion object {
        fun from(keyValue: String?): SteamRealm = when (keyValue) {
            SteamGlobal.keyValue -> SteamGlobal
            SteamChina.keyValue -> SteamChina
            else -> Unknown
        }
    }
}

/** Steam's language names, as its image maps key them. */
@Suppress("EnumEntryName")
enum class Language {
    english, german, french, italian, koreana, spanish, schinese, sc_schinese, tchinese, russian, japanese, polish,
    brazilian, latam, vietnamese, portuguese, danish, dutch, swedish, norwegian, finnish, turkish, thai, czech, unknown,
    ;

    companion object {
        fun from(keyValue: String?): Language = entries.firstOrNull { it.name == keyValue?.lowercase() } ?: unknown
    }
}

object OsEnumSetSerializer : KSerializer<EnumSet<OS>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EnumSet<OS>", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: EnumSet<OS>) = encoder.encodeInt(OS.code(value))
    override fun deserialize(decoder: Decoder): EnumSet<OS> = OS.from(decoder.decodeInt())
}

object SteamRealmSerializer : KSerializer<SteamRealm> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SteamRealm", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: SteamRealm) = encoder.encodeString(value.keyValue ?: "unknown")
    override fun deserialize(decoder: Decoder): SteamRealm = SteamRealm.from(decoder.decodeString())
}

object DateSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Date", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Date) = encoder.encodeLong(value.time)
    override fun deserialize(decoder: Decoder): Date = Date(decoder.decodeLong())
}

@Serializable
data class ManifestInfo(
    val name: String,
    @Serializable(with = LongAsStringSerializer::class)
    val gid: Long,
    @Serializable(with = LongAsStringSerializer::class)
    val size: Long,
    @Serializable(with = LongAsStringSerializer::class)
    val download: Long,
) {
    /**
     * What a download of this manifest moves: its download size, capped at
     * its installed size because Steam sometimes reports a download larger
     * than the install (GameNative's SteamUtils.getDownloadBytes).
     */
    val downloadBytes: Long get() = if (download in 1..size) download else size
}

@Serializable
data class DepotInfo(
    val depotId: Int,
    val dlcAppId: Int,
    val optionalDlcId: Int = SteamIds.INVALID_APP_ID,
    val depotFromApp: Int,
    val sharedInstall: Boolean,
    @Serializable(with = OsEnumSetSerializer::class)
    val osList: EnumSet<OS>,
    val osArch: OSArch,
    val manifests: Map<String, ManifestInfo>,
    val encryptedManifests: Map<String, ManifestInfo>,
    val language: String = "",
    @Serializable(with = SteamRealmSerializer::class)
    val realm: SteamRealm = SteamRealm.Unknown,
    val systemDefined: Boolean = false,
    val steamDeck: Boolean = false,
) {
    /** Windows, or tagged with no system at all, which Steam means as Windows. */
    val isWindowsCompatible: Boolean
        get() = osList.contains(OS.windows) || (!osList.contains(OS.linux) && !osList.contains(OS.macos))
}

@Serializable
data class BranchInfo(
    val name: String,
    @Serializable(with = LongAsStringSerializer::class)
    val buildId: Long,
    val pwdRequired: Boolean,
    @Serializable(with = DateSerializer::class)
    val timeUpdated: Date,
)

/**
 * One launch entry of an app's config. [arguments] is droidtop's addition
 * (the entry's own `arguments`, which GameNative did not read); a row stored
 * before it has none.
 */
@Serializable
data class LaunchInfo(
    val executable: String,
    val workingDir: String,
    val description: String,
    val type: String,
    @Serializable(with = OsEnumSetSerializer::class)
    val configOS: EnumSet<OS>,
    val configArch: OSArch,
    val arguments: String = "",
)

@Serializable
data class ConfigInfo(
    val installDir: String = "",
    val launch: List<LaunchInfo> = emptyList(),
)

@Serializable
data class LibraryCapsuleInfo(
    val image: Map<Language, String> = emptyMap(),
    val image2x: Map<Language, String> = emptyMap(),
)

@Serializable
data class LibraryHeroInfo(
    val image: Map<Language, String> = emptyMap(),
    val image2x: Map<Language, String> = emptyMap(),
)

@Serializable
data class LibraryLogoInfo(
    val image: Map<Language, String> = emptyMap(),
    val image2x: Map<Language, String> = emptyMap(),
)

@Serializable
data class LibraryAssetsInfo(
    val libraryCapsule: LibraryCapsuleInfo = LibraryCapsuleInfo(),
    val libraryHero: LibraryHeroInfo = LibraryHeroInfo(),
    val libraryLogo: LibraryLogoInfo = LibraryLogoInfo(),
)
