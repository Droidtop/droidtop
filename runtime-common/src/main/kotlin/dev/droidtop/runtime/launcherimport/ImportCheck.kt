package dev.droidtop.runtime.launcherimport

import dev.droidtop.runtime.HomeAction

/**
 * Fits an [ImportPlan] to this device before anything changes (Droidtop/tracker#564): only apps that are installed,
 * widgets whose provider is, an icon pack that is; a folder left with one app becomes that app, Launcher3 keeping
 * no folder of one. Everything it drops is a note, so the summary says what did not come across and why. Pure.
 */
object ImportCheck {
    /** What is on the device: launchable apps by flattened component with their labels, widget providers, icon packs. */
    class Device(val apps: Map<String, String>, val widgetProviders: Set<String>, val iconPacks: Set<String>)

    fun fit(plan: ImportPlan, device: Device): ImportPlan {
        val notes = plan.notes.toMutableList()
        val missing = linkedSetOf<String>()
        fun installed(component: String): Boolean = (component in device.apps).also { if (!it) missing += component }
        fun action(what: String, action: HomeAction?): HomeAction? {
            if (action !is HomeAction.Launch || installed(action.component)) return action
            notes += ImportNote(what, "It opened ${pkg(action.component)}, which is not installed")
            return null
        }
        /** A title the person typed: one equal to the app's own (Apex keeps every title) is no title of its own. */
        fun ownTitle(component: String, title: String?) = title?.trim()?.takeIf { it.isNotEmpty() && it != device.apps[component] }

        val items = plan.items.mapNotNull { item ->
            when (item) {
                is PlanItem.App -> {
                    if (!installed(item.component)) return@mapNotNull null
                    val label = ownTitle(item.component, item.customTitle) ?: device.apps[item.component] ?: pkg(item.component)
                    item.copy(
                        customTitle = ownTitle(item.component, item.customTitle),
                        swipeUp = action("$label: swipe up", item.swipeUp),
                        swipeDown = action("$label: swipe down", item.swipeDown),
                    )
                }
                is PlanItem.Folder -> {
                    val children = item.children.filter { installed(it.component) }
                        .map { it.copy(customTitle = ownTitle(it.component, it.customTitle)) }
                    when (children.size) {
                        0 -> {
                            notes += ImportNote("Folder \"${item.title}\"", "None of its apps are installed")
                            null
                        }
                        1 -> {
                            notes += ImportNote("Folder \"${item.title}\"", "Only one of its apps is installed, so that app takes the folder's place", imported = true)
                            val only = children.single()
                            PlanItem.App(item.container, item.page, item.x, item.y, only.component, only.customTitle, only.customIcon)
                        }
                        else -> item.copy(children = children)
                    }
                }
                is PlanItem.Widget -> {
                    if (item.provider in device.widgetProviders) {
                        item
                    } else {
                        notes += ImportNote("Widget from ${pkg(item.provider)}", "That widget's app is not installed")
                        null
                    }
                }
            }
        }
        val gestures = plan.gestures.mapNotNull { (slot, action) ->
            action("Gesture: ${slot.lowercase().replace('_', ' ')}", action)?.let { slot to it }
        }.toMap(LinkedHashMap())
        val groups = plan.drawerGroups.mapNotNull { group ->
            group.copy(apps = group.apps.filter(::installed)).takeIf { it.apps.isNotEmpty() }
        }
        val hidden = plan.hiddenApps.filter(::installed).toSet()
        val titles = plan.appTitles.filterKeys(::installed).filter { (component, title) -> ownTitle(component, title) != null }
        val iconPack = plan.iconPack?.let { pack ->
            if (pack.isEmpty() || pack in device.iconPacks) {
                pack
            } else {
                notes += ImportNote("Icon pack", "Your icon pack ($pack) is not installed; droidtop's icons stay as they are")
                null
            }
        }
        if (missing.isNotEmpty()) {
            notes += ImportNote(
                if (missing.size == 1) "1 app" else "${missing.size} apps",
                "Not installed on this device, so left out: " + missing.map(::pkg).distinct().joinToString(", "),
            )
        }
        return plan.copy(items = items, gestures = gestures, drawerGroups = groups, hiddenApps = hidden, appTitles = titles, iconPack = iconPack, notes = notes)
    }

    private fun pkg(component: String) = component.substringBefore('/')

    /** The summary's first part: what the import will bring across, one line each, in plain words. */
    fun brought(plan: ImportPlan): List<String> = buildList {
        val home = plan.items.filter { it.container == PlanContainer.HOME }
        val dock = plan.items.filter { it.container == PlanContainer.DOCK }
        if (home.isNotEmpty()) {
            val pages = home.maxOf { it.page } + 1
            add("${count(home.count { it is PlanItem.App }, "app")}, ${count(home.count { it is PlanItem.Folder }, "folder")} and ${count(home.count { it is PlanItem.Widget }, "widget")} on ${count(pages, "home page")}")
        }
        if (dock.isNotEmpty()) add("${count(dock.size, "icon")} in the dock")
        if (plan.items.any { it is PlanItem.Widget }) add("Widgets come as \"tap to set up\": tap each once to bind it")
        plan.grid?.let { (cols, rows) -> add("Home grid ${cols}x$rows") }
        plan.dockIcons?.takeIf { it > 0 }?.let { add("Dock of $it icons") }
        val custom = plan.items.sumOf { item ->
            when (item) {
                is PlanItem.App -> listOf(item.customTitle != null, item.customIcon != null, item.swipeUp != null, item.swipeDown != null).count { it }
                is PlanItem.Folder -> (if (item.customIcon != null) 1 else 0) + item.children.count { it.customTitle != null || it.customIcon != null }
                is PlanItem.Widget -> 0
            }
        }
        if (custom > 0) add("${count(custom, "icon title, picture or swipe")} of your own")
        if (plan.gestures.isNotEmpty()) add(count(plan.gestures.size, "gesture"))
        if (plan.drawerGroups.isNotEmpty()) {
            add("${count(plan.drawerGroups.count { !it.folder }, "drawer tab")} and ${count(plan.drawerGroups.count { it.folder }, "drawer folder")}")
        }
        if (plan.hiddenApps.isNotEmpty()) add(count(plan.hiddenApps.size, "hidden app"))
        if (plan.appTitles.isNotEmpty()) add(count(plan.appTitles.size, "renamed app"))
        when (plan.iconPack) {
            null -> {}
            "" -> add("System icons")
            else -> add("Icon pack ${plan.iconPack}")
        }
        plan.labels?.let { add("Labels: " + it.lowercase().replace('_', ' ')) }
    }

    private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"
}
