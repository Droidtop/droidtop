package dev.droidtop.stores.steam

import android.content.Context
import androidx.room.withTransaction
import dev.droidtop.library.ScanLog
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import java.util.EnumSet
import kotlinx.coroutines.future.await
import timber.log.Timber

/**
 * Reads what the account owns into [SteamDatabase] (docs/SPEC.md 7g,
 * "Stores"), the way GameNative's SteamService did after each log-on
 * (onLicenseList, then its package and app product-info queues), run once
 * per sync instead of as a standing watcher: the licences, then each
 * licence's package (which apps and depots it grants), then the product info
 * of every app those name that changed since the last read.
 */
internal object SteamLibrarySync {
    private const val TAG = "SteamLibrarySync"

    /** Steam answers at most this many product-info requests at once (GameNative's MAX_PICS_BUFFER). */
    private const val PICS_CHUNK = 256

    /**
     * The only app type the library lists: games (docs/SPEC.md 7g, "Stores").
     * DLC belongs to its base game ("DLC and versions"); tools, soundtracks,
     * demos, betas and the rest are not library entries.
     */
    val PLAYABLE_TYPES = listOf(AppType.game.code)

    /**
     * Whether a stored app may be a library entry. [SteamAppDao.owned] applies
     * the same rule in SQL; this is for rows read another way (installed
     * ones). An app whose product info names a base game (`dlcforappid`) is
     * DLC whatever its type says, and a row never read from product info
     * (type invalid) is kept only when it is not DLC, as a game installed
     * before its product info came.
     */
    fun isLibraryGame(app: SteamApp, keepInstalledKinds: Boolean = false): Boolean {
        if (app.dlcForAppId != SteamIds.INVALID_APP_ID) return false
        if (app.type.code in PLAYABLE_TYPES) return true
        if (app.type == AppType.invalid) return true
        // A demo or application the person installed stays listed with its files.
        return keepInstalledKinds && (app.type == AppType.demo || app.type == AppType.application)
    }

    /** Reads the account's library; logged on already. Returns how many games it holds. */
    suspend fun run(context: Context, apps: SteamApps, licences: List<License>): Int {
        val db = SteamDatabase.get(context)
        val accountId = SteamSession.accountId
        storeLicences(db, licences, accountId)
        val billing = HashMap<Int, Int>()
        val appIds = readPackages(db, apps, billing)
        readApps(db, apps, appIds)
        val stored = db.licenses().all()
        val ownership = SteamOwnership.of(stored, accountId)
        val kinds = db.apps().kinds()
        val dlcByBase = kinds.filter { it.base != SteamIds.INVALID_APP_ID }.groupBy({ it.base }, { it.id })
        val ownGames = db.apps().owned(PLAYABLE_TYPES).count { ownership.statusOf(it.id, dlcByBase[it.id].orEmpty()) == SteamOwnership.Status.OWN }
        val line = summary(
            licences = licences,
            stored = stored,
            accountId = accountId,
            billing = billing,
            kinds = kinds,
            ownership = ownership,
        )
        ScanLog.write(line)
        Timber.tag(TAG).i(line)
        return ownGames
    }

