package dev.droidtop.pluginhost

import org.json.JSONObject

/** The app an [PluginKind.APP_INTERFACE] plugin is (docs/plugin-api.md 1.3): its package and the service droidtop binds. */
data class AppTarget(val packageName: String, val service: String) {
    fun toJson(): JSONObject = JSONObject().put("package", packageName).put("service", service)

    companion object {
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        /**
         * Whether [name] is a full Android package-style name: two or more segments of letters, digits and underscores,
         * each starting with a letter, in any case. An app interface plugin's id follows these rules, as its app's does.
         */
        fun isPackageName(name: String): Boolean = NAME.matches(name)

        /** The manifest's `app`, or null when it is missing or either name is not a full Java-style name. */
        fun fromJson(json: JSONObject?): AppTarget? {
            json ?: return null
            val packageName = json.optString("package").trim()
            val service = json.optString("service").trim()
            if (!NAME.matches(packageName) || !NAME.matches(service)) return null
            return AppTarget(packageName, service)
        }
    }
}

/**
 * Whether droidtop trusts an app interface plugin's app (docs/plugin-api.md 1.3, Droidtop/tracker#547). The owner:
 * "Applications interface plugins don't get a special signing certificate. They use the android signature. This also
 * means using root to bypass the signature check will STILL trip our trust store". So the decision rests on droidtop's
 * own verification of every APK of the app ([ApkCheck], made with apksig by [AppInterfaces]), checked against what
 * PackageManager reports: a device whose signature checks were patched out reports a signer the APK does not carry, and
 * that refuses the plugin. Pure.
 */
object AppInterfaceTrust {
    /** The action an app interface plugin's service answers. */
    const val ACTION = "dev.droidtop.plugin.APP_INTERFACE"

    /** The action of droidtop's door, which an approved plugin's app knocks on to be handed its broker. */
    const val DOOR_ACTION = "dev.droidtop.plugin.APP_INTERFACE_DOOR"

    /** The manifest's place in the APK. */
    const val ASSET_PATH = "assets/droidtop-plugin.json"

    const val UNCHECKABLE = "Its app's signature could not be checked, so droidtop won't use it"
    const val SIGNED_OLD_WAY = "Its app is signed the old way droidtop cannot check, so droidtop won't use it"
    const val MANY_SIGNERS = "Its app is signed by more than one key, which droidtop doesn't accept for a plugin"
    const val NOT_ITS_SIGNER = "Its app's files are not signed by the key Android reports for it, so droidtop won't use it"

    /**
     * The official app certificates, by package: the SHA-256 of each one's signing certificate (lowercase hex). An app
     * signed by one of these is Official. Enginehost's is its release certificate (the v2 signer of enginehost-latest.apk,
     * Droidtop/tracker#547).
     */
    val OFFICIAL: Map<String, Set<String>> = mapOf(
        "dev.enginehost" to setOf("56ad873eefebacfbbd5662b3b8bd0b094b6ac2d86f27933b257080d04951bf35"),
    )

    /** droidtop's own verification of one APK of the app: verified at all, with v2 or v3, and by which certificates. */
    data class ApkCheck(val verified: Boolean, val v2OrV3: Boolean, val signers: Set<String>)

    sealed interface Verdict {
        /** Signed by a pinned official certificate. */
        data class Official(val certSha256: String) : Verdict

        /** Signed by a certificate the person trusts ("Keys you trust"). */
        data class TrustedByYou(val certSha256: String) : Verdict

        /** Verified, but by a certificate nobody has trusted yet: offered with its fingerprint, to be trusted first. */
        data class NotTrusted(val certSha256: String) : Verdict

        /** Not to be used at all, with the sentence that says why. */
        data class Refused(val reason: String) : Verdict
    }

