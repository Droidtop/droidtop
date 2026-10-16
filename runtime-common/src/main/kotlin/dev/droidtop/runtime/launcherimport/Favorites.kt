package dev.droidtop.runtime.launcherimport

import dev.droidtop.runtime.HomeAction

/**
 * The `favorites` table both launchers keep (the Launcher3 schema, Nova's with REAL cells and its own columns), read
 * into [Placement.Raw] items. Columns are read by presence and type, never by database version, so older and newer
 * backups read alike. The launcher-specific parts (which page a row is on, its custom icon, its swipe actions, its
 * cover flag) come from [Rules]. Pure.
 */
internal object Favorites {
    const val CONTAINER_DESKTOP = -100L
    const val CONTAINER_HOTSEAT = -101L

    const val TYPE_APP = 0L
    const val TYPE_SHORTCUT = 1L
    const val TYPE_FOLDER = 2L
    const val TYPE_WIDGET = 4L
    const val TYPE_CUSTOM_WIDGET = 5L
    const val TYPE_DEEP_SHORTCUT = 6L

    /** What differs between Nova and Apex for one row. */
    interface Rules {
        val launcher: SourceLauncher
        /** The home page of a row (null: not a page droidtop has, with the reason in [notes]). */
        fun homePage(row: Map<String, Any?>): Int?
        /** The dock column of a row, or null when it is on a dock page droidtop does not have. */
        fun dockColumn(row: Map<String, Any?>): Double?
        fun customTitle(row: Map<String, Any?>): String?
        fun customIcon(row: Map<String, Any?>): ByteArray?
        fun swipe(row: Map<String, Any?>, up: Boolean): Pair<HomeAction?, String?>
        fun cover(row: Map<String, Any?>): Boolean
        /** A launcher's own action name in a type-1 shortcut, if it is one. */
        fun launcherAction(intent: IntentUri): String? = intent.launcherAction
    }

    fun read(rows: List<Map<String, Any?>>, rules: Rules, notes: MutableList<ImportNote>): List<Placement.Raw> {
        val byFolder = rows.groupBy { long(it, "container") ?: 0L }
        val out = mutableListOf<Placement.Raw>()
        for (row in rows) {
            val type = long(row, "itemType") ?: continue
            // Nova's sentinel row (itemType -1) records the grid the database was made for, not the person's.
            if (type < 0) continue
            val container = long(row, "container") ?: continue
            if (container != CONTAINER_DESKTOP && container != CONTAINER_HOTSEAT) continue
            val title = str(row, "title").orEmpty()
            val label = title.ifBlank { describeType(type) }
            val place = when (container) {
                CONTAINER_DESKTOP -> rules.homePage(row)?.let { PlanContainer.HOME to it }
                else -> rules.dockColumn(row)?.let { PlanContainer.DOCK to 0 }
            }
            if (place == null) {
                notes += ImportNote(label, if (container == CONTAINER_HOTSEAT) "It was on another dock page; droidtop's dock has one page" else "Its home page could not be read")
                continue
            }
            val (where, page) = place
            val x = if (where == PlanContainer.DOCK) rules.dockColumn(row)!! else num(row, "cellX") ?: 0.0
            val y = if (where == PlanContainer.DOCK) 0.0 else num(row, "cellY") ?: 0.0
            val spanX = num(row, "spanX")?.coerceAtLeast(1.0) ?: 1.0
            val spanY = num(row, "spanY")?.coerceAtLeast(1.0) ?: 1.0
            when (type) {
                TYPE_APP, TYPE_SHORTCUT -> {
                    val component = appComponent(row, rules, label, notes) ?: continue
                    val (up, upNote) = rules.swipe(row, up = true)
                    val (down, downNote) = rules.swipe(row, up = false)
                    upNote?.let { notes += ImportNote("$label: swipe up", it) }
                    downNote?.let { notes += ImportNote("$label: swipe down", it) }
                    val customTitle = rules.customTitle(row)
                    val customIcon = rules.customIcon(row)
                    out += Placement.Raw(where, page, x, y, 1.0, 1.0, label) { p, cx, cy, _, _ ->
                        PlanItem.App(where, p, cx, cy, component, customTitle, customIcon, up, down)
                    }
                }
                TYPE_FOLDER -> {
                    val id = long(row, "_id") ?: continue
                    val children = byFolder[id].orEmpty().sortedBy { long(it, "rank") ?: 0L }.mapNotNull { child ->
                        val childType = long(child, "itemType")
                        val childLabel = str(child, "title").orEmpty().ifBlank { describeType(childType ?: -1) }
                        if (childType != TYPE_APP && childType != TYPE_SHORTCUT) {
                            notes += ImportNote("$label: $childLabel", notInFolderReason(childType))
                            return@mapNotNull null
                        }
                        appComponent(child, rules, "$label: $childLabel", notes)?.let {
                            FolderChild(it, rules.customTitle(child), rules.customIcon(child))
                        }
                    }
                    if (children.isEmpty()) {
                        notes += ImportNote(label, "None of the folder's apps could be brought across")
                        continue
                    }
                    val options = ((long(row, "options") ?: 0L).toInt()) and (FOLDER_MANUAL_NAME or FOLDER_MULTI_PAGE)
                    val cover = rules.cover(row)
                    val image = rules.customIcon(row)
                    out += Placement.Raw(where, page, x, y, 1.0, 1.0, label) { p, cx, cy, _, _ ->
                        PlanItem.Folder(where, p, cx, cy, title, options, cover, image, children)
                    }
                }
                TYPE_WIDGET -> {
                    val provider = IntentUri.normaliseComponent(str(row, "appWidgetProvider"))
                    if (provider == null) {
                        notes += ImportNote(label, "A widget whose app could not be read")
                        continue
                    }
                    if (where == PlanContainer.DOCK) {
                        notes += ImportNote(widgetName(provider), "It was in the dock; droidtop's dock holds icons only")
                        continue
                    }
                    out += Placement.Raw(where, page, x, y, spanX, spanY, widgetName(provider)) { p, cx, cy, sx, sy ->
                        PlanItem.Widget(where, p, cx, cy, sx, sy, provider)
                    }
                }
                TYPE_CUSTOM_WIDGET, 1001L, 1002L -> notes += ImportNote(
                    label.takeIf { title.isNotBlank() } ?: "${rules.launcher.label}'s own widget",
                    "${rules.launcher.label}'s own widgets are part of ${rules.launcher.label}; droidtop has no equivalent",
                )
                TYPE_DEEP_SHORTCUT -> notes += ImportNote(
                    label,
                    "An app's pinned shortcut can only be pinned by that app: add it again from the app's long-press menu",
                )
                else -> notes += ImportNote(label, "An item of a kind droidtop does not know (type $type)")
            }
        }
        return out
    }

