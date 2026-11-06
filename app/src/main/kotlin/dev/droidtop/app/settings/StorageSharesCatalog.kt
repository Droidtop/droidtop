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

    private const val PORT_LINE = "Blank for the usual one"
    private const val ENCRYPTION_LINE = "Connect only with SMB 3 encryption. Turn it on when the server supports it"

    private fun StorageShare.isServerKind() = kind == StorageShares.SFTP || kind == StorageShares.SMB

    /** One field of a share of one of [kinds], or null for a share of another kind. */
    private fun kindField(
        share: StorageShare,
        kinds: Set<String>,
        id: String,
        title: String,
        value: String,
        subtitle: String? = null,
        change: (StorageShare, String) -> StorageShare,
    ): CatalogItem? = if (share.kind !in kinds) null else TextInputItem(
        id = id,
        title = title,
        subtitle = subtitle,
        value = value,
        onChange = { c, v -> withContext(Dispatchers.IO) { StorageShares.update(c, share.id) { change(it, v) } } },
    )

    /**
     * An SFTP share's server key: the one the person accepted, and the one its last Test showed when that differed,
     * with Accept. A key is never accepted for the person: they compare the fingerprint with the server's own
     * (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on it) first.
     */
    private fun serverKeyGroup(id: String, now: StorageShare): CatalogGroup? {
        if (now.kind != StorageShares.SFTP) return null
        val changed = now.hostKey.isNotEmpty() && now.offeredHostKey.isNotEmpty()
        return CatalogGroup(
            id = "${id}_server_key",
            title = "Server's key",
            items = listOfNotNull(
                dev.droidtop.library.settings.TextBlockItem(
                    id = "${id}_server_key_now",
                    text = if (now.hostKey.isEmpty()) {
                        "Not accepted yet. Press Test to see the server's key"
                    } else {
                        "Accepted: ${now.hostKey}"
                    },
                ),
                if (now.offeredHostKey.isEmpty()) null else AsyncActionItem(
                    id = "${id}_server_key_accept",
                    title = if (changed) "Accept the new key" else "Accept this key",
                    subtitle = now.offeredHostKey,
                    statusMark = StatusMark.ATTENTION,
                    confirmTitle = if (changed) {
                        "The server's key changed. Someone may be in between. Accept it only if you changed the server"
                    } else {
                        "Accept this key only if it matches the server's own"
                    },
                    run = { c, _ ->
                        withContext(Dispatchers.IO) {
                            StorageShares.acceptHostKey(c, now.id)
                            StorageShares.get(c, now.id)?.let { StorageShares.test(c, it) } ?: "Accepted"
                        }
                    },
                ),
            ),
        )
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
        items = StorageShares.all(context).map(::shareRow) + addRow() + addS3Row() + addSftpRow() + addSmbRow(),
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
                    ) + listOfNotNull(serverKeyGroup(id, now)) + listOf(
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
                                    title = if (now.isServerKind()) "Server" else "Address",
                                    subtitle = when (now.kind) {
                                        StorageShares.S3 -> "The service's S3 address"
                                        StorageShares.SFTP, StorageShares.SMB -> "The server's name or address"
                                        else -> "The share's WebDAV address"
                                    },
                                    value = now.url,
                                    onChange = { c, v ->
                                        withContext(Dispatchers.IO) {
                                            val problem = if (now.isServerKind()) StorageShares.hostProblem(v) else StorageShares.addressProblem(v, now.allowPlainHttp)
                                            if (problem == null) StorageShares.update(c, now.id) { it.copy(url = v.trim()) }
                                        }
                                    },
                                ),
                                kindField(now, setOf(StorageShares.SMB), "${id}_share_name", "Share", now.shareName, "The share's name, the part after the server") { sh, v ->
                                    v.trim().trim('/', '\\').takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }?.let { sh.copy(shareName = it) } ?: sh
                                },
                                kindField(now, setOf(StorageShares.SFTP, StorageShares.SMB), "${id}_port", "Port", if (now.port == 0) "" else now.port.toString(), PORT_LINE) { sh, v ->
                                    val port = v.trim().ifEmpty { "0" }.toIntOrNull()
                                    if (port != null && port in 0..65535) sh.copy(port = port) else sh
                                },
                                kindField(now, setOf(StorageShares.SFTP, StorageShares.SMB), "${id}_folder_srv", "Folder", now.folder, "On the share; blank is its top") { sh, v ->
                                    sh.copy(folder = v.trim().trim('/', '\\'))
                                },
                                kindField(now, setOf(StorageShares.SMB), "${id}_domain", "Domain", now.domain, "Blank for most home shares") { sh, v -> sh.copy(domain = v.trim()) },
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
                                if (now.isServerKind()) null else ToggleItem(
                                    id = "${id}_plain",
                                    title = "Allow unencrypted",
                                    subtitle = "Only for a share on your own network: the password and files travel readable",
                                    current = now.allowPlainHttp,
                                    onToggle = { c, on -> withContext(Dispatchers.IO) { StorageShares.update(c, now.id) { it.copy(allowPlainHttp = on) } } },
                                ),
                                if (now.kind != StorageShares.SMB) null else ToggleItem(
                                    id = "${id}_encrypt",
                                    title = "Require encryption",
                                    subtitle = ENCRYPTION_LINE,
                                    current = now.requireEncryption,
                                    onToggle = { c, on -> withContext(Dispatchers.IO) { StorageShares.update(c, now.id) { it.copy(requireEncryption = on) } } },
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

    /** Add SFTP share's and Add SMB share's fields until Add is pressed. Passwords are never shown back. */
    @Volatile private var sftpName = ""
    @Volatile private var sftpHost = ""
    @Volatile private var sftpPort = ""
    @Volatile private var sftpUser = ""
    @Volatile private var sftpPassword = ""
    @Volatile private var sftpFolder = ""
    @Volatile private var sftpTrusted = false
    @Volatile private var smbName = ""
    @Volatile private var smbServer = ""
    @Volatile private var smbShare = ""
    @Volatile private var smbUser = ""
    @Volatile private var smbPassword = ""
    @Volatile private var smbDomain = ""
    @Volatile private var smbFolder = ""
    @Volatile private var smbEncrypt = false
    @Volatile private var smbTrusted = false

    /** The port typed in an add screen: blank is the kind's usual one (0), or null when it isn't a port. */
    private fun portOf(text: String): Int? = text.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..65535 }

    /**
     * Add SFTP share: any SSH server, with the person's own user name and password. Test then shows the server's key
     * for them to accept: it is never trusted silently.
     */
    private fun addSftpRow(): CatalogItem = NestedScreenItem(
        id = "accounts_storage_add_sftp",
        title = "Add SFTP share",
        subtitle = "Any computer or NAS you reach with SSH, with your own sign-in",
        inline = CatalogScreen(
            id = "accounts_storage_add_sftp_screen",
            title = "Add SFTP share",
            groups = {
                listOf(
                    CatalogGroup(
                        id = "accounts_storage_add_sftp_group",
                        title = null,
                        items = listOf(
                            TextInputItem(
                                id = "accounts_storage_add_sftp_host",
                                title = "Server",
                                subtitle = "Its name or address, such as nas.local or 192.168.1.20",
                                value = sftpHost,
                                onChange = { _, v -> sftpHost = v.trim() },
                            ),
                            TextInputItem(id = "accounts_storage_add_sftp_port", title = "Port", subtitle = PORT_LINE, value = sftpPort, onChange = { _, v -> sftpPort = v.trim() }),
                            TextInputItem(id = "accounts_storage_add_sftp_user", title = "User name", value = sftpUser, onChange = { _, v -> sftpUser = v.trim() }),
                            TextInputItem(
                                id = "accounts_storage_add_sftp_password",
                                title = "Password",
                                subtitle = "Kept locked on this device",
                                value = "",
                                secret = true,
                                onChange = { _, v -> sftpPassword = v },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_sftp_folder",
                                title = "Folder",
                                subtitle = "On the server; blank is your home folder",
                                value = sftpFolder,
                                onChange = { _, v -> sftpFolder = v.trim() },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_sftp_name",
                                title = "Name",
                                subtitle = "How it is listed; the server's name when left blank",
                                value = sftpName,
                                onChange = { _, v -> sftpName = v },
                            ),
                            ChoiceItem(
                                id = "accounts_storage_add_sftp_trusted",
                                title = "What droidtop writes",
                                subtitle = trustLine(sftpTrusted),
                                options = trustOptions,
                                current = if (sftpTrusted) TRUSTED else LOCKED,
                                onSelect = { _, v -> sftpTrusted = v == TRUSTED },
                            ),
                            AsyncActionItem(
                                id = "accounts_storage_add_sftp_run",
                                title = "Add and test",
                                run = { ctx, _ ->
                                    val port = portOf(sftpPort)
                                    if (port == null) {
                                        "A port is a number up to 65535"
                                    } else {
                                        val added = withContext(Dispatchers.IO) {
                                            StorageShares.addSftp(ctx, sftpName, sftpHost, port, sftpUser, sftpPassword, sftpFolder, sftpTrusted)
                                        }
                                        when (added) {
                                            is StorageShares.Added.Refused -> added.reason
                                            is StorageShares.Added.Share -> {
                                                sftpName = ""; sftpHost = ""; sftpPort = ""; sftpUser = ""; sftpPassword = ""; sftpFolder = ""; sftpTrusted = false
                                                "Added. " + withContext(Dispatchers.IO) { StorageShares.test(ctx, added.share) } +
                                                    ". Open the share to accept its key"
                                            }
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

    /** Add SMB share: a Windows share, Samba or a NAS, with the person's own sign-in. */
    private fun addSmbRow(): CatalogItem = NestedScreenItem(
        id = "accounts_storage_add_smb",
        title = "Add SMB share",
        subtitle = "A Windows shared folder, Samba or a NAS, with your own sign-in",
        inline = CatalogScreen(
            id = "accounts_storage_add_smb_screen",
            title = "Add SMB share",
            groups = {
                listOf(
                    CatalogGroup(
                        id = "accounts_storage_add_smb_group",
                        title = null,
                        items = listOf(
                            TextInputItem(
                                id = "accounts_storage_add_smb_server",
                                title = "Server",
                                subtitle = "Its name or address, such as nas.local or 192.168.1.20",
                                value = smbServer,
                                onChange = { _, v -> smbServer = v.trim() },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_smb_share",
                                title = "Share",
                                subtitle = "The share's name, the part after the server, such as home",
                                value = smbShare,
                                onChange = { _, v -> smbShare = v.trim() },
                            ),
                            TextInputItem(id = "accounts_storage_add_smb_user", title = "User name", value = smbUser, onChange = { _, v -> smbUser = v.trim() }),
                            TextInputItem(
                                id = "accounts_storage_add_smb_password",
                                title = "Password",
                                subtitle = "Kept locked on this device",
                                value = "",
                                secret = true,
                                onChange = { _, v -> smbPassword = v },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_smb_domain",
                                title = "Domain",
                                subtitle = "Blank for most home shares",
                                value = smbDomain,
                                onChange = { _, v -> smbDomain = v.trim() },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_smb_folder",
                                title = "Folder",
                                subtitle = "On the share; blank is its top",
                                value = smbFolder,
                                onChange = { _, v -> smbFolder = v.trim() },
                            ),
                            TextInputItem(
                                id = "accounts_storage_add_smb_name",
                                title = "Name",
                                subtitle = "How it is listed; the share and server when left blank",
                                value = smbName,
                                onChange = { _, v -> smbName = v },
                            ),
                            ToggleItem(
                                id = "accounts_storage_add_smb_encrypt",
                                title = "Require encryption",
                                subtitle = ENCRYPTION_LINE,
                                current = smbEncrypt,
                                onToggle = { _, on -> smbEncrypt = on },
                            ),
                            ChoiceItem(
                                id = "accounts_storage_add_smb_trusted",
                                title = "What droidtop writes",
                                subtitle = trustLine(smbTrusted),
                                options = trustOptions,
                                current = if (smbTrusted) TRUSTED else LOCKED,
                                onSelect = { _, v -> smbTrusted = v == TRUSTED },
                            ),
                            AsyncActionItem(
                                id = "accounts_storage_add_smb_run",
                                title = "Add and test",
                                run = { ctx, _ ->
                                    val added = withContext(Dispatchers.IO) {
                                        StorageShares.addSmb(ctx, smbName, smbServer, smbShare, smbUser, smbPassword, smbDomain, smbFolder, smbEncrypt, smbTrusted)
                                    }
                                    when (added) {
                                        is StorageShares.Added.Refused -> added.reason
                                        is StorageShares.Added.Share -> {
                                            smbName = ""; smbServer = ""; smbShare = ""; smbUser = ""; smbPassword = ""
                                            smbDomain = ""; smbFolder = ""; smbEncrypt = false; smbTrusted = false
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