    /**
     * The decision for [packageName] from droidtop's checks of each of its APKs ([apks], base first),
     * [packageManagerSigners] (what Android reports as the app's current signers) and [trustedByYou] (the certificates
     * the person trusts for this package).
     */
    fun decide(packageName: String, apks: List<ApkCheck>, packageManagerSigners: Set<String>, trustedByYou: Set<String>): Verdict {
        if (apks.isEmpty() || apks.any { !it.verified }) return Verdict.Refused(UNCHECKABLE)
        if (apks.any { !it.v2OrV3 }) return Verdict.Refused(SIGNED_OLD_WAY)
        val signer = apks.first().signers.singleOrNull()?.lowercase() ?: return Verdict.Refused(MANY_SIGNERS)
        if (apks.any { check -> check.signers.map { it.lowercase() }.toSet() != setOf(signer) }) return Verdict.Refused(MANY_SIGNERS)
        if (packageManagerSigners.map { it.lowercase() }.toSet() != setOf(signer)) return Verdict.Refused(NOT_ITS_SIGNER)
        return when {
            OFFICIAL[packageName]?.any { it.equals(signer, ignoreCase = true) } == true -> Verdict.Official(signer)
            trustedByYou.any { it.equals(signer, ignoreCase = true) } -> Verdict.TrustedByYou(signer)
            else -> Verdict.NotTrusted(signer)
        }
    }

    /** The certificate a usable verdict names; null for a refusal. */
    fun certOf(verdict: Verdict): String? = when (verdict) {
        is Verdict.Official -> verdict.certSha256
        is Verdict.TrustedByYou -> verdict.certSha256
        is Verdict.NotTrusted -> verdict.certSha256
        is Verdict.Refused -> null
    }

    /**
     * Why [manifest], read from [packageName]'s APK, cannot be an app interface plugin, or null when it can: its kind,
     * that it names this app, the structural rules ([PluginManifest.structuralProblems]), and that it does not claim
     * droidtop's own origin.
     */
    fun manifestProblem(manifest: PluginManifest?, packageName: String, serviceName: String?): String? = when {
        manifest == null -> "its droidtop-plugin.json is not a plugin manifest droidtop can read"
        manifest.kind != PluginKind.APP_INTERFACE -> "its droidtop-plugin.json is not an app_interface manifest"
        manifest.app?.packageName != packageName -> "its manifest names ${manifest.app?.packageName ?: "no app"}, not $packageName"
        serviceName != null && manifest.app?.service != serviceName -> "its manifest names the service ${manifest.app?.service}, not $serviceName"
        PluginOriginKeys.isOfficial(manifest.origin) -> "an app cannot claim droidtop's own origin"
        else -> manifest.structuralProblems().firstOrNull()
    }

    /** The oldest contract an app's plugin may be (docs/plugin-api.md 1.3). */
    const val MIN_APP_CONTRACT = 2

    /**
     * What to tell the person when an app carries a plugin this droidtop cannot speak (Droidtop/tracker#854), or null
     * when it can: Enginehost was missing from Plugins altogether. [manifestBytes] is its `droidtop-plugin.json` as
     * read, [manifest] what droidtop made of it (null when it could not read it: a kind or shape it does not know).
     * The contract is read from the bytes themselves, so a plugin made for a newer droidtop says so and is not told to
     * update. Pure.
     */
    fun mismatch(label: String, manifestBytes: ByteArray?, manifest: PluginManifest?): String? {
        val contract = manifestBytes?.let { bytes ->
            runCatching { org.json.JSONObject(String(bytes, Charsets.UTF_8)).optInt("contractVersion", -1) }.getOrNull()
        }?.takeIf { it >= 0 }
        val speaks = "this droidtop speaks $MIN_APP_CONTRACT to $PLUGIN_MANIFEST_CONTRACT_VERSION"
        return when {
            contract != null && contract > PLUGIN_MANIFEST_CONTRACT_VERSION ->
                "$label is made for a newer droidtop (its plugin is contract $contract; $speaks). Update droidtop to use it"
            contract != null && contract < MIN_APP_CONTRACT ->
                "$label needs an update to work with this droidtop (its plugin is contract $contract; $speaks)"
            manifestBytes != null && manifest == null ->
                "$label needs an update to work with this droidtop (droidtop can't read its plugin's manifest)"
            else -> null
        }
    }

    /**
     * Whether a record already installed for this plugin carries its approval to [cert]: the same certificate it was
     * approved with. A changed certificate is a new trust decision, as a changed bundle key is (docs/SPEC.md 12a).
     */
    fun carriesApproval(existing: PluginRecord?, origin: String, cert: String): Boolean =
        existing != null && existing.trust == PluginTrustState.APPROVED && existing.manifest.kind == PluginKind.APP_INTERFACE &&
            existing.manifest.origin == origin && existing.approvedKeySha256.equals(cert, ignoreCase = true)
}
