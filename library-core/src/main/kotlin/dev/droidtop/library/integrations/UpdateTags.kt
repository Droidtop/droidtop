package dev.droidtop.library.integrations

/**
 * The name an update's download job carries ([dev.droidtop.pluginhost.DownloadJobs.ARG_UPDATE_FOR]), so the Updates
 * page reads each row's state from its job (Droidtop/tracker#833). The version is in it: a job for an older offer is
 * not the row's. The same plugin version is the same update wherever it was started, a catalog or a repository.
 */
object UpdateTags {
    fun plugin(pluginId: String, version: String): String = "plugin:$pluginId@$version"

    fun app(packageName: String, versionCode: Long): String = "app:$packageName@$versionCode"
}
