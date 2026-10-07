package dev.droidtop.stores.steam

import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.enums.EPaymentMethod
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import java.util.Date
import java.util.EnumSet
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * A licence as Steam sent it, kept as JSON so the depot downloader can be
 * handed the account's licences without a fresh log-on's licence list
 * (GameNative's LicenseSerializer, GPL-3.0; the same JSON, so the licences
 * GameNative cached come across and still work).
 */
internal object SteamLicenses {
    fun toJson(license: License): String = try {
        JSONObject().apply {
            put("packageID", license.packageID)
            put("lastChangeNumber", license.lastChangeNumber)
            put("timeCreated", license.timeCreated.time)
            put("timeNextProcess", license.timeNextProcess.time)
            put("minuteLimit", license.minuteLimit)
            put("minutesUsed", license.minutesUsed)
            put("paymentMethod", license.paymentMethod.code())
            put("licenseFlags", JSONArray(license.licenseFlags.map { it.code() }))
            put("purchaseCode", license.purchaseCode)
            put("licenseType", license.licenseType.code())
            put("territoryCode", license.territoryCode)
            put("accessToken", license.accessToken)
            put("ownerAccountID", license.ownerAccountID)
            put("masterPackageID", license.masterPackageID)
        }.toString()
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Could not keep a Steam licence")
        ""
    }

    fun fromJson(text: String): License? = try {
        if (text.isEmpty()) {
            null
        } else {
            val json = JSONObject(text)
            val flags = EnumSet.noneOf(ELicenseFlags::class.java)
            json.optJSONArray("licenseFlags")?.let { array ->
                for (i in 0 until array.length()) ELicenseFlags.from(array.optInt(i)).firstOrNull()?.let(flags::add)
            }
            val proto = SteammessagesClientserver.CMsgClientLicenseList.License.newBuilder()
                .setPackageId(json.optInt("packageID", 0))
                .setTimeCreated((Date(json.optLong("timeCreated", 0L)).time / 1000).toInt())
                .setTimeNextProcess((Date(json.optLong("timeNextProcess", 0L)).time / 1000).toInt())
                .setMinuteLimit(json.optInt("minuteLimit", 0))
                .setMinutesUsed(json.optInt("minutesUsed", 0))
                .setPaymentMethod(EPaymentMethod.from(json.optInt("paymentMethod", 0)).code())
                .setFlags(ELicenseFlags.code(flags))
                .setPurchaseCountryCode(json.optString("purchaseCode", ""))
                .setLicenseType(ELicenseType.from(json.optInt("licenseType", 0)).code())
                .setTerritoryCode(json.optInt("territoryCode", 0))
                .setAccessToken(json.optLong("accessToken", 0L))
                .setOwnerId(json.optInt("ownerAccountID", 0))
                .setMasterPackageId(json.optInt("masterPackageID", 0))
                .setChangeNumber(json.optInt("lastChangeNumber", 0))
                .build()
            License(proto)
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Could not read a kept Steam licence")
        null
    }

    private const val TAG = "SteamLicenses"
}
