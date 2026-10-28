package dev.droidtop.library.profile

import android.content.Context
import android.net.Uri
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.social.SocialHub
import dev.droidtop.library.stores.StoreLibraries
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A name droidtop already knows for the person: [name], as [from] (a service's label) says it. [key] picks it. */
data class ProfileCandidate(val key: String, val name: String, val from: String)

/** What droidtop shows as the person: a [name] (null: none known yet) and a [picture] file (null: none chosen). */
data class Profile(val name: String?, val picture: File?)

/**
 * droidtop's own profile (Droidtop/tracker#586; owner, 2026-10-28: "a, but autofill from all the profiles we have and
 * let users pick/override"): a name and a picture, set in the Quick Menu's Profile section. The name is filled in from
 * every account droidtop knows ([candidates]: a social provider's own name, Steam's among them, and a store's account
 * name where the store keeps one), the first by default; the person picks another or types their own. No account gives
 * droidtop a picture, so the picture is one the person picks from their files, kept in droidtop's own storage. On the
 * device only; files and preferences, so every read is off the main thread.
 */
object ProfileStore {
    private const val PREFS = "droidtop_profile"
    private const val KEY_SOURCE = "source"
    private const val KEY_OWN_NAME = "own_name"
    private const val SOURCE_AUTO = "auto"
    private const val SOURCE_OWN = "own"
    private const val PICTURE_MAX_BYTES = 8L * 1024 * 1024

    const val SCREEN_ID = "profile"

    private val state = MutableStateFlow(Profile(null, null))

    /** The profile as last read ([refresh]): Home's band and the Quick Menu draw it. */
    val current: StateFlow<Profile> = state

    /**
     * Every name droidtop knows for the person, social providers first (they say the person's own display name), then
     * stores that keep an account name; one per distinct name. Disk and provider reads: not on the main thread.
     */
    fun candidates(context: Context): List<ProfileCandidate> {
        val social = SocialHub.available(context).mapNotNull { p ->
            p.me.value?.takeIf { it.isNotBlank() }?.let { ProfileCandidate("social:${p.id}", it, p.label) }
        }
        val stores = StoreLibraries.all().mapNotNull { s ->
            runCatching { s.accountName(context) }.getOrNull()?.takeIf { it.isNotBlank() && s.signedIn(context) }
                ?.let { ProfileCandidate("store:${s.id}", it, s.label) }
        }
        return (social + stores).distinctBy { it.name.lowercase() }
    }

    /**
     * The profile from the person's choice: their own name, the candidate they picked, or the first candidate. A
     * picked candidate that is gone (signed out) falls back to the first. Pure.
     */
    fun resolve(source: String, ownName: String, candidates: List<ProfileCandidate>, picture: File?): Profile = Profile(
        name = when (source) {
            SOURCE_OWN -> ownName.ifBlank { null }
            SOURCE_AUTO -> candidates.firstOrNull()?.name
            else -> (candidates.firstOrNull { it.key == source } ?: candidates.firstOrNull())?.name
        },
        picture = picture,
    )

    /** Reads the profile again and publishes it. Disk: not on the main thread. */
    fun refresh(context: Context): Profile {
        val p = prefs(context)
        return resolve(
            p.getString(KEY_SOURCE, SOURCE_AUTO) ?: SOURCE_AUTO,
            p.getString(KEY_OWN_NAME, "").orEmpty(),
            candidates(context),
            pictureFile(context).takeIf { it.isFile },
        ).also { state.value = it }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pictureFile(context: Context) = File(context.applicationContext.filesDir, "profile/picture")

    /** Copies the picked image into droidtop's storage; the answer is the line the row shows. Disk: IO only. */
    private suspend fun setPicture(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val target = pictureFile(context)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "picture.part")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > PICTURE_MAX_BYTES) return@runCatching false
                        out.write(buffer, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied) {
            part.delete()
            return@withContext "That picture could not be used. Pick an image under 8 MB"
        }
        part.renameTo(target)
        refresh(context)
        "Picture set"
    }

    /** The Quick Menu's Profile section: the name to show, your own name, and the picture. */
    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Profile",
        subtitle = "The name and picture droidtop shows for you",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val p = prefs(context)
                val source = p.getString(KEY_SOURCE, SOURCE_AUTO) ?: SOURCE_AUTO
                val ownName = p.getString(KEY_OWN_NAME, "").orEmpty()
                val found = candidates(context)
                val profile = refresh(context)
                val options = buildList {
                    add(ChoiceOption(SOURCE_AUTO, found.firstOrNull()?.let { "From your accounts (${it.name})" } ?: "From your accounts"))
                    found.forEach { add(ChoiceOption(it.key, "${it.name} · ${it.from}")) }
                    add(ChoiceOption(SOURCE_OWN, if (ownName.isBlank()) "Your own name" else "Your own name ($ownName)"))
                }
                listOf(
                    CatalogGroup(
                        id = "profile_name",
                        title = null,
                        items = listOfNotNull(
                            ChoiceItem(
                                id = "profile_source",
                                title = "Name",
                                subtitle = if (found.isEmpty()) "No account has told droidtop your name yet. Type your own below" else null,
                                options = options,
                                current = source,
                                onSelect = { ctx, value ->
                                    prefs(ctx).edit().putString(KEY_SOURCE, value).apply()
                                },
                            ),
                            TextInputItem(
                                id = "profile_own_name",
                                title = "Your own name",
                                value = ownName,
                                onChange = { ctx, value ->
                                    withContext(Dispatchers.IO) {
                                        prefs(ctx).edit().putString(KEY_OWN_NAME, value.trim()).putString(KEY_SOURCE, SOURCE_OWN).apply()
                                        refresh(ctx)
                                    }
                                },
                            ),
                            DocumentPickItem(
                                id = "profile_picture",
                                title = "Picture",
                                subtitle = if (profile.picture != null) "Set. Pick another image to change it" else "Pick an image from your files",
                                mimeType = "image/*",
                                onPicked = { ctx, uri -> setPicture(ctx, uri) },
                            ),
                            profile.picture?.let {
                                AsyncActionItem(
                                    id = "profile_picture_remove",
                                    title = "Remove picture",
                                    run = { ctx, _ ->
                                        withContext(Dispatchers.IO) {
                                            pictureFile(ctx).delete()
                                            refresh(ctx)
                                        }
                                        "Picture removed"
                                    },
                                )
                            },
                        ),
                    ),
                )
            }
        },
    )
}