    /**
     * One line per sync for scan.log (Droidtop/tracker#360, #377), to set
     * against the counts Steam's own profile shows: the licences by payment
     * method and by flag, how many are another account's (Steam Families),
     * whether the free sub is held and how many apps it names, the packages
     * by billing type (Steam's EBillingType number), then the apps by
     * product-info type that the account's own licences grant, that only a
     * family member's grant, and that only the free sub or an ended licence
     * names, and the library games of each kind ([SteamOwnership]).
     */
    fun summary(
        licences: List<License>,
        stored: List<SteamLicense>,
        accountId: Int?,
        billing: Map<Int, Int>,
        kinds: List<SteamAppKind>,
        ownership: SteamOwnership,
    ): String {
        fun Map<String, Int>.words() = entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" }
        fun typeName(code: Int) = AppType.fromCode(code).let { if (it == AppType.invalid) "no product info" else it.name }
        fun byType(rows: List<SteamAppKind>) = rows.groupingBy { typeName(it.type) }.eachCount().words()
        val payment = licences.groupingBy { it.paymentMethod.name }.eachCount().words()
        val flags = stored.flatMap { licence -> licence.licenseFlags.map { it.name } }.groupingBy { it }.eachCount().words()
        val borrowed = if (accountId == null) 0 else stored.count { !SteamOwnership.isOwn(it, accountId) }
        val freeSub = stored.firstOrNull { it.packageId == SteamOwnership.FREE_SUB }
        val named = stored.flatMapTo(HashSet()) { it.appIds }
        val dlcByBase = kinds.filter { it.base != SteamIds.INVALID_APP_ID }.groupBy({ it.base }, { it.id })
        val ownApps = kinds.filter { it.id in ownership.own }
        val familyApps = kinds.filter { it.id in ownership.family }
        val notGranted = kinds.filter { it.id in named && it.id !in ownership.own && it.id !in ownership.family }
        val games = kinds.filter { it.type == AppType.game.code && it.base == SteamIds.INVALID_APP_ID }
            .groupingBy { ownership.statusOf(it.id, dlcByBase[it.id].orEmpty()) }.eachCount()
        return "steam sync: ${licences.size} licences (payment: $payment; flags: $flags; another account's $borrowed; " +
            "free sub ${if (freeSub == null) "not held" else "held, ${freeSub.appIds.size} apps"}); " +
            "packages by billing type: ${billing.mapKeys { it.key.toString() }.words()}; " +
            "own apps by type: ${byType(ownApps)}; family apps by type: ${byType(familyApps)}; " +
            "only in the free sub or ended licences: ${byType(notGranted)}; " +
            "library games: own ${games[SteamOwnership.Status.OWN] ?: 0}, family ${games[SteamOwnership.Status.FAMILY] ?: 0}"
    }

    /** The licences as GameNative kept them: each raw, for the depot downloader, and one row per package. */
    private suspend fun storeLicences(db: SteamDatabase, licences: List<License>, accountId: Int?) {
        db.withTransaction {
            db.cachedLicenses().deleteAll()
            licences.chunked(500).forEach { chunk ->
                db.cachedLicenses().insertAll(chunk.map { CachedLicense(licenseJson = SteamLicenses.toJson(it)) })
            }
            val rows = licences.groupBy { it.packageID }.map { (packageId, samePackage) ->
                val preferred = samePackage.firstOrNull { it.ownerAccountID == accountId } ?: samePackage.first()
                val known = db.licenses().find(packageId)
                SteamLicense(
                    packageId = packageId,
                    lastChangeNumber = preferred.lastChangeNumber,
                    licenseFlags = samePackage.map { it.licenseFlags }
                        .reduceOrNull { a, b -> EnumSet.copyOf(a).apply { addAll(b) } }
                        ?: EnumSet.noneOf(ELicenseFlags::class.java),
                    licenseType = preferred.licenseType,
                    accessToken = preferred.accessToken,
                    ownerAccountId = samePackage.map { it.ownerAccountID },
                    // What the package grants is read below; until then the last read stands.
                    appIds = known?.appIds.orEmpty(),
                    depotIds = known?.depotIds.orEmpty(),
                )
            }
            rows.chunked(500).forEach { db.licenses().insertAll(it) }
            db.licenses().deleteAllBut(licences.map { it.packageID }.distinct().ifEmpty { listOf(-1) })
        }
    }

