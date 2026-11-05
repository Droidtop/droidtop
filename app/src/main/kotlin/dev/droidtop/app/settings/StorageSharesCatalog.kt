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

    private const val BUCKET_IN_HOST_LINE = "Reach the bucket as <bucket>.<server>, as AWS does. Off for most other services"

    /** One of an S3 share's own fields, or null for a share of another kind. */
    private fun s3Field(
        share: StorageShare,
        id: String,
        title: String,
        value: String,
        subtitle: String? = null,
        change: (StorageShare, String) -> StorageShare,
    ): CatalogItem? = if (share.kind != StorageShares.S3) null else TextInputItem(
        id = id,
        title = title,
        subtitle = subtitle,
        value = value,
        onChange = { c, v -> withContext(Dispatchers.IO) { StorageShares.update(c, share.id) { change(it, v) } } },
    )

    private fun mark(state: String): StatusMark = when (state) {
        StorageShares.CONNECTED -> StatusMark.SIGNED_IN
        StorageShares.NOT_TESTED -> StatusMark.ATTENTION
        else -> StatusMark.ERROR
    }

    /** The group: every share, then Add share. Preferences: call off the main thread. */
    fun group(context: Context): CatalogGroup = CatalogGroup(
        id = "accounts_storage",
        title = "Network and cloud storage",
        items = StorageShares.all(context).map(::shareRow) + addRow() + addS3Row(),
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
                            items = listOfNotNull(
                                TextInputItem(
                                    id = "${id}_name",
                                    title = "Name",
                                    value = now.name,
                                    onChange = { c, v -> withContext(Dispatchers.IO) { StorageShares.update(c, now.id) { it.copy(name = v.trim().ifBlank { it.name }) } } },
                                ),
                                TextInputItem(
                                    id = "${id}_url",
                                    title = "Address",
                                    subtitle = if (now.kind == StorageShares.S3) "The service's S3 address" else "The share's WebDAV address",
                                    value = now.url,
                                    onChange = { c, v ->
                                        withContext(Dispatchers.IO) {
                                            if (StorageShares.addressProblem(v, now.allowPlainHttp) == null) StorageShares.update(c, now.id) { it.copy(url = v.trim()) }
                                        }
                                    },
                                ),
                                s3Field(now, "${id}_bucket", "Bucket", now.bucket) { sh, v -> if (StorageShares.bucketProblem(v) == null) sh.copy(bucket = v.trim()) else sh },
                                s3Field(now, "${id}_region", "Region", now.region, "Blank is us-east-1") { sh, v -> sh.copy(region = v.trim()) },
                                s3Field(now, "${id}_folder", "Folder", now.folder, "Inside the bucket; blank is its top") { sh, v -> sh.copy(folder = v.trim().trim('/')) },
                                TextInputItem(
                                    id = "${id}_user",
                                    title = if (now.kind == StorageShares.S3) "Access key" else "User name",
                                    value = now.user,
                                    onChange = { c, v -> withContext(Dispatchers.IO) { StorageShares.update(c, now.id) { it.copy(user = v.trim()) } } },
                                ),
                                TextInputItem(
                                    id = "${id}_password",
                                    title = if (now.kind == StorageShares.S3) "Secret key" else "Password",
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
                                if (now.kind != StorageShares.S3) null else ToggleItem(
                                    id = "${id}_bucket_host",
                                    title = "Bucket subdomain",
                                    subtitle = BUCKET_IN_HOST_LINE,
                                    current = now.bucketInHost,
                                    onToggle = { c, on -> withContext(Dispatchers.IO) { StorageShares.update(c, now.id) { it.copy(bucketInHost = on) } } },
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

    /** Add S3 storage's fields until Add is pressed. The secret field is never shown back. */
    @Volatile private var s3Name = ""
    @Volatile private var s3Endpoint = ""
    @Volatile private var s3Bucket = ""
    @Volatile private var s3Region = ""
    @Volatile private var s3AccessKey = ""
    @Volatile private var s3SecretKey = ""
    @Volatile private var s3Folder = ""
    @Volatile private var s3BucketInHost = false
    @Volatile private var s3PlainHttp = false
    @Volatile private var s3Trusted = false

    /** Add S3 storage: the service's address, a bucket and the person's own access key. */
    private fun addS3Row(): CatalogItem = NestedScreenItem(
        id = "accounts_storage_add_s3",
        title = "Add S3 storage",
        subtitle = "AWS S3, Backblaze B2, Wasabi, Cloudflare R2, MinIO or Garage, with your own access key",
        inline = CatalogScreen(
            id = "accounts_storage_add_s3_screen",
            title = "Add S3 storage",
            groups = {
                listOf(
                    CatalogGroup(
                        id = "accounts_storage_add_s3_group",
                        title = null,
                        items = listOf(
                            TextInputItem(
                                id = "accounts_storage_add_s3_endpoint",
                                title = "Address",
                                subtitle = "The service's S3 address, such as https://s3.eu-central-003.backblazeb2.com",
                                value = s3Endpoint,
                                onChange = { _, v -> s3Endpoint = v.trim() },
                            ),
                            TextInputItem(id = "accounts_storage_add_s3_bucket", title = "Bucket", value = s3Bucket, onChange = { _, v -> s3Bucket = v.trim() }),
                            TextInputItem(
                                id = "accounts_storage_add_s3_region",
                                title = "Region",
                                subtitle = "Blank is us-east-1",
                                value = s3Region,
                                onChange = { _, v -> s3Region = v.trim() },
                            ),
                            TextInputItem(id = "accounts_storage_add_s3_access", title = "Access key", value = s3AccessKey, onChange = { _, v -> s3AccessKey = v.trim() }),
                            TextInputItem(
                                id = "accounts_storage_add_s3_secret",
                                title = "Secret key",
                                subtitle = "Kept locked on this device. A key limited to this bucket works best",
                                value = "",
                                secret = true,
                                onChange = { _, v -> s3SecretKey = v },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_s3_folder",
                                title = "Folder",
                                subtitle = "Inside the bucket; blank is its top",
                                value = s3Folder,
                                onChange = { _, v -> s3Folder = v.trim() },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_s3_name",
                                title = "Name",
                                subtitle = "How it is listed; the bucket's name when left blank",
                                value = s3Name,
                                onChange = { _, v -> s3Name = v },
                            ),
                            ToggleItem(
                                id = "accounts_storage_add_s3_host",
                                title = "Bucket subdomain",
                                subtitle = BUCKET_IN_HOST_LINE,
                                current = s3BucketInHost,
                                onToggle = { _, on -> s3BucketInHost = on },
                            ),
                            ToggleItem(
                                id = "accounts_storage_add_s3_plain",
                                title = "Allow unencrypted",
                                subtitle = "Only for a service on your own network: requests and files travel readable",
                                current = s3PlainHttp,
                                onToggle = { _, on -> s3PlainHttp = on },
                            ),
                            ChoiceItem(
                                id = "accounts_storage_add_s3_trusted",
                                title = "What droidtop writes",
                                subtitle = trustLine(s3Trusted),
                                options = trustOptions,
                                current = if (s3Trusted) TRUSTED else LOCKED,
                                onSelect = { _, v -> s3Trusted = v == TRUSTED },
                            ),
                            AsyncActionItem(
                                id = "accounts_storage_add_s3_run",
                                title = "Add and test",
                                run = { ctx, _ ->
                                    val added = withContext(Dispatchers.IO) {
                                        StorageShares.addS3(
                                            ctx, s3Name, s3Endpoint, s3Bucket, s3Region, s3AccessKey, s3SecretKey, s3Folder,
                                            s3BucketInHost, s3PlainHttp, s3Trusted,
                                        )
                                    }
                                    when (added) {
                                        is StorageShares.Added.Refused -> added.reason
                                        is StorageShares.Added.Share -> {
                                            s3Name = ""; s3Endpoint = ""; s3Bucket = ""; s3Region = ""; s3AccessKey = ""; s3SecretKey = ""
                                            s3Folder = ""; s3BucketInHost = false; s3PlainHttp = false; s3Trusted = false
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
