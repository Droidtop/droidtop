package dev.droidtop.pluginhost

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import com.android.apksig.ApkVerifier
import com.android.apksig.util.DataSources
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * Installed apps that are also plugins (docs/plugin-api.md 1.3, kind [PluginKind.APP_INTERFACE], Droidtop/tracker#547):
 * finding them, checking each app's signature in process, and installing one as a plugin record beside the bundle
 * plugins, so it goes through the same approval, grants, Permissions screen and Activity as any plugin.
 *
 * droidtop opens each of the app's APKs (base and splits) once, verifies its v2/v3 signature block with apksig from that
 * open file, and reads the manifest asset from the same file, so what was verified is what was read. The verdict is
 * [AppInterfaceTrust.decide]'s. A check is kept until the APK files or the package's update time change.
 */
object AppInterfaces {
    /** One app that carries a plugin: what droidtop found, and what it decided. */
    data class Found(
        val packageName: String,
        val appLabel: String,
        val serviceName: String?,
        val manifest: PluginManifest?,
        val manifestBytes: ByteArray?,
        val verdict: AppInterfaceTrust.Verdict,
        /** Why the manifest cannot be used, besides the signature (it is not an app_interface manifest for this app). */
        val problem: String?,
    ) {
        val usable: Boolean get() = problem == null && verdict !is AppInterfaceTrust.Verdict.Refused

        override fun equals(other: Any?): Boolean = other is Found && other.packageName == packageName && other.verdict == verdict &&
            other.problem == problem && other.manifestBytes.contentEquals(manifestBytes)

        override fun hashCode(): Int = packageName.hashCode()
    }

    private class Cached(val key: String, val found: Found)

    /**
     * The application context, for the checks a caller holding no Context asks for ([PluginBundleInstaller.verifyInstalled]
     * of an app interface record). Set by every [PluginStore] call, so it is there before any record is read.
     */
    @Volatile private var app: Context? = null

    fun attach(context: Context) {
        if (app == null) app = context.applicationContext ?: context
    }

    /** [verifyRecord] with the attached context; a refusal when droidtop has not been given one yet. */
    fun verifyAttached(record: PluginRecord): PluginInstallError? =
        app?.let { verifyRecord(it, record) } ?: PluginInstallError(AppInterfaceTrust.UNCHECKABLE)

    private val cache = HashMap<String, Cached>()

    /** Forgets [packageName]'s kept check, so the next [inspect] decides again: after the person trusts or stops trusting its key. */
    fun recheck(packageName: String) {
        synchronized(cache) { cache.remove(packageName) }
    }

    /** Whether [record] is an app plugin signed by its app's pinned official certificate. */
    fun isOfficial(record: PluginRecord): Boolean {
        val pkg = record.manifest.app?.packageName ?: return false
        return AppInterfaceTrust.OFFICIAL[pkg]?.any { it.equals(record.approvedKeySha256, ignoreCase = true) } == true
    }

    /**
     * Every installed app whose service answers [AppInterfaceTrust.ACTION], checked. Reads every APK of each: call it off
     * the main thread.
     */
    fun discover(context: Context): List<Found> {
        val pm = context.packageManager
        val services = runCatching { pm.queryIntentServices(Intent(AppInterfaceTrust.ACTION), 0) }.getOrDefault(emptyList())
        return services.mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { inspect(context, it.packageName, it.name) }
    }

    /** One app, checked: its signature, its manifest asset and the verdict. [serviceName] is the service it exports, when known. */
    fun inspect(context: Context, packageName: String, serviceName: String? = null): Found {
        val info = packageInfo(context, packageName)
            ?: return Found(packageName, packageName, serviceName, null, null, AppInterfaceTrust.Verdict.Refused(AppInterfaceTrust.UNCHECKABLE), "it is not installed")
        val app = info.applicationInfo
        val label = runCatching { app?.let { context.packageManager.getApplicationLabel(it).toString() } }.getOrNull() ?: packageName
        val apks = listOfNotNull(app?.sourceDir) + app?.splitSourceDirs.orEmpty().toList()
        val key = cacheKey(info, apks)
        synchronized(cache) { cache[packageName]?.takeIf { it.key == key }?.let { return it.found.copy(serviceName = serviceName ?: it.found.serviceName) } }
        var manifestBytes: ByteArray? = null
        val checks = apks.mapIndexed { index, path ->
            checkApk(File(path), readAsset = index == 0) { bytes -> manifestBytes = bytes }
        }
        val verdict = AppInterfaceTrust.decide(
            packageName,
            checks,
            signersReported(info),
            UserOriginKeys.appCerts(UserOriginKeys.storeFile(context), packageName),
        )
        val manifest = manifestBytes?.let { bytes -> runCatching { PluginManifest.fromJson(JSONObject(String(bytes, Charsets.UTF_8))) }.getOrNull() }
        val problem = if (manifestBytes == null) "its APK carries no ${AppInterfaceTrust.ASSET_PATH}" else AppInterfaceTrust.manifestProblem(manifest, packageName, serviceName)
        val found = Found(packageName, label, serviceName ?: manifest?.app?.service, manifest, manifestBytes, verdict, problem)
        synchronized(cache) { cache[packageName] = Cached(key, found) }
        return found
    }

    /**
     * Installs [found] as a plugin record, or updates the one there: PENDING until the person approves it, and still
     * approved after an update signed by the same certificate (its new permissions and points wait, as any update's do).
     * Returns the record, or the sentence that says why it cannot be installed.
     */
    fun install(context: Context, found: Found): Result<PluginRecord> {
        if (!found.usable) return Result.failure(IllegalStateException(found.problem ?: (found.verdict as? AppInterfaceTrust.Verdict.Refused)?.reason ?: AppInterfaceTrust.UNCHECKABLE))
        val manifest = found.manifest!!
        val bytes = found.manifestBytes!!
        val cert = AppInterfaceTrust.certOf(found.verdict)!!
        val root = PluginStore.root(context)
        val existing = PluginBundleInstaller.readRecord(root, manifest.id)
        if (existing != null && existing.manifest.kind != PluginKind.APP_INTERFACE) {
            return Result.failure(IllegalStateException("a plugin with the id ${manifest.id} is already installed from a bundle"))
        }
        if (existing != null && existing.manifest.app?.packageName != found.packageName) {
            return Result.failure(IllegalStateException("${manifest.id} is already the plugin of ${existing.manifest.app?.packageName}"))
        }
        val digest = sha256(bytes)
        val dir = PluginBundleInstaller.versionDir(root, manifest.id, digest)
        if (!File(dir, "manifest.json").isFile) {
            dir.mkdirs()
            File(dir, "manifest.json").writeBytes(bytes)
        }
        val sameBytes = existing?.archiveDigest == digest && existing.approvedKeySha256.equals(cert, ignoreCase = true)
        val carried = sameBytes || AppInterfaceTrust.carriesApproval(existing, manifest.origin, cert)
        val record = PluginRecord(
            manifest = manifest,
            archiveDigest = digest,
            approvedKeySha256 = cert,
            trust = when {
                sameBytes -> existing!!.trust
                carried -> PluginTrustState.APPROVED
                else -> PluginTrustState.PENDING
            },
            enabled = carried && existing!!.enabled,
            rootApproved = false,
            disabledReason = null,
            replacedKeySha256 = if (existing != null && !carried && existing.trust == PluginTrustState.APPROVED) existing.approvedKeySha256 else existing?.replacedKeySha256,
            replacedOrigin = if (existing != null && !carried && existing.trust == PluginTrustState.APPROVED) existing.manifest.origin else existing?.replacedOrigin,
        )
        PluginBundleInstaller.writeRecord(root, record)
        if (existing != null && carried && !sameBytes) PluginGrants.forPluginsRoot(root).applyUpdate(existing, record)
        return Result.success(record)
    }

    /**
     * Why [record]'s app cannot run as it was approved, or null when it can: the app is still installed, its signature
     * still checks out by the certificate approved, and its manifest asset is still the bytes approved. Checked before
     * every run, so a re-signed or changed app is stopped and asks again.
     */
    fun verifyRecord(context: Context, record: PluginRecord): PluginInstallError? {
        val target = record.manifest.app ?: return PluginInstallError("it names no app")
        val found = inspect(context, target.packageName, target.service)
        (found.verdict as? AppInterfaceTrust.Verdict.Refused)?.let { return PluginInstallError(it.reason) }
        if (found.verdict is AppInterfaceTrust.Verdict.NotTrusted) return PluginInstallError("droidtop no longer trusts the key ${record.manifest.label}'s app is signed with")
        val cert = AppInterfaceTrust.certOf(found.verdict)
        if (!cert.equals(record.approvedKeySha256, ignoreCase = true)) return PluginInstallError("its app is now signed by a different key; approve it again")
        val bytes = found.manifestBytes ?: return PluginInstallError(PluginRecord.FILES_MISSING)
        if (sha256(bytes) != record.archiveDigest) return PluginInstallError("its app was updated; approve the new version")
        return null
    }

    private fun packageInfo(context: Context, packageName: String): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        context.packageManager.getPackageInfo(packageName, flags)
    }.getOrNull()

    /** What Android reports as the app's current signers: the certificates its contents are signed with now. */
    @Suppress("DEPRECATION")
    private fun signersReported(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().filterNotNull().map { sha256(it.toByteArray()) }.toSet()
    }

    /** Size, modification time and the package's update time of every APK: a re-check whenever any of them moves. */
    private fun cacheKey(info: PackageInfo, apks: List<String>): String =
        info.lastUpdateTime.toString() + "|" + apks.joinToString("|") { path -> File(path).let { "$path:${it.length()}:${it.lastModified()}" } }

    /**
     * One APK, opened once: its signature verified from the open file, and, for the base APK, the manifest asset read
     * from the same file through /proc/self/fd, so a file swapped in between cannot pass for the one checked.
     */
    private fun checkApk(apk: File, readAsset: Boolean, onAsset: (ByteArray?) -> Unit): AppInterfaceTrust.ApkCheck = runCatching {
        ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val result = FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                ApkVerifier.Builder(DataSources.asDataSource(channel)).build().verify()
            }
            if (readAsset) {
                onAsset(
                    runCatching {
                        ZipFile("/proc/self/fd/${pfd.fd}").use { zip ->
                            val entry = zip.getEntry(AppInterfaceTrust.ASSET_PATH) ?: return@use null
                            if (entry.size > MAX_MANIFEST_BYTES) return@use null
                            zip.getInputStream(entry).use { it.readBytes() }
                        }
                    }.getOrNull(),
                )
            }
            AppInterfaceTrust.ApkCheck(
                verified = result.isVerified,
                v2OrV3 = result.isVerifiedUsingV2Scheme || result.isVerifiedUsingV3Scheme,
                signers = result.signerCertificates.map { sha256(it.encoded) }.toSet(),
            )
        }
    }.getOrElse { AppInterfaceTrust.ApkCheck(verified = false, v2OrV3 = false, signers = emptySet()) }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The largest manifest asset read: a manifest is a few kilobytes. */
    private const val MAX_MANIFEST_BYTES = 256L * 1024
}