    /**
     * Each licence's package: the apps and depots it grants, and a row for
     * every app it names. When one app is in several packages (a purchase and
     * a free weekend, say), the package the account itself owns and that has
     * not expired wins (GameNative's package ranking). Returns every app id
     * the packages name.
     */
    private suspend fun readPackages(db: SteamDatabase, apps: SteamApps, billing: MutableMap<Int, Int>): Set<Int> {
        val accountId = SteamSession.accountId
        val licences = db.licenses().all()
        val byPackage = licences.associateBy { it.packageId }
        fun rank(packageId: Int): Int {
            val licence = byPackage[packageId] ?: return 0
            if (accountId == null || accountId !in licence.ownerAccountId) return 0
            return if (ELicenseFlags.Expired in licence.licenseFlags) 1 else 2
        }
        val named = LinkedHashSet<Int>()
        for (chunk in licences.map { PICSRequest(it.packageId, it.accessToken) }.chunked(PICS_CHUNK)) {
            val answer = apps.picsGetProductInfo(apps = emptyList(), packages = chunk).await()
            for (result in answer.results) {
                db.withTransaction {
                    for (pkg in result.packages.values.sortedBy { rank(it.id) }) {
                        val appIds = pkg.keyValues["appids"].children.map { it.asInteger() }
                        billing.merge(pkg.keyValues["billingtype"].asInteger(-1), 1, Int::plus)
                        val depotIds = pkg.keyValues["depotids"].children.map { it.asInteger() }
                        db.licenses().setContents(pkg.id, appIds, depotIds)
                        for (appId in appIds) {
                            val existing = db.apps().find(appId)
                            when {
                                existing == null -> db.apps().insert(SteamApp(id = appId, packageId = pkg.id))
                                existing.packageId == pkg.id -> Unit
                                existing.packageId != SteamIds.INVALID_PKG_ID && rank(existing.packageId) > rank(pkg.id) -> Unit
                                else -> db.apps().update(existing.copy(packageId = pkg.id))
                            }
                        }
                        named += appIds
                    }
                }
            }
        }
        return named
    }

    /** Product info for every app in [appIds] whose change number moved since it was last read. */
    private suspend fun readApps(db: SteamDatabase, apps: SteamApps, appIds: Set<Int>) {
        if (appIds.isEmpty()) return
        val tokens = HashMap<Int, Long>()
        for (chunk in appIds.toList().chunked(PICS_CHUNK)) {
            runCatching { apps.picsGetAccessTokens(appIds = chunk, packageIds = emptyList()).await().appTokens }
                .onSuccess { tokens.putAll(it) }
                .onFailure { Timber.tag(TAG).w(it, "Steam gave no access tokens for ${chunk.size} apps") }
        }
        for (chunk in appIds.map { PICSRequest(id = it, accessToken = tokens[it] ?: 0L) }.chunked(PICS_CHUNK)) {
            val answer = apps.picsGetProductInfo(apps = chunk, packages = emptyList()).await()
            for (result in answer.results) {
                val changed = result.apps.values.mapNotNull { info ->
                    val known = db.apps().find(info.id)
                    if (known != null && known.receivedPICS && known.lastChangeNumber == info.changeNumber) return@mapNotNull null
                    SteamPics.appFrom(info.keyValues).copy(
                        id = info.id,
                        packageId = known?.packageId ?: SteamIds.INVALID_PKG_ID,
                        receivedPICS = true,
                        lastChangeNumber = info.changeNumber,
                    )
                }
                if (changed.isNotEmpty()) db.withTransaction { db.apps().insertAll(changed) }
            }
        }
    }

    /**
     * Fresh product info for one app (an install, an update check, a save
     * sync), stored over the old row; the stored row when Steam does not
     * answer. [onUfs] gets the app's Auto-Cloud save locations from the same answer.
     */
    suspend fun refreshApp(db: SteamDatabase, apps: SteamApps, appId: Int, onUfs: (SteamUfs) -> Unit = {}): SteamApp? {
        val known = db.apps().find(appId)
        val token = runCatching { apps.picsGetAccessTokens(appIds = listOf(appId), packageIds = emptyList()).await().appTokens[appId] }
            .getOrNull() ?: 0L
        val info = runCatching {
            apps.picsGetProductInfo(apps = listOf(PICSRequest(id = appId, accessToken = token)), packages = emptyList()).await()
                .results.firstNotNullOfOrNull { it.apps[appId] }
        }.onFailure { Timber.tag(TAG).w(it, "Steam did not answer for app $appId") }.getOrNull() ?: return known
        val fresh = SteamPics.appFrom(info.keyValues).copy(
            id = appId,
            packageId = known?.packageId ?: SteamIds.INVALID_PKG_ID,
            receivedPICS = true,
            lastChangeNumber = info.changeNumber,
        )
        db.apps().insert(fresh)
        // Where the game keeps its cloud saves is read from the same answer, for the save sync.
        runCatching { onUfs(SteamUfsParser.parse(info.keyValues)) }.onFailure { Timber.tag(TAG).w(it, "Could not read the save locations of app $appId") }
        return fresh
    }
}
