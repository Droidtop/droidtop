package dev.droidtop.library.social

import android.content.Context
import dev.droidtop.library.integrations.PluginSocialProviders
import dev.droidtop.library.stores.StoreLibraries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * The one registry of social providers and the one way their friends, conversations and messages reach
 * the screens (docs/SPEC.md "Social", Droidtop/tracker#327): the stores droidtop runs that have friends
 * (Steam, `StoreLibrary.social`) and every running plugin entry that provides `social.provider@1`
 * ([PluginSocialProviders]). The Social place, the companion's Social tab, the Quick Menu tile and the
 * message notifications read it; none of them knows a service.
 *
 * Nothing here polls. A store pushes (Steam's callbacks); a plugin is asked when a social screen opens
 * ([refresh]) and when it says something changed (`social.changed`).
 */
object SocialHub {
    private const val TAG = "SocialHub"

    private val plugins = MutableStateFlow<List<SocialProvider>>(emptyList())

    /** Every provider: the built-in stores first, then the plugins by name. In memory; cheap. */
    fun providers(): List<SocialProvider> = StoreLibraries.all().mapNotNull { it.social } + plugins.value

    /** The provider with [id], or null when it is gone (a plugin was turned off). */
    fun provider(id: String): SocialProvider? = providers().firstOrNull { it.id == id }

    /** Called by [PluginSocialProviders] when the running plugin providers changed. */
    internal fun setPluginProviders(list: List<SocialProvider>) {
        plugins.value = list.sortedBy { it.label.lowercase() }
    }

    /** The providers with an account to show (signed in, running). Reads the stores' sign-in state: not on the main thread. */
    fun available(context: Context): List<SocialProvider> = providers().filter { runCatching { it.available(context) }.getOrDefault(false) }

    /** The providers whose friends are shown: available, and not set to Offline. Not on the main thread. */
    fun online(context: Context): List<SocialProvider> = available(context).filter { it.presence(context) != SocialPresence.OFFLINE }

    /** Every shown provider's friends as one list ([SocialOrder.contacts]). Not on the main thread. */
    fun contacts(context: Context): List<SocialContact> = SocialOrder.contacts(online(context))

    /** Unread messages over every provider, from memory. */
    fun unread(): Int = SocialOrder.unread(providers())

    /**
     * Fires when any provider's friends, connection, name or unread count changed, or a provider came or went:
     * what a live screen ([dev.droidtop.library.settings.CatalogScreen.live]) and the Quick Menu's count follow.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun changes(): Flow<Any?> = plugins.flatMapLatest { pluginList ->
        val all = StoreLibraries.all().mapNotNull { it.social } + pluginList
        val flows = all.flatMap { p -> listOf<Flow<Any?>>(p.friends, p.link, p.me, p.unread) }
        if (flows.isEmpty()) flowOf(Unit) else combine(flows) { it.toList() }.map { it as Any? }
    }

    /**
     * Brings the plugin providers in line with the running plugins and asks each one again, in parallel. Called
     * when a social screen opens; never from list drawing and never on a timer. A provider that fails keeps what
     * it had.
     */
    suspend fun refresh(context: Context) {
        PluginSocialProviders.sync(context)
        coroutineScope {
            providers().map { provider ->
                async {
                    try {
                        provider.refresh(context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Timber.tag(TAG).w(t, "Refreshing %s failed", provider.id)
                    }
                }
            }.awaitAll()
        }
    }

    /** What a new message shows as: who, on which service, and the text. */
    data class Incoming(val providerId: String, val providerLabel: String, val friendId: String, val name: String, val text: String) {
        /** One notification per conversation, replaced by its next message. */
        val conversationKey: String get() = "$providerId/$friendId"
    }

    /** Set by the app, which owns notifications: shows a message that arrived while its conversation is not open. */
    @Volatile
    var notifier: ((Incoming) -> Unit)? = null

    /**
     * A provider's message arrived while its conversation is not open. The provider has already decided whether
     * the person wants it shown (Steam's own switch, a plugin's `notify.post` grant).
     */
    fun incoming(provider: SocialProvider, friendId: String, name: String, text: String) {
        val incoming = Incoming(provider.id, provider.label, friendId, name, text)
        runCatching { notifier?.invoke(incoming) }.onFailure { Timber.tag(TAG).w(it, "Notifying a message failed") }
    }
}