    /** An app row's component: a plain launch of an app. A launcher action or another kind of intent is noted. */
    private fun appComponent(row: Map<String, Any?>, rules: Rules, label: String, notes: MutableList<ImportNote>): String? {
        val intent = IntentUri.parse(str(row, "intent"))
        val action = intent?.let { rules.launcherAction(it) }
        return when {
            intent == null -> {
                notes += ImportNote(label, "Its target could not be read"); null
            }
            action != null -> {
                notes += ImportNote(label, "A shortcut to ${rules.launcher.label}'s \"${ImportActions.describe(action)}\"; droidtop's home screen has no icon for that (set it as a gesture instead)"); null
            }
            intent.isAppLaunch -> intent.component
            else -> {
                notes += ImportNote(label, "A shortcut that is not an app (it opens ${intent.action ?: "something else"}); only apps are brought across"); null
            }
        }
    }

    private fun notInFolderReason(type: Long?) = when (type) {
        TYPE_DEEP_SHORTCUT -> "An app's pinned shortcut can only be pinned by that app: add it again from the app's long-press menu"
        TYPE_WIDGET, TYPE_CUSTOM_WIDGET -> "A widget cannot be in a folder in droidtop"
        else -> "An item of a kind droidtop does not keep in folders"
    }

    private fun describeType(type: Long) = when (type) {
        TYPE_APP -> "An app"
        TYPE_SHORTCUT -> "A shortcut"
        TYPE_FOLDER -> "A folder"
        TYPE_WIDGET -> "A widget"
        TYPE_DEEP_SHORTCUT -> "A pinned shortcut"
        else -> "An item"
    }

    private fun widgetName(provider: String) = "Widget from ${provider.substringBefore('/')}"

    const val FOLDER_MULTI_PAGE = 4
    const val FOLDER_MANUAL_NAME = 8

    fun long(row: Map<String, Any?>, column: String): Long? = when (val v = row[column]) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong()
        else -> null
    }

    fun num(row: Map<String, Any?>, column: String): Double? = when (val v = row[column]) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun str(row: Map<String, Any?>, column: String): String? = when (val v = row[column]) {
        null -> null
        is ByteArray -> null
        else -> v.toString()
    }

    fun blob(row: Map<String, Any?>, column: String): ByteArray? = (row[column] as? ByteArray)?.takeIf { it.isNotEmpty() }
}
