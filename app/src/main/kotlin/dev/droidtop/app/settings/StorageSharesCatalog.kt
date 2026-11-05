package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.StatusMark
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.storage.StorageShare
import dev.droidtop.library.storage.StorageShares
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Accounts and sources > "Network and cloud storage" (docs/SPEC.md "Network and cloud storage",
 * Droidtop/tracker#573): the person's own shares, one row each with its state, and Add share. A share is added
 * and tested only by the person; what uses it (saves, backups, a games root) is turned on where that thing is.
 */
object StorageSharesCatalog {
    /** Add share's fields until Add is pressed. The password field is never shown back. */
    @Volatile private var pendingName = ""
    @Volatile private var pendingUrl = ""
    @Volatile private var pendingUser = ""
    @Volatile private var pendingPassword = ""
    @Volatile private var pendingPlainHttp = false
    @Volatile private var pendingTrusted = false

    private const val TRUSTED = "trusted"
    private const val LOCKED = "locked"

    /**
     * The trust choice (owner, 2026-11-05, #573: "Encryption should probably be toggleable"). Locked is the default:
     * nothing goes to a share in plain until the person says they trust it.
     */
    private val trustOptions = listOf(ChoiceOption(LOCKED, "Locked"), ChoiceOption(TRUSTED, "Trusted"))

    private fun trustLine(trusted: Boolean): String = if (trusted) {
        "Anyone who runs this share can read your saves and backups. You can open them yourself and restore them on any device"
    } else {
        // The lock is this device's own key, which never leaves the device (DeviceIdentity), so only this device opens it.
        "droidtop locks what it writes here. The share's owner can't read it, and only this device can open it: " +
            "a new or reset device can't restore it"
    }

    private fun mark(state: String): StatusMark = when (state) {
        StorageShares.CONNECTED -> StatusMark.SIGNED_IN
        StorageShares.NOT_TESTED -> StatusMark.ATTENTION
        else -> StatusMark.ERROR
    }

    /** The group: every share, then Add share. Preferences: call off the main thread. */
    fun group(context: Context): CatalogGroup = CatalogGroup(
        id = "accounts_storage",
        title = "Network and cloud storage",
        items = StorageShares.all(context).map(::shareRow) + addRow(),
    )

    private fun shareRow(share: StorageShare): CatalogItem {
        val id = "accounts_storage_${share.id}"
        return NestedScreenItem(
            id = id,
            title = share.name,
            subtitle = share.url.substringAfter("://").substringBefore('/'),
            valueLabel = { StorageShares.stateLabel(share.state) },
            statusMark = mark(share.state),
            inline = CatalogScreen(
                id = "${id}_screen",
                title = share.name,
                subtitle = share.url,
                groups = { ctx ->
                    val now = withContext(Dispatchers.IO) { StorageShares.get(ctx, share.id) } ?: share
                    listOf(
                        CatalogGroup(
                            id = "${id}_state",
                            title = null,
                            items = listOf(
                                AsyncActionItem(
                                    id = "${id}_test",
                                    title = "Test",
                                    subtitle = now.stateDetail,
                                    value = StorageShares.stateLabel(now.state),
                                    statusMark = mark(now.state),
                                    run = { c, _ -> withContext(Dispatchers.IO) { StorageShares.test(c, now) } },
                                ),
                            ),
                        ),
                        CatalogGroup(
                            id = "${id}_sign_in",
                            title = "Address and sign-in",
                            items = listOf(
                                TextInputItem(
                                    id = "${id}_name",
                                    title = "Name",
                                    value = now.name,
                                    onChange = { c, v -> withContext(Dispatchers.IO) { StorageShares.update(c, now.id) { it.copy(name = v.trim().ifBlank { it.name }) } } },
                                ),
                                TextInputItem(
                                    id = "${id}_url",
                                    title = "Address",
                                    subtitle = "The share's WebDAV address",
                                    value = now.url,
                                    onChange = { c, v ->
                                        withContext(Dispatchers.IO) {
                                            if (StorageShares.addressProblem(v, now.allowPlainHttp) == null) StorageShares.update(c, now.id) { it.copy(url = v.trim()) }
                                        }
                                    },
                                ),
                                TextInputItem(
                                    id = "${id}_user",
                                    title = "User name",
                                    value = now.user,
                                    onChange = { c, v -> withContext(Dispatchers.IO) { StorageShares.update(c, now.id) { it.copy(user = v.trim()) } } },
                                ),
                                TextInputItem(
                                    id = "${id}_password",
                                    title = "Password",
                                    subtitle = "Kept locked on this device, never in a backup",
                                    value = "",
                                    secret = true,
                                    onChange = { c, v -> withContext(Dispatchers.IO) { StorageShares.setPassword(c, now.id, v) } },
                                ),
                                ToggleItem(
                                    id = "${id}_plain",
                                    title = "Allow unencrypted",
                                    subtitle = "Only for a share on your own network: the password and files travel readable",
                                    current = now.allowPlainHttp,
                                    onToggle = { c, on -> withContext(Dispatchers.IO) { StorageShares.update(c, now.id) { it.copy(allowPlainHttp = on) } } },
                                ),
                            ),
                        ),
                        CatalogGroup(
                            id = "${id}_trust",
                            title = "Trust",
                            items = listOf(
                                ChoiceItem(
                                    id = "${id}_trusted",
                                    title = "What droidtop writes",
                                    subtitle = trustLine(now.trusted),
                                    options = trustOptions,
                                    current = if (now.trusted) TRUSTED else LOCKED,
                                    onSelect = { c, v -> StorageShares.update(c, now.id) { it.copy(trusted = v == TRUSTED) } },
                                ),
                            ),
                        ),
                        CatalogGroup(
                            id = "${id}_remove_group",
                            title = null,
                            items = listOf(
                                AsyncActionItem(
                                    id = "${id}_remove",
                                    title = "Remove",
                                    subtitle = "Takes it off this list and forgets its password. Nothing on the share is deleted",
                                    confirmTitle = "Remove ${now.name}?",
                                    run = { c, _ -> withContext(Dispatchers.IO) { StorageShares.remove(c, now.id) }; "Removed ${now.name}" },
                                ),
                            ),
                        ),
                    )
                },
            ),
        )
    }

    /** Add share: a WebDAV address and the person's own sign-in. More kinds join this screen as their backends land. */
    private fun addRow(): CatalogItem = NestedScreenItem(
        id = "accounts_storage_add",
        title = "Add share",
        subtitle = "Nextcloud, ownCloud, a NAS or any WebDAV server, with your own sign-in",
        inline = CatalogScreen(
            id = "accounts_storage_add_screen",
            title = "Add share",
            groups = {
                listOf(
                    CatalogGroup(
                        id = "accounts_storage_add_group",
                        title = null,
                        items = listOf(
                            TextInputItem(
                                id = "accounts_storage_add_url",
                                title = "Address",
                                subtitle = "Its WebDAV address, such as https://cloud.example.com/remote.php/dav/files/you/",
                                value = pendingUrl,
                                onChange = { _, v -> pendingUrl = v.trim() },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_user",
                                title = "User name",
                                value = pendingUser,
                                onChange = { _, v -> pendingUser = v.trim() },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_password",
                                title = "Password",
                                subtitle = "Kept locked on this device. An app password works best where the service has them",
                                value = "",
                                secret = true,
                                onChange = { _, v -> pendingPassword = v },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_name",
                                title = "Name",
                                subtitle = "How it is listed; its server name when left blank",
                                value = pendingName,
                                onChange = { _, v -> pendingName = v },
                            ),
                            ToggleItem(
                                id = "accounts_storage_add_plain",
                                title = "Allow unencrypted",
                                subtitle = "Only for a share on your own network: the password and files travel readable",
                                current = pendingPlainHttp,
                                onToggle = { _, on -> pendingPlainHttp = on },
                            ),
                            ChoiceItem(
                                id = "accounts_storage_add_trusted",
                                title = "What droidtop writes",
                                subtitle = trustLine(pendingTrusted),
                                options = trustOptions,
                                current = if (pendingTrusted) TRUSTED else LOCKED,
                                onSelect = { _, v -> pendingTrusted = v == TRUSTED },
                            ),
                            AsyncActionItem(
                                id = "accounts_storage_add_run",
                                title = "Add and test",
                                run = { ctx, _ ->
                                    val added = withContext(Dispatchers.IO) {
                                        StorageShares.addWebDav(ctx, pendingName, pendingUrl, pendingUser, pendingPassword, pendingPlainHttp, pendingTrusted)
                                    }
                                    when (added) {
                                        is StorageShares.Added.Refused -> added.reason
                                        is StorageShares.Added.Share -> {
                                            pendingName = ""; pendingUrl = ""; pendingUser = ""; pendingPassword = ""
                                            pendingPlainHttp = false; pendingTrusted = false
                                            "Added. " + withContext(Dispatchers.IO) { StorageShares.test(ctx, added.share) }
                                        }
                                    }
                                },
                            ),
                        ),
                    ),
                )
            },
        ),
    )
}
